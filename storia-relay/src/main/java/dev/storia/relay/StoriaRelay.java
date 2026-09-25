package dev.storia.relay;

import dev.storia.offload.protocol.Messages;
import dev.storia.offload.protocol.SecureChannel;
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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Storia Relay: sits between Storia servers and any number of Storia Workers and hands out terrain work.
 *
 * <pre>
 *   Storia server 1 --\                     /-- Storia Worker A
 *   Storia server 2 ----&gt;  Storia Relay  &lt;---- Storia Worker B
 *                                           \-- Storia Worker C (joins/leaves any time)
 * </pre>
 *
 * Servers connect as they would to a worker ({@code offload.workers: ["relay-host:25590"]}); workers connect out
 * to the relay ({@code offload.relay: "relay-host:25590"}). Each request goes to the least busy worker whose
 * probe fingerprint for that dimension matches the requesting server's, so a worker never serves a world whose
 * terrain it would generate differently. When workers join or leave, every server is told its new capacity. If
 * a worker drops, its requests are retried on another worker, or failed so the server generates them locally.
 * All links are encrypted with the shared secret.
 */
public final class StoriaRelay {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final Properties config;
    private final String secret;
    private final boolean compress;
    private final int perThread;
    private final long timeoutNanos;
    private final List<Worker> workers = new CopyOnWriteArrayList<>();
    private final List<Server> servers = new CopyOnWriteArrayList<>();
    private final AtomicLong nextRelayId = new AtomicLong();
    private final AtomicLong forwarded = new AtomicLong();
    private final AtomicLong retried = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();

    private StoriaRelay(final Properties config) {
        this.config = config;
        this.secret = config.getProperty("secret", "");
        this.compress = Boolean.parseBoolean(config.getProperty("compress", "true"));
        this.perThread = Integer.parseInt(config.getProperty("in-flight-per-thread", "4"));
        this.timeoutNanos = Long.parseLong(config.getProperty("timeout-ms", "20000")) * 1_000_000L;
    }

    public static void main(final String[] args) throws IOException {
        final Path file = Path.of(args.length > 0 ? args[0] : "relay.properties");
        if (!Files.exists(file)) {
            try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                writer.write("""
                    # Storia Relay
                    # Storia servers:  offload.mode: client, offload.workers: ["<this host>:<port>"]
                    # Storia Workers:  offload.mode: worker, offload.relay: "<this host>:<port>"
                    # Everyone must use the same secret (at least 8 characters). Traffic is encrypted.
                    bind=0.0.0.0
                    port=25590
                    secret=
                    # Deflate frames before encryption (saves bandwidth, costs a little CPU).
                    compress=true
                    # Requests queued per worker thread.
                    in-flight-per-thread=4
                    # Give up on a worker's answer after this long (the server then generates the chunk itself).
                    timeout-ms=20000
                    """);
            }
            log("Created " + file.toAbsolutePath() + "; set a secret and start again.");
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
        final String bind = this.config.getProperty("bind", "0.0.0.0");
        final int port = Integer.parseInt(this.config.getProperty("port", "25590"));
        final Thread console = new Thread(this::console, "console");
        console.setDaemon(true);
        console.start();
        final Thread timeouts = new Thread(this::timeoutLoop, "timeouts");
        timeouts.setDaemon(true);
        timeouts.start();
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
                    case "status" -> this.printStatus();
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

    private void printStatus() {
        log("Workers: " + this.workers.size() + ", servers: " + this.servers.size() + ", forwarded " + this.forwarded.get()
            + ", retried " + this.retried.get() + ", rejected (no worker) " + this.rejected.get());
        for (final Worker worker : this.workers) {
            log("  worker " + worker.channel.remoteAddress() + ": " + worker.threads + " threads, " + worker.pending.size() + "/" + worker.limit()
                + " in flight, " + worker.done.get() + " done, dims " + worker.probes.keySet());
        }
        for (final Server server : this.servers) {
            log("  server " + server.channel.remoteAddress() + ": capacity " + server.lastCapacity);
        }
    }

    private void handle(final Socket socket) {
        final SecureChannel channel;
        final Messages.Hello hello;
        try {
            channel = SecureChannel.respond(socket, this.secret, this.compress);
            hello = Messages.readHello(channel.receive());
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
            if (hello.role() == Messages.ROLE_WORKER) {
                this.runWorker(new Worker(channel, hello.threads(), hello.probes()));
            } else if (hello.role() == Messages.ROLE_SERVER) {
                this.runServer(new Server(channel, hello.probes()));
            } else {
                channel.send(Messages.welcome(new Messages.Welcome(false, "unknown role")));
            }
        } catch (final IOException ex) {
            // connection ended; cleanup happens in runWorker/runServer
        } finally {
            channel.close();
        }
    }

    // ---- workers ----------------------------------------------------------------------------------

    private record Pending(Server server, long serverId, Messages.Request request, long deadline, Set<Worker> tried) {}

    private final class Worker {
        final SecureChannel channel;
        final int threads;
        final Map<String, String> probes;
        final Map<Long, Pending> pending = new ConcurrentHashMap<>();
        final AtomicLong done = new AtomicLong();

        Worker(final SecureChannel channel, final int threads, final Map<String, String> probes) {
            this.channel = channel;
            this.threads = Math.max(1, threads);
            this.probes = Map.copyOf(probes);
        }

        int limit() {
            return this.threads * StoriaRelay.this.perThread;
        }

        boolean serves(final Server server, final String dim) {
            final String mine = this.probes.get(dim);
            return mine != null && mine.equals(server.probes.get(dim));
        }
    }

    private void runWorker(final Worker worker) throws IOException {
        worker.channel.send(Messages.welcome(new Messages.Welcome(true, "")));
        this.workers.add(worker);
        log("Worker " + worker.channel.remoteAddress() + " joined: " + worker.threads + " threads, dims " + worker.probes.keySet());
        this.pushCapacity();
        try {
            while (true) {
                final byte[] message = worker.channel.receive();
                if (Messages.type(message) != Messages.RESPONSE) {
                    continue;
                }
                final Messages.Response response = Messages.readResponse(message);
                final Pending entry = worker.pending.remove(response.id());
                if (entry == null) {
                    continue;
                }
                worker.done.incrementAndGet();
                this.reply(entry.server(), new Messages.Response(entry.serverId(), response.status(), response.body()));
            }
        } finally {
            this.workers.remove(worker);
            log("Worker " + worker.channel.remoteAddress() + " left (" + worker.pending.size() + " request(s) in flight)");
            for (final Pending entry : worker.pending.values()) {
                entry.tried().add(worker);
                this.dispatch(entry.server(), entry.serverId(), entry.request(), entry.tried(), true);
            }
            worker.pending.clear();
            this.pushCapacity();
        }
    }

    // ---- servers ----------------------------------------------------------------------------------

    private final class Server {
        final SecureChannel channel;
        final Map<String, String> probes;
        volatile String lastCapacity = "none";

        Server(final SecureChannel channel, final Map<String, String> probes) {
            this.channel = channel;
            this.probes = Map.copyOf(probes);
        }
    }

    private void runServer(final Server server) throws IOException {
        server.channel.send(Messages.welcome(new Messages.Welcome(true, "")));
        this.servers.add(server);
        log("Server " + server.channel.remoteAddress() + " connected for dims " + server.probes.keySet());
        this.sendCapacity(server);
        try {
            while (true) {
                final byte[] message = server.channel.receive();
                if (Messages.type(message) != Messages.REQUEST) {
                    continue;
                }
                final Messages.Request request = Messages.readRequest(message);
                this.dispatch(server, request.id(), request, new LinkedHashSet<>(), false);
            }
        } finally {
            this.servers.remove(server);
            log("Server " + server.channel.remoteAddress() + " disconnected");
            for (final Worker worker : this.workers) {
                worker.pending.values().removeIf(entry -> entry.server() == server);
            }
        }
    }

    private void dispatch(final Server server, final long serverId, final Messages.Request request, final Set<Worker> tried, final boolean retry) {
        Worker best = null;
        for (final Worker worker : this.workers) {
            if (tried.contains(worker) || !worker.serves(server, request.dim()) || worker.pending.size() >= worker.limit()) {
                continue;
            }
            if (best == null || (double) worker.pending.size() / worker.limit() < (double) best.pending.size() / best.limit()) {
                best = worker;
            }
        }
        if (best == null) {
            this.rejected.incrementAndGet();
            try {
                this.reply(server, new Messages.Response(serverId, Messages.STATUS_ERROR, Messages.error("no worker available")));
            } catch (final IOException ignored) {
                // server gone
            }
            return;
        }
        final long relayId = this.nextRelayId.incrementAndGet();
        best.pending.put(relayId, new Pending(server, serverId, request, System.nanoTime() + this.timeoutNanos, tried));
        try {
            best.channel.send(Messages.request(new Messages.Request(relayId, request.dim(), request.body())));
            this.forwarded.incrementAndGet();
            if (retry) {
                this.retried.incrementAndGet();
            }
        } catch (final IOException ex) {
            best.pending.remove(relayId);
            tried.add(best);
            best.channel.close();
            this.dispatch(server, serverId, request, tried, true);
        }
    }

    private void reply(final Server server, final Messages.Response response) throws IOException {
        if (this.servers.contains(server)) {
            server.channel.send(Messages.response(response));
        }
    }

    private void timeoutLoop() {
        while (true) {
            try {
                Thread.sleep(1_000L);
            } catch (final InterruptedException ex) {
                return;
            }
            final long now = System.nanoTime();
            for (final Worker worker : this.workers) {
                for (final Map.Entry<Long, Pending> entry : worker.pending.entrySet()) {
                    if (now - entry.getValue().deadline() > 0 && worker.pending.remove(entry.getKey()) != null) {
                        try {
                            this.reply(entry.getValue().server(), new Messages.Response(entry.getValue().serverId(), Messages.STATUS_ERROR, Messages.error("worker timed out")));
                        } catch (final IOException ignored) {
                            // server gone
                        }
                    }
                }
            }
        }
    }

    // ---- capacity ---------------------------------------------------------------------------------

    private void pushCapacity() {
        for (final Server server : this.servers) {
            try {
                this.sendCapacity(server);
            } catch (final IOException ignored) {
                // its reader thread will clean up
            }
        }
    }

    private void sendCapacity(final Server server) throws IOException {
        final List<String> dims = new ArrayList<>();
        int threads = 0;
        for (final String dim : server.probes.keySet()) {
            for (final Worker worker : this.workers) {
                if (worker.serves(server, dim)) {
                    dims.add(dim);
                    break;
                }
            }
        }
        for (final Worker worker : this.workers) {
            for (final String dim : dims) {
                if (worker.serves(server, dim)) {
                    threads += worker.threads;
                    break;
                }
            }
        }
        server.lastCapacity = threads + " threads for " + dims;
        server.channel.send(Messages.capacity(new Messages.Capacity(threads, dims)));
    }
}
