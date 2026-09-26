package dev.storia.cluster;

import com.mojang.logging.LogUtils;
import dev.storia.cluster.protocol.ClusterProtocol;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.slf4j.Logger;

/**
 * Writes that could not reach the coordinator, kept on local disk in order and replayed once it is back
 * (also after a restart). While anything is spooled, new writes are spooled too, so an old write can never
 * land after a newer one, and reads see the newest spooled data first.
 */
final class ClusterSpool {

    private static final Logger LOGGER = LogUtils.getClassLogger();
    private static final Path DIR = Path.of("cluster-spool");

    /** Newest spooled data by key; {@link Optional#empty()} means "deleted". */
    private final Map<String, Optional<byte[]>> latest = new ConcurrentHashMap<>();
    private final Map<String, Long> latestSeq = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong();
    private volatile int size;

    ClusterSpool() {
        try {
            Files.createDirectories(DIR);
            final List<Path> files = this.files();
            for (final Path file : files) {
                final Entry entry = read(file);
                this.remember(entry, seqOf(file));
                this.seq.set(Math.max(this.seq.get(), seqOf(file)));
            }
            this.size = files.size();
            if (!files.isEmpty()) {
                LOGGER.warn("Storia Cluster: {} write(s) from an earlier run are waiting to be sent to the coordinator", files.size());
            }
        } catch (final IOException ex) {
            throw new IllegalStateException("Cannot read the cluster spool in " + DIR.toAbsolutePath(), ex);
        }
    }

    private record Entry(byte op, String key, byte[] body) {}

    boolean isEmpty() {
        return this.size == 0;
    }

    int size() {
        return this.size;
    }

    /** Newest spooled value for a key, if any: empty Optional = deleted, null = nothing spooled. */
    Optional<byte[]> latest(final String key) {
        return this.latest.get(key);
    }

    synchronized void append(final byte op, final String key, final byte[] body) throws IOException {
        final long seq = this.seq.incrementAndGet();
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeByte(op);
            out.writeUTF(key);
            ClusterProtocol.writeBytes(out, body);
        }
        final Path file = DIR.resolve(String.format("%016d.w", seq));
        final Path temp = DIR.resolve(String.format("%016d.tmp", seq));
        Files.write(temp, bytes.toByteArray());
        Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE);
        final Entry entry = new Entry(op, key, body);
        this.remember(entry, seq);
        this.size++;
    }

    private void remember(final Entry entry, final long seq) throws IOException {
        final byte[] value = switch (entry.op()) {
            case ClusterProtocol.OP_WRITE -> ClusterProtocol.readWrite(entry.body()).record();
            case ClusterProtocol.OP_PLAYER_WRITE -> ClusterProtocol.readPlayer(entry.body()).data();
            default -> null;
        };
        this.latest.put(entry.key(), Optional.ofNullable(value));
        this.latestSeq.put(entry.key(), seq);
    }

    /** Sends spooled writes in order until one fails. Returns how many were sent. */
    synchronized int drain(final ClusterClient client) {
        int sent = 0;
        try {
            for (final Path file : this.files()) {
                final Entry entry = read(file);
                if (entry.op() == ClusterProtocol.OP_WRITE) {
                    final ClusterProtocol.ChunkKey key = ClusterProtocol.readWrite(entry.body()).key();
                    Cluster.claimForReplay(key.dimension(), key.x(), key.z());
                }
                final ClusterProtocol.Response response = client.request(entry.op(), entry.body());
                if (response.status() == ClusterProtocol.DENIED) {
                    LOGGER.warn("Storia Cluster: a spooled write for {} was refused (another node owns it now); dropped", entry.key());
                } else if (response.status() != ClusterProtocol.OK) {
                    LOGGER.warn("Storia Cluster: a spooled write for {} failed: {}", entry.key(), ClusterProtocol.readString(response.body()));
                }
                Files.deleteIfExists(file);
                this.size--;
                sent++;
                final long seq = seqOf(file);
                if (this.latestSeq.remove(entry.key(), seq)) {
                    this.latest.remove(entry.key());
                }
            }
        } catch (final IOException ex) {
            // coordinator still unavailable; try again later
        }
        if (sent > 0) {
            LOGGER.info("Storia Cluster: sent {} spooled write(s) to the coordinator ({} left)", sent, this.size);
        }
        return sent;
    }

    private List<Path> files() throws IOException {
        try (Stream<Path> stream = Files.list(DIR)) {
            return stream.filter(p -> p.getFileName().toString().endsWith(".w")).sorted().toList();
        }
    }

    private static long seqOf(final Path file) {
        final String name = file.getFileName().toString();
        return Long.parseLong(name.substring(0, name.length() - 2));
    }

    private static Entry read(final Path file) throws IOException {
        try (DataInputStream in = new DataInputStream(Files.newInputStream(file))) {
            return new Entry(in.readByte(), in.readUTF(), ClusterProtocol.readBytes(in));
        }
    }
}
