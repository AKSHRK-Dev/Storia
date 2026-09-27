package dev.storia.command;

import dev.storia.budget.PlayerBudget;
import dev.storia.pregen.Pregenerator;
import dev.storia.ramworld.RamWorld;
import io.papermc.paper.ServerBuildInfo;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.command.CommandSender;

import static net.kyori.adventure.text.Component.text;

public final class StoriaCommand extends Command {

    private static final List<String> SUBCOMMANDS = List.of("sync", "status", "pregen", "budget", "region", "cluster");
    private static final List<String> PREGEN_SUBCOMMANDS = List.of("start", "stop", "status", "resume");

    public StoriaCommand(final String name) {
        super(name);
        this.description = "Storia commands";
        this.usageMessage = "/storia [sync | status | pregen | budget | region | cluster]";
        this.setPermission("storia.command.storia");
    }

    @Override
    public boolean execute(final CommandSender sender, final String commandLabel, final String[] args) {
        if (!this.testPermission(sender)) {
            return true;
        }
        final String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "sync" -> this.sync(sender);
            case "status" -> this.status(sender);
            case "pregen" -> this.pregen(sender, args);
            case "budget" -> this.budget(sender);
            case "region" -> this.regions(sender);
            case "cluster" -> {
                // asks the coordinator over the network: never on a region thread
                final Thread thread = new Thread(() -> dev.storia.cluster.Cluster.status().forEach(line -> sender.sendMessage(text(line, NamedTextColor.WHITE))), "Storia cluster status");
                thread.setDaemon(true);
                thread.start();
            }
            default -> sender.sendMessage(text("Usage: " + this.usageMessage, NamedTextColor.RED));
        }
        return true;
    }

    @Override
    public List<String> tabComplete(final CommandSender sender, final String alias, final String[] args) {
        if (args.length == 1) {
            final String prefix = args[0].toLowerCase(Locale.ROOT);
            return SUBCOMMANDS.stream().filter(s -> s.startsWith(prefix)).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("pregen")) {
            final String prefix = args[1].toLowerCase(Locale.ROOT);
            return PREGEN_SUBCOMMANDS.stream().filter(s -> s.startsWith(prefix)).toList();
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("pregen") && args[1].equalsIgnoreCase("start")) {
            return Bukkit.getWorlds().stream().map(World::getName).filter(n -> n.startsWith(args[3])).toList();
        }
        return List.of();
    }

    private void sync(final CommandSender sender) {
        final RamWorld ramWorld = RamWorld.get();
        if (ramWorld == null) {
            sender.sendMessage(text("RAM world is disabled; worlds are already on disk.", NamedTextColor.YELLOW));
            return;
        }
        sender.sendMessage(text("Syncing RAM world to disk...", NamedTextColor.GRAY));
        final long start = System.nanoTime();
        ramWorld.syncAsync().whenComplete((files, ex) -> {
            if (ex != null) {
                sender.sendMessage(text("Sync failed: " + ex.getMessage() + " (see console)", NamedTextColor.RED));
            } else if (files < 0) {
                sender.sendMessage(text("A sync is already running; try again in a moment.", NamedTextColor.YELLOW));
            } else {
                final long ms = Duration.ofNanos(System.nanoTime() - start).toMillis();
                sender.sendMessage(text("Synced " + files + " file(s) to disk in " + ms + " ms.", NamedTextColor.GREEN));
            }
        });
    }

    private void status(final CommandSender sender) {
        sender.sendMessage(text(ServerBuildInfo.buildInfo().brandName() + " " + ServerBuildInfo.buildInfo().asString(ServerBuildInfo.StringRepresentation.VERSION_SIMPLE), NamedTextColor.AQUA));
        final RamWorld ramWorld = RamWorld.get();
        if (ramWorld == null) {
            sender.sendMessage(line("RAM world", text("disabled", NamedTextColor.YELLOW)));
            return;
        }
        final long last = ramWorld.lastSyncMillis();
        final Component lastSync = last == 0
            ? text("not yet", NamedTextColor.YELLOW)
            : text(formatAgo(System.currentTimeMillis() - last) + " ago (" + ramWorld.lastSyncFiles() + " file(s))", NamedTextColor.WHITE);
        Stream.of(
            line("RAM world", text("enabled", NamedTextColor.GREEN)),
            line("RAM path", text(ramWorld.ramRoot().toString(), NamedTextColor.WHITE)),
            line("Disk path", text(ramWorld.diskRoot().toString(), NamedTextColor.WHITE)),
            line("RAM used", text((ramWorld.ramUsageBytes() >> 20) + " MB", NamedTextColor.WHITE)),
            line("Sync interval", text(ramWorld.intervalSeconds() + " s", NamedTextColor.WHITE)),
            line("Last sync", lastSync)
        ).forEach(sender::sendMessage);
    }

    private void pregen(final CommandSender sender, final String[] args) {
        final String sub = args.length < 2 ? "status" : args[1].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "start" -> {
                if (args.length < 3) {
                    sender.sendMessage(text("Usage: /storia pregen start <radius-in-blocks> [world] [centerX centerZ]", NamedTextColor.RED));
                    return;
                }
                final int radiusBlocks;
                try {
                    radiusBlocks = Integer.parseInt(args[2]);
                } catch (final NumberFormatException ex) {
                    sender.sendMessage(text("Radius must be a number of blocks.", NamedTextColor.RED));
                    return;
                }
                final World world = args.length >= 4 ? Bukkit.getWorld(args[3]) : Bukkit.getWorlds().getFirst();
                if (world == null || radiusBlocks < 0) {
                    sender.sendMessage(text("Unknown world or negative radius.", NamedTextColor.RED));
                    return;
                }
                int centerX;
                int centerZ;
                if (args.length >= 6) {
                    try {
                        centerX = Integer.parseInt(args[4]) >> 4;
                        centerZ = Integer.parseInt(args[5]) >> 4;
                    } catch (final NumberFormatException ex) {
                        sender.sendMessage(text("Center must be block coordinates.", NamedTextColor.RED));
                        return;
                    }
                } else {
                    final Location spawn = world.getSpawnLocation();
                    centerX = spawn.getBlockX() >> 4;
                    centerZ = spawn.getBlockZ() >> 4;
                }
                this.startPregen(sender, world, centerX, centerZ, (radiusBlocks + 15) >> 4, 0L);
            }
            case "resume" -> {
                final YamlConfiguration state = Pregenerator.savedState();
                final World world = state == null ? null : Bukkit.getWorld(state.getString("world", ""));
                if (world == null) {
                    sender.sendMessage(text("Nothing to resume.", NamedTextColor.YELLOW));
                    return;
                }
                this.startPregen(sender, world, state.getInt("center-x"), state.getInt("center-z"), state.getInt("radius"), state.getLong("index"));
            }
            case "stop" -> sender.sendMessage(Pregenerator.stop()
                ? text("Stopping pregeneration; progress is saved (/storia pregen resume).", NamedTextColor.GREEN)
                : text("No pregeneration is running.", NamedTextColor.YELLOW));
            case "status" -> {
                final Pregenerator pregen = Pregenerator.current();
                sender.sendMessage(pregen == null
                    ? text(Pregenerator.savedState() != null ? "Not running (a stopped run can be resumed)." : "Not running.", NamedTextColor.YELLOW)
                    : text(pregen.progressLine(), NamedTextColor.WHITE));
            }
            default -> sender.sendMessage(text("Usage: /storia pregen [start | stop | status | resume]", NamedTextColor.RED));
        }
    }

    private void startPregen(final CommandSender sender, final World world, final int centerX, final int centerZ, final int radius, final long index) {
        final Pregenerator pregen = Pregenerator.start(((CraftWorld) world).getHandle(), world.getName(), centerX, centerZ, radius, index);
        if (pregen == null) {
            sender.sendMessage(text("Pregeneration is already running; see /storia pregen status.", NamedTextColor.YELLOW));
            return;
        }
        final long side = 2L * radius + 1;
        sender.sendMessage(text("Pregenerating " + (side * side) + " chunks in " + world.getName() + " with "
            + pregen.workerThreads() + " worker threads. Progress is logged to the console.", NamedTextColor.GREEN));
    }

    private void budget(final CommandSender sender) {
        final PlayerBudget budget = PlayerBudget.get();
        if (budget == null) {
            sender.sendMessage(text("Player budget is disabled (player-budget.enabled in storia.yml).", NamedTextColor.YELLOW));
            return;
        }
        sender.sendMessage(text("Player budget (checked every " + budget.intervalSeconds() + "s)", NamedTextColor.AQUA));
        sender.sendMessage(line("Tick threads busy", text(Math.round(budget.poolUtilisation() * 100) + "%"
            + (budget.poolSaturated() ? " (saturated: busy regions thin out crowds more)" : " (headroom)"),
            budget.poolSaturated() ? NamedTextColor.RED : NamedTextColor.GREEN)));
        sender.sendMessage(line("Share per player", text(String.format(Locale.ROOT, "%.0f%% of a thread", budget.sharePerPlayer() * 100), NamedTextColor.WHITE)));
        sender.sendMessage(line("Heap after GC", text(Math.round(budget.heapFraction() * 100) + "%"
            + (budget.memoryLevel() > 0 ? " (view distance -" + budget.memoryLevel() + " for everyone)" : ""),
            budget.memoryLevel() > 0 ? NamedTextColor.RED : NamedTextColor.GREEN)));
        for (final org.bukkit.entity.Player player : Bukkit.getOnlinePlayers()) {
            final PlayerBudget.PlayerState state = budget.state(player.getUniqueId());
            if (state == null) {
                sender.sendMessage(text(" " + player.getName() + ": not checked yet", NamedTextColor.GRAY));
                continue;
            }
            final String sim = state.appliedSimulation() < 0 ? "default" : String.valueOf(state.appliedSimulation());
            final String view = state.appliedView() < 0 ? "default" : String.valueOf(state.appliedView());
            sender.sendMessage(text(" " + player.getName() + ": ", NamedTextColor.WHITE)
                .append(text(String.format(Locale.ROOT, "region %.1f MSPT, %.1f TPS, %.0f%% thread, %d player(s)",
                    state.regionMspt(), state.regionTps(), state.regionUtilisation() * 100, state.regionPlayers()), NamedTextColor.GRAY))
                .append(text(String.format(Locale.ROOT, " | %.0f blocks/s", state.speed()), NamedTextColor.GRAY))
                .append(text(" | sim " + sim + ", view " + view, state.cpuLevel() > 0 ? NamedTextColor.YELLOW : NamedTextColor.GREEN)));
        }
    }

    private void regions(final CommandSender sender) {
        final List<io.papermc.paper.threadedregions.ThreadedRegionizer.ThreadedRegion<io.papermc.paper.threadedregions.TickRegions.TickRegionData, io.papermc.paper.threadedregions.TickRegions.TickRegionSectionData>> regions = new java.util.ArrayList<>();
        for (final World world : Bukkit.getWorlds()) {
            ((CraftWorld) world).getHandle().regioniser.computeForAllRegions(regions::add);
        }
        final long now = System.nanoTime();
        record Row(String where, double util, double mspt, double tps, int players, int chunks, dev.storia.tickguard.TickGuard.State guard) {}
        final List<Row> rows = new java.util.ArrayList<>();
        for (final var region : regions) {
            final ca.spottedleaf.common.time.TickData.TickReportData report = region.getData().getRegionSchedulingHandle().getTickReport5s(now);
            final net.minecraft.world.level.ChunkPos center = region.getCenterChunk();
            if (report == null || center == null) {
                continue;
            }
            final var stats = region.getData().getRegionStats();
            rows.add(new Row(region.regioniser.world.getWorld().getName() + " " + ((center.x() << 4) | 7) + ", " + ((center.z() << 4) | 7),
                report.utilisation(), report.timePerTickData().segmentAll().average() / 1.0E6, report.tpsData().segmentAll().average(),
                stats.getPlayerCount(), stats.getChunkCount(), region.getData().storiaTickGuard));
        }
        rows.sort((a, b) -> Double.compare(b.util(), a.util()));
        sender.sendMessage(text(rows.size() + " region(s), busiest first (last 5s):", NamedTextColor.AQUA));
        for (final Row row : rows.subList(0, Math.min(10, rows.size()))) {
            sender.sendMessage(text(" " + row.where() + ": ", NamedTextColor.WHITE)
                .append(text(String.format(Locale.ROOT, "%.0f%% thread, %.1f MSPT, %.1f TPS, %d player(s), %d chunks",
                    row.util() * 100, row.mspt(), row.tps(), row.players(), row.chunks()),
                    row.tps() < 19.5 ? NamedTextColor.RED : NamedTextColor.GRAY)));
            final dev.storia.tickguard.TickGuard.State guard = row.guard();
            if (guard.level() > 0) {
                sender.sendMessage(text(String.format(Locale.ROOT, "   tick guard: crowded mobs re-plan every %d ticks (%d AI updates skipped/s)",
                    1 << guard.level(), guard.skippedPerSecond()), NamedTextColor.YELLOW));
            }
            for (final dev.storia.tickguard.TickGuard.Crowd crowd : guard.topCrowds()) {
                sender.sendMessage(text(String.format(Locale.ROOT, "   crowd at %d, %d: %d mobs, mostly %s",
                    (crowd.chunkX() << 4) + 8, (crowd.chunkZ() << 4) + 8, crowd.mobs(), crowd.mainType()), NamedTextColor.GRAY));
            }
        }
    }

    private static Component line(final String label, final Component value) {
        return text(label + ": ", NamedTextColor.GRAY).append(value);
    }

    private static String formatAgo(final long millis) {
        final long seconds = millis / 1000L;
        if (seconds < 60) {
            return seconds + "s";
        }
        return (seconds / 60) + "m " + (seconds % 60) + "s";
    }
}
