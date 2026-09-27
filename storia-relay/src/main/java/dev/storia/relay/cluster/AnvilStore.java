package dev.storia.relay.cluster;

import dev.storia.cluster.protocol.ClusterProtocol;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Stores chunk records in vanilla Anvil region files ({@code r.X.Z.mca}, 4 KiB sectors with a location and a
 * timestamp table), so the cluster's world is an ordinary Minecraft world that a single server can open.
 *
 * <p>A record is what a region file holds for one chunk: a compression type byte followed by the compressed
 * NBT. Records too large for 255 sectors go to an external {@code c.X.Z.mcc} file, as in vanilla.
 * New data is written to free sectors before the header points at it, so a crash leaves either the old or the
 * new record, never a mix.
 */
public final class AnvilStore implements AutoCloseable {

    private static final int SECTOR = 4096;
    private static final int EXTERNAL_FLAG = 128;
    private static final int MAX_OPEN = 256;

    private final Path world;
    private final Map<Path, Region> open = new LinkedHashMap<>(16, 0.75f, true);

    public AnvilStore(final Path world) {
        this.world = world;
    }

    /** The folder of one storage type in one dimension, using the Minecraft 26 layout. */
    public Path folder(final byte type, final String dimension) {
        final int colon = dimension.indexOf(':');
        final String namespace = colon < 0 ? "minecraft" : dimension.substring(0, colon);
        final String path = colon < 0 ? dimension : dimension.substring(colon + 1);
        if (!namespace.matches("[a-z0-9_.-]+") || !path.matches("[a-z0-9_./-]+") || path.contains("..")) {
            throw new IllegalArgumentException("bad dimension " + dimension);
        }
        return this.world.resolve("dimensions").resolve(namespace).resolve(path).resolve(ClusterProtocol.TYPE_FOLDERS[type]);
    }

    public byte[] read(final ClusterProtocol.ChunkKey key) throws IOException {
        while (true) {
            final Region region = this.region(key, false);
            try {
                return region == null ? null : region.read(key.x() & 31, key.z() & 31, key.x(), key.z());
            } catch (final RegionClosedException ex) {
                // closed to make room for another region file between region() and read(): open it again
            }
        }
    }

    /** Writes a record, or deletes it when {@code record} is null. */
    public void write(final ClusterProtocol.ChunkKey key, final byte[] record) throws IOException {
        while (true) {
            final Region region = this.region(key, record != null);
            try {
                if (region != null) {
                    region.write(key.x() & 31, key.z() & 31, key.x(), key.z(), record);
                }
                return;
            } catch (final RegionClosedException ex) {
                // closed to make room for another region file between region() and write(): open it again
            }
        }
    }

    /** The region file was closed by another thread while this one was about to use it. */
    private static final class RegionClosedException extends IOException {
        RegionClosedException() {
            super("region file closed");
        }
    }

    private Region region(final ClusterProtocol.ChunkKey key, final boolean create) throws IOException {
        final Path folder = this.folder(key.type(), key.dimension());
        final Path file = folder.resolve("r." + key.cellX() + "." + key.cellZ() + ".mca");
        synchronized (this.open) {
            Region region = this.open.get(file);
            if (region != null) {
                return region;
            }
            if (!Files.exists(file)) {
                if (!create) {
                    return null;
                }
                Files.createDirectories(folder);
            }
            region = new Region(folder, file);
            this.open.put(file, region);
            if (this.open.size() > MAX_OPEN) {
                final var eldest = this.open.entrySet().iterator().next();
                this.open.remove(eldest.getKey());
                eldest.getValue().close();
            }
            return region;
        }
    }

    public void flush() throws IOException {
        synchronized (this.open) {
            for (final Region region : this.open.values()) {
                region.flush();
            }
        }
    }

    @Override
    public void close() throws IOException {
        synchronized (this.open) {
            for (final Region region : this.open.values()) {
                region.close();
            }
            this.open.clear();
        }
    }

    private static final class Region {
        private final Path folder;
        private final FileChannel channel;
        private final int[] locations = new int[1024];
        private final BitSet used = new BitSet();
        private boolean closed;

        Region(final Path folder, final Path file) throws IOException {
            this.folder = folder;
            this.channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
            final ByteBuffer header = ByteBuffer.allocate(SECTOR * 2);
            if (this.channel.size() < SECTOR * 2) {
                this.channel.write(ByteBuffer.allocate(SECTOR * 2), 0);
            } else {
                this.channel.read(header, 0);
                header.flip();
            }
            this.used.set(0, 2);
            final long sectors = (this.channel.size() + SECTOR - 1) / SECTOR;
            for (int i = 0; i < 1024; ++i) {
                final int location = header.remaining() >= (i + 1) * 4 ? header.getInt(i * 4) : 0;
                final int offset = location >>> 8;
                final int count = location & 0xFF;
                if (location != 0 && offset >= 2 && offset + count <= sectors) {
                    this.locations[i] = location;
                    this.used.set(offset, offset + count);
                }
            }
        }

        synchronized byte[] read(final int localX, final int localZ, final int chunkX, final int chunkZ) throws IOException {
            if (this.closed) {
                throw new RegionClosedException();
            }
            final int location = this.locations[localX + localZ * 32];
            if (location == 0) {
                return null;
            }
            final ByteBuffer head = ByteBuffer.allocate(5);
            this.channel.read(head, (long) (location >>> 8) * SECTOR);
            head.flip();
            if (head.remaining() < 5) {
                return null;
            }
            final int length = head.getInt();
            final int type = head.get() & 0xFF;
            if ((type & EXTERNAL_FLAG) != 0) {
                final Path external = this.folder.resolve("c." + chunkX + "." + chunkZ + ".mcc");
                if (!Files.exists(external)) {
                    return null;
                }
                final byte[] data = Files.readAllBytes(external);
                final byte[] record = new byte[data.length + 1];
                record[0] = (byte) (type & ~EXTERNAL_FLAG);
                System.arraycopy(data, 0, record, 1, data.length);
                return record;
            }
            if (length <= 1 || length > (location & 0xFF) * SECTOR - 4) {
                return null;
            }
            final ByteBuffer record = ByteBuffer.allocate(length);
            record.put((byte) type);
            this.channel.read(record, (long) (location >>> 8) * SECTOR + 5);
            return record.array();
        }

        synchronized void write(final int localX, final int localZ, final int chunkX, final int chunkZ, final byte[] record) throws IOException {
            if (this.closed) {
                throw new RegionClosedException();
            }
            final int index = localX + localZ * 32;
            final int oldLocation = this.locations[index];
            final Path external = this.folder.resolve("c." + chunkX + "." + chunkZ + ".mcc");
            if (record == null) {
                this.setHeader(index, 0);
                this.free(oldLocation);
                Files.deleteIfExists(external);
                return;
            }
            byte[] stored = record;
            int sectors = (4 + record.length + SECTOR - 1) / SECTOR;
            if (sectors > 255) {
                final Path temp = this.folder.resolve(external.getFileName() + ".tmp");
                Files.write(temp, java.util.Arrays.copyOfRange(record, 1, record.length));
                move(temp, external);
                stored = new byte[] {(byte) (record[0] | EXTERNAL_FLAG)};
                sectors = 1;
            }
            final int offset = this.allocate(sectors);
            final ByteBuffer buffer = ByteBuffer.allocate(sectors * SECTOR);
            buffer.putInt(stored.length);
            buffer.put(stored);
            buffer.position(0);
            this.channel.write(buffer, (long) offset * SECTOR);
            this.setHeader(index, (offset << 8) | sectors);
            this.free(oldLocation);
            if (stored.length > 1 || (stored[0] & EXTERNAL_FLAG) == 0) {
                Files.deleteIfExists(external);
            }
        }

        private int allocate(final int sectors) {
            int start = 2;
            while (true) {
                final int free = this.used.nextClearBit(start);
                final int nextUsed = this.used.nextSetBit(free);
                if (nextUsed < 0 || nextUsed - free >= sectors) {
                    this.used.set(free, free + sectors);
                    return free;
                }
                start = nextUsed;
            }
        }

        private void free(final int location) {
            if (location != 0) {
                this.used.clear(location >>> 8, (location >>> 8) + (location & 0xFF));
            }
        }

        private void setHeader(final int index, final int location) throws IOException {
            this.locations[index] = location;
            final ByteBuffer value = ByteBuffer.allocate(4).putInt(location);
            value.flip();
            this.channel.write(value, index * 4L);
            final ByteBuffer time = ByteBuffer.allocate(4).putInt((int) (System.currentTimeMillis() / 1000L));
            time.flip();
            this.channel.write(time, SECTOR + index * 4L);
        }

        synchronized void flush() throws IOException {
            if (!this.closed) {
                this.channel.force(false);
            }
        }

        synchronized void close() throws IOException {
            if (!this.closed) {
                this.closed = true;
                this.channel.force(false);
                this.channel.close();
            }
        }
    }

    static void move(final Path from, final Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (final AtomicMoveNotSupportedException ex) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Opens region files for tests: reads the raw location entry of a chunk. */
    static int location(final Path file, final int localX, final int localZ) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            raf.seek((localX + localZ * 32) * 4L);
            return raf.readInt();
        }
    }
}
