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
 * Cells this node cannot get are "foreign": they are shown as last saved, never written, and
 * {@link ClusterGuard} keeps players far enough away that they do not tick here.
 */
public final class Cluster {

    private static final Logger LOGGER = LogUtils.getClassLogger();
    private static final long FOREIGN_RECHECK_MILLIS = 10_000L;
    private static final long RELEASE_IDLE_MILLIS = 15_000L;
    /** Ten minutes of players nearby, in ticks. */
    private static final long LINK_MIN_INHABITED_TICKS = 20L * 60L * 10L;

    private static volatile ClusterClient client;
    private static volatile String nodeName = "";

    /** Cells this node owns, and when a chunk in them was last read or written. */
    private static final Map<Cell, Long> owned = new ConcurrentHashMap<>();
    /** Cells owned by another node, with the time we last asked. */
    private static final Map<Cell, Long> foreign = new ConcurrentHashMap<>();
    private static final Map<Cell, AtomicInteger> writesInFlight = new ConcurrentHashMap<>();
    private static final Set<String> linksSent = ConcurrentHashMap.newKeySet();

    static final AtomicLong reads = new AtomicLong();
    static final AtomicLong writes = new AtomicLong();
    static final AtomicLong skippedWrites = new AtomicLong();
    static final AtomicLong links = new AtomicLong();

    private Cluster() {}

    public static boolean enabled() {
        return client != null;
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
        final ClusterClient connection = new ClusterClient(coordinator, secret, name);
        try {
            connection.connect(60_000L);
        } catch (final IOException ex) {
            throw new IllegalStateException("Cannot reach the cluster coordinator at " + connection.coordinatorAddress() + ": " + ex.getMessage(), ex);
        }
        client = connection;
        nodeName = name;
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

    public static CompoundTag read(final RegionStorageInfo info, final int x, final int z) throws IOException {
        final ClusterProtocol.ChunkKey key = key(info, x, z);
        final Cell cell = new Cell(key.dimension(), key.cellX(), key.cellZ());
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
        if (!claim(cell)) {
            skippedWrites.incrementAndGet();
            return;
        }
        final AtomicInteger inFlight = writesInFlight.computeIfAbsent(cell, c -> new AtomicInteger());
        inFlight.incrementAndGet();
        try {
            touch(cell);
            final ClusterProtocol.Response response = client.request(ClusterProtocol.OP_WRITE, ClusterProtocol.write(key, record));
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
        long lastGuard = 0L;
        while (true) {
            try {
                Thread.sleep(250L);
                final long now = System.currentTimeMillis();
                if (now - lastHeartbeat >= 1000L) {
                    lastHeartbeat = now;
                    heartbeat();
                }
                if (now - lastGuard >= 1000L) {
                    lastGuard = now;
                    ClusterGuard.checkAll();
                }
                if (now - lastSweep >= 5000L) {
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
        final int players = server == null ? 0 : server.getPlayerCount();
        client.request(ClusterProtocol.OP_HEARTBEAT, ClusterProtocol.heartbeat(0.0, players, owned.size()));
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
            if (!inUse.contains(entry.getKey()) && now - entry.getValue() > RELEASE_IDLE_MILLIS && (inFlight == null || inFlight.get() == 0)) {
                release.add(entry.getKey());
            }
        }
        for (final Cell cell : release) {
            owned.remove(cell);
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
        final ClusterProtocol.Response response = client.request(ClusterProtocol.OP_PLAYER_READ, ClusterProtocol.player(uuid, kind, null));
        return response.status() == ClusterProtocol.OK ? response.body() : null;
    }

    public static void writePlayer(final String uuid, final String kind, final byte[] data) throws IOException {
        final ClusterProtocol.Response response = client.request(ClusterProtocol.OP_PLAYER_WRITE, ClusterProtocol.player(uuid, kind, data));
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
            + " writes, " + skippedWrites.get() + " writes skipped (foreign), " + links.get() + " contraption link(s) reported");
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
