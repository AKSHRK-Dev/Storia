package dev.storia.storage;

import ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO.RegionDataController.ReadData;
import ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO.RegionDataController.WriteData;
import com.github.luben.zstd.ZstdCompressCtx;
import com.github.luben.zstd.ZstdInputStream;
import com.mojang.logging.LogUtils;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import org.bukkit.configuration.file.YamlConfiguration;
import org.slf4j.Logger;

/**
 * The Linear region format (https://github.com/xymb-endcrystalme/LinearRegionFileFormatTools): one {@code r.X.Z.linear}
 * file per region, holding all its chunks as one Zstandard stream. About half the size of Anvil in the overworld and
 * far less in the End. Chunks, entities and POI all use it when {@code region-format.type: linear} is set in
 * storia.yml.
 *
 * <p>File layout (version 1): a 32-byte header ({@code signature, version, newest timestamp, compression level,
 * chunk count, compressed length, reserved}), the Zstandard-compressed body (1024 x {@code size, timestamp}, then the
 * chunks' uncompressed NBT one after another) and the signature again as a footer.
 *
 * <p>A region is kept in memory while it is in use. Saved chunks are written to its file in the background every
 * {@code flush-seconds}, on save-all flush and when the world is closed. A region that only exists as {@code .mca}
 * is read from it the first time and written as {@code .linear} from then on; the {@code .mca} file is left as it is.
 */
public final class LinearStorage {

    private static final Logger LOGGER = LogUtils.getClassLogger();
    private static final long SIGNATURE = 0xc3ff13183cca9d9aL;
    private static final byte VERSION = 1;
    private static final int HEADER = 32;
    private static final long UNLOAD_IDLE_MILLIS = 60_000L;

    private static volatile boolean enabled;
    private static volatile int compressionLevel = 1;
    private static volatile long flushMillis = 5_000L;
    private static final Map<Path, LinearStorage> STORAGES = new ConcurrentHashMap<>();

    private final Path folder;
    private final Map<Long, Region> regions = new ConcurrentHashMap<>();

    private LinearStorage(final Path folder) {
        this.folder = folder;
    }

    // ---------------------------------------------------------------------------------------------
    // Configuration
    // ---------------------------------------------------------------------------------------------

    /**
     * Reads {@code region-format} from storia.yml before any world is opened. With Anvil, refuses to start if the
     * world already has Linear region files, so it never runs on the old {@code .mca} copies by mistake.
     */
    public static void configure(final YamlConfiguration config, final Path universe, final String levelName) {
        final String type = config.getString("region-format.type", "anvil").trim().toLowerCase(java.util.Locale.ROOT);
        compressionLevel = Math.max(1, Math.min(22, config.getInt("region-format.linear.compression-level", 1)));
        flushMillis = Math.max(1, config.getInt("region-format.linear.flush-seconds", 5)) * 1000L;
        if (type.equals("linear")) {
            enabled = true;
            LOGGER.info("Region files: Linear (Zstandard level {}, written every {} s). Existing .mca regions are converted when first used.",
                compressionLevel, flushMillis / 1000);
            final Thread flusher = new Thread(LinearStorage::flushLoop, "Storia Linear flush");
            flusher.setDaemon(true);
            flusher.start();
            return;
        }
        if (!type.equals("anvil")) {
            throw new IllegalStateException("region-format.type in storia.yml must be anvil or linear, not " + type);
        }
        final long linear = countLinear(universe.resolve(levelName));
        if (linear > 0) {
            throw new IllegalStateException("The world has " + linear + " Linear region file(s) (.linear), but region-format.type in storia.yml is anvil. "
                + "Set it to linear, or convert the world back to .mca first (https://storiamc.com/en-us/docs/linear/), so the server does not run on outdated .mca files.");
        }
    }

    private static long countLinear(final Path world) {
        if (!Files.isDirectory(world)) {
            return 0;
        }
        try (Stream<Path> files = Files.walk(world)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".linear")).count();
        } catch (final IOException ex) {
            return 0;
        }
    }

    /** Whether this region storage (chunks, entities or POI of one dimension) uses Linear files. */
    public static boolean handles(final RegionStorageInfo info) {
        return enabled && !dev.storia.cluster.Cluster.enabled();
    }

    public static LinearStorage of(final Path folder) {
        return STORAGES.computeIfAbsent(folder.toAbsolutePath().normalize(), LinearStorage::new);
    }

    // ---------------------------------------------------------------------------------------------
    // Chunk access (called from RegionFileStorage)
    // ---------------------------------------------------------------------------------------------

    public CompoundTag read(final int chunkX, final int chunkZ) throws IOException {
        final byte[] data = this.region(chunkX, chunkZ).get(index(chunkX, chunkZ));
        if (data == null) {
            return null;
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            return NbtIo.read(in, NbtAccounter.unlimitedHeap());
        }
    }

    public ReadData readData(final int chunkX, final int chunkZ) throws IOException {
        final CompoundTag tag = this.read(chunkX, chunkZ);
        return tag == null
            ? new ReadData(ReadData.ReadResult.NO_DATA, null, null, 0)
            : new ReadData(ReadData.ReadResult.SYNC_READ, null, tag, 0);
    }

    public WriteData startWrite(final CompoundTag compound) {
        return compound == null
            ? new WriteData(null, WriteData.WriteResult.DELETE, null, null)
            : new WriteData(compound, WriteData.WriteResult.WRITE, null, null);
    }

    public void finishWrite(final int chunkX, final int chunkZ, final WriteData writeData) throws IOException {
        this.write(chunkX, chunkZ, writeData.result() == WriteData.WriteResult.DELETE ? null : writeData.input());
    }

    public void write(final int chunkX, final int chunkZ, final CompoundTag compound) throws IOException {
        byte[] data = null;
        if (compound != null) {
            final ByteArrayOutputStream bytes = new ByteArrayOutputStream(16 * 1024);
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                NbtIo.write(compound, out);
            }
            data = bytes.toByteArray();
        }
        this.region(chunkX, chunkZ).put(index(chunkX, chunkZ), data);
    }

    /** Writes every changed region of this storage to disk now. */
    public void flush() throws IOException {
        IOException failure = null;
        for (final Region region : List.copyOf(this.regions.values())) {
            try {
                region.save();
            } catch (final IOException ex) {
                failure = ex;
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    /** Flushes and forgets every region (the world is closing). */
    public void close() throws IOException {
        this.flush();
        this.regions.clear();
    }

    private static int index(final int chunkX, final int chunkZ) {
        return (chunkX & 31) + (chunkZ & 31) * 32;
    }

    private Region region(final int chunkX, final int chunkZ) throws IOException {
        final int rx = chunkX >> 5;
        final int rz = chunkZ >> 5;
        final long key = ((long) rx << 32) | (rz & 0xFFFFFFFFL);
        Region region = this.regions.get(key);
        if (region == null) {
            synchronized (this.regions) {
                region = this.regions.get(key);
                if (region == null) {
                    region = Region.load(this.folder, rx, rz);
                    this.regions.put(key, region);
                }
            }
        }
        region.lastUse = System.currentTimeMillis();
        return region;
    }

    // ---------------------------------------------------------------------------------------------
    // Background saving
    // ---------------------------------------------------------------------------------------------

    private static void flushLoop() {
        while (true) {
            try {
                Thread.sleep(flushMillis);
                final long now = System.currentTimeMillis();
                for (final LinearStorage storage : STORAGES.values()) {
                    for (final var entry : List.copyOf(storage.regions.entrySet())) {
                        final Region region = entry.getValue();
                        try {
                            region.save();
                        } catch (final IOException ex) {
                            LOGGER.error("Could not write {}; trying again in {} s", region.file, flushMillis / 1000, ex);
                            continue;
                        }
                        // free regions nobody has used for a while (they are re-read when needed)
                        if (now - region.lastUse > UNLOAD_IDLE_MILLIS && !region.dirty()) {
                            synchronized (storage.regions) {
                                if (now - region.lastUse > UNLOAD_IDLE_MILLIS && !region.dirty()) {
                                    storage.regions.remove(entry.getKey(), region);
                                }
                            }
                        }
                    }
                }
            } catch (final InterruptedException ex) {
                return;
            } catch (final Throwable ex) {
                LOGGER.error("Linear region saving failed", ex);
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // One region file
    // ---------------------------------------------------------------------------------------------

    private static final class Region {
        final Path file;
        final byte[][] chunks = new byte[1024][];
        final int[] timestamps = new int[1024];
        volatile long lastUse = System.currentTimeMillis();
        /** Set when the file could not be read: it is never written over, so it can be repaired by hand. */
        private String broken;
        /** Bumped on every change; a save only marks the region clean if nothing changed while it was writing. */
        private long version;
        private long savedVersion;
        private final Object saveLock = new Object();

        private Region(final Path file) {
            this.file = file;
        }

        synchronized byte[] get(final int index) throws IOException {
            if (this.broken != null) {
                throw new IOException(this.broken);
            }
            return this.chunks[index];
        }

        synchronized void put(final int index, final byte[] data) throws IOException {
            if (this.broken != null) {
                throw new IOException(this.broken + "; the chunk was not saved, so the file stays as it is");
            }
            this.chunks[index] = data;
            this.timestamps[index] = data == null ? 0 : (int) (System.currentTimeMillis() / 1000L);
            this.version++;
        }

        synchronized boolean dirty() {
            return this.version != this.savedVersion;
        }

        static Region load(final Path folder, final int rx, final int rz) throws IOException {
            final Region region = new Region(folder.resolve("r." + rx + "." + rz + ".linear"));
            if (Files.exists(region.file)) {
                try {
                    region.readLinear(Files.readAllBytes(region.file));
                } catch (final IOException | RuntimeException ex) {
                    region.broken = "Could not read " + region.file + " (" + ex.getMessage() + "); it is left untouched";
                    LOGGER.error("{}. Its chunks cannot load until it is repaired or restored from a backup.", region.broken);
                }
                return region;
            }
            final Path anvil = folder.resolve("r." + rx + "." + rz + ".mca");
            if (Files.exists(anvil)) {
                final int count = region.readAnvil(folder, anvil, rx, rz);
                if (count > 0) {
                    region.version++; // written as .linear at the next save
                    LOGGER.info("Converting {} ({} chunk(s)) to {}", anvil.getFileName(), count, region.file.getFileName());
                }
            }
            return region;
        }

        private void readLinear(final byte[] raw) throws IOException {
            if (raw.length < HEADER + 8) {
                throw new IOException(this.file + " is too short to be a Linear region file");
            }
            final ByteBuffer header = ByteBuffer.wrap(raw, 0, HEADER);
            final long signature = header.getLong();
            final byte version = header.get();
            header.getLong(); // newest timestamp
            header.get(); // compression level
            final int chunkCount = header.getShort() & 0xFFFF;
            final int length = header.getInt();
            if (signature != SIGNATURE || ByteBuffer.wrap(raw, raw.length - 8, 8).getLong() != SIGNATURE) {
                throw new IOException(this.file + " is not a Linear region file (bad signature)");
            }
            if (version != 1 && version != 2) {
                throw new IOException(this.file + " uses Linear version " + version + ", which Storia cannot read (it reads versions 1 and 2)");
            }
            if (length < 0 || HEADER + length + 8 > raw.length) {
                throw new IOException(this.file + " is truncated");
            }
            final byte[] body;
            try (InputStream in = new ZstdInputStream(new ByteArrayInputStream(raw, HEADER, length))) {
                body = in.readAllBytes();
            }
            final ByteBuffer table = ByteBuffer.wrap(body);
            int offset = 1024 * 8;
            int found = 0;
            for (int i = 0; i < 1024; ++i) {
                final int size = table.getInt(i * 8);
                this.timestamps[i] = table.getInt(i * 8 + 4);
                if (size < 0 || offset + size > body.length) {
                    throw new IOException(this.file + " is corrupt (chunk " + i + " runs past the end)");
                }
                if (size > 0) {
                    this.chunks[i] = java.util.Arrays.copyOfRange(body, offset, offset + size);
                    found++;
                }
                offset += size;
            }
            if (found != chunkCount) {
                LOGGER.warn("{} says it has {} chunk(s) but holds {}; using what it holds", this.file, chunkCount, found);
            }
        }

        /** Reads every chunk of an Anvil region file, uncompressed. */
        private int readAnvil(final Path folder, final Path anvil, final int rx, final int rz) throws IOException {
            final byte[] raw = Files.readAllBytes(anvil);
            int count = 0;
            for (int i = 0; i < 1024 && raw.length >= 8192; ++i) {
                final int location = ByteBuffer.wrap(raw, i * 4, 4).getInt();
                if (location == 0) {
                    continue;
                }
                final int offset = (location >>> 8) * 4096;
                if (offset + 5 > raw.length) {
                    continue;
                }
                final int length = ByteBuffer.wrap(raw, offset, 4).getInt();
                final int type = raw[offset + 4] & 0xFF;
                final byte[] compressed;
                if ((type & 128) != 0) {
                    final Path external = folder.resolve("c." + (rx * 32 + (i & 31)) + "." + (rz * 32 + (i >> 5)) + ".mcc");
                    if (!Files.exists(external)) {
                        continue;
                    }
                    compressed = Files.readAllBytes(external);
                } else {
                    if (length <= 1 || offset + 4 + length > raw.length) {
                        continue;
                    }
                    compressed = java.util.Arrays.copyOfRange(raw, offset + 5, offset + 4 + length);
                }
                final InputStream source = new ByteArrayInputStream(compressed);
                final InputStream in = switch (type & 127) {
                    case 1 -> new GZIPInputStream(source);
                    case 2 -> new InflaterInputStream(source);
                    case 3 -> source;
                    case 4 -> new net.jpountz.lz4.LZ4BlockInputStream(source);
                    default -> throw new IOException(anvil + ": chunk " + i + " uses unknown compression " + type);
                };
                try (in) {
                    this.chunks[i] = in.readAllBytes();
                } catch (final IOException ex) {
                    LOGGER.warn("{}: chunk {} could not be read ({}); it is generated again, as with Anvil", anvil, i, ex.getMessage());
                    continue;
                }
                this.timestamps[i] = ByteBuffer.wrap(raw, 4096 + i * 4, 4).getInt();
                count++;
            }
            return count;
        }

        /** Writes the region if it changed: compressed, to a temporary file, synced, then moved over the old one. */
        void save() throws IOException {
            synchronized (this.saveLock) {
                final byte[][] chunks;
                final int[] timestamps;
                final long version;
                synchronized (this) {
                    if (this.version == this.savedVersion || this.broken != null) {
                        return;
                    }
                    chunks = this.chunks.clone();
                    timestamps = this.timestamps.clone();
                    version = this.version;
                }
                int total = 1024 * 8;
                int count = 0;
                int newest = 0;
                for (int i = 0; i < 1024; ++i) {
                    if (chunks[i] != null) {
                        total += chunks[i].length;
                        count++;
                        newest = Math.max(newest, timestamps[i]);
                    }
                }
                if (count == 0) {
                    Files.deleteIfExists(this.file);
                } else {
                    final ByteBuffer body = ByteBuffer.allocate(total);
                    for (int i = 0; i < 1024; ++i) {
                        body.putInt(chunks[i] == null ? 0 : chunks[i].length).putInt(chunks[i] == null ? 0 : timestamps[i]);
                    }
                    for (final byte[] chunk : chunks) {
                        if (chunk != null) {
                            body.put(chunk);
                        }
                    }
                    final byte[] compressed;
                    try (ZstdCompressCtx ctx = new ZstdCompressCtx()) {
                        ctx.setLevel(compressionLevel);
                        ctx.setChecksum(true);
                        compressed = ctx.compress(body.array());
                    }
                    final ByteBuffer out = ByteBuffer.allocate(HEADER + compressed.length + 8);
                    out.putLong(SIGNATURE).put(VERSION).putLong(newest).put((byte) compressionLevel).putShort((short) count)
                        .putInt(compressed.length).putLong(0L).put(compressed).putLong(SIGNATURE);
                    Files.createDirectories(this.file.getParent());
                    final Path temp = this.file.resolveSibling(this.file.getFileName() + ".storia-tmp"); // skipped by the RAM world sync
                    try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                        out.flip();
                        while (out.hasRemaining()) {
                            channel.write(out);
                        }
                        channel.force(true);
                    }
                    try {
                        Files.move(temp, this.file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                    } catch (final AtomicMoveNotSupportedException ex) {
                        Files.move(temp, this.file, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
                synchronized (this) {
                    this.savedVersion = Math.max(this.savedVersion, version);
                }
            }
        }
    }

    /** Status lines for /storia status. */
    public static List<String> status() {
        final List<String> lines = new ArrayList<>();
        if (!enabled) {
            return lines;
        }
        int loaded = 0;
        int dirty = 0;
        for (final LinearStorage storage : STORAGES.values()) {
            for (final Region region : storage.regions.values()) {
                loaded++;
                if (region.dirty()) {
                    dirty++;
                }
            }
        }
        lines.add("Linear regions: " + loaded + " in memory, " + dirty + " waiting to be written (every " + (flushMillis / 1000) + " s)");
        return lines;
    }

    public static boolean enabled() {
        return enabled;
    }
}
