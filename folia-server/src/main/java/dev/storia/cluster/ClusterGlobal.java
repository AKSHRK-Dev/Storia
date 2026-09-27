package dev.storia.cluster;

import com.mojang.logging.LogUtils;
import dev.storia.cluster.protocol.ClusterProtocol;
import io.papermc.paper.threadedregions.RegionizedServer;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.clock.ServerClockManager;
import net.minecraft.world.clock.WorldClock;
import net.minecraft.world.level.saveddata.WeatherData;
import org.slf4j.Logger;

/**
 * Keeps time, weather and game rules the same on every node. The coordinator picks a primary node (the lowest index); it runs
 * the weather cycle and its clocks are the reference. Other nodes do not advance the weather themselves, follow
 * the primary's weather and correct their clocks when they drift. A /time or /weather change on any node is
 * detected and sent to all nodes.
 */
public final class ClusterGlobal {

    private static final Logger LOGGER = LogUtils.getClassLogger();
    private static final long CLOCK_TOLERANCE_TICKS = 20L;
    private static final long CHANGE_THRESHOLD_TICKS = 100L;

    private static volatile boolean known;
    private static volatile boolean primary = true;
    /** Last weather line applied from the primary, per key. */
    private static final Map<String, String> appliedWeather = new ConcurrentHashMap<>();
    /** Last game rule value applied, per key. */
    private static final Map<String, String> appliedRules = new ConcurrentHashMap<>();
    /** Last clock value applied from the primary and when, per key. */
    private static final Map<String, long[]> appliedClock = new ConcurrentHashMap<>();

    private ClusterGlobal() {}

    /** Whether this node follows another node's weather instead of running its own cycle. */
    public static boolean isFollower() {
        return known && !primary;
    }

    public static boolean isPrimary() {
        return !known || primary;
    }

    /** Called once a second from the maintenance thread. */
    static void tick(final ClusterClient client) {
        final MinecraftServer server = MinecraftServer.getServer();
        if (server == null || !Cluster.serverStarted) { // Folia never sets isReady()
            return;
        }
        RegionizedServer.getInstance().addTask(() -> {
            final Map<String, String> state = collect(server);
            final Thread sender = new Thread(() -> send(client, state), "Storia Cluster global");
            sender.setDaemon(true);
            sender.start();
        });
    }

    private static Map<String, String> collect(final MinecraftServer server) {
        final Map<String, String> state = new LinkedHashMap<>();
        for (final ServerLevel level : server.getAllLevels()) {
            final String dimension = level.dimension().identifier().toString();
            final WeatherData weather = level.getWeatherData();
            state.put("weather " + dimension, (weather.isRaining() ? 1 : 0) + " " + (weather.isThundering() ? 1 : 0) + " "
                + weather.getRainTime() + " " + weather.getThunderTime() + " " + weather.getClearWeatherTime());
            final net.minecraft.world.level.gamerules.GameRules rules = level.getGameRules();
            rules.availableRules().forEach(rule -> state.put("rule " + dimension + " " + rule.id(), rules.getAsString(rule)));
            final ServerClockManager clocks = level.clockManager();
            server.registryAccess().lookupOrThrow(Registries.WORLD_CLOCK).listElements().forEach(clock -> {
                try {
                    state.put("clock " + dimension + " " + clock.key().identifier(), Long.toString(clocks.getTotalTicks(clock)));
                } catch (final IllegalStateException ignored) {
                    // this level does not run that clock
                }
            });
        }
        return state;
    }

    private static void send(final ClusterClient client, final Map<String, String> state) {
        final StringBuilder body = new StringBuilder();
        if (isPrimary()) {
            body.append("state\n");
            state.forEach((key, value) -> body.append(key).append('=').append(value).append('\n'));
        } else {
            final long now = System.currentTimeMillis();
            final StringBuilder changes = new StringBuilder();
            state.forEach((key, value) -> {
                if (key.startsWith("rule ")) {
                    final String applied = appliedRules.get(key);
                    if (applied != null && !applied.equals(value)) {
                        changes.append(key).append('=').append(value).append('\n');
                    }
                } else if (key.startsWith("weather ")) {
                    final String applied = appliedWeather.get(key);
                    if (applied != null && !sameWeather(applied, value)) {
                        changes.append(key).append('=').append(value).append('\n');
                    }
                } else {
                    final long[] applied = appliedClock.get(key);
                    if (applied != null) {
                        final long expected = applied[0] + (now - applied[1]) / 50L;
                        if (Math.abs(Long.parseLong(value) - expected) > CHANGE_THRESHOLD_TICKS) {
                            changes.append(key).append('=').append(value).append('\n');
                        }
                    }
                }
            });
            if (changes.isEmpty() && known) {
                // still report in, so the coordinator knows who is primary
                body.append("change\n");
            } else {
                body.append("change\n").append(changes);
            }
        }
        try {
            final ClusterProtocol.Response response = client.request(ClusterProtocol.OP_GLOBAL, ClusterProtocol.string(body.toString()));
            if (response.status() == ClusterProtocol.OK) {
                final boolean nowPrimary = "primary".equals(ClusterProtocol.readString(response.body()));
                if (!known || nowPrimary != primary) {
                    LOGGER.info("Storia Cluster: this node {} time and weather", nowPrimary ? "now keeps" : "now follows another node's");
                }
                primary = nowPrimary;
                known = true;
            }
        } catch (final IOException ex) {
            // try again next second
        }
    }

    /** Weather lines match if raining and thundering match (timers of a follower are frozen, the primary's run). */
    private static boolean sameWeather(final String a, final String b) {
        final String[] x = a.split(" ");
        final String[] y = b.split(" ");
        return x[0].equals(y[0]) && x[1].equals(y[1]);
    }

    /** Applies time and weather pushed by the coordinator (the primary's state or another node's change). */
    static void apply(final String body) {
        final MinecraftServer server = MinecraftServer.getServer();
        if (server == null) {
            return;
        }
        final String[] lines = body.split("\n");
        final boolean fromPrimary = lines.length > 0 && lines[0].equals("state");
        RegionizedServer.getInstance().addTask(() -> {
            final long now = System.currentTimeMillis();
            for (int i = 1; i < lines.length; ++i) {
                final int eq = lines[i].indexOf('=');
                if (eq < 0) {
                    continue;
                }
                final String key = lines[i].substring(0, eq);
                final String value = lines[i].substring(eq + 1);
                final String[] parts = key.split(" ");
                final ServerLevel level = level(server, parts[1]);
                if (level == null) {
                    continue;
                }
                try {
                    if (parts[0].equals("rule") && parts.length == 3) {
                        final String localRule = ruleValue(level, parts[2]);
                        if (localRule.equals(value)) {
                            appliedRules.put(key, value); // in agreement (also: our change has arrived)
                            continue;
                        }
                        // a local change not yet reported wins over the primary's older state
                        if (fromPrimary && appliedRules.containsKey(key) && !appliedRules.get(key).equals(localRule)) {
                            continue;
                        }
                        applyRule(level, parts[2], value);
                        appliedRules.put(key, value);
                    } else if (parts[0].equals("weather")) {
                        final String[] w = value.split(" ");
                        final WeatherData weather = level.getWeatherData();
                        final String local = (weather.isRaining() ? 1 : 0) + " " + (weather.isThundering() ? 1 : 0);
                        if (fromPrimary && sameWeather(value, local + " 0 0 0")) {
                            appliedWeather.put(key, value); // in agreement; keep the primary's timers
                            weather.setRainTime(Integer.parseInt(w[2]));
                            weather.setThunderTime(Integer.parseInt(w[3]));
                            weather.setClearWeatherTime(Integer.parseInt(w[4]));
                            continue;
                        }
                        if (fromPrimary && appliedWeather.containsKey(key) && !sameWeather(appliedWeather.get(key), local + " 0 0 0")) {
                            continue;
                        }
                        weather.setRaining(w[0].equals("1"));
                        weather.setThundering(w[1].equals("1"));
                        weather.setRainTime(Integer.parseInt(w[2]));
                        weather.setThunderTime(Integer.parseInt(w[3]));
                        weather.setClearWeatherTime(Integer.parseInt(w[4]));
                        appliedWeather.put(key, value);
                    } else if (parts[0].equals("clock") && parts.length == 3) {
                        final long ticks = Long.parseLong(value);
                        final Holder<WorldClock> clock = server.registryAccess().lookupOrThrow(Registries.WORLD_CLOCK)
                            .get(net.minecraft.resources.ResourceKey.create(Registries.WORLD_CLOCK, net.minecraft.resources.Identifier.parse(parts[2])))
                            .orElse(null);
                        if (clock != null) {
                            final ServerClockManager clocks = level.clockManager();
                            final long[] applied = appliedClock.get(key);
                            if (Math.abs(clocks.getTotalTicks(clock) - ticks) <= CLOCK_TOLERANCE_TICKS) {
                                appliedClock.put(key, new long[] {ticks, now}); // in agreement
                                continue;
                            }
                            if (fromPrimary && applied != null
                                && Math.abs(clocks.getTotalTicks(clock) - (applied[0] + (now - applied[1]) / 50L)) > CHANGE_THRESHOLD_TICKS) {
                                continue; // set locally (/time), not reported yet
                            }
                            if (Math.abs(clocks.getTotalTicks(clock) - ticks) > CLOCK_TOLERANCE_TICKS || !fromPrimary) {
                                clocks.setTotalTicks(clock, ticks);
                            }
                            appliedClock.put(key, new long[] {ticks, now});
                        }
                    }
                } catch (final RuntimeException ex) {
                    LOGGER.debug("Could not apply cluster state {}: {}", key, ex.toString());
                }
            }
        });
    }

    private static String ruleValue(final ServerLevel level, final String id) {
        final net.minecraft.world.level.gamerules.GameRules rules = level.getGameRules();
        return rules.availableRules().filter(rule -> rule.id().equals(id)).findFirst().map(rules::getAsString).orElse("");
    }

    private static void applyRule(final ServerLevel level, final String id, final String value) {
        final net.minecraft.world.level.gamerules.GameRules rules = level.getGameRules();
        rules.availableRules().filter(rule -> rule.id().equals(id)).findFirst().ifPresent(rule -> setRule(rules, rule, value, level));
    }

    private static <T> void setRule(final net.minecraft.world.level.gamerules.GameRules rules, final net.minecraft.world.level.gamerules.GameRule<T> rule,
                                    final String value, final ServerLevel level) {
        rule.deserialize(value).result().ifPresent(parsed -> {
            if (!parsed.equals(rules.get(rule))) {
                rules.set(rule, parsed, level);
            }
        });
    }

    /** "rain, clock minecraft:overworld 13000" for status output. */
    static String describe(final MinecraftServer server, final ServerLevel level) {
        final WeatherData weather = level.getWeatherData();
        final StringBuilder out = new StringBuilder(weather.isThundering() ? "thunder" : weather.isRaining() ? "rain" : "clear");
        server.registryAccess().lookupOrThrow(Registries.WORLD_CLOCK).listElements().forEach(clock -> {
            try {
                out.append(", ").append(clock.key().identifier().getPath()).append(" clock ").append(level.clockManager().getTotalTicks(clock) % 24000L);
            } catch (final IllegalStateException ignored) {
                // not run by this level
            }
        });
        return out.toString();
    }

    private static ServerLevel level(final MinecraftServer server, final String dimension) {
        for (final ServerLevel level : server.getAllLevels()) {
            if (level.dimension().identifier().toString().equals(dimension)) {
                return level;
            }
        }
        return null;
    }
}
