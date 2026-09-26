package dev.storia.cluster;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;

/**
 * Phase 1 only: keeps players far enough from cells owned by other nodes that nothing there ticks on this node.
 * A player who comes within simulation distance (+2 chunks) of a foreign cell is moved back to where they
 * last stood safely. Seamless switching between nodes (phase 3) replaces this.
 */
final class ClusterGuard {

    private static final Map<UUID, Location> SAFE = new ConcurrentHashMap<>();

    private ClusterGuard() {}

    static void checkAll() {
        if (Bukkit.getServer() == null) {
            return;
        }
        SAFE.keySet().removeIf(uuid -> Bukkit.getPlayer(uuid) == null);
        for (final Player player : Bukkit.getOnlinePlayers()) {
            ((CraftPlayer) player).taskScheduler.schedule(entity -> {
                if (entity instanceof ServerPlayer serverPlayer) {
                    check(serverPlayer);
                }
            }, null, 1L);
        }
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
        if (!near) {
            SAFE.put(player.getUUID(), bukkit.getLocation());
            return;
        }
        final Location back = SAFE.get(player.getUUID());
        bukkit.sendActionBar(Component.text("Another Storia server runs the area ahead.", NamedTextColor.GOLD));
        if (back != null && back.getWorld() == bukkit.getWorld()) {
            bukkit.teleportAsync(back);
        }
    }
}
