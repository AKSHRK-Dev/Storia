package dev.stolia.pregen;

import ca.spottedleaf.concurrentutil.util.Priority;
import ca.spottedleaf.moonrise.common.PlatformHooks;
import ca.spottedleaf.moonrise.common.util.MoonriseCommon;
import com.mojang.logging.LogUtils;
import io.papermc.paper.configuration.GlobalConfiguration;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.bukkit.configuration.file.YamlConfiguration;
import org.slf4j.Logger;

/**
 * Generates every chunk in a square around a center, spiralling outwards so neighbouring chunks
 * are generated close together in time (their shared neighbour work is reused while still loaded).
 *
 * <p>While running, the chunk system worker pool is enlarged (worldgen is CPU bound and the
 * default pool is deliberately small to leave room for region tick threads) and restored when done.
 * Progress is saved to {@value #STATE_FILE} so a run can be resumed after a restart.
 */
public final class Pregenerator {

    private static final Logger LOGGER = LogUtils.getClassLogger();
    public static final String STATE_FILE = "stolia-pregen.yml";
    private static final long REPORT_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(10);

    private static volatile Pregenerator current;

    private final ServerLevel level;
    private final String worldName;
    private final int centerX;
    private final int centerZ;
    private final int radius;
    private final long total;
    private final long startIndex;
    private final int maxInFlight;
    private final int workerThreads;
    private final Semaphore permits;
    private final AtomicLong completed = new AtomicLong();
    private volatile long dispatched;
    private volatile boolean running = true;
    private volatile long startNanos;
    private Thread thread;

    private Pregenerator(final ServerLevel level, final String worldName, final int centerX, final int centerZ, final int radius,
                         final long startIndex, final int maxInFlight, final int workerThreads) {
        this.level = level;
        this.worldName = worldName;
        this.centerX = centerX;
        this.centerZ = centerZ;
        this.radius = radius;
        final long side = 2L * radius + 1;
        this.total = side * side;
        this.startIndex = Math.min(startIndex, this.total);
        this.maxInFlight = maxInFlight;
        this.workerThreads = workerThreads;
        this.permits = new Semaphore(maxInFlight);
    }

    public static Pregenerator current() {
        final Pregenerator pregen = current;
        return pregen != null && pregen.running ? pregen : null;
    }

    /**
     * Starts a run. Returns null (and does nothing) if one is already running.
     *
     * @param radius radius in chunks around the center chunk
     */
    public static synchronized Pregenerator start(final ServerLevel level, final String worldName, final int centerX, final int centerZ,
                                                  final int radius, final long startIndex) {
        if (current() != null) {
            return null;
        }
        final YamlConfiguration config = YamlConfiguration.loadConfiguration(new File("stolia.yml"));
        final int cpus = Runtime.getRuntime().availableProcessors();
        int workers = config.getInt("pregen.worker-threads", -1);
        if (workers <= 0) {
            workers = Math.max(1, cpus - 1);
        }
        int maxInFlight = config.getInt("pregen.max-in-flight", -1);
        if (maxInFlight <= 0) {
            maxInFlight = workers * 16;
        }
        final Pregenerator pregen = new Pregenerator(level, worldName, centerX, centerZ, radius, startIndex, maxInFlight, workers);
        current = pregen;
        pregen.thread = new Thread(pregen::run, "Stolia Pregenerator");
        pregen.thread.setDaemon(true);
        pregen.thread.start();
        return pregen;
    }

    /** Stops the running pregen (if any), keeping its progress for {@code resume}. */
    public static synchronized boolean stop() {
        final Pregenerator pregen = current();
        if (pregen == null) {
            return false;
        }
        pregen.running = false;
        pregen.thread.interrupt();
        return true;
    }

    public static YamlConfiguration savedState() {
        final File file = new File(STATE_FILE);
        return file.exists() ? YamlConfiguration.loadConfiguration(file) : null;
    }

    private void run() {
        MoonriseCommon.WORKER_POOL.adjustThreadCount(this.workerThreads);
        LOGGER.info("Pregenerating {} chunks in {} around chunk {}, {} (radius {} chunks) with {} worker threads{}",
            this.total, this.worldName, this.centerX, this.centerZ, this.radius, this.workerThreads,
            this.startIndex > 0 ? ", resuming at " + this.startIndex : "");
        this.startNanos = System.nanoTime();
        long lastReport = this.startNanos;
        final Consumer<net.minecraft.world.level.chunk.ChunkAccess> onLoad = chunk -> {
            this.completed.incrementAndGet();
            this.permits.release();
        };
        final SpiralIterator spiral = new SpiralIterator(this.centerX, this.centerZ, this.radius);
        spiral.skip(this.startIndex);
        this.dispatched = this.startIndex;
        try {
            while (this.running && spiral.hasNext()) {
                this.permits.acquire();
                spiral.next();
                PlatformHooks.get().scheduleChunkLoad(this.level, spiral.x(), spiral.z(), true, ChunkStatus.FULL, true, Priority.NORMAL, onLoad);
                this.dispatched++;
                final long now = System.nanoTime();
                if (now - lastReport >= REPORT_INTERVAL_NANOS) {
                    lastReport = now;
                    LOGGER.info(this.progressLine());
                    this.saveState(false);
                }
            }
            // Wait for in-flight chunks so the final count and state are accurate.
            this.permits.acquire(this.maxInFlight);
            this.permits.release(this.maxInFlight);
        } catch (final InterruptedException ignored) {
            // stop() was called
        } catch (final Throwable ex) {
            LOGGER.error("Pregeneration failed", ex);
        } finally {
            final boolean finished = !spiral.hasNext() && this.running;
            this.running = false;
            this.saveState(finished);
            final GlobalConfiguration.ChunkSystem chunkSystem = GlobalConfiguration.get().chunkSystem;
            MoonriseCommon.adjustWorkerThreads(chunkSystem.workerThreads, chunkSystem.ioThreads);
            if (finished) {
                LOGGER.info("Pregeneration of {} finished: {} chunks in {}", this.worldName, this.total - this.startIndex, formatDuration(System.nanoTime() - this.startNanos));
            } else {
                LOGGER.info("Pregeneration of {} stopped at {} - resume with /stolia pregen resume", this.worldName, this.progressLine());
            }
        }
    }

    // Chunks can complete out of order, so only indices below (dispatched - inFlight) are known done.
    private long safeResumeIndex() {
        final long inFlight = this.maxInFlight - this.permits.availablePermits();
        return Math.max(this.startIndex, this.dispatched - Math.max(0, inFlight));
    }

    private void saveState(final boolean finished) {
        final File file = new File(STATE_FILE);
        if (finished) {
            file.delete();
            return;
        }
        final YamlConfiguration state = new YamlConfiguration();
        state.set("world", this.worldName);
        state.set("center-x", this.centerX);
        state.set("center-z", this.centerZ);
        state.set("radius", this.radius);
        state.set("index", this.safeResumeIndex());
        try {
            state.save(file);
        } catch (final IOException ex) {
            LOGGER.warn("Could not save pregen progress", ex);
        }
    }

    public String progressLine() {
        final long done = this.completed.get();
        final long doneTotal = this.startIndex + done;
        final double seconds = Math.max(1e-3, (System.nanoTime() - this.startNanos) / 1e9);
        final double rate = done / seconds;
        final long remaining = this.total - doneTotal;
        final String eta = rate > 0 ? formatDuration((long) (remaining / rate * 1e9)) : "?";
        return String.format("%s: %d/%d chunks (%.1f%%), %.1f chunks/s, ETA %s",
            this.worldName, doneTotal, this.total, 100.0 * doneTotal / this.total, rate, eta);
    }

    public int workerThreads() {
        return this.workerThreads;
    }

    private static String formatDuration(final long nanos) {
        final long s = TimeUnit.NANOSECONDS.toSeconds(nanos);
        if (s < 60) {
            return s + "s";
        }
        if (s < 3600) {
            return (s / 60) + "m " + (s % 60) + "s";
        }
        return (s / 3600) + "h " + ((s % 3600) / 60) + "m";
    }

    /** Square spiral: the center, then each ring outwards, walking each ring's perimeter. */
    static final class SpiralIterator {
        private final int cx;
        private final int cz;
        private final int radius;
        private int ring;
        private int pos = -1;
        private int x;
        private int z;

        SpiralIterator(final int cx, final int cz, final int radius) {
            this.cx = cx;
            this.cz = cz;
            this.radius = radius;
        }

        boolean hasNext() {
            if (this.ring == 0) {
                return this.pos < 0 || this.radius > 0;
            }
            return this.pos + 1 < 8 * this.ring || this.ring < this.radius;
        }

        void next() {
            if (this.ring == 0 && this.pos < 0) {
                this.pos = 0;
                this.x = this.cx;
                this.z = this.cz;
                return;
            }
            if (this.ring == 0 || this.pos + 1 >= 8 * this.ring) {
                this.ring++;
                this.pos = 0;
            } else {
                this.pos++;
            }
            final int r = this.ring;
            final int side = 2 * r;
            final int edge = this.pos / side;
            final int offset = this.pos % side;
            switch (edge) {
                case 0 -> { this.x = -r + offset; this.z = -r; }     // top, left -> right
                case 1 -> { this.x = r; this.z = -r + offset; }      // right, top -> bottom
                case 2 -> { this.x = r - offset; this.z = r; }       // bottom, right -> left
                default -> { this.x = -r; this.z = r - offset; }     // left, bottom -> top
            }
            this.x += this.cx;
            this.z += this.cz;
        }

        void skip(final long count) {
            if (count <= 0) {
                return;
            }
            // Jump whole rings: rings 0..k-1 contain (2k-1)^2 chunks.
            long k = (long) Math.floor((Math.sqrt((double) count) + 1) / 2);
            while (k > 0 && (2 * k - 1) * (2 * k - 1) > count) {
                k--;
            }
            long remaining = count;
            if (k > 0) {
                remaining -= (2 * k - 1) * (2 * k - 1);
                this.ring = (int) k - 1;
                this.pos = this.ring == 0 ? 0 : 8 * this.ring - 1; // positioned at end of ring k-1
            }
            for (long i = 0; i < remaining && this.hasNext(); i++) {
                this.next();
            }
        }

        int x() {
            return this.x;
        }

        int z() {
            return this.z;
        }
    }
}
