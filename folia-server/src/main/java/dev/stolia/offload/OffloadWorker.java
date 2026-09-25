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
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
 * Server 2: accepts NOISE requests from a Stolia server and computes them on a thread pool, using this
 * server's own (identical) world generator. Nothing is written to this server's worlds.
 */
final class OffloadWorker {

    private static final Logger LOGGER = LogUtils.getClassLogger();

    private final OffloadConfig config;
    private final ExecutorService pool;
    private final int threads;
    private final Map<String, String> probes = new ConcurrentHashMap<>();
    final AtomicLong computed = new AtomicLong();
    final AtomicInteger connections = new AtomicInteger();

    private OffloadWorker(final OffloadConfig config) {
        this.config = config;
        this.threads = config.threads() > 0 ? config.threads() : Runtime.getRuntime().availableProcessors();
        final AtomicInteger ids = new AtomicInteger();
        this.pool = Executors.newFixedThreadPool(this.threads, r -> {
            final Thread thread = new Thread(r, "Stolia Offload Worker #" + ids.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        });
    }

    static OffloadWorker start(final OffloadConfig config) {
        if (config.secret().isEmpty()) {
            LOGGER.error("offload.mode is worker but offload.secret is empty; refusing to start");
            return null;
        }
        final OffloadWorker worker = new OffloadWorker(config);
        final Thread acceptor = new Thread(worker::acceptLoop, "Stolia Offload Acceptor");
        acceptor.setDaemon(true);
        acceptor.start();
        return worker;
    }

    int threads() {
        return this.threads;
    }

    private void acceptLoop() {
        try (ServerSocket server = new ServerSocket()) {
            server.bind(new InetSocketAddress(this.config.bind(), this.config.port()));
            LOGGER.info("Offload worker listening on {}:{} with {} threads", this.config.bind(), this.config.port(), this.threads);
            while (true) {
                final Socket socket = server.accept();
                socket.setTcpNoDelay(true);
                final Thread handler = new Thread(() -> this.handle(socket), "Stolia Offload Connection " + socket.getRemoteSocketAddress());
                handler.setDaemon(true);
                handler.start();
            }
        } catch (final IOException ex) {
            LOGGER.error("Offload worker stopped", ex);
        }
    }

    private void handle(final Socket socket) {
        this.connections.incrementAndGet();
        try (socket) {
            final DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream(), 64 * 1024));
            final DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream(), 64 * 1024));
            if (!this.handshake(socket, in, out)) {
                return;
            }
            while (true) {
                final byte[] frame = Frames.read(in);
                this.pool.execute(() -> this.process(frame, out));
            }
        } catch (final IOException ex) {
            LOGGER.info("Offload client {} disconnected: {}", socket.getRemoteSocketAddress(), ex.getMessage());
        } finally {
            this.connections.decrementAndGet();
        }
    }

    private boolean handshake(final Socket socket, final DataInputStream in, final DataOutputStream out) throws IOException {
        final String magic = in.readUTF();
        final int version = in.readInt();
        final String secret = in.readUTF();
        final int dimCount = in.readInt();
        if (dimCount < 0 || dimCount > 64) {
            throw new IOException("Bad dimension count " + dimCount);
        }
        final List<String> dims = new ArrayList<>();
        final List<String> clientProbes = new ArrayList<>();
        for (int i = 0; i < dimCount; i++) {
            dims.add(in.readUTF());
            clientProbes.add(in.readUTF());
        }
        String error = null;
        if (!Frames.MAGIC.equals(magic) || version != Frames.PROTOCOL_VERSION) {
            error = "protocol mismatch (worker speaks " + Frames.PROTOCOL_VERSION + ")";
        } else if (!Frames.secretMatches(this.config.secret(), secret)) {
            error = "wrong secret";
        } else if (!NoiseOffload.serverStarted) {
            error = "worker is still starting";
        }
        final List<String> accepted = new ArrayList<>();
        final List<String> rejected = new ArrayList<>();
        if (error == null) {
            for (int i = 0; i < dims.size(); i++) {
                final ServerLevel level = NoiseOffload.levelById(dims.get(i));
                if (level == null || !(level.getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator)) {
                    rejected.add(dims.get(i) + " (no such noise dimension here)");
                    continue;
                }
                final String probe = this.probes.computeIfAbsent(dims.get(i), k -> NoiseCodec.probe(level));
                if (probe.equals(clientProbes.get(i))) {
                    accepted.add(dims.get(i));
                } else {
                    rejected.add(dims.get(i) + " (terrain differs: check seed, datapacks and Stolia build)");
                }
            }
        }
        out.writeBoolean(error == null);
        out.writeUTF(error == null ? String.join(", ", rejected) : error);
        out.writeInt(this.threads);
        out.writeInt(accepted.size());
        for (final String dim : accepted) {
            out.writeUTF(dim);
        }
        out.flush();
        if (error != null) {
            LOGGER.warn("Rejected offload client {}: {}", socket.getRemoteSocketAddress(), error);
            return false;
        }
        LOGGER.info("Offload client {} connected; accepted {}{}", socket.getRemoteSocketAddress(), accepted,
            rejected.isEmpty() ? "" : ", rejected " + rejected);
        return true;
    }

    private void process(final byte[] frame, final DataOutputStream out) {
        long id = -1;
        byte[] response;
        try {
            final DataInputStream in = new DataInputStream(new ByteArrayInputStream(frame));
            id = in.readLong();
            final String dim = in.readUTF();
            final int x = in.readInt();
            final int z = in.readInt();
            final Beardifier beardifier = NoiseCodec.readBeardifier(in);
            final ServerLevel level = NoiseOffload.levelById(dim);
            if (level == null) {
                throw new IOException("unknown dimension " + dim);
            }
            final ProtoChunk chunk = NoiseCodec.computeDetached(level, x, z, beardifier);
            final byte[] result = NoiseCodec.encodeResult(chunk);
            final ByteArrayOutputStream bytes = new ByteArrayOutputStream(result.length + 16);
            final DataOutputStream msg = new DataOutputStream(bytes);
            msg.writeLong(id);
            msg.writeByte(0);
            msg.write(result);
            response = bytes.toByteArray();
            this.computed.incrementAndGet();
        } catch (final Throwable ex) {
            LOGGER.warn("Offload request {} failed", id, ex);
            try {
                final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                final DataOutputStream msg = new DataOutputStream(bytes);
                msg.writeLong(id);
                msg.writeByte(1);
                msg.writeUTF(String.valueOf(ex));
                response = bytes.toByteArray();
            } catch (final IOException impossible) {
                return;
            }
        }
        try {
            Frames.write(out, response, this.config.compress());
        } catch (final IOException ex) {
            // connection gone; the client times out and generates locally
        }
    }
}
