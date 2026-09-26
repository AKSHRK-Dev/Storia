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

    // ---- phase 2: placement and player transfers ----
    /** Which node holds each player's data (only it may write it). */
    private final Map<String, String> playerHolder = new ConcurrentHashMap<>();
    /** Last known position of each player. */
    private final Map<String, ClusterProtocol.PlayerPos> lastPos = new ConcurrentHashMap<>();
    private final Map<String, Transfer> transfers = new ConcurrentHashMap<>();
    private final java.util.Set<SecureChannel> proxies = ConcurrentHashMap.newKeySet();
    private final AtomicLong moves = new AtomicLong();
    private volatile long lastBalance = System.currentTimeMillis();

    private record Transfer(String uuid, String from, String to, long started, String why) {}

    /** Entity id a moving player keeps, so the proxy can switch without a respawn. */
    private final Map<String, Integer> transferIds = new ConcurrentHashMap<>();

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
        final Thread placement = new Thread(this::placementLoop, "cluster placement");
        placement.setDaemon(true);
        placement.start();
    }

    /** A connected node. */
    final class Node {
        final String name;
        final int index;
        final SecureChannel channel;
        volatile long lastHeartbeat = System.currentTimeMillis();
        volatile ClusterProtocol.Heartbeat lastStats = new ClusterProtocol.Heartbeat(0, 0, 0);
        volatile ClusterProtocol.Active active = new ClusterProtocol.Active(Map.of(), List.of());
        /** Stopping: its players are moved away and it gets no new ones. */
        volatile boolean draining;

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
                final String holder = this.playerHolder.get(player.uuid());
                final boolean force = "force".equals(new String(player.data() == null ? new byte[0] : player.data(), java.nio.charset.StandardCharsets.UTF_8));
                if (holder != null && !holder.equals(node.name) && this.nodes.containsKey(holder) && !force) {
                    // still on another node, which has not saved them for the last time yet
                    yield new ClusterProtocol.Response(id, ClusterProtocol.WAIT, ClusterProtocol.string(holder));
                }
                this.playerHolder.put(player.uuid(), node.name);
                final Path file = this.playerFile(player);
                yield Files.exists(file)
                    ? new ClusterProtocol.Response(id, ClusterProtocol.OK, Files.readAllBytes(file))
                    : new ClusterProtocol.Response(id, ClusterProtocol.NOT_FOUND, null);
            }
            case ClusterProtocol.OP_PLAYER_WRITE -> {
                final ClusterProtocol.Player player = ClusterProtocol.readPlayer(request.body());
                final String holder = this.playerHolder.get(player.uuid());
                if (holder != null && !holder.equals(node.name)) {
                    // the player has moved on; this is a late save from the old node
                    yield new ClusterProtocol.Response(id, ClusterProtocol.DENIED, ClusterProtocol.string(holder));
                }
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
            case ClusterProtocol.OP_ACTIVE -> {
                node.active = ClusterProtocol.readActive(request.body());
                for (final ClusterProtocol.PlayerPos pos : node.active.players()) {
                    this.lastPos.put(pos.uuid(), pos);
                }
                yield new ClusterProtocol.Response(id, ClusterProtocol.OK, null);
            }
            case ClusterProtocol.OP_TRANSFER_READY -> {
                final String[] ready = ClusterProtocol.readString(request.body()).split(" ");
                final String uuid = ready[0];
                final Transfer transfer = this.transfers.get(uuid);
                if (transfer != null && ready.length > 1) {
                    this.transferIds.put(uuid, Integer.parseInt(ready[1]));
                }
                if (transfer != null && transfer.from().equals(node.name)) {
                    this.playerHolder.put(uuid, transfer.to());
                    this.pushProxies(ClusterProtocol.PUSH_MOVE, ClusterProtocol.move(uuid, transfer.to()));
                    this.moves.incrementAndGet();
                    this.log.accept("Moving player " + uuid + " from " + transfer.from() + " to " + transfer.to() + " (" + transfer.why() + ")");
                }
                yield new ClusterProtocol.Response(id, ClusterProtocol.OK, null);
            }
            case ClusterProtocol.OP_TRANSFER_INFO -> {
                final String uuid = ClusterProtocol.readString(request.body());
                final Transfer transfer = this.transfers.remove(uuid);
                final Integer entityId = this.transferIds.remove(uuid);
                yield transfer != null && transfer.to().equals(node.name) && entityId != null
                    ? new ClusterProtocol.Response(id, ClusterProtocol.OK, ClusterProtocol.string(String.valueOf(entityId)))
                    : new ClusterProtocol.Response(id, ClusterProtocol.NOT_FOUND, null);
            }
            case ClusterProtocol.OP_GLOBAL -> {
                final String body = ClusterProtocol.readString(request.body());
                final boolean primary = node.name.equals(this.primaryNode());
                final boolean state = body.startsWith("state\n") && primary;
                final boolean change = body.startsWith("change\n") && body.length() > "change\n".length();
                if (state || change) {
                    for (final Node other : this.nodes.values()) {
                        if (!other.name.equals(node.name)) {
                            this.pushNode(other.name, ClusterProtocol.PUSH_GLOBAL, ClusterProtocol.string(body));
                        }
                    }
                }
                yield new ClusterProtocol.Response(id, ClusterProtocol.OK, ClusterProtocol.string(primary ? "primary" : "follower"));
            }
            case ClusterProtocol.OP_SCOREBOARD -> {
                final ClusterProtocol.Named named = ClusterProtocol.readNamed(request.body());
                if (named.name().equals("?")) {
                    // a node that just started wants the current scoreboard: ask another node
                    this.nodes.values().stream().filter(n -> !n.name.equals(node.name)).min(java.util.Comparator.comparingInt((Node n) -> n.index))
                        .ifPresent(n -> this.pushNode(n.name, ClusterProtocol.PUSH_SCOREBOARD, ClusterProtocol.named(node.name, new byte[0])));
                } else if (named.name().isEmpty()) {
                    for (final Node other : this.nodes.values()) {
                        if (!other.name.equals(node.name)) {
                            this.pushNode(other.name, ClusterProtocol.PUSH_SCOREBOARD, ClusterProtocol.named(node.name, named.data()));
                        }
                    }
                } else {
                    this.pushNode(named.name(), ClusterProtocol.PUSH_SCOREBOARD, ClusterProtocol.named(node.name, named.data()));
                }
                yield new ClusterProtocol.Response(id, ClusterProtocol.OK, null);
            }
            case ClusterProtocol.OP_DRAIN -> {
                node.draining = true;
                int moved = 0;
                for (final ClusterProtocol.PlayerPos pos : node.active.players()) {
                    final String target = this.nodes.values().stream().filter(n -> !n.draining && !n.name.equals(node.name))
                        .min(java.util.Comparator.comparingInt((Node n) -> n.lastStats.players())).map(n -> n.name).orElse(null);
                    if (target != null && !this.transfers.containsKey(pos.uuid())) {
                        this.transfers.put(pos.uuid(), new Transfer(pos.uuid(), node.name, target, System.currentTimeMillis(), node.name + " is stopping"));
                        this.pushNode(node.name, ClusterProtocol.PUSH_PREPARE, ClusterProtocol.string(pos.uuid()));
                        moved++;
                    }
                }
                this.log.accept("Node " + node.name + " is stopping; moving " + moved + " player(s) to other nodes");
                yield new ClusterProtocol.Response(id, ClusterProtocol.OK, ClusterProtocol.string(String.valueOf(moved)));
            }
            case ClusterProtocol.OP_DATA_READ -> {
                final Path file = this.dataFile(ClusterProtocol.readPlayer(request.body()).uuid());
                yield Files.exists(file)
                    ? new ClusterProtocol.Response(id, ClusterProtocol.OK, Files.readAllBytes(file))
                    : new ClusterProtocol.Response(id, ClusterProtocol.NOT_FOUND, null);
            }
            case ClusterProtocol.OP_DATA_WRITE -> {
                final ClusterProtocol.Player data = ClusterProtocol.readPlayer(request.body());
                final Path file = this.dataFile(data.uuid());
                Files.createDirectories(file.getParent());
                final Path temp = file.resolveSibling(file.getFileName() + ".tmp");
                Files.write(temp, data.data() == null ? new byte[0] : data.data());
                AnvilStore.move(temp, file);
                yield new ClusterProtocol.Response(id, ClusterProtocol.OK, null);
            }
            case ClusterProtocol.OP_COUNTER -> new ClusterProtocol.Response(id, ClusterProtocol.OK,
                ClusterProtocol.string(Long.toString(this.nextCounter(ClusterProtocol.readString(request.body())))));
            case ClusterProtocol.OP_PLAYER_RELEASE -> {
                this.playerHolder.remove(ClusterProtocol.readString(request.body()), node.name);
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

    // ---------------------------------------------------------------------------------------------
    // Proxies
    // ---------------------------------------------------------------------------------------------

    /** Serves a Storia Proxy connection: login routing requests; receives PUSH_MOVE. */
    public void serveProxy(final SecureChannel channel) throws IOException {
        channel.send(Messages.welcome(new Messages.Welcome(true, "proxy")));
        this.proxies.add(channel);
        this.log.accept("Storia Proxy " + channel.remoteAddress() + " connected to the cluster");
        try {
            while (true) {
                final byte[] message = channel.receive();
                if (ClusterProtocol.type(message) != ClusterProtocol.REQUEST) {
                    continue;
                }
                final ClusterProtocol.Request request = ClusterProtocol.readRequest(message);
                ClusterProtocol.Response response;
                if (request.op() == ClusterProtocol.OP_ROUTE) {
                    final String node = this.route(ClusterProtocol.readString(request.body()));
                    response = node == null
                        ? new ClusterProtocol.Response(request.id(), ClusterProtocol.NOT_FOUND, null)
                        : new ClusterProtocol.Response(request.id(), ClusterProtocol.OK, ClusterProtocol.string(node));
                } else if (request.op() == ClusterProtocol.OP_STATUS) {
                    response = new ClusterProtocol.Response(request.id(), ClusterProtocol.OK, ClusterProtocol.string(String.join("\n", this.statusLines())));
                } else {
                    response = new ClusterProtocol.Response(request.id(), ClusterProtocol.ERROR, ClusterProtocol.string("unsupported"));
                }
                channel.send(ClusterProtocol.response(response));
            }
        } finally {
            this.proxies.remove(channel);
            this.log.accept("Storia Proxy " + channel.remoteAddress() + " left the cluster");
        }
    }

    private void pushProxies(final byte op, final byte[] body) {
        final byte[] message = ClusterProtocol.push(new ClusterProtocol.Push(op, body));
        for (final SecureChannel proxy : this.proxies) {
            try {
                proxy.send(message);
            } catch (final IOException ignored) {
                // gone; removed by its serve loop
            }
        }
    }

    private void pushNode(final String name, final byte op, final byte[] body) {
        final Node node = this.nodes.get(name);
        if (node != null) {
            try {
                node.channel.send(ClusterProtocol.push(new ClusterProtocol.Push(op, body)));
            } catch (final IOException ignored) {
                // gone
            }
        }
    }

    /** The node a joining player should go to: a pending move, the owner of their last position, or the least busy node. */
    String route(final String uuid) {
        final Transfer transfer = this.transfers.get(uuid);
        if (transfer != null && this.nodes.containsKey(transfer.to())) {
            return transfer.to();
        }
        final ClusterProtocol.PlayerPos pos = this.lastPos.get(uuid);
        if (pos != null) {
            final String owner = this.owners.get(Cell.of(pos.dimension(), pos.chunkX(), pos.chunkZ()));
            if (owner != null && this.nodes.containsKey(owner) && !this.nodes.get(owner).draining) {
                return owner;
            }
        }
        return this.nodes.values().stream().filter(n -> !n.draining)
            .min(java.util.Comparator.comparingInt((Node n) -> n.lastStats.players()).thenComparing(n -> n.name))
            .map(n -> n.name).orElse(null);
    }

    // ---------------------------------------------------------------------------------------------
    // Placement: one cluster region, one node
    // ---------------------------------------------------------------------------------------------

    private void placementLoop() {
        while (true) {
            try {
                Thread.sleep(1000L);
                this.place();
            } catch (final InterruptedException ex) {
                return;
            } catch (final Exception ex) {
                this.log.accept("Cluster placement failed: " + ex);
            }
        }
    }

    /** A connected group of active cells and, per node, how many players it has in it. */
    private record Component(java.util.Set<Cell> cells, Map<String, Integer> players) {
        int total() {
            return this.players.values().stream().mapToInt(Integer::intValue).sum();
        }
    }

    private void place() {
        final long now = System.currentTimeMillis();
        this.transfers.values().removeIf(t -> now - t.started() > 15_000L);
        this.transferIds.keySet().removeIf(uuid -> !this.transfers.containsKey(uuid));
        // cell -> node -> players
        final Map<Cell, Map<String, Integer>> activity = new java.util.HashMap<>();
        for (final Node node : this.nodes.values()) {
            for (final var entry : node.active.cells().entrySet()) {
                activity.computeIfAbsent(entry.getKey(), c -> new java.util.HashMap<>()).merge(node.name, entry.getValue(), Integer::sum);
            }
        }
        final List<Component> components = new ArrayList<>();
        final java.util.Set<Cell> seen = new java.util.HashSet<>();
        for (final Cell start : activity.keySet()) {
            if (!seen.add(start)) {
                continue;
            }
            final java.util.Set<Cell> cells = new java.util.HashSet<>();
            final Map<String, Integer> players = new java.util.HashMap<>();
            final java.util.ArrayDeque<Cell> queue = new java.util.ArrayDeque<>(List.of(start));
            while (!queue.isEmpty()) {
                final Cell cell = queue.poll();
                cells.add(cell);
                activity.getOrDefault(cell, Map.of()).forEach((n, p) -> players.merge(n, p, Integer::sum));
                final List<Cell> next = new ArrayList<>(this.links.getOrDefault(cell, java.util.Set.of()));
                for (int dx = -1; dx <= 1; ++dx) {
                    for (int dz = -1; dz <= 1; ++dz) {
                        next.add(new Cell(cell.dimension(), cell.x() + dx, cell.z() + dz));
                    }
                }
                for (final Cell neighbour : next) {
                    if (activity.containsKey(neighbour) && seen.add(neighbour)) {
                        queue.add(neighbour);
                    }
                }
            }
            components.add(new Component(cells, players));
        }
        // 1. merge: a component active on several nodes goes to the node with most players there
        for (final Component component : components) {
            if (component.players().size() < 2) {
                continue;
            }
            final String target = component.players().entrySet().stream()
                .filter(e -> this.nodes.containsKey(e.getKey()) && !this.nodes.get(e.getKey()).draining)
                .max(java.util.Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue)
                    .thenComparing(e -> -this.load(e.getKey())).thenComparing(Map.Entry::getKey, java.util.Comparator.reverseOrder()))
                .map(Map.Entry::getKey).orElse(null);
            if (target != null) {
                this.moveComponent(component, target, "areas met");
            }
        }
        // 2. balance: every 10 s, move one whole component from the busiest to the quietest node
        if (now - this.lastBalance < 10_000L || !this.transfers.isEmpty() || this.nodes.size() < 2) {
            return;
        }
        this.lastBalance = now;
        final Map<String, Integer> totals = new java.util.HashMap<>();
        this.nodes.values().stream().filter(n -> !n.draining).forEach(n -> totals.put(n.name, 0));
        for (final Component component : components) {
            component.players().forEach((n, p) -> totals.computeIfPresent(n, (k, v) -> v + p));
        }
        if (totals.size() < 2) {
            return;
        }
        final String heavy = totals.entrySet().stream().max(Map.Entry.comparingByValue()).get().getKey();
        final String light = totals.entrySet().stream().min(Map.Entry.comparingByValue()).get().getKey();
        final int gap = totals.get(heavy) - totals.get(light);
        if (gap < 2) {
            return;
        }
        components.stream()
            .filter(c -> c.players().size() == 1 && c.players().containsKey(heavy) && c.total() > 0 && c.total() * 2 <= gap)
            .min(java.util.Comparator.comparingInt(Component::total))
            .ifPresent(c -> this.moveComponent(c, light, "balancing " + heavy + " -> " + light));
    }

    /** A shared data file: only map and command storage files under data/, nothing else. */
    private Path dataFile(final String relative) throws IOException {
        if (!relative.matches("data/[a-z0-9_.-]+/(map_[0-9]+|command_storage_[a-z0-9_.-]+|scoreboard)\\.dat")) {
            throw new IOException("not a shared data file: " + relative);
        }
        return this.world.resolve(relative);
    }

    private final Map<String, Long> counters = new ConcurrentHashMap<>();

    /** Next value of a cluster-wide counter, persisted next to the world. Map ids start after the highest map file. */
    private synchronized long nextCounter(final String name) throws IOException {
        if (!name.equals("map")) {
            throw new IOException("unknown counter " + name);
        }
        final Path file = this.world.resolve("storia-cluster-counters.txt");
        if (this.counters.isEmpty() && Files.exists(file)) {
            for (final String line : Files.readAllLines(file)) {
                final String[] kv = line.split("=");
                if (kv.length == 2) {
                    this.counters.put(kv[0], Long.parseLong(kv[1].trim()));
                }
            }
        }
        long next = this.counters.getOrDefault(name, -1L);
        if (next < 0) {
            next = 0;
            final Path maps = this.world.resolve("data").resolve("minecraft");
            if (Files.isDirectory(maps)) {
                try (var files = Files.list(maps)) {
                    for (final Path map : (Iterable<Path>) files::iterator) {
                        final String n = map.getFileName().toString();
                        if (n.matches("map_[0-9]+\\.dat")) {
                            next = Math.max(next, Long.parseLong(n.substring(4, n.length() - 4)) + 1);
                        }
                    }
                }
            }
        }
        this.counters.put(name, next + 1);
        final StringBuilder out = new StringBuilder();
        this.counters.forEach((k, v) -> out.append(k).append('=').append(v).append('\n'));
        Files.writeString(file, out.toString());
        return next;
    }

    /** The node that keeps time and weather: the connected node with the lowest index. */
    private String primaryNode() {
        return this.nodes.values().stream().min(java.util.Comparator.comparingInt((Node n) -> n.index)).map(n -> n.name).orElse(null);
    }

    private double load(final String node) {
        final Node n = this.nodes.get(node);
        return n == null ? Double.MAX_VALUE : n.lastStats.players();
    }

    /** Moves every player in the component to {@code target} and asks the other nodes to let go of its cells. */
    private void moveComponent(final Component component, final String target, final String why) {
        for (final Node node : this.nodes.values()) {
            if (node.name.equals(target) || !component.players().containsKey(node.name)) {
                continue;
            }
            for (final ClusterProtocol.PlayerPos pos : node.active.players()) {
                if (component.cells().contains(Cell.of(pos.dimension(), pos.chunkX(), pos.chunkZ())) && !this.transfers.containsKey(pos.uuid())) {
                    this.transfers.put(pos.uuid(), new Transfer(pos.uuid(), node.name, target, System.currentTimeMillis(), why));
                    this.pushNode(node.name, ClusterProtocol.PUSH_PREPARE, ClusterProtocol.string(pos.uuid()));
                }
            }
            final List<Cell> owned = new ArrayList<>();
            for (final Cell cell : component.cells()) {
                if (node.name.equals(this.owners.get(cell))) {
                    owned.add(cell);
                }
            }
            if (!owned.isEmpty()) {
                this.pushNode(node.name, ClusterProtocol.PUSH_EVICT, ClusterProtocol.cells(owned));
            }
        }
    }

    public List<String> statusLines() {
        final List<String> lines = new ArrayList<>();
        lines.add("Cluster world " + this.world.toAbsolutePath() + ": " + this.nodes.size() + " node(s), " + this.owners.size()
            + " owned cell(s), " + this.proxies.size() + " proxy(ies), " + this.moves.get() + " player move(s), " + (this.links.values().stream().mapToInt(java.util.Set::size).sum() / 2) + " contraption link(s), " + this.reads.get() + " reads, " + this.writes.get() + " writes, " + this.denied.get() + " denied writes");
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
