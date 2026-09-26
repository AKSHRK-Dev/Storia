package dev.storia.budget;

import ca.spottedleaf.common.time.TickData;
import com.mojang.logging.LogUtils;
import io.papermc.paper.threadedregions.ThreadedRegionizer;
import io.papermc.paper.threadedregions.TickRegionScheduler;
import io.papermc.paper.threadedregions.TickRegions;
import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import net.minecraft.server.level.ServerLevel;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.slf4j.Logger;

/**
 * Gives every player a fair share of the server. Tick load in a region (farms, crowds) is handled by
 * {@link dev.storia.tickguard.TickGuard}, which thins out the crowd itself; this class only limits the
 * players who add load by themselves: when their region is over budget, players who are moving fast
 * (flying with elytra, riding, ...) and so make the server load and send many chunks get a shorter view
 * distance (and, only if {@code lower-simulation-distance} is enabled, simulation distance) until they
 * slow down or the region recovers. Players who merely stand or walk in a busy place are never limited.
 *
 * <p>Region tick threads are a shared pool: when there are more busy regions than threads, a region
 * that ticks slowly delays the others. Each player's share of the pool is
 * {@code tickThreads / onlinePlayers}; a region is over budget when its own tick time passes the
 * MSPT limit, or when the pool is saturated and the region uses more than its players' combined share.
 * TPS is not used: it also drops for regions that are merely starved by others.
 * Nothing is limited while the server has headroom.
 *
 * <p>Memory is one shared heap, so it is handled globally: when the live heap after GC passes a
 * threshold, everyone's view distance is reduced one step at a time.
 *
 * <p>A daemon thread wakes every interval, computes the global state, then schedules one task per
 * player on that player's own region thread, where the region stats are read and the distances set.
 */
public final class PlayerBudget {

    private static final Logger LOGGER = LogUtils.getClassLogger();

    private static volatile PlayerBudget instance;

    private final long intervalTicks;
    private final double maxRegionMspt;
    private final double recoverFraction;
    private final double saturatedUtilisation;
    private final int minSimulationDistance;
    private final boolean lowerSimulationDistance;
    private final int minViewDistance;
    private final double memoryHigh;
    private final double memoryLow;
    private final double fastMoverSpeed;

    private final Map<UUID, PlayerState> states = new ConcurrentHashMap<>();
    private volatile int memoryLevel;
    private volatile double lastHeapFraction;
    private volatile double lastPoolUtilisation;
    private volatile boolean poolSaturated;
    private volatile double sharePerPlayer;

    /** Per-player adjustment; only written from the player's region thread. */
    public static final class PlayerState {
        volatile int cpuLevel;
        volatile int appliedSimulation = -1;
        volatile int appliedView = -1;
        volatile double regionMspt;
        volatile double regionTps = 20.0;
        volatile double regionUtilisation;
        volatile int regionPlayers;
        volatile boolean overBudget;
        volatile double speed;
        double lastX = Double.NaN;
        double lastZ;
        long lastNanos;
        net.minecraft.world.level.Level lastWorld;
        int fastStreak;

        public int cpuLevel() { return this.cpuLevel; }
        public int appliedSimulation() { return this.appliedSimulation; }
        public int appliedView() { return this.appliedView; }
        public double regionMspt() { return this.regionMspt; }
        public double regionTps() { return this.regionTps; }
        public double regionUtilisation() { return this.regionUtilisation; }
        public int regionPlayers() { return this.regionPlayers; }
        public boolean overBudget() { return this.overBudget; }
        public double speed() { return this.speed; }
    }

    private PlayerBudget(final YamlConfiguration config) {
        this.intervalTicks = Math.max(20, config.getLong("player-budget.check-interval-ticks", 100));
        this.maxRegionMspt = config.getDouble("player-budget.max-region-mspt", 45.0);
        this.recoverFraction = config.getDouble("player-budget.recover-below-percent", 70) / 100.0;
        this.saturatedUtilisation = config.getDouble("player-budget.pool-saturated-percent", 85) / 100.0;
        this.minSimulationDistance = Math.max(2, config.getInt("player-budget.min-simulation-distance", 4));
        this.lowerSimulationDistance = config.getBoolean("player-budget.lower-simulation-distance", false);
        this.minViewDistance = Math.max(2, config.getInt("player-budget.min-view-distance", 6));
        this.memoryHigh = config.getDouble("player-budget.memory-high-percent", 85) / 100.0;
        this.memoryLow = config.getDouble("player-budget.memory-low-percent", 70) / 100.0;
        this.fastMoverSpeed = Math.max(1.0, config.getDouble("player-budget.fast-mover-speed", 12.0));
    }

    public static PlayerBudget get() {
        return instance;
    }

    /** Starts the budget controller if enabled in storia.yml. Call once the server has started. */
    public static synchronized void start() {
        if (instance != null) {
            return;
        }
        final YamlConfiguration config = YamlConfiguration.loadConfiguration(new File("storia.yml"));
        if (!config.getBoolean("player-budget.enabled", true)) {
            return;
        }
        final PlayerBudget budget = new PlayerBudget(config);
        instance = budget;
        final Thread thread = new Thread(budget::loop, "Storia Player Budget");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
        LOGGER.info("Player budget enabled: checking every {} ticks, region MSPT limit {}, min simulation/view distance {}/{}",
            budget.intervalTicks, budget.maxRegionMspt, budget.minSimulationDistance, budget.minViewDistance);
    }

    private void loop() {
        final long sleepMillis = this.intervalTicks * 50L;
        while (true) {
            try {
                Thread.sleep(sleepMillis);
                this.tick();
            } catch (final InterruptedException ex) {
                return;
            } catch (final Throwable ex) {
                LOGGER.error("Player budget check failed", ex);
            }
        }
    }

    private void tick() {
        final Collection<? extends Player> online = Bukkit.getOnlinePlayers();
        this.states.keySet().removeIf(uuid -> Bukkit.getPlayer(uuid) == null);
        if (online.isEmpty()) {
            return;
        }

        this.updateMemoryLevel();
        this.updatePoolState(online.size());

        for (final Player player : online) {
            final UUID uuid = player.getUniqueId();
            ((CraftPlayer) player).taskScheduler.schedule(
                (final net.minecraft.world.entity.Entity entity) -> this.checkPlayer(uuid, entity),
                null,
                1L
            );
        }
    }

    private void updatePoolState(final int onlinePlayers) {
        final List<ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData>> regions = new ArrayList<>();
        for (final World world : Bukkit.getWorlds()) {
            ((CraftWorld) world).getHandle().regioniser.computeForAllRegions(regions::add);
        }
        final long now = System.nanoTime();
        double total = 0.0;
        for (final ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> region : regions) {
            final TickData.TickReportData report = region.getData().getRegionSchedulingHandle().getTickReport5s(now);
            if (report != null) {
                total += report.utilisation();
            }
        }
        final int threads = Math.max(1, TickRegions.getScheduler().getTotalThreadCount());
        this.lastPoolUtilisation = total / threads;
        this.poolSaturated = this.lastPoolUtilisation >= this.saturatedUtilisation;
        this.sharePerPlayer = (double) threads / onlinePlayers;
    }

    private void updateMemoryLevel() {
        long used = 0L;
        for (final MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() != MemoryType.HEAP) {
                continue;
            }
            // Usage right after the last GC approximates live data; current usage includes garbage.
            final MemoryUsage afterGc = pool.getCollectionUsage();
            used += afterGc != null ? afterGc.getUsed() : 0L;
        }
        final long max = Runtime.getRuntime().maxMemory();
        final double fraction = max > 0 ? (double) used / max : 0.0;
        this.lastHeapFraction = fraction;
        final int maxLevel = 32;
        if (fraction >= this.memoryHigh && this.memoryLevel < maxLevel) {
            this.memoryLevel++;
            LOGGER.warn("Heap {}% used after GC; reducing view distance for all players (level {})", Math.round(fraction * 100), this.memoryLevel);
        } else if (fraction <= this.memoryLow && this.memoryLevel > 0) {
            this.memoryLevel--;
        }
    }

    /** Runs on the region thread that owns the player. */
    private void checkPlayer(final UUID uuid, final net.minecraft.world.entity.Entity entity) {
        if (!(entity instanceof net.minecraft.server.level.ServerPlayer player) || player.hasDisconnected()) {
            return;
        }
        final ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> region = TickRegionScheduler.getCurrentRegion();
        if (region == null) {
            return;
        }
        final PlayerState state = this.states.computeIfAbsent(uuid, k -> new PlayerState());
        final TickData.TickReportData report = region.getData().getRegionSchedulingHandle().getTickReport5s(System.nanoTime());
        if (report != null) {
            state.regionMspt = report.timePerTickData().segmentAll().average() / 1.0E6;
            state.regionTps = report.tpsData().segmentAll().average();
            state.regionUtilisation = report.utilisation();
        }
        state.regionPlayers = Math.max(1, region.getData().getRegionStats().getPlayerCount());

        final double share = this.sharePerPlayer * state.regionPlayers;
        // Only the region's own cost counts: TPS also drops when other regions starve the shared
        // tick threads, and those players are victims, not the cause.
        final boolean lagging = state.regionMspt > this.maxRegionMspt;
        final boolean hogging = this.poolSaturated && state.regionUtilisation > share;
        state.overBudget = lagging || hogging;

        // Horizontal speed since the last check: fast movers make the server load, generate and send chunks.
        final long nowNanos = System.nanoTime();
        if (!Double.isNaN(state.lastX) && player.level() == state.lastWorld) {
            final double seconds = Math.max(0.05, (nowNanos - state.lastNanos) / 1.0E9);
            final double dx = player.getX() - state.lastX;
            final double dz = player.getZ() - state.lastZ;
            state.speed = Math.sqrt(dx * dx + dz * dz) / seconds;
        } else {
            state.speed = 0.0;
        }
        state.lastX = player.getX();
        state.lastZ = player.getZ();
        state.lastNanos = nowNanos;
        state.lastWorld = player.level();
        // Two checks in a row, so a single teleport does not count as moving fast.
        state.fastStreak = state.speed >= this.fastMoverSpeed ? state.fastStreak + 1 : 0;
        final boolean fastMover = state.fastStreak >= 2;

        final ServerLevel level = (ServerLevel) player.level();
        final int worldSimulation = level.getWorld().getSimulationDistance();
        final int worldView = level.getWorld().getViewDistance();
        // By default simulation distance is never lowered, so redstone and farms near players keep running;
        // view distance then never goes below the simulation distance either (it would clamp ticking).
        final int simulationFloor = this.lowerSimulationDistance ? Math.min(this.minSimulationDistance, worldSimulation) : worldSimulation;
        final int viewFloor = Math.min(worldView, this.lowerSimulationDistance ? this.minViewDistance : Math.max(this.minViewDistance, worldSimulation));
        final int maxCpuLevel = (worldSimulation - simulationFloor) + Math.max(0, worldView - viewFloor);

        if (state.overBudget && fastMover) {
            state.cpuLevel = Math.min(maxCpuLevel, state.cpuLevel + 1);
        } else if (state.cpuLevel > 0 && (!fastMover
            || (state.regionMspt < this.maxRegionMspt * this.recoverFraction
                && !(this.poolSaturated && state.regionUtilisation > share * this.recoverFraction)))) {
            state.cpuLevel--;
        }

        // CPU steps lower simulation distance first (entity/redstone/farm ticking), then view distance.
        final int simulationSteps = Math.min(state.cpuLevel, worldSimulation - simulationFloor);
        final int viewSteps = state.cpuLevel - simulationSteps + this.memoryLevel;
        final int view = Math.max(viewFloor, worldView - viewSteps);
        final int simulation = Math.min(view, Math.max(simulationFloor, worldSimulation - simulationSteps));

        final int newView = view == worldView ? -1 : view;
        final int newSimulation = simulation == worldSimulation ? -1 : simulation;
        // -1 means "use the world's distance"; FeatureHooks accepts it directly (the Bukkit setters validate 2..32).
        if (newSimulation != state.appliedSimulation) {
            io.papermc.paper.FeatureHooks.setSimulationDistance(player, newSimulation);
            state.appliedSimulation = newSimulation;
        }
        if (newView != state.appliedView) {
            io.papermc.paper.FeatureHooks.setViewDistance(player, newView);
            state.appliedView = newView;
        }
    }

    /**
     * Whether a region uses more than its players' share of saturated tick threads. The tick guard then
     * aims lower in that region, so busy regions cannot starve everyone else. Safe to call from any thread.
     */
    public static boolean regionHogging(final TickRegions.TickRegionData region) {
        final PlayerBudget budget = instance;
        if (budget == null || !budget.poolSaturated) {
            return false;
        }
        final TickData.TickReportData report = region.getRegionSchedulingHandle().getTickReport5s(System.nanoTime());
        if (report == null) {
            return false;
        }
        final int players = Math.max(1, region.getRegionStats().getPlayerCount());
        return report.utilisation() > budget.sharePerPlayer * players;
    }

    public double fastMoverSpeed() {
        return this.fastMoverSpeed;
    }

    public PlayerState state(final UUID uuid) {
        return this.states.get(uuid);
    }

    public int memoryLevel() {
        return this.memoryLevel;
    }

    public double heapFraction() {
        return this.lastHeapFraction;
    }

    public double poolUtilisation() {
        return this.lastPoolUtilisation;
    }

    public boolean poolSaturated() {
        return this.poolSaturated;
    }

    public double sharePerPlayer() {
        return this.sharePerPlayer;
    }

    public long intervalSeconds() {
        return TimeUnit.MILLISECONDS.toSeconds(this.intervalTicks * 50L);
    }
}
