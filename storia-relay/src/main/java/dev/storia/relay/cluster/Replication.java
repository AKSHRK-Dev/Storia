package dev.storia.relay.cluster;

import dev.storia.cluster.protocol.ClusterProtocol;
import dev.storia.net.SecureChannel;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Copies everything the active relay stores to a standby relay (see STANDBY.md).
 *
 * <p>Every change is an idempotent "set this = value": a chunk record or a whole file (relative to the cluster world).
 * A standby that joins first gets the live stream, then a snapshot of everything; snapshot items for keys the live
 * stream has already set are skipped, since the live value is newer. Once the snapshot is done the standby is in
 * sync, and from then on a write is acknowledged to the worker only after the standby has stored it too.
 */
public final class Replication {

    static final byte CHANGE_CHUNK = 1;
    static final byte CHANGE_FILE = 2;
    static final byte SNAP_CHUNK = 3;
    static final byte SNAP_FILE = 4;
    static final byte SNAPSHOT_DONE = 5;
    static final byte OUT_OF_SYNC = 6;
    static final byte ACK = 10;

    /** How long the active relay waits for the standby before going on alone. */
    static final long ACK_TIMEOUT_MILLIS = 2_000L;
    private static final Pattern REGION = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");

    private Replication() {
    }

    // ---------------------------------------------------------------------------------------------
    // Encoding
    // ---------------------------------------------------------------------------------------------

    private static byte[] encodeChunk(final byte kind, final long seq, final ClusterProtocol.ChunkKey key, final byte[] record) throws IOException {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream(record == null ? 64 : record.length + 64);
        final DataOutputStream out = new DataOutputStream(bytes);
        out.writeByte(kind);
        out.writeLong(seq);
        out.writeByte(key.type());
        out.writeUTF(key.dimension());
        out.writeInt(key.x());
        out.writeInt(key.z());
        out.writeBoolean(record != null);
        if (record != null) {
            ClusterProtocol.writeBytes(out, record);
        }
        return bytes.toByteArray();
    }

    private static byte[] encodeFile(final byte kind, final long seq, final String path, final byte[] data) throws IOException {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream(data == null ? 64 : data.length + 64);
        final DataOutputStream out = new DataOutputStream(bytes);
        out.writeByte(kind);
        out.writeLong(seq);
        out.writeUTF(path);
        out.writeBoolean(data != null);
        if (data != null) {
            ClusterProtocol.writeBytes(out, data);
        }
        return bytes.toByteArray();
    }

    private static byte[] control(final byte kind, final long seq) throws IOException {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream(16);
        final DataOutputStream out = new DataOutputStream(bytes);
        out.writeByte(kind);
        out.writeLong(seq);
        return bytes.toByteArray();
    }

    /** "dimensions/minecraft/overworld/region/r.0.0.mca" relative to the world, with forward slashes. */
    static String relative(final Path world, final Path file) {
        return world.relativize(file).toString().replace(java.io.File.separatorChar, '/');
    }

    private static String chunkKey(final ClusterProtocol.ChunkKey key) {
        return "c " + key.type() + " " + key.dimension() + " " + key.x() + " " + key.z();
    }

    /** The storage type and dimension of a region file, or null if the path is not one. */
    private record RegionFile(byte type, String dimension, int cellX, int cellZ) {
        static RegionFile of(final String relative) {
            final String[] parts = relative.split("/");
            if (parts.length < 5 || !parts[0].equals("dimensions")) {
                return null;
            }
            final Matcher name = REGION.matcher(parts[parts.length - 1]);
            final int type = List.of(ClusterProtocol.TYPE_FOLDERS).indexOf(parts[parts.length - 2]);
            if (!name.matches() || type < 0) {
                return null;
            }
            final String path = String.join("/", java.util.Arrays.copyOfRange(parts, 2, parts.length - 2));
            return new RegionFile((byte) type, parts[1] + ":" + path, Integer.parseInt(name.group(1)), Integer.parseInt(name.group(2)));
        }

        /** Region files are copied chunk by chunk, everything else under a storage folder is skipped. */
        static boolean inStorageFolder(final String relative) {
            final String[] parts = relative.split("/");
            return parts.length >= 5 && parts[0].equals("dimensions") && List.of(ClusterProtocol.TYPE_FOLDERS).contains(parts[parts.length - 2]);
        }
    }

    /** Files that are never copied: locks and files still being written. */
    private static boolean skipped(final String relative) {
        final String name = relative.substring(relative.lastIndexOf('/') + 1);
        return name.equals("session.lock") || name.endsWith(".tmp");
    }

    // ---------------------------------------------------------------------------------------------
    // Active side
    // ---------------------------------------------------------------------------------------------

    /** The active relay's end: sends every change to the standby and waits for it once the standby is in sync. */
    public static final class Source {
        private final Path world;
        private final AnvilStore store;
        private final Consumer<String> log;
        private final Object sendLock = new Object();
        private final Map<Long, CompletableFuture<Void>> waiting = new ConcurrentHashMap<>();
        private volatile SecureChannel replica;
        private volatile boolean inSync;
        private volatile String state = "no standby";
        private long seq;

        Source(final Path world, final AnvilStore store, final Consumer<String> log) {
            this.world = world;
            this.store = store;
            this.log = log;
        }

        public String status() {
            return this.state;
        }

        /** Serves a standby until its connection ends: sends it a snapshot and reads its acknowledgements. */
        public void serve(final SecureChannel channel) throws IOException {
            final SecureChannel previous = this.replica;
            if (previous != null) {
                this.drop(previous, "a standby connected again");
            }
            synchronized (this.sendLock) {
                this.inSync = false;
                this.replica = channel;
            }
            this.state = "standby " + channel.remoteAddress() + ": copying the world";
            this.log.accept("Standby relay " + channel.remoteAddress() + " connected; copying the world to it");
            final Thread snapshot = new Thread(() -> this.snapshot(channel), "replication snapshot");
            snapshot.setDaemon(true);
            snapshot.start();
            try {
                while (true) {
                    final DataInputStream in = new DataInputStream(new ByteArrayInputStream(channel.receive()));
                    if (in.readByte() == ACK) {
                        final CompletableFuture<Void> future = this.waiting.remove(in.readLong());
                        if (future != null) {
                            future.complete(null);
                        }
                    }
                }
            } catch (final IOException ex) {
                this.drop(channel, "connection lost (" + ex.getMessage() + ")");
            }
        }

        private void snapshot(final SecureChannel channel) {
            final long start = System.currentTimeMillis();
            long chunks = 0;
            long files = 0;
            try (Stream<Path> walk = Files.walk(this.world)) {
                for (final Path path : (Iterable<Path>) walk::iterator) {
                    if (this.replica != channel) {
                        return; // replaced or dropped
                    }
                    if (!Files.isRegularFile(path)) {
                        continue;
                    }
                    final String relative = relative(this.world, path);
                    if (skipped(relative)) {
                        continue;
                    }
                    final RegionFile region = RegionFile.of(relative);
                    if (region != null) {
                        for (int i = 0; i < 1024; ++i) {
                            final ClusterProtocol.ChunkKey key = new ClusterProtocol.ChunkKey(region.type(), region.dimension(),
                                region.cellX() * 32 + (i & 31), region.cellZ() * 32 + (i >> 5));
                            final byte[] record = this.store.read(key);
                            if (record != null) {
                                this.send(channel, encodeChunk(SNAP_CHUNK, 0L, key, record));
                                chunks++;
                            }
                        }
                    } else if (!RegionFile.inStorageFolder(relative)) {
                        final byte[] data;
                        try {
                            data = Files.readAllBytes(path);
                        } catch (final java.nio.file.NoSuchFileException gone) {
                            continue;
                        }
                        this.send(channel, encodeFile(SNAP_FILE, 0L, relative, data));
                        files++;
                    }
                }
            } catch (final IOException | RuntimeException ex) {
                this.drop(channel, "could not copy the world (" + ex.getMessage() + ")");
                return;
            }
            synchronized (this.sendLock) {
                if (this.replica != channel) {
                    return;
                }
                try {
                    channel.send(control(SNAPSHOT_DONE, 0L), false);
                } catch (final IOException ex) {
                    this.drop(channel, "connection lost");
                    return;
                }
                this.inSync = true;
            }
            this.state = "standby " + channel.remoteAddress() + ": in sync";
            this.log.accept(String.format(java.util.Locale.ROOT, "Standby relay %s is in sync (%d chunk(s), %d file(s) copied in %.1f s); "
                + "writes are now stored on both relays", channel.remoteAddress(), chunks, files, (System.currentTimeMillis() - start) / 1000.0));
        }

        private void send(final SecureChannel channel, final byte[] message) throws IOException {
            synchronized (this.sendLock) {
                if (this.replica == channel) {
                    channel.send(message, false);
                }
            }
        }

        /**
         * A chunk record was written (or deleted, {@code record == null}). Completes when the standby has stored it
         * (at once if there is no standby in sync): the caller answers the worker then, without holding a thread.
         */
        CompletableFuture<Void> chunk(final ClusterProtocol.ChunkKey key, final byte[] record) {
            return this.replicateAsync(seq -> encodeChunk(CHANGE_CHUNK, seq, key, record));
        }

        /** A file in the world was written or deleted: copies its current content. */
        void file(final Path file) {
            if (this.replica == null) {
                return;
            }
            byte[] data;
            try {
                data = Files.exists(file) ? Files.readAllBytes(file) : null;
            } catch (final IOException ex) {
                data = null;
            }
            final byte[] content = data;
            this.replicate(seq -> encodeFile(CHANGE_FILE, seq, relative(this.world, file), content));
        }

        /** A file was written with this content (or deleted, {@code data == null}); waits for the standby. */
        void file(final Path file, final byte[] data) {
            this.replicate(seq -> encodeFile(CHANGE_FILE, seq, relative(this.world, file), data));
        }

        /** Like {@link #file(Path, byte[])}, but completes when the standby has it instead of waiting. */
        CompletableFuture<Void> fileAsync(final Path file, final byte[] data) {
            return this.replicateAsync(seq -> encodeFile(CHANGE_FILE, seq, relative(this.world, file), data));
        }

        @FunctionalInterface
        private interface Encoder {
            byte[] encode(long seq) throws IOException;
        }

        private static final CompletableFuture<Void> DONE = CompletableFuture.completedFuture(null);

        private void replicate(final Encoder encoder) {
            this.replicateAsync(encoder).join();
        }

        /**
         * Sends the change; the future completes when the standby has stored it, or at once if no standby is in sync.
         * It never fails: a standby that does not answer in time is dropped and the relay goes on alone.
         */
        private CompletableFuture<Void> replicateAsync(final Encoder encoder) {
            final SecureChannel channel = this.replica;
            if (channel == null) {
                return DONE;
            }
            final CompletableFuture<Void> future;
            final long id;
            synchronized (this.sendLock) {
                if (this.replica != channel) {
                    return DONE;
                }
                id = ++this.seq;
                future = this.inSync ? new CompletableFuture<>() : null;
                if (future != null) {
                    this.waiting.put(id, future);
                }
                try {
                    channel.send(encoder.encode(id), false);
                } catch (final IOException ex) {
                    this.waiting.remove(id);
                    this.drop(channel, "connection lost (" + ex.getMessage() + ")");
                    return DONE;
                }
            }
            if (future == null) {
                return DONE; // still copying the world: nothing to wait for yet
            }
            return future.orTimeout(ACK_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS).handle((ok, ex) -> {
                if (ex != null) {
                    this.waiting.remove(id);
                    if (ex instanceof java.util.concurrent.TimeoutException || ex.getCause() instanceof java.util.concurrent.TimeoutException) {
                        this.drop(channel, "no answer within " + (ACK_TIMEOUT_MILLIS / 1000) + " s");
                    }
                }
                return null;
            });
        }

        private void drop(final SecureChannel channel, final String why) {
            synchronized (this.sendLock) {
                if (this.replica != channel) {
                    return;
                }
                this.replica = null;
                this.inSync = false;
                try {
                    channel.send(control(OUT_OF_SYNC, 0L), false); // so it never takes over with old data
                } catch (final IOException ignored) {
                    // gone already
                }
            }
            channel.close();
            this.waiting.values().forEach(f -> f.completeExceptionally(new IOException("standby dropped")));
            this.waiting.clear();
            this.state = "standby lost: " + why + "; running alone";
            this.log.accept("WARNING: standby relay " + channel.remoteAddress() + " dropped (" + why + "); this relay goes on alone "
                + "until the standby connects again");
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Standby side
    // ---------------------------------------------------------------------------------------------

    /** The standby's end: applies the active relay's changes to its own copy of the world. */
    public static final class Sink {
        private final Path world;
        private final Consumer<String> log;
        private AnvilStore store;
        /** In sync with the active relay when the connection was last open: only then may it take over. */
        private volatile boolean inSync;
        private volatile String state = "not connected";

        public Sink(final Path world, final Consumer<String> log) throws IOException {
            this.world = world.toAbsolutePath().normalize();
            Files.createDirectories(this.world);
            this.store = new AnvilStore(this.world);
            this.log = log;
            final Thread flusher = new Thread(() -> {
                while (this.store != null) {
                    try {
                        Thread.sleep(1000L);
                        final AnvilStore current = this.store;
                        if (current != null) {
                            current.flush();
                        }
                    } catch (final InterruptedException ex) {
                        return;
                    } catch (final IOException ignored) {
                        // next second
                    }
                }
            }, "standby flush");
            flusher.setDaemon(true);
            flusher.start();
        }

        public boolean inSync() {
            return this.inSync;
        }

        public String status() {
            return this.state;
        }

        /** Applies changes from the active relay, in order, until the connection ends. */
        public void run(final SecureChannel channel) throws IOException {
            this.inSync = false;
            this.state = "copying the world from " + channel.remoteAddress();
            final Set<String> touched = new HashSet<>();
            final Set<String> seenFiles = new HashSet<>();
            final Map<String, BitSet> seenChunks = new HashMap<>();
            try {
                while (true) {
                    final DataInputStream in = new DataInputStream(new ByteArrayInputStream(channel.receive()));
                    final byte kind = in.readByte();
                    final long seq = in.readLong();
                    switch (kind) {
                        case CHANGE_CHUNK, SNAP_CHUNK -> {
                            final ClusterProtocol.ChunkKey key = new ClusterProtocol.ChunkKey(in.readByte(), in.readUTF(), in.readInt(), in.readInt());
                            final byte[] record = in.readBoolean() ? ClusterProtocol.readBytes(in) : null;
                            final String id = chunkKey(key);
                            if (kind == CHANGE_CHUNK) {
                                touched.add(id);
                                this.store.write(key, record);
                            } else {
                                seenChunks.computeIfAbsent(this.regionPath(key), k -> new BitSet(1024)).set((key.x() & 31) + (key.z() & 31) * 32);
                                if (!touched.contains(id)) {
                                    this.store.write(key, record);
                                }
                            }
                        }
                        case CHANGE_FILE, SNAP_FILE -> {
                            final String path = in.readUTF();
                            final byte[] data = in.readBoolean() ? ClusterProtocol.readBytes(in) : null;
                            if (kind == CHANGE_FILE) {
                                touched.add("f " + path);
                                this.writeFile(path, data);
                            } else {
                                seenFiles.add(path);
                                if (!touched.contains("f " + path)) {
                                    this.writeFile(path, data);
                                }
                            }
                        }
                        case SNAPSHOT_DONE -> {
                            final int removed = this.removeStale(seenFiles, seenChunks, touched);
                            touched.clear();
                            seenFiles.clear();
                            seenChunks.clear();
                            this.store.flush();
                            this.inSync = true;
                            this.state = "in sync with " + channel.remoteAddress();
                            this.log.accept("In sync with the active relay " + channel.remoteAddress()
                                + (removed > 0 ? " (removed " + removed + " item(s) it no longer has)" : ""));
                        }
                        case OUT_OF_SYNC -> {
                            this.inSync = false;
                            this.state = "out of sync: the active relay went on without this standby";
                            this.log.accept("The active relay dropped this standby; it is out of date until it has copied the world again");
                        }
                        default -> throw new IOException("unknown replication message " + kind);
                    }
                    if (kind == CHANGE_CHUNK || kind == CHANGE_FILE) {
                        channel.send(control(ACK, seq), false); // only once it is stored
                    }
                }
            } catch (final IOException | RuntimeException ex) {
                if (!(ex instanceof java.io.EOFException) && !(ex instanceof java.net.SocketException)) {
                    // a change this standby could not store: it must not take over with that gap
                    this.inSync = false;
                    this.state = "out of sync: " + ex.getMessage();
                }
                throw ex;
            }
        }

        private String regionPath(final ClusterProtocol.ChunkKey key) {
            return relative(this.world, this.store.folder(key.type(), key.dimension()).resolve("r." + key.cellX() + "." + key.cellZ() + ".mca"));
        }

        private void writeFile(final String path, final byte[] data) throws IOException {
            final Path target = this.world.resolve(path).normalize();
            if (!target.startsWith(this.world) || target.equals(this.world) || RegionFile.inStorageFolder(path)) {
                throw new IOException("refusing to write " + path);
            }
            if (data == null) {
                Files.deleteIfExists(target);
                return;
            }
            Files.createDirectories(target.getParent());
            final Path temp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.write(temp, data);
            AnvilStore.move(temp, target);
        }

        /** After a snapshot: removes what the active relay no longer has (files, region files, single chunks). */
        private int removeStale(final Set<String> seenFiles, final Map<String, BitSet> seenChunks, final Set<String> touched) throws IOException {
            final List<Path> files;
            try (Stream<Path> walk = Files.walk(this.world)) {
                files = walk.filter(Files::isRegularFile).toList();
            }
            int removed = 0;
            for (final Path file : files) {
                final String relative = relative(this.world, file);
                if (skipped(relative)) {
                    continue;
                }
                final RegionFile region = RegionFile.of(relative);
                if (region != null) {
                    final BitSet seen = seenChunks.getOrDefault(relative, new BitSet());
                    for (int i = 0; i < 1024; ++i) {
                        final ClusterProtocol.ChunkKey key = new ClusterProtocol.ChunkKey(region.type(), region.dimension(),
                            region.cellX() * 32 + (i & 31), region.cellZ() * 32 + (i >> 5));
                        if (!seen.get(i) && !touched.contains(chunkKey(key)) && this.store.read(key) != null) {
                            this.store.write(key, null);
                            removed++;
                        }
                    }
                } else if (!RegionFile.inStorageFolder(relative) && !seenFiles.contains(relative) && !touched.contains("f " + relative)) {
                    Files.deleteIfExists(file);
                    removed++;
                }
            }
            return removed;
        }

        /** Stops applying changes and closes the copy, before this relay becomes the active one. */
        public void close() throws IOException {
            final AnvilStore current = this.store;
            this.store = null;
            if (current != null) {
                current.close();
            }
        }
    }

    /** For tests and status: every file of a world, relative, sorted. */
    static List<String> listFiles(final Path world) throws IOException {
        final List<String> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(world)) {
            walk.filter(Files::isRegularFile).forEach(p -> files.add(relative(world, p)));
        }
        files.sort(null);
        return files;
    }
}
