package dev.storia.cluster;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;

/**
 * Last line of defence: keeps players far enough from cells owned by other nodes that nothing there ticks on this
 * node. Normally the coordinator merges areas and moves the player long before this matters, so the guard only
 * acts when a player has stayed near a foreign cell for {@link #PATIENCE_MILLIS} (the merge did not happen) and
 * leaves players alone for {@link #ARRIVAL_GRACE_MILLIS} after they arrived from another node, while the old node
 * is still handing its cells over.
 */
final class ClusterGuard {

    static final long PATIENCE_MILLIS = 8000L;
    static final long ARRIVAL_GRACE_MILLIS = 15000L;

    private static final Map<UUID, Location> SAFE = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> NEAR_SINCE = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> ARRIVED = new ConcurrentHashMap<>();

    private ClusterGuard() {}

    static void checkAll() {
        if (Bukkit.getServer() == null) {
            return;
        }
        SAFE.keySet().removeIf(uuid -> Bukkit.getPlayer(uuid) == null);
        NEAR_SINCE.keySet().removeIf(uuid -> Bukkit.getPlayer(uuid) == null);
        final long now = System.currentTimeMillis();
        ARRIVED.values().removeIf(t -> now - t > ARRIVAL_GRACE_MILLIS);
        for (final Player player : Bukkit.getOnlinePlayers()) {
            ((CraftPlayer) player).taskScheduler.schedule(entity -> {
                if (entity instanceof ServerPlayer serverPlayer) {
                    check(serverPlayer);
                }
            }, null, 1L);
        }
    }

    /** A player arrived from another node; its cells may still be foreign for a moment. */
    static void arrived(final UUID uuid) {
        ARRIVED.put(uuid, System.currentTimeMillis());
    }

    private static void check(final ServerPlayer player) {
        final String dimension = player.level().dimension().identifier().toString();
        final int margin = player.level().getWorld().getSimulationDistance() + 2;
        final int cx = player.chunkPosition().x();
        final int cz = player.chunkPosition().z();
        boolean near = false;
        for (int x = (cx - margin) >> 5; x <= (cx + margin) >> 5 && !near; ++x) {
            for (int z = (cz - margin) >> 5; z <= (cz + margin) >> 5; ++z) {
                if (Cluster.isForeign(dimension, x << 5, z << 5)) {
                    near = true;
                    break;
                }
            }
        }
        final CraftPlayer bukkit = player.getBukkitEntity();
        final UUID uuid = player.getUUID();
        if (!near) {
            SAFE.put(uuid, bukkit.getLocation());
            NEAR_SINCE.remove(uuid);
            return;
        }
        final long now = System.currentTimeMillis();
        final long since = NEAR_SINCE.computeIfAbsent(uuid, k -> now);
        if (now - since < PATIENCE_MILLIS || ARRIVED.containsKey(uuid) || Cluster.isMovingOut(uuid)) {
            return; // the coordinator is about to merge the areas or move the player
        }
        final Location back = SAFE.get(player.getUUID());
        if (back != null && back.getWorld() == bukkit.getWorld()) {
            bukkit.teleportAsync(back);
        }
    }
}
