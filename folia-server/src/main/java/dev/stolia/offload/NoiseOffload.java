package dev.stolia.offload;

import ca.spottedleaf.concurrentutil.util.Priority;
import ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel;
import com.mojang.logging.LogUtils;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.slf4j.Logger;

/**
 * Entry point for offloading the NOISE generation step to other Stolia servers ("workers").
 *
 * <p>Server 1 ({@code offload.mode: client}) sends each eligible chunk's position and Beardifier to the
 * least busy worker, and the result (block states, worldgen heightmaps, fluid post-processing marks) is
 * applied to the chunk. The generation task completes when the result arrives, without holding a worker
 * thread, so server 1 keeps generating other chunks meanwhile. If no worker has room, or a request fails
 * or times out, the chunk is generated locally. Workers only accept dimensions whose probe chunks match
 * server 1 exactly, so the terrain is identical either way.
 *
 * <p>Only fresh chunks without old-world blending are offloaded; everything else is generated locally.
 */
public final class NoiseOffload {

    private static final Logger LOGGER = LogUtils.getClassLogger();
    private static final boolean VERIFY = Boolean.getBoolean("stolia.verifyOffload");

    /** Set once all worlds are loaded (Folia never sets MinecraftServer#isReady). */
    public static volatile boolean serverStarted;
    private static volatile OffloadClient client;
    private static volatile OffloadWorker worker;
    static final AtomicLong offloaded = new AtomicLong();
    static final AtomicLong fallbacks = new AtomicLong();
    static final AtomicLong verified = new AtomicLong();
    static final AtomicLong verifyMismatches = new AtomicLong();
    /** Chunks generated locally because: [0] blending, [1] old/upgraded chunk, [2] no plain NoiseChunk, [3] no worker capacity. */
    static final AtomicLong[] SKIPPED = {new AtomicLong(), new AtomicLong(), new AtomicLong(), new AtomicLong()};

    private NoiseOffload() {
    }

    public static synchronized void start() {
        if (client != null || worker != null) {
            return;
        }
        final OffloadConfig config = OffloadConfig.load();
        switch (config.mode()) {
            case "client" -> {
                if (config.workers().isEmpty()) {
                    LOGGER.warn("offload.mode is client but offload.workers is empty");
                    return;
                }
                final OffloadClient newClient = new OffloadClient(config);
                newClient.start();
                client = newClient;
                LOGGER.info("Noise offload client enabled; workers: {}{}", config.workers(), VERIFY ? " (verifying every result)" : "");
            }
            case "worker" -> worker = OffloadWorker.start(config);
            case "off" -> { }
            default -> LOGGER.warn("Unknown offload.mode '{}' (use off, client or worker)", config.mode());
        }
    }

    public static String levelId(final ServerLevel level) {
        return level.getWorld().getKey().asString();
    }

    static ServerLevel levelById(final String id) {
        for (final ServerLevel level : MinecraftServer.getServer().getAllLevels()) {
            if (levelId(level).equals(id)) {
                return level;
            }
        }
        return null;
    }

    /**
     * Called from the NOISE step. Returns a future for the filled chunk if it was sent to a worker,
     * or null if it should be generated locally as usual.
     */
    public static CompletableFuture<ChunkAccess> tryOffload(final ServerLevel level, final ChunkGenerator generator, final Blender blender,
                                                            final StructureManager structureManager, final ChunkAccess chunk) {
        final OffloadClient currentClient = client;
        if (currentClient == null || !(generator instanceof NoiseBasedChunkGenerator noiseGenerator)) {
            return null;
        }
        if (blender != Blender.empty()) {
            SKIPPED[0].incrementAndGet();
            return null;
        }
        if (!(chunk instanceof ProtoChunk proto) || proto.getBelowZeroRetrogen() != null || chunk.getBlendingData() != null) {
            SKIPPED[1].incrementAndGet();
            return null;
        }
        // Send exactly the Beardifier the local fill would use: the one in the NoiseChunk kept from BIOMES if the
        // chunk stayed loaded, otherwise the one a new NoiseChunk would be built with from this step's structures.
        final NoiseChunk noiseChunk = chunk.stolia$getNoiseChunk();
        final Beardifier beardifier;
        if (noiseChunk == null) {
            beardifier = Beardifier.forStructuresInChunk(structureManager, chunk.getPos());
        } else if (noiseChunk.stolia$blender() == Blender.empty() && noiseChunk.stolia$beardifier() instanceof Beardifier existing) {
            beardifier = existing;
        } else {
            SKIPPED[2].incrementAndGet();
            return null;
        }
        final CompletableFuture<byte[]> remote = currentClient.submit(levelId(level), chunk.getPos().x(), chunk.getPos().z(), beardifier);
        if (remote == null) {
            SKIPPED[3].incrementAndGet();
            return null;
        }
        return remote.handle((final byte[] payload, final Throwable error) -> {
            if (error == null) {
                try {
                    NoiseCodec.applyResult(payload, chunk);
                    if (VERIFY) {
                        verify(level, chunk, beardifier);
                    }
                    offloaded.incrementAndGet();
                    return chunk;
                } catch (final Throwable ex) {
                    LOGGER.warn("Could not apply offloaded chunk {}; generating locally", chunk.getPos(), ex);
                }
            }
            fallbacks.incrementAndGet();
            return null;
        }).thenCompose((final ChunkAccess done) -> done != null ? CompletableFuture.completedFuture(done) : generateLocally(level, noiseGenerator, blender, structureManager, chunk));
    }

    /** Local fallback, run on the chunk worker pool rather than the network thread that noticed the failure. */
    private static CompletableFuture<ChunkAccess> generateLocally(final ServerLevel level, final NoiseBasedChunkGenerator generator, final Blender blender,
                                                                  final StructureManager structureManager, final ChunkAccess chunk) {
        final CompletableFuture<ChunkAccess> future = new CompletableFuture<>();
        ((ChunkSystemServerLevel) level).moonrise$getChunkTaskScheduler().parallelGenExecutor.createTask(() -> {
            try {
                future.complete(generator.fillFromNoise(blender, level.getChunkSource().randomState(), structureManager, chunk).join());
            } catch (final Throwable ex) {
                future.completeExceptionally(ex);
            }
        }, Priority.NORMAL).queue();
        return future;
    }

    private static void verify(final ServerLevel level, final ChunkAccess chunk, final Beardifier beardifier) {
        final String local = NoiseCodec.terrainHash(NoiseCodec.computeDetached(level, chunk.getPos().x(), chunk.getPos().z(), beardifier));
        final long checks = verified.incrementAndGet();
        if (!local.equals(NoiseCodec.terrainHash(chunk))) {
            verifyMismatches.incrementAndGet();
            LOGGER.error("Offload verify mismatch at {}", chunk.getPos());
        }
        if (checks % 1000 == 0) {
            LOGGER.info("[Stolia] offload verify: {} chunks, {} mismatches", checks, verifyMismatches.get());
        }
    }

    /** Status lines for /stolia offload. */
    public static java.util.List<String> status() {
        final java.util.List<String> lines = new java.util.ArrayList<>();
        final OffloadClient currentClient = client;
        final OffloadWorker currentWorker = worker;
        if (currentClient == null && currentWorker == null) {
            lines.add("Offload is off (offload.mode in stolia.yml).");
            return lines;
        }
        if (currentWorker != null) {
            lines.add("Worker mode: " + currentWorker.threads() + " threads, " + currentWorker.connections.get() + " client(s), "
                + currentWorker.computed.get() + " chunks computed");
        }
        if (currentClient != null) {
            lines.add("Client mode: " + offloaded.get() + " chunks offloaded, " + fallbacks.get() + " generated locally after a failure"
                + (VERIFY ? ", verified " + verified.get() + " (" + verifyMismatches.get() + " mismatches)" : ""));
            lines.add(" generated locally: " + SKIPPED[3].get() + " (workers busy), " + SKIPPED[0].get() + " (blending with old terrain), "
                + SKIPPED[1].get() + " (upgraded chunk), " + SKIPPED[2].get() + " (no plain noise state)");
            for (final OffloadClient.Connection connection : currentClient.connections()) {
                lines.add(String.format(java.util.Locale.ROOT, " %s: %s, %d/%d in flight, %d done, %d failed, avg %.1f ms, dims %s",
                    connection.address, connection.status(), connection.inFlight(), connection.limit(), connection.completed.get(),
                    connection.failed.get(), connection.averageRttMillis(), connection.dims()));
            }
        }
        return lines;
    }
}
