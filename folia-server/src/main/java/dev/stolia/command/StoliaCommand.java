package dev.stolia.command;

import dev.stolia.pregen.Pregenerator;
import dev.stolia.ramworld.RamWorld;
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

public final class StoliaCommand extends Command {

    private static final List<String> SUBCOMMANDS = List.of("sync", "status", "pregen");
    private static final List<String> PREGEN_SUBCOMMANDS = List.of("start", "stop", "status", "resume");

    public StoliaCommand(final String name) {
        super(name);
        this.description = "Stolia commands";
        this.usageMessage = "/stolia [sync | status | pregen]";
        this.setPermission("stolia.command.stolia");
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
                    sender.sendMessage(text("Usage: /stolia pregen start <radius-in-blocks> [world] [centerX centerZ]", NamedTextColor.RED));
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
                ? text("Stopping pregeneration; progress is saved (/stolia pregen resume).", NamedTextColor.GREEN)
                : text("No pregeneration is running.", NamedTextColor.YELLOW));
            case "status" -> {
                final Pregenerator pregen = Pregenerator.current();
                sender.sendMessage(pregen == null
                    ? text(Pregenerator.savedState() != null ? "Not running (a stopped run can be resumed)." : "Not running.", NamedTextColor.YELLOW)
                    : text(pregen.progressLine(), NamedTextColor.WHITE));
            }
            default -> sender.sendMessage(text("Usage: /stolia pregen [start | stop | status | resume]", NamedTextColor.RED));
        }
    }

    private void startPregen(final CommandSender sender, final World world, final int centerX, final int centerZ, final int radius, final long index) {
        final Pregenerator pregen = Pregenerator.start(((CraftWorld) world).getHandle(), world.getName(), centerX, centerZ, radius, index);
        if (pregen == null) {
            sender.sendMessage(text("Pregeneration is already running; see /stolia pregen status.", NamedTextColor.YELLOW));
            return;
        }
        final long side = 2L * radius + 1;
        sender.sendMessage(text("Pregenerating " + (side * side) + " chunks in " + world.getName() + " with "
            + pregen.workerThreads() + " worker threads. Progress is logged to the console.", NamedTextColor.GREEN));
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
