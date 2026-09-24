package dev.stolia.command;

import dev.stolia.ramworld.RamWorld;
import io.papermc.paper.ServerBuildInfo;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;

import static net.kyori.adventure.text.Component.text;

public final class StoliaCommand extends Command {

    private static final List<String> SUBCOMMANDS = List.of("sync", "status");

    public StoliaCommand(final String name) {
        super(name);
        this.description = "Stolia commands";
        this.usageMessage = "/stolia [sync | status]";
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
