package dev.storia.relay.cluster;

import dev.storia.cluster.protocol.ClusterProtocol;
import dev.storia.cluster.protocol.ClusterProtocol.Cell;
import dev.storia.offload.protocol.Messages;
import dev.storia.offload.protocol.SecureChannel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Storia Cluster coordinator: the source of truth for a world shared by several Storia nodes.
 *
 * <p>It stores chunk, entity and POI records in Anvil region files, stores player data, and decides which node
 * owns each cell (one region file of one dimension). Only a cell's owner may write it. A released cell is
 * held back from other nodes for {@link #RELEASE_QUIET_MILLIS}, so writes still in flight from the old owner
 * land before anyone else reads the cell. A node that stops sending heartbeats loses its cells.
 */
public final class ClusterCoordinator {

    static final long RELEASE_QUIET_MILLIS = 10_000L;
    static final long HEARTBEAT_TIMEOUT_MILLIS = 15_000L;
    private static final Pattern UUID = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    private final Path world;
    private final AnvilStore store;
    private final Consumer<String> log;
    private final ExecutorService io = Executors.newFixedThreadPool(8, runnable -> {
        final Thread thread = new Thread(runnable, "cluster io");
        thread.setDaemon(true);
        return thread;
    });

    private final Map<String, Node> nodes = new ConcurrentHashMap<>();
    private final Map<String, Integer> indexes = new ConcurrentHashMap<>();
    private final AtomicInteger nextIndex = new AtomicInteger(1);
    private final Map<Cell, String> owners = new ConcurrentHashMap<>();
    private final Map<Cell, Released> released = new ConcurrentHashMap<>();
    /** Cells whose owner no longer uses them; a linked group is freed once all its cells are idle. */
    private final java.util.Set<Cell> idle = ConcurrentHashMap.newKeySet();
    /** Contraption links: cells that must always have the same owner. */
    private final Map<Cell, java.util.Set<Cell>> links = new ConcurrentHashMap<>();
    private final Path linksFile;
    private final AtomicLong reads = new AtomicLong();
    private final AtomicLong writes = new AtomicLong();
    private final AtomicLong denied = new AtomicLong();

    private record Released(String node, long at) {}

    public ClusterCoordinator(final Path world, final Consumer<String> log) throws IOException {
        this.world = world;
        Files.createDirectories(world);
        this.store = new AnvilStore(world);
        this.log = log;
        this.linksFile = world.resolve("storia-cluster-links.txt");
        this.loadLinks();
        final Thread reaper = new Thread(this::reapLoop, "cluster heartbeats");
        reaper.setDaemon(true);
        reaper.start();
    }

    /** A connected node. */
    final class Node {
        final String name;
        final int index;
        final SecureChannel channel;
        volatile long lastHeartbeat = System.currentTimeMillis();
        volatile ClusterProtocol.Heartbeat lastStats = new ClusterProtocol.Heartbeat(0, 0, 0);

        Node(final String name, final int index, final SecureChannel channel) {
            this.name = name;
            this.index = index;
            this.channel = channel;
        }
    }

    /** Serves one node connection until it closes. Called on the connection's thread after its HELLO. */
    public void serve(final SecureChannel channel, final Messages.Hello hello) throws IOException {
        final String name = hello.probes().getOrDefault("node", channel.remoteAddress());
        final int index = this.indexes.computeIfAbsent(name, n -> this.nextIndex.getAndIncrement());
        if (index >= 128) {
            channel.send(Messages.welcome(new Messages.Welcome(false, "too many nodes")));
            return;
        }
        final Node node = new Node(name, index, channel);
        final Node previous = this.nodes.put(name, node);
        if (previous != null) {
            this.log.accept("Node " + name + " reconnected; dropping its old connection");
            previous.channel.close();
        }
        channel.send(Messages.welcome(new Messages.Welcome(true, "index=" + index)));
        this.log.accept("Node " + name + " joined the cluster (index " + index + ", " + channel.remoteAddress() + ")");
        try {
            while (true) {
                final byte[] message = channel.receive();
                if (ClusterProtocol.type(message) != ClusterProtocol.REQUEST) {
                    continue;
                }
                final ClusterProtocol.Request request = ClusterProtocol.readRequest(message);
                this.io.execute(() -> {
                    ClusterProtocol.Response response;
                    try {
                        response = this.handle(node, request);
                    } catch (final Exception ex) {
                        response = new ClusterProtocol.Response(request.id(), ClusterProtocol.ERROR, ClusterProtocol.string(String.valueOf(ex.getMessage())));
                    }
                    try {
                        channel.send(ClusterProtocol.response(response));
                    } catch (final IOException ignored) {
                        // node gone; cleanup happens in the finally below
                    }
                });
            }
        } finally {
            if (this.nodes.remove(name, node)) {
                this.dropNode(node, "disconnected");
            }
        }
    }

    private ClusterProtocol.Response handle(final Node node, final ClusterProtocol.Request request) throws IOException {
        final long id = request.id();
        return switch (request.op()) {
            case ClusterProtocol.OP_READ -> {
                final ClusterProtocol.ChunkKey key = ClusterProtocol.readChunkKey(request.body());
                this.reads.incrementAndGet();
                final byte[] record = this.store.read(key);
                yield record == null
                    ? new ClusterProtocol.Response(id, ClusterProtocol.NOT_FOUND, null)
                    : new ClusterProtocol.Response(id, ClusterProtocol.OK, record);
            }
            case ClusterProtocol.OP_WRITE -> {
                final ClusterProtocol.Write write = ClusterProtocol.readWrite(request.body());
                final Cell cell = new Cell(write.key().dimension(), write.key().cellX(), write.key().cellZ());
                if (!this.mayWrite(node, cell)) {
                    this.denied.incrementAndGet();
                    yield new ClusterProtocol.Response(id, ClusterProtocol.DENIED, ClusterProtocol.string(String.valueOf(this.owners.get(cell))));
                }
                this.store.write(write.key(), write.record());
                this.writes.incrementAndGet();
                yield new ClusterProtocol.Response(id, ClusterProtocol.OK, null);
            }
            case ClusterProtocol.OP_CLAIM -> this.claim(node, ClusterProtocol.readCell(request.body()), id);
            case ClusterProtocol.OP_RELEASE -> {
                this.release(node, ClusterProtocol.readCell(request.body()));
                yield new ClusterProtocol.Response(id, ClusterProtocol.OK, null);
            }
            case ClusterProtocol.OP_LINK -> {
                final Cell[] pair = ClusterProtocol.readLink(request.body());
                this.link(node, pair[0], pair[1]);
                yield new ClusterProtocol.Response(id, ClusterProtocol.OK, null);
            }
            case ClusterProtocol.OP_PLAYER_READ -> {
                final ClusterProtocol.Player player = ClusterProtocol.readPlayer(request.body());
                final Path file = this.playerFile(player);
                yield Files.exists(file)
                    ? new ClusterProtocol.Response(id, ClusterProtocol.OK, Files.readAllBytes(file))
                    : new ClusterProtocol.Response(id, ClusterProtocol.NOT_FOUND, null);
            }
            case ClusterProtocol.OP_PLAYER_WRITE -> {
                final ClusterProtocol.Player player = ClusterProtocol.readPlayer(request.body());
                final Path file = this.playerFile(player);
                Files.createDirectories(file.getParent());
                final Path temp = file.resolveSibling(file.getFileName() + ".tmp");
                Files.write(temp, player.data() == null ? new byte[0] : player.data());
                AnvilStore.move(temp, file);
                yield new ClusterProtocol.Response(id, ClusterProtocol.OK, null);
            }
            case ClusterProtocol.OP_HEARTBEAT -> {
                node.lastHeartbeat = System.currentTimeMillis();
                node.lastStats = ClusterProtocol.readHeartbeat(request.body());
                yield new ClusterProtocol.Response(id, ClusterProtocol.OK, null);
            }
            case ClusterProtocol.OP_STATUS -> new ClusterProtocol.Response(id, ClusterProtocol.OK, ClusterProtocol.string(String.join("\n", this.statusLines())));
            default -> new ClusterProtocol.Response(id, ClusterProtocol.ERROR, ClusterProtocol.string("unknown op " + request.op()));
        };
    }

    private ClusterProtocol.Response claim(final Node node, final Cell cell, final long id) {
        synchronized (this.owners) {
            final java.util.Set<Cell> group = this.group(cell);
            final long now = System.currentTimeMillis();
            for (final Cell member : group) {
                final String owner = this.owners.get(member);
                if (owner != null && !owner.equals(node.name)) {
                    return new ClusterProtocol.Response(id, ClusterProtocol.DENIED, ClusterProtocol.string(owner));
                }
                final Released last = this.released.get(member);
                if (owner == null && last != null && !last.node().equals(node.name) && now - last.at() < RELEASE_QUIET_MILLIS) {
                    return new ClusterProtocol.Response(id, ClusterProtocol.WAIT, ClusterProtocol.string(last.node()));
                }
            }
            for (final Cell member : group) {
                if (this.owners.putIfAbsent(member, node.name) == null) {
                    this.released.remove(member);
                    if (!member.equals(cell)) {
                        this.idle.add(member);
                    }
                }
            }
            this.idle.remove(cell);
            return new ClusterProtocol.Response(id, ClusterProtocol.OK, ClusterProtocol.cells(group));
        }
    }

    /** Marks the cell idle; frees its whole linked group once every cell in it is idle. */
    private void release(final Node node, final Cell cell) {
        synchronized (this.owners) {
            if (!node.name.equals(this.owners.get(cell))) {
                return;
            }
            this.idle.add(cell);
            final java.util.Set<Cell> group = this.group(cell);
            for (final Cell member : group) {
                if (node.name.equals(this.owners.get(member)) && !this.idle.contains(member)) {
                    return;
                }
            }
            final long now = System.currentTimeMillis();
            for (final Cell member : group) {
                if (this.owners.remove(member, node.name)) {
                    this.released.put(member, new Released(node.name, now));
                }
                this.idle.remove(member);
            }
        }
    }

    /** All cells linked to this one (including itself). */
    private java.util.Set<Cell> group(final Cell cell) {
        final java.util.Set<Cell> group = new java.util.LinkedHashSet<>();
        final java.util.ArrayDeque<Cell> queue = new java.util.ArrayDeque<>();
        queue.add(cell);
        while (!queue.isEmpty() && group.size() < 4096) {
            final Cell next = queue.poll();
            if (group.add(next)) {
                queue.addAll(this.links.getOrDefault(next, java.util.Set.of()));
            }
        }
        return group;
    }

    private void link(final Node node, final Cell a, final Cell b) throws IOException {
        if (!a.dimension().equals(b.dimension()) || Math.abs(a.x() - b.x()) > 1 || Math.abs(a.z() - b.z()) > 1) {
            return;
        }
        synchronized (this.owners) {
            if (!this.links.computeIfAbsent(a, c -> ConcurrentHashMap.newKeySet()).add(b)) {
                return;
            }
            this.links.computeIfAbsent(b, c -> ConcurrentHashMap.newKeySet()).add(a);
            Files.writeString(this.linksFile, a.dimension() + " " + a.x() + " " + a.z() + " " + b.x() + " " + b.z() + "\n",
                java.nio.charset.StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            // keep the group under one owner: a free cell joins its partner's owner
            final String ownerA = this.owners.get(a);
            final String ownerB = this.owners.get(b);
            if (ownerA != null && ownerB == null) {
                this.owners.put(b, ownerA);
                this.idle.add(b);
            } else if (ownerB != null && ownerA == null) {
                this.owners.put(a, ownerB);
                this.idle.add(a);
            } else if (ownerA != null && !ownerA.equals(ownerB)) {
                this.log.accept("Contraption across " + a + " / " + b + " is split between nodes " + ownerA + " and " + ownerB
                    + " until one of them lets go");
            }
        }
        this.log.accept("Linked " + a + " and " + b + " (a contraption spans the border; reported by " + node.name + ")");
    }

    private void loadLinks() throws IOException {
        if (!Files.exists(this.linksFile)) {
            return;
        }
        int count = 0;
        for (final String line : Files.readAllLines(this.linksFile)) {
            final String[] parts = line.trim().split(" ");
            if (parts.length != 5) {
                continue;
            }
            final Cell a = new Cell(parts[0], Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
            final Cell b = new Cell(parts[0], Integer.parseInt(parts[3]), Integer.parseInt(parts[4]));
            this.links.computeIfAbsent(a, c -> ConcurrentHashMap.newKeySet()).add(b);
            this.links.computeIfAbsent(b, c -> ConcurrentHashMap.newKeySet()).add(a);
            count++;
        }
        this.log.accept("Loaded " + count + " contraption link(s)");
    }

    /** The owner may write; so may the last owner of a free cell, for saves that were in flight when it released. */
    private boolean mayWrite(final Node node, final Cell cell) {
        final String owner = this.owners.get(cell);
        if (owner != null) {
            return owner.equals(node.name);
        }
        final Released last = this.released.get(cell);
        return last != null && last.node().equals(node.name);
    }

    private Path playerFile(final ClusterProtocol.Player player) throws IOException {
        if (!UUID.matcher(player.uuid()).matches()) {
            throw new IOException("bad uuid");
        }
        return switch (player.kind()) {
            case "data" -> this.world.resolve("players").resolve("data").resolve(player.uuid() + ".dat");
            case "advancements" -> this.world.resolve("players").resolve("advancements").resolve(player.uuid() + ".json");
            case "stats" -> this.world.resolve("players").resolve("stats").resolve(player.uuid() + ".json");
            default -> throw new IOException("bad player data kind " + player.kind());
        };
    }

    private void reapLoop() {
        while (true) {
            try {
                Thread.sleep(1000L);
                final long now = System.currentTimeMillis();
                for (final Node node : new ArrayList<>(this.nodes.values())) {
                    if (now - node.lastHeartbeat > HEARTBEAT_TIMEOUT_MILLIS && this.nodes.remove(node.name, node)) {
                        node.channel.close();
                        this.dropNode(node, "no heartbeat for " + (HEARTBEAT_TIMEOUT_MILLIS / 1000) + "s");
                    }
                }
                this.released.values().removeIf(r -> now - r.at() > 10 * RELEASE_QUIET_MILLIS);
                this.store.flush();
            } catch (final InterruptedException ex) {
                return;
            } catch (final Exception ex) {
                this.log.accept("Cluster maintenance failed: " + ex);
            }
        }
    }

    private void dropNode(final Node node, final String why) {
        int freed = 0;
        synchronized (this.owners) {
            final long now = System.currentTimeMillis();
            for (final var entry : new ArrayList<>(this.owners.entrySet())) {
                if (entry.getValue().equals(node.name) && this.owners.remove(entry.getKey(), node.name)) {
                    this.released.put(entry.getKey(), new Released(node.name, now));
                    this.idle.remove(entry.getKey());
                    freed++;
                }
            }
        }
        this.log.accept("Node " + node.name + " left the cluster (" + why + "); freed " + freed + " cell(s)");
    }

    public List<String> statusLines() {
        final List<String> lines = new ArrayList<>();
        lines.add("Cluster world " + this.world.toAbsolutePath() + ": " + this.nodes.size() + " node(s), " + this.owners.size()
            + " owned cell(s), " + (this.links.values().stream().mapToInt(java.util.Set::size).sum() / 2) + " contraption link(s), " + this.reads.get() + " reads, " + this.writes.get() + " writes, " + this.denied.get() + " denied writes");
        for (final Node node : this.nodes.values()) {
            final long cells = this.owners.values().stream().filter(node.name::equals).count();
            lines.add(String.format(java.util.Locale.ROOT, "  node %s (index %d, %s): %d cell(s), %d player(s), %.1f MSPT",
                node.name, node.index, node.channel.remoteAddress(), cells, node.lastStats.players(), node.lastStats.mspt()));
        }
        return lines;
    }

    public void close() throws IOException {
        this.store.close();
    }
}
