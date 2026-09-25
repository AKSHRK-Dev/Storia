package dev.storia.ramworld;

import com.mojang.logging.LogUtils;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;
import org.bukkit.configuration.file.YamlConfiguration;
import org.slf4j.Logger;

/**
 * Keeps the world folders in RAM (tmpfs) and periodically writes changed files back to disk.
 *
 * <p>On startup the world folders are copied from the world container on disk into a RAM directory
 * (default {@code /dev/shm/storia}), and the server loads worlds from there. A background thread
 * copies files whose size or mtime differ back to disk; each file is written to a temp file and
 * atomically renamed, so the on-disk copy is never half-written. On shutdown everything is synced.
 *
 * <p>If the RAM copy still exists from a previous run that did not shut down cleanly (the process
 * died but the machine did not reboot), it is treated as newer than disk and synced back first.
 */
public final class RamWorld {

    private static final Logger LOGGER = LogUtils.getClassLogger();
    private static final String CONFIG_FILE = "storia.yml";
    private static final String DIRTY_MARKER = ".storia-dirty";
    private static final String DISK_PATH_MARKER = ".storia-disk-path";
    private static final String TEMP_SUFFIX = ".storia-tmp";

    private static volatile RamWorld instance;

    private final Path diskRoot;
    private final Path ramRoot;
    private final List<String> worldDirs;
    private final ReentrantLock syncLock = new ReentrantLock();
    private final ScheduledExecutorService scheduler;
    private final boolean deleteOnShutdown;
    private final long intervalSeconds;
    private volatile boolean shutdown;
    private volatile long lastSyncMillis;
    private volatile int lastSyncFiles;

    private RamWorld(final Path diskRoot, final Path ramRoot, final List<String> worldDirs, final long intervalSeconds, final boolean deleteOnShutdown) {
        this.intervalSeconds = intervalSeconds;
        this.diskRoot = diskRoot;
        this.ramRoot = ramRoot;
        this.worldDirs = worldDirs;
        this.deleteOnShutdown = deleteOnShutdown;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            final Thread thread = new Thread(r, "Storia RAM World Sync");
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY);
            return thread;
        });
        this.scheduler.scheduleWithFixedDelay(this::periodicSync, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
    }

    public static RamWorld get() {
        return instance;
    }

    /**
     * Sets up the RAM copy of the worlds, returning the world container the server should load from.
     * Returns {@code universe} unchanged when disabled or when setup fails.
     */
    public static Path init(final Path universe, final String levelName) {
        final YamlConfiguration config = loadConfig();
        if (!config.getBoolean("ram-world.enabled")) {
            return universe;
        }

        try {
            final Path diskRoot = universe.toAbsolutePath().normalize();
            final Path ramBase = Path.of(config.getString("ram-world.ram-directory", "/dev/shm/storia"));
            final Path ramRoot = ramBase.resolve(hash(diskRoot.toString()));
            final List<String> worldDirs = findWorldDirs(diskRoot, levelName);

            Files.createDirectories(ramRoot);
            Files.writeString(ramRoot.resolve(DISK_PATH_MARKER), diskRoot.toString(), StandardCharsets.UTF_8);

            // A dirty RAM copy means the last run died without syncing; it holds the newest data.
            if (Files.exists(ramRoot.resolve(DIRTY_MARKER))) {
                LOGGER.warn("Found RAM world left by a previous run that did not shut down cleanly; syncing it to disk first");
                for (final String dir : worldDirs) {
                    if (Files.isDirectory(ramRoot.resolve(dir))) {
                        mirror(ramRoot.resolve(dir), diskRoot.resolve(dir));
                    }
                }
            }

            final long needed = worldDirs.stream().mapToLong(dir -> size(diskRoot.resolve(dir))).sum();
            final long reserve = config.getLong("ram-world.min-free-mb", 512) * 1024L * 1024L;
            final long available = Files.getFileStore(ramRoot).getUsableSpace() + worldDirs.stream().mapToLong(dir -> size(ramRoot.resolve(dir))).sum();
            if (needed + reserve > available) {
                LOGGER.error("Not enough space in {} (need {} MB + {} MB reserve, have {} MB); loading worlds from disk instead",
                    ramBase, needed >> 20, reserve >> 20, available >> 20);
                return universe;
            }

            final long start = System.nanoTime();
            for (final String dir : worldDirs) {
                mirror(diskRoot.resolve(dir), ramRoot.resolve(dir));
            }
            Files.writeString(ramRoot.resolve(DIRTY_MARKER), Long.toString(ProcessHandle.current().pid()), StandardCharsets.UTF_8);
            LOGGER.info("Loaded {} ({} MB) into {} in {} ms",
                worldDirs.isEmpty() ? "new world" : String.join(", ", worldDirs), needed >> 20, ramRoot,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));

            final long interval = Math.max(10, config.getLong("ram-world.sync-interval-seconds", 300));
            instance = new RamWorld(diskRoot, ramRoot, worldDirs, interval, config.getBoolean("ram-world.delete-on-shutdown", true));
            LOGGER.info("Syncing to disk every {} seconds. Changes made after the last sync are lost if the machine loses power.", interval);
            // No JVM shutdown hook: it could race the server's own save. MinecraftServer#stopPart2 calls
            // shutdown() after the worlds are closed; if that never happens the dirty marker triggers recovery.
            return ramRoot;
        } catch (final IOException | RuntimeException ex) {
            LOGGER.error("Failed to set up RAM world; loading worlds from disk instead", ex);
            return universe;
        }
    }

    /**
     * Writes all changed files to disk now. Returns the number of files written, or -1 if a sync
     * was already running.
     */
    public int syncNow() throws IOException {
        if (!this.syncLock.tryLock()) {
            return -1;
        }
        try {
            final long start = System.nanoTime();
            int changed = 0;
            for (final String dir : this.currentWorldDirs()) {
                changed += mirror(this.ramRoot.resolve(dir), this.diskRoot.resolve(dir));
            }
            if (changed > 0) {
                LOGGER.info("Synced {} file(s) to disk in {} ms", changed, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
            }
            this.lastSyncMillis = System.currentTimeMillis();
            this.lastSyncFiles = changed;
            return changed;
        } finally {
            this.syncLock.unlock();
        }
    }

    /**
     * Runs {@link #syncNow()} on the sync thread so callers (e.g. commands on a region thread) never block on disk I/O.
     */
    public CompletableFuture<Integer> syncAsync() {
        final CompletableFuture<Integer> future = new CompletableFuture<>();
        try {
            this.scheduler.execute(() -> {
                try {
                    future.complete(this.syncNow());
                } catch (final Throwable ex) {
                    future.completeExceptionally(ex);
                }
            });
        } catch (final RejectedExecutionException ex) {
            future.completeExceptionally(new IllegalStateException("RAM world is shutting down"));
        }
        return future;
    }

    public Path diskRoot() {
        return this.diskRoot;
    }

    public long intervalSeconds() {
        return this.intervalSeconds;
    }

    /** Time of the last completed sync in epoch millis, or 0 if none has run yet. */
    public long lastSyncMillis() {
        return this.lastSyncMillis;
    }

    public int lastSyncFiles() {
        return this.lastSyncFiles;
    }

    /** Bytes currently used by the RAM copy. */
    public long ramUsageBytes() {
        return size(this.ramRoot);
    }

    /** Final sync; call after the worlds have been saved and closed. Safe to call more than once. */
    public void shutdown() {
        if (this.shutdown) {
            return;
        }
        this.shutdown = true;
        this.scheduler.shutdownNow();
        this.syncLock.lock();
        try {
            LOGGER.info("Writing RAM world to disk...");
            for (final String dir : this.currentWorldDirs()) {
                mirror(this.ramRoot.resolve(dir), this.diskRoot.resolve(dir));
            }
            Files.deleteIfExists(this.ramRoot.resolve(DIRTY_MARKER));
            if (this.deleteOnShutdown) {
                deleteTree(this.ramRoot);
            }
            LOGGER.info("RAM world written to {}", this.diskRoot);
        } catch (final IOException ex) {
            LOGGER.error("FAILED to write RAM world to disk. The latest data is still in {} - copy it manually before rebooting!", this.ramRoot, ex);
        } finally {
            this.syncLock.unlock();
        }
    }

    public Path ramRoot() {
        return this.ramRoot;
    }

    private void periodicSync() {
        if (this.shutdown) {
            return;
        }
        try {
            this.syncNow();
        } catch (final Throwable ex) {
            LOGGER.error("Periodic sync failed; will retry next interval", ex);
        }
    }

    // New worlds may be created in RAM after startup (e.g. a fresh level folder), so rescan.
    private List<String> currentWorldDirs() throws IOException {
        final List<String> dirs = new ArrayList<>(this.worldDirs);
        try (Stream<Path> children = Files.list(this.ramRoot)) {
            children.filter(Files::isDirectory)
                .map(p -> p.getFileName().toString())
                .filter(name -> !dirs.contains(name))
                .forEach(dirs::add);
        }
        return dirs;
    }

    private static YamlConfiguration loadConfig() {
        final File file = new File(CONFIG_FILE);
        final YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        config.options().setHeader(List.of(
            "Storia settings",
            "",
            "ram-world: keeps worlds in RAM and writes them to disk in the background.",
            "  WARNING: if the machine loses power or crashes, changes since the last sync are lost.",
            "  If only the server process dies, the RAM copy survives and is recovered on next start.",
            "  Needs free RAM at least as large as the world folder (check `df -h /dev/shm`).",
            "",
            "pregen: /storia pregen. worker-threads -1 = CPU cores - 1 while pregenerating",
            "  (restored afterwards); max-in-flight -1 = worker-threads * 16.",
            "",
            "player-budget: gives each player a fair share of the tick threads. When a region's own tick",
            "  time passes max-region-mspt, or the tick threads are saturated and a region uses more than its",
            "  players' share, those players' view distance is lowered one step per check (never below",
            "  the simulation distance, so redstone and farms keep running) and raised back once the region",
            "  recovers. lower-simulation-distance: true also lowers simulation distance first (more CPU",
            "  saved, but machines far from the player stop). When the heap after GC passes",
            "  memory-high-percent, everyone's view distance is lowered. Nothing is limited while the",
            "  server has headroom.",
            "",
            "offload: generate terrain noise on other machines. On the main server set mode: client and",
            "  list workers (host:port); on each helper machine run Storia with a copy of the same world",
            "  (same seed and datapacks) and set mode: worker. Both need the same secret. Workers only",
            "  accept dimensions whose terrain matches exactly; anything else is generated locally, as is",
            "  everything when a worker is busy, slow (timeout-ms) or down. Traffic is encrypted and",
            "  authenticated with the secret (at least 8 characters; the secret itself is never sent).",
            "  workers may also point at a Storia Relay. A worker with relay: host:port connects out to that",
            "  relay instead of listening. max-in-flight -1 = worker threads * 4; threads -1 = cores."
        ));
        config.addDefault("ram-world.enabled", true);
        config.addDefault("ram-world.ram-directory", "/dev/shm/storia");
        config.addDefault("ram-world.sync-interval-seconds", 300);
        config.addDefault("ram-world.min-free-mb", 512);
        config.addDefault("ram-world.delete-on-shutdown", true);
        config.addDefault("pregen.worker-threads", -1);
        config.addDefault("pregen.max-in-flight", -1);
        config.addDefault("player-budget.enabled", true);
        config.addDefault("player-budget.check-interval-ticks", 100);
        config.addDefault("player-budget.max-region-mspt", 45.0);
        config.addDefault("player-budget.pool-saturated-percent", 85);
        config.addDefault("player-budget.recover-below-percent", 70);
        config.addDefault("player-budget.lower-simulation-distance", false);
        config.addDefault("player-budget.min-simulation-distance", 4);
        config.addDefault("player-budget.min-view-distance", 6);
        config.addDefault("player-budget.memory-high-percent", 85);
        config.addDefault("player-budget.memory-low-percent", 70);
        config.addDefault("offload.mode", "off");
        config.addDefault("offload.secret", "");
        config.addDefault("offload.workers", java.util.List.of("127.0.0.1:25590"));
        config.addDefault("offload.max-in-flight", -1);
        config.addDefault("offload.timeout-ms", 10000);
        config.addDefault("offload.compress", true);
        config.addDefault("offload.bind", "0.0.0.0");
        config.addDefault("offload.port", 25590);
        config.addDefault("offload.threads", -1);
        config.addDefault("offload.relay", "");
        config.options().copyDefaults(true);
        try {
            config.save(file);
        } catch (final IOException ex) {
            LOGGER.warn("Could not write {}", CONFIG_FILE, ex);
        }
        return config;
    }

    /** The level folder plus legacy sibling folders (world_nether, world_the_end) awaiting migration. */
    private static List<String> findWorldDirs(final Path diskRoot, final String levelName) throws IOException {
        final List<String> dirs = new ArrayList<>();
        dirs.add(levelName);
        if (Files.isDirectory(diskRoot)) {
            try (Stream<Path> children = Files.list(diskRoot)) {
                children.filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .filter(name -> name.startsWith(levelName + "_"))
                    .filter(name -> Files.exists(diskRoot.resolve(name).resolve("level.dat")))
                    .sorted()
                    .forEach(dirs::add);
            }
        }
        return dirs;
    }

    /**
     * Makes {@code target} match {@code source}: copies new or changed files (by size and mtime)
     * and removes files that no longer exist in source. Returns the number of files copied.
     */
    private static int mirror(final Path source, final Path target) throws IOException {
        if (!Files.isDirectory(source)) {
            return 0;
        }
        Files.createDirectories(target);
        final int[] copied = {0};
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(final Path dir, final BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(target.resolve(source.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException {
                final String name = file.getFileName().toString();
                if (!attrs.isRegularFile() || name.equals("session.lock") || name.endsWith(TEMP_SUFFIX)) {
                    return FileVisitResult.CONTINUE;
                }
                final Path dest = target.resolve(source.relativize(file).toString());
                if (isUpToDate(attrs, dest)) {
                    return FileVisitResult.CONTINUE;
                }
                if (copyConsistent(file, dest)) {
                    copied[0]++;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(final Path file, final IOException exc) {
                // The server may delete a file while we walk; it will be picked up next sync.
                return FileVisitResult.CONTINUE;
            }
        });
        removeStale(source, target);
        return copied[0];
    }

    private static boolean isUpToDate(final BasicFileAttributes sourceAttrs, final Path dest) {
        try {
            final BasicFileAttributes destAttrs = Files.readAttributes(dest, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return destAttrs.size() == sourceAttrs.size() && destAttrs.lastModifiedTime().equals(sourceAttrs.lastModifiedTime());
        } catch (final IOException ex) {
            return false;
        }
    }

    /**
     * Copies via a temp file and an atomic rename. If the source changes while being copied the copy
     * is retried a few times; if it keeps changing it is left for the next sync.
     */
    private static boolean copyConsistent(final Path file, final Path dest) throws IOException {
        final Path temp = dest.resolveSibling(dest.getFileName() + TEMP_SUFFIX);
        for (int attempt = 0; attempt < 3; attempt++) {
            final FileTime before;
            try {
                before = Files.getLastModifiedTime(file);
                Files.copy(file, temp, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            } catch (final java.nio.file.NoSuchFileException ex) {
                Files.deleteIfExists(temp);
                return false;
            }
            if (Files.getLastModifiedTime(file).equals(before)) {
                Files.setLastModifiedTime(temp, before);
                try {
                    Files.move(temp, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (final AtomicMoveNotSupportedException ex) {
                    Files.move(temp, dest, StandardCopyOption.REPLACE_EXISTING);
                }
                return true;
            }
        }
        Files.deleteIfExists(temp);
        return false;
    }

    private static void removeStale(final Path source, final Path target) throws IOException {
        final List<Path> stale = new ArrayList<>();
        Files.walkFileTree(target, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) {
                final String name = file.getFileName().toString();
                if (!name.equals("session.lock") && !Files.exists(source.resolve(target.relativize(file).toString()))) {
                    stale.add(file);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(final Path dir, final IOException exc) {
                if (!dir.equals(target) && !Files.exists(source.resolve(target.relativize(dir).toString()))) {
                    stale.add(dir);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(final Path file, final IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });
        for (final Path path : stale) {
            try {
                Files.deleteIfExists(path);
            } catch (final IOException ignored) {
                // Non-empty directory (the server created something meanwhile); retry next sync.
            }
        }
    }

    private static long size(final Path dir) {
        if (!Files.isDirectory(dir)) {
            return 0L;
        }
        try (Stream<Path> files = Files.walk(dir)) {
            return files.filter(Files::isRegularFile).mapToLong(p -> {
                try {
                    return Files.size(p);
                } catch (final IOException ex) {
                    return 0L;
                }
            }).sum();
        } catch (final IOException ex) {
            return 0L;
        }
    }

    private static void deleteTree(final Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            for (final Path path : paths.sorted((a, b) -> b.getNameCount() - a.getNameCount()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static String hash(final String value) {
        try {
            final byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (final java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
