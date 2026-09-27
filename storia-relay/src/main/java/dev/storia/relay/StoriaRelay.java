package dev.storia.relay;

import dev.storia.cluster.protocol.ClusterProtocol;
import dev.storia.net.Handshake;
import dev.storia.net.SecureChannel;
import dev.storia.relay.cluster.ClusterCoordinator;
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
 */
public final class StoriaRelay {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final Properties config;
    private final String secret;
    private final boolean compress;
    private ClusterCoordinator cluster;

    private StoriaRelay(final Properties config) {
        this.config = config;
        this.secret = config.getProperty("secret", "");
        this.compress = Boolean.parseBoolean(config.getProperty("compress", "true"));
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

    private void run() throws IOException {
        final Path world = Path.of(this.config.getProperty("cluster-world", "cluster-world"));
        this.cluster = new ClusterCoordinator(world, StoriaRelay::log);
        log("Cluster mode on: storing the shared world in " + world.toAbsolutePath());
        final String bind = this.config.getProperty("bind", "0.0.0.0");
        final int port = Integer.parseInt(this.config.getProperty("port", "25590"));
        final Thread console = new Thread(this::console, "console");
        console.setDaemon(true);
        console.start();
        try (ServerSocket listener = new ServerSocket()) {
            listener.bind(new InetSocketAddress(bind, port));
            log("Storia Relay listening on " + bind + ":" + port + " (encrypted). Type 'status' or 'stop'.");
            while (true) {
                final Socket socket = listener.accept();
                final Thread thread = new Thread(() -> this.handle(socket), "conn " + socket.getRemoteSocketAddress());
                thread.setDaemon(true);
                thread.start();
            }
        }
    }

    private void console() {
        final BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                switch (line.trim().toLowerCase(java.util.Locale.ROOT)) {
                    case "status" -> this.cluster.statusLines().forEach(StoriaRelay::log);
                    case "stop", "end", "exit" -> {
                        log("Stopping.");
                        System.exit(0);
                    }
                    case "" -> { }
                    default -> log("Commands: status, stop");
                }
            }
        } catch (final IOException ignored) {
            // no console
        }
    }

    private void handle(final Socket socket) {
        final SecureChannel channel;
        final Handshake.Hello hello;
        try {
            channel = SecureChannel.respond(socket, this.secret, this.compress);
            hello = Handshake.readHello(channel.receive());
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
            if (hello.role() == ClusterProtocol.ROLE_NODE) {
                this.cluster.serve(channel, hello);
            } else if (hello.role() == ClusterProtocol.ROLE_PROXY) {
                this.cluster.serveProxy(channel);
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
}
