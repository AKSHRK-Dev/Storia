package dev.storia.offload;

import com.mojang.logging.LogUtils;
import dev.storia.offload.protocol.Messages;
import dev.storia.offload.protocol.SecureChannel;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import org.slf4j.Logger;

/**
 * Server 2: computes NOISE requests on a thread pool with this server's own (identical) world generator.
 * Nothing is written to this server's worlds.
 *
 * <p>Either listens for Storia servers to connect ({@code offload.port}), or, when {@code offload.relay} is set,
 * connects out to a Storia Relay and takes work from it.
 */
final class OffloadWorker {

    private static final Logger LOGGER = LogUtils.getClassLogger();

    private final OffloadConfig config;
    private final ExecutorService pool;
    private final int threads;
    private volatile Map<String, String> probes;
    final AtomicLong computed = new AtomicLong();
    final AtomicInteger connections = new AtomicInteger();
    volatile String status = "starting";

    private OffloadWorker(final OffloadConfig config) {
        this.config = config;
        this.threads = config.threads() > 0 ? config.threads() : Runtime.getRuntime().availableProcessors();
        final AtomicInteger ids = new AtomicInteger();
        this.pool = Executors.newFixedThreadPool(this.threads, r -> {
            final Thread thread = new Thread(r, "Storia Offload Worker #" + ids.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        });
    }

    static OffloadWorker start(final OffloadConfig config) {
        if (config.secret().length() < 8) {
            LOGGER.error("offload.mode is worker but offload.secret is shorter than 8 characters; refusing to start");
            return null;
        }
        final OffloadWorker worker = new OffloadWorker(config);
        final Thread thread = config.relay().isEmpty()
            ? new Thread(worker::acceptLoop, "Storia Offload Acceptor")
            : new Thread(worker::relayLoop, "Storia Offload Relay Link");
        thread.setDaemon(true);
        thread.start();
        return worker;
    }

    int threads() {
        return this.threads;
    }

    private Map<String, String> probes() {
        Map<String, String> result = this.probes;
        if (result == null) {
            synchronized (this) {
                if (this.probes == null) {
                    final Map<String, String> computedProbes = new LinkedHashMap<>();
                    for (final ServerLevel level : MinecraftServer.getServer().getAllLevels()) {
                        if (level.getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator) {
                            computedProbes.put(NoiseOffload.levelId(level), NoiseCodec.probe(level));
                        }
                    }
                    this.probes = computedProbes;
                }
                result = this.probes;
            }
        }
        return result;
    }

    private static void awaitStarted() throws InterruptedException {
        while (!NoiseOffload.serverStarted) {
            Thread.sleep(500L);
        }
    }

    // ---- listening for servers ----------------------------------------------------------------------

    private void acceptLoop() {
        try (ServerSocket server = new ServerSocket()) {
            server.bind(new InetSocketAddress(this.config.bind(), this.config.port()));
            this.status = "listening on " + this.config.bind() + ":" + this.config.port();
            LOGGER.info("Offload worker listening on {}:{} with {} threads (encrypted)", this.config.bind(), this.config.port(), this.threads);
            while (true) {
                final Socket socket = server.accept();
                final Thread handler = new Thread(() -> this.handleServer(socket), "Storia Offload Connection " + socket.getRemoteSocketAddress());
                handler.setDaemon(true);
                handler.start();
            }
        } catch (final IOException ex) {
            this.status = "stopped: " + ex.getMessage();
            LOGGER.error("Offload worker stopped", ex);
        }
    }

    private void handleServer(final Socket socket) {
        this.connections.incrementAndGet();
        try (SecureChannel channel = SecureChannel.respond(socket, this.config.secret(), this.config.compress())) {
            final Messages.Hello hello = Messages.readHello(channel.receive());
            if (hello.role() != Messages.ROLE_SERVER) {
                channel.send(Messages.welcome(new Messages.Welcome(false, "this is a worker; connect workers to a relay instead")));
                return;
            }
            if (!NoiseOffload.serverStarted) {
                channel.send(Messages.welcome(new Messages.Welcome(false, "worker is still starting")));
                return;
            }
            final List<String> accepted = new ArrayList<>();
            final List<String> rejected = new ArrayList<>();
            final Map<String, String> own = this.probes();
            for (final Map.Entry<String, String> probe : hello.probes().entrySet()) {
                if (probe.getValue().equals(own.get(probe.getKey()))) {
                    accepted.add(probe.getKey());
                } else {
                    rejected.add(probe.getKey() + (own.containsKey(probe.getKey()) ? " (terrain differs: check seed, datapacks and Storia build)" : " (no such dimension here)"));
                }
            }
            channel.send(Messages.welcome(new Messages.Welcome(true, String.join(", ", rejected))));
            channel.send(Messages.capacity(new Messages.Capacity(this.threads, accepted)));
            LOGGER.info("Offload server {} connected; accepted {}{}", channel.remoteAddress(), accepted, rejected.isEmpty() ? "" : ", rejected " + rejected);
            this.serve(channel);
        } catch (final IOException ex) {
            LOGGER.info("Offload server {} disconnected: {}", socket.getRemoteSocketAddress(), ex.getMessage());
        } finally {
            this.connections.decrementAndGet();
        }
    }

    // ---- connecting to a relay ----------------------------------------------------------------------

    private void relayLoop() {
        long backoff = 2_000L;
        while (true) {
            try {
                awaitStarted();
                final HostPort target = HostPort.parse(this.config.relay(), 25590);
                final Socket socket = new Socket();
                socket.connect(new InetSocketAddress(target.host(), target.port()), 5_000);
                try (SecureChannel channel = SecureChannel.initiate(socket, this.config.secret(), this.config.compress())) {
                    channel.send(Messages.hello(new Messages.Hello(Messages.ROLE_WORKER, this.threads, this.probes())));
                    final Messages.Welcome welcome = Messages.readWelcome(channel.receive());
                    if (!welcome.ok()) {
                        throw new IOException("relay refused: " + welcome.message());
                    }
                    this.connections.incrementAndGet();
                    this.status = "connected to relay " + this.config.relay();
                    LOGGER.info("Offload worker connected to relay {} with {} threads for {}", this.config.relay(), this.threads, this.probes().keySet());
                    backoff = 2_000L;
                    try {
                        this.serve(channel);
                    } finally {
                        this.connections.decrementAndGet();
                    }
                }
            } catch (final InterruptedException ex) {
                return;
            } catch (final Throwable ex) {
                this.status = "relay link down: " + ex.getMessage();
                LOGGER.warn("Offload relay link to {} failed: {}", this.config.relay(), ex.getMessage());
            }
            try {
                Thread.sleep(backoff);
            } catch (final InterruptedException ex) {
                return;
            }
            backoff = Math.min(60_000L, backoff * 2);
        }
    }

    // ---- work -------------------------------------------------------------------------------------

    private void serve(final SecureChannel channel) throws IOException {
        while (true) {
            final byte[] message = channel.receive();
            if (Messages.type(message) != Messages.REQUEST) {
                continue;
            }
            final Messages.Request request = Messages.readRequest(message);
            this.pool.execute(() -> {
                final byte[] reply;
                try {
                    reply = Messages.response(this.compute(request));
                } catch (final IOException ex) {
                    return;
                }
                try {
                    channel.send(reply);
                } catch (final IOException ex) {
                    // connection gone; the server times out and generates locally
                }
            });
        }
    }

    private Messages.Response compute(final Messages.Request request) throws IOException {
        try {
            final ServerLevel level = NoiseOffload.levelById(request.dim());
            if (level == null) {
                throw new IOException("unknown dimension " + request.dim());
            }
            final DataInputStream in = new DataInputStream(new ByteArrayInputStream(request.body()));
            final int x = in.readInt();
            final int z = in.readInt();
            final Beardifier beardifier = NoiseCodec.readBeardifier(in);
            final ProtoChunk chunk = NoiseCodec.computeDetached(level, x, z, beardifier);
            final Messages.Response response = new Messages.Response(request.id(), Messages.STATUS_OK, NoiseCodec.encodeResult(chunk));
            this.computed.incrementAndGet();
            return response;
        } catch (final Throwable ex) {
            LOGGER.warn("Offload request {} failed", request.id(), ex);
            return new Messages.Response(request.id(), Messages.STATUS_ERROR, Messages.error(String.valueOf(ex)));
        }
    }

    static byte[] requestBody(final int x, final int z, final Beardifier beardifier) throws IOException {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream(128);
        final DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(x);
        out.writeInt(z);
        NoiseCodec.writeBeardifier(out, beardifier);
        return bytes.toByteArray();
    }

    public record HostPort(String host, int port) {
        public static HostPort parse(final String address, final int defaultPort) {
            final int colon = address.lastIndexOf(':');
            return colon < 0 ? new HostPort(address, defaultPort) : new HostPort(address.substring(0, colon), Integer.parseInt(address.substring(colon + 1)));
        }
    }
}
