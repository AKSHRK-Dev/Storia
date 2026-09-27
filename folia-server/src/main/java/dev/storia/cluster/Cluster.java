package dev.storia.cluster;

import ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO.RegionDataController.ReadData;
import ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO.RegionDataController.WriteData;
import ca.spottedleaf.moonrise.patches.chunk_system.scheduling.NewChunkHolder;
import com.mojang.logging.LogUtils;
import dev.storia.cluster.protocol.ClusterProtocol;
import dev.storia.cluster.protocol.ClusterProtocol.Cell;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import org.bukkit.configuration.file.YamlConfiguration;
import org.slf4j.Logger;

/**
 * Storia Cluster, node side (phase 1: a shared world).
 *
 * <p>With {@code cluster.enabled: true}, this node keeps no world data of its own: chunk, entity and POI records
 * and player data are read from and written to the coordinator (Storia Relay with {@code cluster=true}). Before
 * writing a cell (one region file) the node must own it; the coordinator grants each cell to one node at a time.
 * Cells this node cannot get are "foreign": they are shown as last saved and never written. The coordinator
 * merges players' areas onto one node long before anyone gets close to a foreign cell.
 */
public final class Cluster {

    private static final Logger LOGGER = LogUtils.getClassLogger();
    private static final long FOREIGN_RECHECK_MILLIS = 10_000L;
    private static final long RELEASE_IDLE_MILLIS = 15_000L;
    /** Ten minutes of players nearby, in ticks. */
    private static final long LINK_MIN_INHABITED_TICKS = 20L * 60L * 10L;

    private static volatile ClusterClient client;
    private static volatile ClusterSpool spool;
    private static volatile String nodeName = "";

    /** Cells this node owns, and when a chunk in them was last read or written. */
    private static final Map<Cell, Long> owned = new ConcurrentHashMap<>();
    /** Cells owned by another node, with the time we last asked. */
    private static final Map<Cell, Long> foreign = new ConcurrentHashMap<>();
    private static final Map<Cell, AtomicInteger> writesInFlight = new ConcurrentHashMap<>();
    private static final Set<String> linksSent = ConcurrentHashMap.newKeySet();
    /** Cells the coordinator asked us to hand over: released as soon as they are unloaded and saved. */
    private static final Set<Cell> evicting = ConcurrentHashMap.newKeySet();
    static final AtomicLong playersSent = new AtomicLong();
    /** Players leaving for another node / arriving from one: no quit or join message for them. */
    /** Players being handed to another node, and since when; a move that has not finished in 30 s may be retried. */
    private static final Map<java.util.UUID, Long> movingOut = new ConcurrentHashMap<>();
    private static final long MOVE_TIMEOUT_MILLIS = 30000L;
    private static final Set<java.util.UUID> movedIn = ConcurrentHashMap.newKeySet();

    static final AtomicLong reads = new AtomicLong();
    static final AtomicLong writes = new AtomicLong();
    static final AtomicLong skippedWrites = new AtomicLong();
    static final AtomicLong links = new AtomicLong();

    private Cluster() {}

    public static boolean enabled() {
        return client != null;
    }

    static ClusterClient client() {
        return client;
    }

    /**
     * Connects to the coordinator if {@code cluster.enabled} is set in storia.yml. Called from Main before any
     * world is loaded; exits the server if the coordinator cannot be reached, since running without it would
     * fork the world.
     */
    public static void init() {
        final YamlConfiguration config = YamlConfiguration.loadConfiguration(new File("storia.yml"));
        if (!config.getBoolean("cluster.enabled", false)) {
            return;
        }
        final String secret = config.getString("cluster.secret", config.getString("offload.secret", ""));
        final String name = config.getString("cluster.node-name", "");
        if (secret == null || secret.length() < 8 || name == null || name.isBlank()) {
            throw new IllegalStateException("cluster.enabled is true, but cluster.node-name or a secret (at least 8 characters) is missing in storia.yml");
        }
        final ClusterClient.Address coordinator = ClusterClient.Address.parse(config.getString("cluster.coordinator", "127.0.0.1:25590"), 25590);
        spool = new ClusterSpool();
        final ClusterClient connection = new ClusterClient(coordinator, secret, name);
        try {
            connection.connect(60_000L);
        } catch (final IOException ex) {
            throw new IllegalStateException("Cannot reach the cluster coordinator at " + connection.coordinatorAddress() + ": " + ex.getMessage(), ex);
        }
        client = connection;
        nodeName = name;
        spool.drain(connection);
        LOGGER.info("Storia Cluster: node '{}' (index {}) joined the cluster at {}; the world is stored by the coordinator",
            name, connection.index(), connection.coordinatorAddress());
        final Thread maintenance = new Thread(Cluster::maintenanceLoop, "Storia Cluster maintenance");
        maintenance.setDaemon(true);
        maintenance.start();
    }

    /** First entity id of this node, so entity ids never collide between nodes. */
    public static int entityIdBase() {
        final ClusterClient current = client;
        return current == null ? 0 : current.index() << 24;
    }

    public static String nodeName() {
        return nodeName;
    }

    // ---------------------------------------------------------------------------------------------
    // Region storage (called from RegionFileStorage when enabled)
    // ---------------------------------------------------------------------------------------------

    private static byte storageType(final RegionStorageInfo info) {
        return switch (info.type()) {
            case "chunk" -> ClusterProtocol.TYPE_CHUNK;
            case "entities" -> ClusterProtocol.TYPE_ENTITIES;
            case "poi" -> ClusterProtocol.TYPE_POI;
            default -> -1;
        };
    }

    /** Whether reads and writes of this region storage go to the coordinator. */
    public static boolean handles(final RegionStorageInfo info) {
        return client != null && info != null && storageType(info) >= 0;
    }

    private static ClusterProtocol.ChunkKey key(final RegionStorageInfo info, final int x, final int z) {
        return new ClusterProtocol.ChunkKey(storageType(info), info.dimension().identifier().toString(), x, z);
    }

    private static String spoolKey(final ClusterProtocol.ChunkKey key) {
        return "c " + key.type() + " " + key.dimension() + " " + key.x() + " " + key.z();
    }

    public static CompoundTag read(final RegionStorageInfo info, final int x, final int z) throws IOException {
        final ClusterProtocol.ChunkKey key = key(info, x, z);
        final Cell cell = new Cell(key.dimension(), key.cellX(), key.cellZ());
        final java.util.Optional<byte[]> spooled = spool.latest(spoolKey(key));
        if (spooled != null) {
            // newer than anything the coordinator has; it is still waiting to be sent
            return spooled.isEmpty() ? null : decode(spooled.get());
        }
        if (key.type() == ClusterProtocol.TYPE_CHUNK) {
            claim(cell);
        }
        touch(cell);
        final ClusterProtocol.Response response = client.request(ClusterProtocol.OP_READ, ClusterProtocol.chunkKey(key));
        reads.incrementAndGet();
        if (response.status() == ClusterProtocol.NOT_FOUND) {
            return null;
        }
        if (response.status() != ClusterProtocol.OK) {
            throw new IOException("cluster read failed: " + ClusterProtocol.readString(response.body()));
        }
        final CompoundTag tag = decode(response.body());
        if (key.type() == ClusterProtocol.TYPE_CHUNK) {
            detectLinks(key, tag);
        }
        return tag;
    }

    public static ReadData readData(final RegionStorageInfo info, final int x, final int z) throws IOException {
        final CompoundTag tag = read(info, x, z);
        return tag == null
            ? new ReadData(ReadData.ReadResult.NO_DATA, null, null, 0)
            : new ReadData(ReadData.ReadResult.SYNC_READ, null, tag, 0);
    }

    /** Holds the encoded record between startWrite and finishWrite. */
    private static final class RecordOutput extends DataOutputStream {
        final ByteArrayOutputStream bytes;

        RecordOutput(final ByteArrayOutputStream bytes) {
            super(bytes);
            this.bytes = bytes;
        }
    }

    public static WriteData startWrite(final RegionStorageInfo info, final int x, final int z, final CompoundTag compound) throws IOException {
        if (compound == null) {
            return new WriteData(null, WriteData.WriteResult.DELETE, null, null);
        }
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.write(2); // zlib, as in vanilla region files
        try (DataOutputStream out = new DataOutputStream(new DeflaterOutputStream(bytes))) {
            NbtIo.write(compound, out);
        }
        if (storageType(info) == ClusterProtocol.TYPE_CHUNK) {
            detectLinks(key(info, x, z), compound);
        }
        return new WriteData(compound, WriteData.WriteResult.WRITE, new RecordOutput(bytes), null);
    }

    public static void finishWrite(final RegionStorageInfo info, final int x, final int z, final WriteData writeData) throws IOException {
        final byte[] record = writeData.result() == WriteData.WriteResult.DELETE ? null : ((RecordOutput) writeData.output()).bytes.toByteArray();
        write(info, x, z, record);
    }

    public static void write(final RegionStorageInfo info, final int x, final int z, final CompoundTag compound) throws IOException {
        finishWrite(info, x, z, startWrite(info, x, z, compound));
    }

    private static void write(final RegionStorageInfo info, final int x, final int z, final byte[] record) throws IOException {
        final ClusterProtocol.ChunkKey key = key(info, x, z);
        final Cell cell = new Cell(key.dimension(), key.cellX(), key.cellZ());
        if (!spool.isEmpty()) {
            spool.append(ClusterProtocol.OP_WRITE, spoolKey(key), ClusterProtocol.write(key, record));
            return;
        }
        try {
            writeNow(key, cell, record);
        } catch (final IOException ex) {
            if (ex.getMessage() != null && ex.getMessage().startsWith("cluster write failed")) {
                throw ex;
            }
            LOGGER.warn("Storia Cluster: could not send a write to the coordinator ({}); keeping it locally until it is back", ex.getMessage());
            spool.append(ClusterProtocol.OP_WRITE, spoolKey(key), ClusterProtocol.write(key, record));
        }
    }

    private static void writeNow(final ClusterProtocol.ChunkKey key, final Cell cell, final byte[] record) throws IOException {
        writeNow(key, cell, record, true);
    }

    private static void writeNow(final ClusterProtocol.ChunkKey key, final Cell cell, final byte[] record, final boolean retryClaim) throws IOException {
        if (!claim(cell)) {
            skippedWrites.incrementAndGet();
            return;
        }
        final AtomicInteger inFlight = writesInFlight.computeIfAbsent(cell, c -> new AtomicInteger());
        inFlight.incrementAndGet();
        try {
            touch(cell);
            final ClusterProtocol.Response response = client.request(ClusterProtocol.OP_WRITE, ClusterProtocol.write(key, record));
            if (response.status() == ClusterProtocol.DENIED && "null".equals(ClusterProtocol.readString(response.body())) && retryClaim) {
                // the coordinator forgot we own this cell (it restarted): claim it again and retry once
                owned.remove(cell);
                writeNow(key, cell, record, false);
                return;
            }
            if (response.status() == ClusterProtocol.DENIED) {
                owned.remove(cell);
                foreign.put(cell, System.currentTimeMillis());
                skippedWrites.incrementAndGet();
                LOGGER.warn("Cluster refused a write to {} (now owned by {}); dropped it", cell, ClusterProtocol.readString(response.body()));
                return;
            }
            if (response.status() != ClusterProtocol.OK) {
                throw new IOException("cluster write failed: " + ClusterProtocol.readString(response.body()));
            }
            writes.incrementAndGet();
        } finally {
            inFlight.decrementAndGet();
            touch(cell);
        }
    }

    private static CompoundTag decode(final byte[] record) throws IOException {
        if (record.length < 1) {
            throw new IOException("empty chunk record");
        }
        final InputStream raw = new ByteArrayInputStream(record, 1, record.length - 1);
        final InputStream in = switch (record[0]) {
            case 1 -> new GZIPInputStream(raw);
            case 2 -> new InflaterInputStream(raw);
            case 3 -> raw;
            case 4 -> new net.jpountz.lz4.LZ4BlockInputStream(raw);
            default -> throw new IOException("unknown chunk compression " + record[0]);
        };
        try (DataInputStream data = new DataInputStream(in)) {
            return NbtIo.read(data, NbtAccounter.unlimitedHeap());
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Ownership
    // ---------------------------------------------------------------------------------------------

    private static void touch(final Cell cell) {
        owned.computeIfPresent(cell, (c, t) -> System.currentTimeMillis());
    }

    /** Whether the cell is foreign (owned by another node) as far as this node knows. */
    public static boolean isForeign(final String dimension, final int chunkX, final int chunkZ) {
        return foreign.containsKey(Cell.of(dimension, chunkX, chunkZ));
    }

    /** Makes sure this node owns the cell. Returns false if another node owns it. */
    static boolean claim(final Cell cell) throws IOException {
        if (owned.containsKey(cell)) {
            return true;
        }
        final Long foreignSince = foreign.get(cell);
        if (foreignSince != null && System.currentTimeMillis() - foreignSince < FOREIGN_RECHECK_MILLIS) {
            return false;
        }
        final long deadline = System.currentTimeMillis() + 20_000L;
        while (true) {
            final ClusterProtocol.Response response = client.request(ClusterProtocol.OP_CLAIM, ClusterProtocol.cell(cell));
            switch (response.status()) {
                case ClusterProtocol.OK -> {
                    final long now = System.currentTimeMillis();
                    owned.put(cell, now);
                    for (final Cell granted : ClusterProtocol.readCells(response.body())) {
                        owned.putIfAbsent(granted, now);
                        foreign.remove(granted);
                    }
                    foreign.remove(cell);
                    return true;
                }
                case ClusterProtocol.DENIED -> {
                    if (foreign.put(cell, System.currentTimeMillis()) == null) {
                        LOGGER.info("Cluster: {} is run by node {}; players here are kept away from it", cell, ClusterProtocol.readString(response.body()));
                    }
                    return false;
                }
                case ClusterProtocol.WAIT -> {
                    if (System.currentTimeMillis() > deadline) {
                        foreign.put(cell, System.currentTimeMillis());
                        return false;
                    }
                    try {
                        Thread.sleep(500L);
                    } catch (final InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
                default -> throw new IOException("cluster claim failed: " + ClusterProtocol.readString(response.body()));
            }
        }
    }

    private static void maintenanceLoop() {
        long lastHeartbeat = 0L;
        long lastSweep = System.currentTimeMillis();
        while (true) {
            try {
                Thread.sleep(250L);
                final long now = System.currentTimeMillis();
                if (!spool.isEmpty() && client.connected()) {
                    spool.drain(client);
                }
                if (now - lastHeartbeat >= 1000L) {
                    lastHeartbeat = now;
                    heartbeat();
                    ClusterGlobal.tick(client);
                    ClusterScoreboard.tick(client);
                }
                if (now - lastSweep >= (evicting.isEmpty() ? 5000L : 1000L)) {
                    lastSweep = now;
                    releaseIdleCells(now);
                    foreign.values().removeIf(t -> now - t > 5 * FOREIGN_RECHECK_MILLIS);
                }
            } catch (final InterruptedException ex) {
                return;
            } catch (final Throwable ex) {
                LOGGER.warn("Cluster maintenance failed: {}", ex.toString());
            }
        }
    }

    private static void heartbeat() throws IOException {
        final MinecraftServer server = MinecraftServer.getServer();
        final int players = server == null || server.getPlayerList() == null ? 0 : server.getPlayerCount();
        client.request(ClusterProtocol.OP_HEARTBEAT, ClusterProtocol.heartbeat(0.0, players, owned.size()));
        client.request(ClusterProtocol.OP_ACTIVE, ClusterProtocol.active(activeArea(server)));
    }

    /**
     * The cells this node's players can see: every cell within view distance + 1 chunks of a player. Areas whose
     * cells touch are merged onto one node, so no player ever sees chunks that another node runs. Each cell carries the number of players standing in it, so the coordinator can weigh areas.
     */
    private static ClusterProtocol.Active activeArea(final MinecraftServer server) {
        final Map<Cell, Integer> cells = new java.util.HashMap<>();
        final List<ClusterProtocol.PlayerPos> players = new ArrayList<>();
        if (server == null || server.getPlayerList() == null) {
            return new ClusterProtocol.Active(cells, players);
        }
        for (final net.minecraft.server.level.ServerPlayer player : List.copyOf(server.getPlayerList().getPlayers())) {
            if (player.hasDisconnected()) {
                continue;
            }
            final String dimension = player.level().dimension().identifier().toString();
            final int cx = player.chunkPosition().x();
            final int cz = player.chunkPosition().z();
            // view distance, not just simulation distance: areas merge before a player could see another node's chunks
            final int reach = Math.max(player.level().getWorld().getViewDistance(), player.level().getWorld().getSimulationDistance()) + 1;
            for (int x = (cx - reach) >> 5; x <= (cx + reach) >> 5; ++x) {
                for (int z = (cz - reach) >> 5; z <= (cz + reach) >> 5; ++z) {
                    cells.merge(new Cell(dimension, x, z), 0, Integer::sum);
                }
            }
            cells.merge(Cell.of(dimension, cx, cz), 1, Integer::sum);
            players.add(new ClusterProtocol.PlayerPos(player.getStringUUID(), dimension, cx, cz));
        }
        return new ClusterProtocol.Active(cells, players);
    }

    // ---------------------------------------------------------------------------------------------
    // Pushes from the coordinator
    // ---------------------------------------------------------------------------------------------

    /**
     * Called when a player is created at login. If they are arriving from another node, they keep their entity id,
     * so Storia Proxy can switch them over without a respawn.
     */
    public static void applyTransferredId(final net.minecraft.server.level.ServerPlayer player) {
        if (client == null) {
            return;
        }
        try {
            final ClusterProtocol.Response response = client.request(ClusterProtocol.OP_TRANSFER_INFO, ClusterProtocol.string(player.getStringUUID()));
            if (response.status() == ClusterProtocol.OK) {
                player.setId(Integer.parseInt(ClusterProtocol.readString(response.body())));
                movedIn.add(player.getUUID());
            }
        } catch (final IOException ex) {
            LOGGER.warn("Could not ask the coordinator about {}: {}", player.getGameProfile().name(), ex.getMessage());
        }
    }

    /** Whether this join is a move from another node (no join message). Clears the mark. */
    public static boolean arrivedByMove(final java.util.UUID uuid) {
        return movedIn.remove(uuid);
    }

    /** Whether the player is being handed to another node right now. */
    static boolean isMovingOut(final java.util.UUID uuid) {
        final Long since = movingOut.get(uuid);
        return since != null && System.currentTimeMillis() - since < MOVE_TIMEOUT_MILLIS;
    }

    /** Whether this quit is a move to another node (no quit message). Clears the mark. */
    public static boolean leftByMove(final java.util.UUID uuid) {
        return movingOut.remove(uuid) != null;
    }

    /** Called after a player has left and all their data is saved: another node may load them now. */
    public static void releasePlayer(final java.util.UUID uuid) {
        if (client == null) {
            return;
        }
        try {
            client.request(ClusterProtocol.OP_PLAYER_RELEASE, ClusterProtocol.string(uuid.toString()));
        } catch (final IOException ex) {
            LOGGER.warn("Could not tell the coordinator that {} left: {}", uuid, ex.getMessage());
        }
    }

    private static volatile boolean draining;

    /**
     * Called from /stop: moves this node's players to other nodes first (while regions still tick), then runs
     * {@code halt}. Returns false if there is nothing to do, so the caller halts right away.
     */
    public static boolean drainThenHalt(final Runnable halt) {
        final MinecraftServer server = MinecraftServer.getServer();
        if (client == null || draining || server == null || server.getPlayerList() == null || server.getPlayerCount() == 0) {
            return false;
        }
        draining = true;
        final Thread thread = new Thread(() -> {
            drainBeforeStop();
            halt.run();
        }, "Storia Cluster drain");
        thread.start();
        return true;
    }

    /**
     * Asks the coordinator to move this node's players to other nodes, and waits up to 15 seconds for them to leave.
     */
    public static void drainBeforeStop() {
        final MinecraftServer server = MinecraftServer.getServer();
        if (client == null || server == null || server.getPlayerList() == null || server.getPlayerCount() == 0) {
            return;
        }
        try {
            final ClusterProtocol.Response response = client.request(ClusterProtocol.OP_DRAIN, new byte[0]);
            final int moving = Integer.parseInt(ClusterProtocol.readString(response.body()));
            if (moving == 0) {
                return;
            }
            LOGGER.info("Storia Cluster: moving {} player(s) to other nodes before stopping", moving);
            final long deadline = System.currentTimeMillis() + 15_000L;
            while (server.getPlayerCount() > 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(200L);
            }
        } catch (final Exception ex) {
            LOGGER.warn("Storia Cluster: could not move players away before stopping: {}", ex.toString());
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Shared world data: map ids and map files
    // ---------------------------------------------------------------------------------------------

    /** Whether a saved data file (relative to the world folder) is shared through the coordinator. */
    public static boolean sharesData(final String relative) {
        return client != null && relative.matches("data/[a-z0-9_.-]+/(map_[0-9]+|command_storage_[a-z0-9_.-]+|scoreboard)\\.dat");
    }

    public static byte[] readData(final String relative) throws IOException {
        final ClusterProtocol.Response response = client.request(ClusterProtocol.OP_DATA_READ, ClusterProtocol.player(relative, "data", null));
        return response.status() == ClusterProtocol.OK ? response.body() : null;
    }

    public static void writeData(final String relative, final byte[] data) throws IOException {
        client.request(ClusterProtocol.OP_DATA_WRITE, ClusterProtocol.player(relative, "data", data));
    }

    /** Next map id, unique across the cluster. */
    public static int nextMapId() throws IOException {
        final ClusterProtocol.Response response = client.request(ClusterProtocol.OP_COUNTER, ClusterProtocol.string("map"));
        return Integer.parseInt(ClusterProtocol.readString(response.body()));
    }

    /** After a reconnect the coordinator may have restarted and forgotten our cells: claim them again. */
    static void onReconnect() {
        int kept = 0;
        int lost = 0;
        for (final Cell cell : List.copyOf(owned.keySet())) {
            owned.remove(cell);
            try {
                if (claim(cell)) {
                    kept++;
                } else {
                    lost++;
                }
            } catch (final IOException ex) {
                owned.put(cell, System.currentTimeMillis());
            }
        }
        LOGGER.info("Storia Cluster: claimed {} cell(s) again after reconnecting{}", kept, lost > 0 ? ", " + lost + " now run by other nodes" : "");
    }

    /** Claim used when replaying spooled writes. */
    static boolean claimForReplay(final String dimension, final int chunkX, final int chunkZ) throws IOException {
        return claim(Cell.of(dimension, chunkX, chunkZ));
    }

    static void onPush(final ClusterProtocol.Push push) {
        try {
            switch (push.op()) {
                case ClusterProtocol.PUSH_EVICT -> {
                    final List<Cell> cells = ClusterProtocol.readCells(push.body());
                    for (final Cell cell : cells) {
                        if (owned.containsKey(cell) && evicting.add(cell)) {
                            LOGGER.info("Cluster: handing {} over to another node once its players have moved", cell);
                        }
                    }
                }
                case ClusterProtocol.PUSH_PREPARE -> prepareTransfer(ClusterProtocol.readString(push.body()));
                case ClusterProtocol.PUSH_GLOBAL -> ClusterGlobal.apply(ClusterProtocol.readString(push.body()));
                case ClusterProtocol.PUSH_SCOREBOARD -> ClusterScoreboard.onPush(ClusterProtocol.readNamed(push.body()));
                default -> LOGGER.warn("Unknown cluster push {}", push.op());
            }
        } catch (final Exception ex) {
            LOGGER.warn("Cluster push failed: {}", ex.toString());
        }
    }

    /** Saves the player's data where the next node will read it, then tells the coordinator the move can happen. */
    private static void prepareTransfer(final String uuid) {
        final MinecraftServer server = MinecraftServer.getServer();
        final net.minecraft.server.level.ServerPlayer player = server == null ? null : server.getPlayerList().getPlayer(java.util.UUID.fromString(uuid));
        if (player == null) {
            return;
        }
        player.getBukkitEntity().taskScheduler.schedule(entity -> {
            if (!(entity instanceof net.minecraft.server.level.ServerPlayer p) || p.hasDisconnected() || isMovingOut(p.getUUID())) {
                return; // gone, or already on the way to another node
            }
            movingOut.put(p.getUUID(), System.currentTimeMillis());
            server.getPlayerList().playerIo.save(p);
            p.getAdvancements().save();
            p.getStats().save();
            final int entityId = p.getId();
            final Thread thread = new Thread(() -> {
                try {
                    client.request(ClusterProtocol.OP_TRANSFER_READY, ClusterProtocol.string(uuid + " " + entityId));
                    playersSent.incrementAndGet();
                } catch (final IOException ex) {
                    LOGGER.warn("Could not confirm the move of {}: {}", p.getPlainTextName(), ex.getMessage());
                    movingOut.remove(p.getUUID());
                }
            }, "Storia Cluster transfer");
            thread.setDaemon(true);
            thread.start();
        }, null, 1L);
    }

    /** Releases cells with no loaded chunks, no writes in flight and no use for a while. */
    private static void releaseIdleCells(final long now) throws IOException {
        final MinecraftServer server = MinecraftServer.getServer();
        if (server == null || owned.isEmpty()) {
            return;
        }
        final Set<Cell> inUse = new HashSet<>();
        for (final ServerLevel level : server.getAllLevels()) {
            final String dimension = level.dimension().identifier().toString();
            for (final NewChunkHolder holder : level.moonrise$getChunkTaskScheduler().chunkHolderManager.getChunkHolders()) {
                inUse.add(Cell.of(dimension, holder.chunkX, holder.chunkZ));
            }
        }
        final List<Cell> release = new ArrayList<>();
        for (final Map.Entry<Cell, Long> entry : owned.entrySet()) {
            final AtomicInteger inFlight = writesInFlight.get(entry.getKey());
            final boolean idleLongEnough = now - entry.getValue() > RELEASE_IDLE_MILLIS || (evicting.contains(entry.getKey()) && now - entry.getValue() > 2000L);
            if (!inUse.contains(entry.getKey()) && idleLongEnough && (inFlight == null || inFlight.get() == 0)) {
                release.add(entry.getKey());
            }
        }
        for (final Cell cell : release) {
            owned.remove(cell);
            evicting.remove(cell);
            client.request(ClusterProtocol.OP_RELEASE, ClusterProtocol.cell(cell));
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Contraption links
    // ---------------------------------------------------------------------------------------------

    private static final Set<String> MECHANISMS = Set.of(
        "redstone_wire", "repeater", "comparator", "redstone_torch", "redstone_wall_torch", "redstone_block",
        "piston", "sticky_piston", "piston_head", "moving_piston", "observer", "hopper", "dropper", "dispenser",
        "crafter", "slime_block", "honey_block", "rail", "powered_rail", "detector_rail", "activator_rail",
        "target", "daylight_detector", "tripwire", "tripwire_hook", "lever", "sculk_sensor", "calibrated_sculk_sensor",
        "lightning_rod", "note_block", "trapped_chest", "redstone_lamp", "copper_bulb"
    );

    /** If a chunk on the edge of its cell contains mechanism blocks, links the cell with its neighbours across that edge. */
    private static void detectLinks(final ClusterProtocol.ChunkKey key, final CompoundTag chunk) {
        final int lx = key.x() & 31;
        final int lz = key.z() & 31;
        if (lx != 0 && lx != 31 && lz != 0 && lz != 31) {
            return;
        }
        // Natural structures (mineshaft rails, temple traps) are not contraptions: only count chunks
        // where players have spent a while.
        if (chunk.getLongOr("InhabitedTime", 0L) < LINK_MIN_INHABITED_TICKS || !hasMechanism(chunk)) {
            return;
        }
        final Cell cell = new Cell(key.dimension(), key.cellX(), key.cellZ());
        final int dx = lx == 0 ? -1 : lx == 31 ? 1 : 0;
        final int dz = lz == 0 ? -1 : lz == 31 ? 1 : 0;
        if (dx != 0) {
            link(cell, new Cell(key.dimension(), cell.x() + dx, cell.z()));
        }
        if (dz != 0) {
            link(cell, new Cell(key.dimension(), cell.x(), cell.z() + dz));
        }
        if (dx != 0 && dz != 0) {
            link(cell, new Cell(key.dimension(), cell.x() + dx, cell.z() + dz));
        }
    }

    private static boolean hasMechanism(final CompoundTag chunk) {
        final ListTag sections = chunk.getListOrEmpty("sections");
        for (int i = 0; i < sections.size(); ++i) {
            final ListTag palette = sections.getCompoundOrEmpty(i).getCompoundOrEmpty("block_states").getListOrEmpty("palette");
            for (int j = 0; j < palette.size(); ++j) {
                final String name = palette.getCompoundOrEmpty(j).getStringOr("Name", "");
                final int colon = name.indexOf(':');
                if (MECHANISMS.contains(colon < 0 ? name : name.substring(colon + 1))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void link(final Cell a, final Cell b) {
        final String sa = a.x() + "," + a.z();
        final String sb = b.x() + "," + b.z();
        final String id = a.dimension() + " " + (sa.compareTo(sb) < 0 ? sa + "|" + sb : sb + "|" + sa);
        if (!linksSent.add(id)) {
            return;
        }
        final Thread thread = new Thread(() -> {
            try {
                client.request(ClusterProtocol.OP_LINK, ClusterProtocol.link(a, b));
                links.incrementAndGet();
            } catch (final IOException ex) {
                linksSent.remove(id);
            }
        }, "Storia Cluster link");
        thread.setDaemon(true);
        thread.start();
    }

    // ---------------------------------------------------------------------------------------------
    // Player data
    // ---------------------------------------------------------------------------------------------

    public static byte[] readPlayer(final String uuid, final String kind) throws IOException {
        final java.util.Optional<byte[]> spooled = spool.latest("p " + uuid + " " + kind);
        if (spooled != null) {
            return spooled.orElse(null);
        }
        // If the player is still on another node, wait until it has saved them for the last time.
        final long deadline = System.currentTimeMillis() + 10_000L;
        ClusterProtocol.Response response = client.request(ClusterProtocol.OP_PLAYER_READ, ClusterProtocol.player(uuid, kind, null));
        while (response.status() == ClusterProtocol.WAIT) {
            if (System.currentTimeMillis() > deadline) {
                LOGGER.warn("Storia Cluster: node {} did not let go of player {} within 10 s; loading the last saved data", ClusterProtocol.readString(response.body()), uuid);
                response = client.request(ClusterProtocol.OP_PLAYER_READ, ClusterProtocol.player(uuid, kind, "force".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                break;
            }
            try {
                Thread.sleep(250L);
            } catch (final InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", ex);
            }
            response = client.request(ClusterProtocol.OP_PLAYER_READ, ClusterProtocol.player(uuid, kind, null));
        }
        return response.status() == ClusterProtocol.OK ? response.body() : null;
    }

    public static void writePlayer(final String uuid, final String kind, final byte[] data) throws IOException {
        final byte[] body = ClusterProtocol.player(uuid, kind, data);
        final ClusterProtocol.Response response;
        try {
            if (!spool.isEmpty()) {
                throw new IOException("earlier writes are still waiting");
            }
            response = client.request(ClusterProtocol.OP_PLAYER_WRITE, body);
        } catch (final IOException ex) {
            spool.append(ClusterProtocol.OP_PLAYER_WRITE, "p " + uuid + " " + kind, body);
            return;
        }
        if (response.status() == ClusterProtocol.DENIED) {
            return; // the player has moved to another node, which now owns their data
        }
        if (response.status() != ClusterProtocol.OK) {
            throw new IOException("cluster player write failed: " + ClusterProtocol.readString(response.body()));
        }
    }

    public static CompoundTag readPlayerTag(final String uuid) throws IOException {
        final byte[] data = readPlayer(uuid, "data");
        return data == null ? null : NbtIo.readCompressed(new ByteArrayInputStream(data), NbtAccounter.unlimitedHeap());
    }

    public static void writePlayerTag(final String uuid, final CompoundTag tag) throws IOException {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        NbtIo.writeCompressed(tag, (OutputStream) bytes);
        writePlayer(uuid, "data", bytes.toByteArray());
    }

    // ---------------------------------------------------------------------------------------------
    // Status
    // ---------------------------------------------------------------------------------------------

    public static List<String> status() {
        final List<String> lines = new ArrayList<>();
        final ClusterClient current = client;
        if (current == null) {
            lines.add("Cluster is off (cluster.enabled in storia.yml).");
            return lines;
        }
        lines.add("Node " + nodeName + " (index " + current.index() + "), coordinator " + current.coordinatorAddress()
            + (current.connected() ? " (connected)" : " (DISCONNECTED)"));
        lines.add(" own " + owned.size() + " cell(s), " + foreign.size() + " foreign; " + reads.get() + " reads, " + writes.get()
            + " writes, " + skippedWrites.get() + " writes skipped (foreign), " + links.get() + " contraption link(s) reported, "
            + playersSent.get() + " player(s) handed to other nodes, " + evicting.size() + " cell(s) being handed over, "
            + spool.size() + " write(s) waiting for the coordinator; time and weather: "
            + (ClusterGlobal.isPrimary() ? "kept by this node" : "following the primary node"));
        final MinecraftServer server = MinecraftServer.getServer();
        if (server != null) {
            for (final ServerLevel level : server.getAllLevels()) {
                lines.add(" " + level.dimension().identifier() + ": " + ClusterGlobal.describe(server, level));
            }
            lines.add(" scoreboard: " + ClusterScoreboard.describe(server.getScoreboard()));
        }
        try {
            final ClusterProtocol.Response response = current.request(ClusterProtocol.OP_STATUS, new byte[0]);
            if (response.status() == ClusterProtocol.OK) {
                for (final String line : ClusterProtocol.readString(response.body()).split("\n")) {
                    lines.add(" " + line);
                }
            }
        } catch (final IOException ex) {
            lines.add(" coordinator status unavailable: " + ex.getMessage());
        }
        return lines;
    }
}
