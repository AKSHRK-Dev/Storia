package dev.stolia.offload;

import com.mojang.logging.LogUtils;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
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

/** Server 1: keeps a connection to each configured worker and sends NOISE requests to the least busy one. */
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

    /** Sends a request to the least loaded worker that accepted the dimension, or returns null if none has room. */
    CompletableFuture<byte[]> submit(final String dim, final int x, final int z, final Beardifier beardifier) {
        Connection best = null;
        for (final Connection connection : this.connections) {
            if (connection.canTake(dim) && (best == null || connection.load() < best.load())) {
                best = connection;
            }
        }
        return best == null ? null : best.send(this.nextId.incrementAndGet(), dim, x, z, beardifier);
    }

    private synchronized Map<String, String> localProbes() {
        if (this.localProbes == null) {
            final Map<String, String> probes = new java.util.LinkedHashMap<>();
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
        private volatile DataOutputStream out;
        private volatile Socket socket;
        private volatile Set<String> dims = Set.of();
        private volatile int workerThreads;
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
            return OffloadClient.this.config.maxInFlight() > 0 ? OffloadClient.this.config.maxInFlight() : Math.max(1, this.workerThreads * 4);
        }

        double averageRttMillis() {
            final long done = this.completed.get();
            return done == 0 ? 0.0 : this.totalRttNanos.get() / 1.0e6 / done;
        }

        Set<String> dims() {
            return this.dims;
        }

        private boolean canTake(final String dim) {
            return this.out != null && this.dims.contains(dim) && this.inFlight.get() < this.limit();
        }

        private double load() {
            return (double) this.inFlight.get() / this.limit();
        }

        private CompletableFuture<byte[]> send(final long id, final String dim, final int x, final int z, final Beardifier beardifier) {
            final DataOutputStream out = this.out;
            if (out == null) {
                return null;
            }
            final byte[] payload;
            try {
                final ByteArrayOutputStream bytes = new ByteArrayOutputStream(256);
                final DataOutputStream msg = new DataOutputStream(bytes);
                msg.writeLong(id);
                msg.writeUTF(dim);
                msg.writeInt(x);
                msg.writeInt(z);
                NoiseCodec.writeBeardifier(msg, beardifier);
                payload = bytes.toByteArray();
            } catch (final IOException ex) {
                return null;
            }
            final long now = System.nanoTime();
            final CompletableFuture<byte[]> future = new CompletableFuture<>();
            this.pending.put(id, new Pending(future, now + OffloadClient.this.config.timeoutMillis() * 1_000_000L, now));
            this.inFlight.incrementAndGet();
            try {
                Frames.write(out, payload, false);
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
                    this.connectOnce();
                    backoff = 2_000L;
                    this.readLoop();
                } catch (final InterruptedException ex) {
                    return;
                } catch (final Throwable ex) {
                    this.disconnect(ex.getMessage());
                }
                try {
                    Thread.sleep(backoff);
                } catch (final InterruptedException ex) {
                    return;
                }
                backoff = Math.min(60_000L, backoff * 2);
            }
        }

        private void connectOnce() throws IOException {
            final int colon = this.address.lastIndexOf(':');
            final String host = colon < 0 ? this.address : this.address.substring(0, colon);
            final int port = colon < 0 ? 25590 : Integer.parseInt(this.address.substring(colon + 1));
            final Socket socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), 5_000);
            socket.setTcpNoDelay(true);
            final DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream(), 64 * 1024));
            final DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream(), 64 * 1024));
            final Map<String, String> probes = OffloadClient.this.localProbes();
            out.writeUTF(Frames.MAGIC);
            out.writeInt(Frames.PROTOCOL_VERSION);
            out.writeUTF(OffloadClient.this.config.secret());
            out.writeInt(probes.size());
            for (final Map.Entry<String, String> probe : probes.entrySet()) {
                out.writeUTF(probe.getKey());
                out.writeUTF(probe.getValue());
            }
            out.flush();
            final boolean ok = in.readBoolean();
            final String message = in.readUTF();
            final int threads = in.readInt();
            final int accepted = in.readInt();
            final Set<String> dims = new HashSet<>();
            for (int i = 0; i < accepted; i++) {
                dims.add(in.readUTF());
            }
            if (!ok) {
                socket.close();
                throw new IOException("worker refused: " + message);
            }
            this.socket = socket;
            this.workerThreads = threads;
            this.dims = Set.copyOf(dims);
            this.out = out;
            this.status = "connected (" + threads + " threads)";
            LOGGER.info("Offloading noise generation to {} ({} threads) for {}{}", this.address, threads, dims,
                message.isEmpty() ? "" : "; not offloaded: " + message);
            this.readerIn = in;
        }

        private DataInputStream readerIn;

        private void readLoop() throws IOException {
            final DataInputStream in = this.readerIn;
            while (true) {
                final byte[] frame = Frames.read(in);
                final DataInputStream msg = new DataInputStream(new ByteArrayInputStream(frame));
                final long id = msg.readLong();
                final int status = msg.readUnsignedByte();
                final Pending entry = this.pending.remove(id);
                if (entry == null) {
                    continue; // timed out already
                }
                this.inFlight.decrementAndGet();
                if (status == 0) {
                    final byte[] result = new byte[frame.length - 9];
                    System.arraycopy(frame, 9, result, 0, result.length);
                    this.completed.incrementAndGet();
                    this.totalRttNanos.addAndGet(System.nanoTime() - entry.sentAt());
                    entry.future().complete(result);
                } else {
                    this.failed.incrementAndGet();
                    entry.future().completeExceptionally(new IOException("worker error: " + msg.readUTF()));
                }
            }
        }

        private void disconnect(final String reason) {
            final boolean wasConnected = this.out != null;
            this.out = null;
            this.dims = Set.of();
            this.status = "disconnected: " + reason;
            final Socket socket = this.socket;
            this.socket = null;
            if (socket != null) {
                try {
                    socket.close();
                } catch (final IOException ignored) {
                    // closing anyway
                }
            }
            for (final Long id : new ArrayList<>(this.pending.keySet())) {
                this.fail(id, new IOException("worker disconnected"));
            }
            if (wasConnected) {
                LOGGER.warn("Offload worker {} disconnected ({}); generating locally until it is back", this.address, reason);
            }
        }
    }
}
