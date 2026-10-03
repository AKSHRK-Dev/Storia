package dev.storia.relay;

import dev.storia.cluster.protocol.ClusterProtocol;
import dev.storia.net.Handshake;
import dev.storia.net.SecureChannel;
import dev.storia.relay.cluster.ClusterCoordinator;
import dev.storia.relay.cluster.Replication;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Properties;

/**
 * Storia Relay: the coordinator of a Storia Cluster.
 *
 * <pre>
 *   players --&gt; Storia Proxy --&gt; Storia Worker A --\
 *                            \-&gt; Storia Worker B ---&gt; Storia Relay: the world, who runs what
 *                             \-&gt; Storia Worker C --/
 * </pre>
 *
 * The relay stores the world (normal Anvil files), player data and shared data, grants each part of the world to
 * one worker at a time, merges and balances players across workers and tells Storia Proxy when to move a player.
 * All links are encrypted with the shared secret.
 *
 * <p>A second relay can run as a <b>standby</b> ({@code role=standby}, {@code peer=} the active relay): it keeps a
 * live copy of everything and takes over with {@code promote} (see STANDBY.md).
 */
public final class StoriaRelay {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    /** Role and term of this relay; next to relay.properties, never inside the world (which is copied). */
    private static final Path STATE_FILE = Path.of("relay-state.txt");

    private final Properties config;
    private final String secret;
    private final boolean compress;
    private final Path world;
    private final String peer;
    private volatile boolean active;
    private volatile long term;
    private volatile ClusterCoordinator cluster;
    private volatile Replication.Sink sink;
    private volatile SecureChannel replicationChannel;

    private StoriaRelay(final Properties config) {
        this.config = config;
        this.secret = config.getProperty("secret", "");
        this.compress = Boolean.parseBoolean(config.getProperty("compress", "true"));
        this.world = Path.of(config.getProperty("cluster-world", "cluster-world"));
        this.peer = config.getProperty("peer", "").trim();
    }

    public static void main(final String[] args) throws IOException {
        final Path file = Path.of(args.length > 0 ? args[0] : "relay.properties");
        if (!Files.exists(file)) {
            try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                writer.write("""
                    # Storia Relay: the coordinator of a Storia Cluster.
                    # Workers: cluster.enabled: true, cluster.coordinator: "<this host>:<port>", cluster.node-name
                    # and cluster.secret in storia.yml. Storia Proxy: [cluster] in storia-proxy.toml.
                    # Everyone must use the same secret (at least 8 characters). Traffic is encrypted.
                    # Guide: https://storiamc.com/en-us/docs/cluster/
                    bind=0.0.0.0
                    port=25590
                    secret=
                    # Deflate frames before encryption (saves bandwidth, costs a little CPU).
                    compress=true
                    # The shared world: a normal world folder. Only the relay writes to it.
                    cluster-world=cluster-world
                    # Optional standby relay (https://storiamc.com/en-us/docs/relay/): set role=standby on the second
                    # relay and peer=<the other relay's host:port> on both. Type 'promote' on the standby to take over.
                    role=active
                    peer=
                    """);
            }
            log("Created " + file.toAbsolutePath() + "; set a secret, put your world in cluster-world and start again.");
            return;
        }
        final Properties config = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            config.load(reader);
        }
        if (config.getProperty("secret", "").length() < 8) {
            log("secret in " + file + " must be at least 8 characters.");
            System.exit(1);
        }
        new StoriaRelay(config).run();
    }

    static void log(final String message) {
        System.out.println("[" + LocalTime.now().format(TIME) + "] " + message);
    }

    // ---------------------------------------------------------------------------------------------
    // Start-up: which role this relay takes
    // ---------------------------------------------------------------------------------------------

    private void run() throws IOException {
        this.loadState();
        if (!this.peer.isEmpty()) {
            final Probe other = this.probe();
            if (other == null) {
                log("The other relay (" + this.peer + ") is not reachable; starting as " + (this.active ? "the active relay" : "a standby") + " (term " + this.term + ")");
            } else if (other.active() && this.active) {
                if (other.term() > this.term) {
                    log("The other relay (" + this.peer + ") took over while this one was away (term " + other.term() + " > " + this.term
                        + "); this relay becomes its standby and copies the world from it");
                    this.active = false;
                    this.term = other.term();
                    this.saveState();
                } else {
                    log("Both relays are active (the other one has term " + other.term() + ", this one " + this.term + "). Stop one of them, "
                        + "then start it again as the standby, so the world is never written in two places.");
                    System.exit(1);
                }
            } else if (!other.active() && !this.active) {
                log("The other relay (" + this.peer + ") is a standby too; type 'promote' on the one with the newest copy");
            }
        }
        if (this.active) {
            this.startActive();
        } else {
            this.startStandby();
        }
        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown, "relay shutdown"));
        final String bind = this.config.getProperty("bind", "0.0.0.0");
        final int port = Integer.parseInt(this.config.getProperty("port", "25590"));
        final Thread console = new Thread(this::console, "console");
        console.setDaemon(true);
        console.start();
        try (ServerSocket listener = new ServerSocket()) {
            listener.bind(new InetSocketAddress(bind, port));
            log("Storia Relay listening on " + bind + ":" + port + " (encrypted). Type 'status'" + (this.active ? "" : ", 'promote'") + " or 'stop'.");
            while (true) {
                final Socket socket = listener.accept();
                final Thread thread = new Thread(() -> this.handle(socket), "conn " + socket.getRemoteSocketAddress());
                thread.setDaemon(true);
                thread.start();
            }
        }
    }

    private void startActive() throws IOException {
        this.cluster = new ClusterCoordinator(this.world, StoriaRelay::log);
        log("Active relay (term " + this.term + "): storing the shared world in " + this.world.toAbsolutePath()
            + (this.peer.isEmpty() ? "" : "; the standby " + this.peer + " gets a copy of every write"));
    }

    private void startStandby() throws IOException {
        if (this.peer.isEmpty()) {
            log("role=standby needs peer=<the active relay's host:port> in relay.properties");
            System.exit(1);
        }
        this.sink = new Replication.Sink(this.world, StoriaRelay::log);
        log("Standby relay (term " + this.term + "): copying the world from " + this.peer + " into " + this.world.toAbsolutePath());
        final Thread thread = new Thread(this::replicationLoop, "replication");
        thread.setDaemon(true);
        thread.start();
    }

    /** As a standby: keep a connection to the active relay and apply its changes. */
    private void replicationLoop() {
        boolean warned = false;
        while (!this.active) {
            try {
                final SecureChannel channel = this.connectPeer(Map.of("term", Long.toString(this.term)));
                final Handshake.Welcome welcome = Handshake.readWelcome(channel.receive());
                if (!welcome.ok()) {
                    channel.close();
                    throw new IOException(welcome.message());
                }
                channel.clearReadTimeout(); // changes can be minutes apart
                warned = false;
                this.replicationChannel = channel;
                final Replication.Sink current = this.sink;
                if (current == null || this.active) {
                    channel.close();
                    return;
                }
                current.run(channel);
            } catch (final IOException | RuntimeException ex) {
                if (this.active) {
                    return;
                }
                if (!warned) {
                    log("Not connected to the active relay " + this.peer + " (" + ex.getMessage() + "); retrying. "
                        + "If it is gone for good, type 'promote' to take over.");
                    warned = true;
                }
            }
            try {
                Thread.sleep(2000L);
            } catch (final InterruptedException ex) {
                return;
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Taking over
    // ---------------------------------------------------------------------------------------------

    private synchronized void promote(final boolean force) {
        if (this.active) {
            log("This relay is already the active one.");
            return;
        }
        final Probe other = this.probe();
        if (other != null && other.active()) {
            log("The active relay " + this.peer + " is still running (term " + other.term() + "). Stop it first: two active relays "
                + "would write the world in two places.");
            return;
        }
        final Replication.Sink current = this.sink;
        if (!force && (current == null || !current.inSync())) {
            log("This standby was not in sync with the active relay when it last heard from it, so it may miss recent writes. "
                + "Type 'promote force' to take over anyway.");
            return;
        }
        log("Taking over as the active relay...");
        this.active = true;
        final SecureChannel channel = this.replicationChannel;
        if (channel != null) {
            channel.close();
        }
        try {
            if (current != null) {
                current.close();
            }
            this.sink = null;
            this.term++;
            this.saveState();
            this.startActive();
            log("This relay is now the active relay (term " + this.term + "). Workers and Storia Proxy connect to it by themselves "
                + "if it is in their list of relays. Start the old relay again later: it becomes the standby.");
        } catch (final IOException ex) {
            log("Could not take over: " + ex.getMessage());
            System.exit(1);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // The other relay
    // ---------------------------------------------------------------------------------------------

    private record Probe(boolean active, long term) {}

    /** Asks the other relay whether it is active and its term; null if it does not answer. */
    private Probe probe() {
        try {
            final SecureChannel channel = this.connectPeer(Map.of("probe", "1", "term", Long.toString(this.term)));
            try {
                final Handshake.Welcome welcome = Handshake.readWelcome(channel.receive());
                final String[] parts = welcome.message().split(" ");
                long term = 0;
                for (final String part : parts) {
                    if (part.startsWith("term=")) {
                        term = Long.parseLong(part.substring(5));
                    }
                }
                return new Probe(parts.length > 0 && parts[0].equals("active"), term);
            } finally {
                channel.close();
            }
        } catch (final IOException | RuntimeException ex) {
            return null;
        }
    }

    private SecureChannel connectPeer(final Map<String, String> values) throws IOException {
        final int colon = this.peer.lastIndexOf(':');
        final String host = colon < 0 ? this.peer : this.peer.substring(0, colon);
        final int port = colon < 0 ? 25590 : Integer.parseInt(this.peer.substring(colon + 1));
        final Socket socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), 3000);
        socket.setSoTimeout(5000);
        final SecureChannel channel = SecureChannel.initiate(socket, this.secret, this.compress);
        channel.send(Handshake.hello(new Handshake.Hello(ClusterProtocol.ROLE_REPLICA, 0, values)));
        // initiate() cleared the timeout; keep one for the WELCOME (a standby clears it once it is in)
        socket.setSoTimeout(5000);
        return channel;
    }

    private void loadState() throws IOException {
        this.active = !"standby".equalsIgnoreCase(this.config.getProperty("role", "active").trim());
        this.term = 0;
        if (Files.exists(STATE_FILE)) {
            final Properties state = new Properties();
            try (Reader reader = Files.newBufferedReader(STATE_FILE, StandardCharsets.UTF_8)) {
                state.load(reader);
            }
            this.term = Long.parseLong(state.getProperty("term", "0").trim());
            this.active = "active".equals(state.getProperty("role", this.active ? "active" : "standby").trim());
        }
    }

    private void saveState() throws IOException {
        final Path temp = STATE_FILE.resolveSibling(STATE_FILE.getFileName() + ".tmp");
        Files.writeString(temp, "# Written by Storia Relay: this relay's current role and term. Do not edit while it runs.\n"
            + "role=" + (this.active ? "active" : "standby") + "\nterm=" + this.term + "\n");
        Files.move(temp, STATE_FILE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    private void shutdown() {
        try {
            final ClusterCoordinator coordinator = this.cluster;
            if (coordinator != null) {
                coordinator.close();
            }
            final Replication.Sink current = this.sink;
            if (current != null) {
                current.close();
            }
            log("World files written to disk.");
        } catch (final IOException ex) {
            log("Could not write the world files to disk on stop: " + ex.getMessage());
        }
    }

    private void console() {
        final BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                switch (line.trim().toLowerCase(java.util.Locale.ROOT)) {
                    case "status" -> this.status();
                    case "promote" -> this.promote(false);
                    case "promote force" -> this.promote(true);
                    case "stop", "end", "exit" -> {
                        log("Stopping.");
                        System.exit(0);
                    }
                    case "" -> { }
                    default -> log("Commands: status, promote, stop");
                }
            }
        } catch (final IOException ignored) {
            // no console
        }
    }

    private void status() {
        if (this.active) {
            log("Active relay, term " + this.term);
            this.cluster.statusLines().forEach(StoriaRelay::log);
        } else {
            final Replication.Sink current = this.sink;
            log("Standby relay, term " + this.term + ", active relay " + this.peer + ": " + (current == null ? "stopped" : current.status()));
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Connections
    // ---------------------------------------------------------------------------------------------

    private void handle(final Socket socket) {
        final SecureChannel channel;
        final Handshake.Hello hello;
        try {
            channel = SecureChannel.respond(socket, this.secret, this.compress);
            hello = Handshake.readHello(channel.receive());
            channel.clearReadTimeout();
        } catch (final IOException ex) {
            log("Rejected " + socket.getRemoteSocketAddress() + ": " + ex.getMessage());
            try {
                socket.close();
            } catch (final IOException ignored) {
                // closing anyway
            }
            return;
        }
        try {
            if (hello.role() == ClusterProtocol.ROLE_REPLICA) {
                this.handleReplica(channel, hello);
            } else if (hello.role() == ClusterProtocol.ROLE_NODE || hello.role() == ClusterProtocol.ROLE_PROXY) {
                final ClusterCoordinator coordinator = this.cluster;
                if (!this.active || coordinator == null) {
                    // workers and Storia Proxy try the next relay in their list
                    channel.send(Handshake.welcome(new Handshake.Welcome(false, "standby; the active relay is " + this.peer)));
                } else if (hello.role() == ClusterProtocol.ROLE_NODE) {
                    coordinator.serve(channel, hello);
                } else {
                    coordinator.serveProxy(channel);
                }
            } else {
                // 0 and 1 were servers and workers of the removed terrain offload (26.2-2-beta and earlier)
                channel.send(Handshake.welcome(new Handshake.Welcome(false,
                    "terrain offload was removed; this relay only runs a Storia Cluster (update Storia and use cluster.* in storia.yml)")));
                log("Rejected " + socket.getRemoteSocketAddress() + ": an old Storia asked for terrain offload, which was removed");
            }
        } catch (final IOException ex) {
            // connection ended
        } finally {
            channel.close();
        }
    }

    /** The other relay: a probe ("are you active?") or a standby that wants a copy of the world. */
    private void handleReplica(final SecureChannel channel, final Handshake.Hello hello) throws IOException {
        final long theirTerm = Long.parseLong(hello.values().getOrDefault("term", "0"));
        if (hello.values().containsKey("probe")) {
            channel.send(Handshake.welcome(new Handshake.Welcome(true, (this.active ? "active" : "standby") + " term=" + this.term)));
            return;
        }
        final ClusterCoordinator coordinator = this.cluster;
        if (!this.active || coordinator == null) {
            channel.send(Handshake.welcome(new Handshake.Welcome(false, "this relay is a standby too")));
            return;
        }
        if (theirTerm > this.term) {
            channel.send(Handshake.welcome(new Handshake.Welcome(false, "this relay is out of date (term " + this.term + " < " + theirTerm + ")")));
            log("WARNING: a relay with a newer term (" + theirTerm + ") wants to copy from this one; this relay is out of date. "
                + "Stop it and start it again: it will become the standby.");
            return;
        }
        channel.send(Handshake.welcome(new Handshake.Welcome(true, "term=" + this.term)));
        coordinator.replication().serve(channel);
    }
}
