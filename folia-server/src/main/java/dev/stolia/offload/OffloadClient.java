package dev.stolia.offload;

import com.mojang.logging.LogUtils;
import dev.stolia.offload.protocol.Messages;
import dev.stolia.offload.protocol.SecureChannel;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import org.slf4j.Logger;

/**
 * Server 1: keeps an encrypted connection to each configured worker or relay and sends NOISE requests to the
 * least busy one that has capacity for the dimension.
 */
final class OffloadClient {

    private static final Logger LOGGER = LogUtils.getClassLogger();

    private final OffloadConfig config;
    private final List<Connection> connections = new ArrayList<>();
    private final AtomicLong nextId = new AtomicLong();
    private volatile Map<String, String> localProbes;

    OffloadClient(final OffloadConfig config) {
        this.config = config;
        for (final String address : config.workers()) {
            this.connections.add(new Connection(address));
        }
    }

    void start() {
        for (final Connection connection : this.connections) {
            final Thread thread = new Thread(connection::connectLoop, "Stolia Offload Client " + connection.address);
            thread.setDaemon(true);
            thread.start();
        }
        final Thread timeouts = new Thread(this::timeoutLoop, "Stolia Offload Timeouts");
        timeouts.setDaemon(true);
        timeouts.start();
    }

    List<Connection> connections() {
        return Collections.unmodifiableList(this.connections);
    }

    /** Sends a request to the least loaded connection that accepts the dimension, or returns null if none has room. */
    CompletableFuture<byte[]> submit(final String dim, final int x, final int z, final Beardifier beardifier) {
        Connection best = null;
        for (final Connection connection : this.connections) {
            if (connection.canTake(dim) && (best == null || connection.load() < best.load())) {
                best = connection;
            }
        }
        if (best == null) {
            return null;
        }
        try {
            return best.send(this.nextId.incrementAndGet(), dim, OffloadWorker.requestBody(x, z, beardifier));
        } catch (final IOException ex) {
            return null;
        }
    }

    private synchronized Map<String, String> localProbes() {
        if (this.localProbes == null) {
            final Map<String, String> probes = new LinkedHashMap<>();
            for (final ServerLevel level : MinecraftServer.getServer().getAllLevels()) {
                if (level.getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator) {
                    probes.put(NoiseOffload.levelId(level), NoiseCodec.probe(level));
                }
            }
            this.localProbes = probes;
        }
        return this.localProbes;
    }

    private void timeoutLoop() {
        while (true) {
            try {
                Thread.sleep(250L);
            } catch (final InterruptedException ex) {
                return;
            }
            final long now = System.nanoTime();
            for (final Connection connection : this.connections) {
                connection.expire(now);
            }
        }
    }

    private record Pending(CompletableFuture<byte[]> future, long deadline, long sentAt) {}

    final class Connection {
        final String address;
        private volatile SecureChannel channel;
        private volatile Set<String> dims = Set.of();
        private volatile int remoteThreads;
        private volatile String status = "connecting";
        private final Map<Long, Pending> pending = new ConcurrentHashMap<>();
        private final AtomicInteger inFlight = new AtomicInteger();
        final AtomicLong completed = new AtomicLong();
        final AtomicLong failed = new AtomicLong();
        private final AtomicLong totalRttNanos = new AtomicLong();

        Connection(final String address) {
            this.address = address;
        }

        String status() {
            return this.status;
        }

        int inFlight() {
            return this.inFlight.get();
        }

        int limit() {
            return OffloadClient.this.config.maxInFlight() > 0 ? OffloadClient.this.config.maxInFlight() : this.remoteThreads * 4;
        }

        double averageRttMillis() {
            final long done = this.completed.get();
            return done == 0 ? 0.0 : this.totalRttNanos.get() / 1.0e6 / done;
        }

        Set<String> dims() {
            return this.dims;
        }

        private boolean canTake(final String dim) {
            return this.channel != null && this.remoteThreads > 0 && this.dims.contains(dim) && this.inFlight.get() < this.limit();
        }

        private double load() {
            return (double) this.inFlight.get() / Math.max(1, this.limit());
        }

        private CompletableFuture<byte[]> send(final long id, final String dim, final byte[] body) throws IOException {
            final SecureChannel current = this.channel;
            if (current == null) {
                return null;
            }
            final byte[] message = Messages.request(new Messages.Request(id, dim, body));
            final long now = System.nanoTime();
            final CompletableFuture<byte[]> future = new CompletableFuture<>();
            this.pending.put(id, new Pending(future, now + OffloadClient.this.config.timeoutMillis() * 1_000_000L, now));
            this.inFlight.incrementAndGet();
            try {
                current.send(message);
            } catch (final IOException ex) {
                this.fail(id, ex);
                this.disconnect("send failed: " + ex.getMessage());
            }
            return future;
        }

        private void fail(final long id, final Throwable cause) {
            final Pending entry = this.pending.remove(id);
            if (entry != null) {
                this.inFlight.decrementAndGet();
                this.failed.incrementAndGet();
                entry.future().completeExceptionally(cause);
            }
        }

        private void expire(final long now) {
            for (final Map.Entry<Long, Pending> entry : this.pending.entrySet()) {
                if (now - entry.getValue().deadline() > 0) {
                    this.fail(entry.getKey(), new TimeoutException("offload timed out"));
                }
            }
        }

        private void connectLoop() {
            long backoff = 2_000L;
            while (true) {
                try {
                    // All worlds must be loaded before the probe chunks can be generated.
                    while (!NoiseOffload.serverStarted) {
                        Thread.sleep(1_000L);
                    }
                    final SecureChannel opened = this.connectOnce();
                    backoff = 2_000L;
                    this.readLoop(opened);
                } catch (final InterruptedException ex) {
                    return;
                } catch (final java.io.EOFException ex) {
                    this.disconnect("closed by peer (wrong secret, or the peer is not ready)");
                } catch (final Throwable ex) {
                    this.disconnect(ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName());
                }
                try {
                    Thread.sleep(backoff);
                } catch (final InterruptedException ex) {
                    return;
                }
                backoff = Math.min(60_000L, backoff * 2);
            }
        }

        private SecureChannel connectOnce() throws IOException {
            final OffloadWorker.HostPort target = OffloadWorker.HostPort.parse(this.address, 25590);
            final Socket socket = new Socket();
            socket.connect(new InetSocketAddress(target.host(), target.port()), 5_000);
            final SecureChannel opened = SecureChannel.initiate(socket, OffloadClient.this.config.secret(), OffloadClient.this.config.compress());
            try {
                opened.send(Messages.hello(new Messages.Hello(Messages.ROLE_SERVER, 0, OffloadClient.this.localProbes())));
                final Messages.Welcome welcome = Messages.readWelcome(opened.receive());
                if (!welcome.ok()) {
                    throw new IOException("refused: " + welcome.message());
                }
                if (!welcome.message().isEmpty()) {
                    LOGGER.warn("Offload peer {}: not offloaded: {}", this.address, welcome.message());
                }
            } catch (final IOException ex) {
                opened.close();
                throw ex;
            }
            this.channel = opened;
            this.status = "connected (encrypted), waiting for capacity";
            return opened;
        }

        private void readLoop(final SecureChannel opened) throws IOException {
            while (true) {
                final byte[] message = opened.receive();
                switch (Messages.type(message)) {
                    case Messages.CAPACITY -> {
                        final Messages.Capacity capacity = Messages.readCapacity(message);
                        final boolean first = this.remoteThreads == 0 && capacity.threads() > 0;
                        this.remoteThreads = capacity.threads();
                        this.dims = Set.copyOf(capacity.dims());
                        this.status = "connected (encrypted), " + capacity.threads() + " threads";
                        if (first) {
                            LOGGER.info("Offloading noise generation to {} ({} threads, encrypted) for {}", this.address, capacity.threads(), capacity.dims());
                        }
                    }
                    case Messages.RESPONSE -> {
                        final Messages.Response response = Messages.readResponse(message);
                        final Pending entry = this.pending.remove(response.id());
                        if (entry == null) {
                            continue; // timed out already
                        }
                        this.inFlight.decrementAndGet();
                        if (response.status() == Messages.STATUS_OK) {
                            this.completed.incrementAndGet();
                            this.totalRttNanos.addAndGet(System.nanoTime() - entry.sentAt());
                            entry.future().complete(response.body());
                        } else {
                            this.failed.incrementAndGet();
                            entry.future().completeExceptionally(new IOException("worker error: " + Messages.readError(response.body())));
                        }
                    }
                    default -> { }
                }
            }
        }

        private void disconnect(final String reason) {
            final SecureChannel current = this.channel;
            this.channel = null;
            this.remoteThreads = 0;
            this.dims = Set.of();
            this.status = "disconnected: " + reason;
            if (current != null) {
                current.close();
            }
            for (final Long id : new ArrayList<>(this.pending.keySet())) {
                this.fail(id, new IOException("offload peer disconnected"));
            }
            if (current != null) {
                LOGGER.warn("Offload peer {} disconnected ({}); generating locally until it is back", this.address, reason);
            }
        }
    }
}
