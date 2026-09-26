package dev.storia.tickguard;

import com.mojang.logging.LogUtils;
import io.papermc.paper.threadedregions.RegionizedWorldData;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import org.bukkit.configuration.file.YamlConfiguration;
import org.slf4j.Logger;

/**
 * Keeps a crowded region under its tick budget by thinning out mob <em>decisions</em> where the
 * crowd is, so players who merely stand nearby are not slowed down by someone else's farm.
 *
 * <p>Every region measures its own world tick time. When the average rises above {@code target-mspt},
 * mobs in crowded chunks (at least {@code crowd-threshold} mobs) re-plan only every 2nd, 4th or 8th
 * tick: sensing, target and goal selection and brain updates are skipped on the other ticks. Movement,
 * pathing along the current path, looking, jumping, collisions, gravity and water flow still run every
 * tick, and blocks, redstone, hoppers and block entities are never touched. The level drops back to
 * normal as soon as the region has headroom again.
 *
 * <p>Mobs near a player, mobs fighting a player, pets and bosses always think every tick.
 */
public final class TickGuard {

    private static final Logger LOGGER = LogUtils.getClassLogger();

    public static final Settings SETTINGS = Settings.load();

    public record Settings(boolean enabled, double targetMspt, int crowdThreshold, double exemptRadiusSq, int maxLevel) {
        static Settings load() {
            final YamlConfiguration config = YamlConfiguration.loadConfiguration(new File("storia.yml"));
            final double radius = Math.max(0.0, config.getDouble("tick-guard.player-radius", 8.0));
            final Settings settings = new Settings(
                config.getBoolean("tick-guard.enabled", true),
                Math.max(5.0, config.getDouble("tick-guard.target-mspt", 40.0)),
                Math.max(2, config.getInt("tick-guard.crowd-threshold", 16)),
                radius * radius,
                Math.max(1, Math.min(4, config.getInt("tick-guard.max-level", 3)))
            );
            if (settings.enabled()) {
                LOGGER.info("Tick guard enabled: target {} MSPT, crowds of {}+ mobs per chunk, up to 1/{} AI updates",
                    settings.targetMspt(), settings.crowdThreshold(), 1 << settings.maxLevel());
            }
            return settings;
        }
    }

    /** A crowded chunk, for {@code /storia region}. */
    public record Crowd(int chunkX, int chunkZ, int mobs, String mainType) {}

    /** Per-region, per-world state. Only touched from the owning region thread, except the volatile snapshot fields. */
    public static final class State {
        long tickStart;
        double averageMspt;
        int level;
        int ticks;
        int skippedThisSecond;
        final LongOpenHashSet crowded = new LongOpenHashSet();

        volatile int lastLevel;
        volatile double lastAverageMspt;
        volatile int lastSkippedPerSecond;
        volatile List<Crowd> topCrowds = List.of();

        public int level() { return this.lastLevel; }
        public double averageMspt() { return this.lastAverageMspt; }
        public int skippedPerSecond() { return this.lastSkippedPerSecond; }
        public List<Crowd> topCrowds() { return this.topCrowds; }
    }

    private TickGuard() {}

    public static void beginTick(final RegionizedWorldData data) {
        if (SETTINGS.enabled()) {
            data.regionData.storiaTickGuard.tickStart = System.nanoTime();
        }
    }

    public static void endTick(final ServerLevel level, final RegionizedWorldData data) {
        if (!SETTINGS.enabled()) {
            return;
        }
        final State state = data.regionData.storiaTickGuard;
        final double mspt = (System.nanoTime() - state.tickStart) / 1.0E6;
        state.averageMspt = state.ticks == 0 && state.averageMspt == 0.0 ? mspt : state.averageMspt * 0.9 + mspt * 0.1;
        if (++state.ticks % 20 != 0) {
            return;
        }
        recountCrowds(data, state);
        final double target = SETTINGS.targetMspt() * (dev.storia.budget.PlayerBudget.regionHogging(data.regionData) ? 0.6 : 1.0);
        if (state.averageMspt > target && !state.crowded.isEmpty()) {
            state.level = Math.min(SETTINGS.maxLevel(), state.level + 1);
        } else if (state.level > 0 && (state.averageMspt < target * 0.6 || state.crowded.isEmpty())) {
            state.level--;
        }
        state.lastLevel = state.level;
        state.lastAverageMspt = state.averageMspt;
        state.lastSkippedPerSecond = state.skippedThisSecond;
        state.skippedThisSecond = 0;
    }

    private static void recountCrowds(final RegionizedWorldData data, final State state) {
        final Long2IntOpenHashMap counts = new Long2IntOpenHashMap();
        data.forEachTickingEntity(entity -> {
            if (entity instanceof Mob) {
                counts.addTo(entity.chunkPosition().longKey(), 1);
            }
        });
        state.crowded.clear();
        final List<long[]> top = new ArrayList<>();
        for (final Long2IntOpenHashMap.Entry entry : counts.long2IntEntrySet()) {
            if (entry.getIntValue() >= SETTINGS.crowdThreshold()) {
                state.crowded.add(entry.getLongKey());
                top.add(new long[] {entry.getLongKey(), entry.getIntValue()});
            }
        }
        top.sort((a, b) -> Long.compare(b[1], a[1]));
        final List<long[]> shown = top.subList(0, Math.min(5, top.size()));
        final List<Object2IntOpenHashMap<EntityType<?>>> types = new ArrayList<>();
        for (int i = 0; i < shown.size(); ++i) {
            types.add(new Object2IntOpenHashMap<>());
        }
        if (!shown.isEmpty()) {
            data.forEachTickingEntity(entity -> {
                if (entity instanceof Mob) {
                    final long key = entity.chunkPosition().longKey();
                    for (int i = 0; i < shown.size(); ++i) {
                        if (shown.get(i)[0] == key) {
                            types.get(i).addTo(entity.getType(), 1);
                        }
                    }
                }
            });
        }
        final List<Crowd> crowds = new ArrayList<>(shown.size());
        for (int i = 0; i < shown.size(); ++i) {
            final long key = shown.get(i)[0];
            String main = "";
            int best = -1;
            for (final Object2IntOpenHashMap.Entry<EntityType<?>> type : types.get(i).object2IntEntrySet()) {
                if (type.getIntValue() > best) {
                    best = type.getIntValue();
                    main = EntityType.getKey(type.getKey()).getPath();
                }
            }
            crowds.add(new Crowd(ChunkPos.getX(key), ChunkPos.getZ(key), (int) shown.get(i)[1], main));
        }
        state.topCrowds = List.copyOf(crowds);
    }

    /**
     * Whether this mob should run its full AI (sensing, goal and target selection, brain) this tick.
     * Called from {@link Mob#serverAiStep()} on the owning region thread.
     */
    public static boolean fullAiTick(final Mob mob) {
        if (!SETTINGS.enabled()) {
            return true;
        }
        final ServerLevel level = (ServerLevel) mob.level();
        final RegionizedWorldData data = level.getCurrentWorldData();
        final State state = data.regionData.storiaTickGuard;
        if (state.level == 0) {
            return true;
        }
        final int mask = (1 << state.level) - 1;
        if (((mob.tickCount + mob.getId()) & mask) == 0) {
            return true;
        }
        if (!state.crowded.contains(mob.chunkPosition().longKey())) {
            return true;
        }
        if (mob.getTarget() instanceof Player || mob.isLeashed()
            || (mob instanceof OwnableEntity ownable && ownable.getOwnerReference() != null)
            || mob instanceof EnderDragon || mob instanceof WitherBoss) {
            return true;
        }
        final double radiusSq = SETTINGS.exemptRadiusSq();
        for (final ServerPlayer player : data.getLocalPlayers()) {
            if (player.level() == level && player.distanceToSqr(mob) <= radiusSq) {
                return true;
            }
        }
        state.skippedThisSecond++;
        return false;
    }

    /** Brain-driven mobs (villagers, piglins, ...) do their thinking in customServerAiStep. */
    public static boolean usesBrain(final Entity entity) {
        return entity instanceof net.minecraft.world.entity.LivingEntity living && living.getBrain().storia$hasMemories();
    }
}
