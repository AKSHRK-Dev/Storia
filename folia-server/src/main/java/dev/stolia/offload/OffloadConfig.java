package dev.stolia.offload;

import java.io.File;
import java.util.List;
import org.bukkit.configuration.file.YamlConfiguration;

/** The {@code offload} section of stolia.yml. */
record OffloadConfig(
    String mode,
    String secret,
    List<String> workers,
    int maxInFlight,
    long timeoutMillis,
    boolean compress,
    String bind,
    int port,
    int threads,
    String relay
) {

    static OffloadConfig load() {
        final YamlConfiguration config = YamlConfiguration.loadConfiguration(new File("stolia.yml"));
        return new OffloadConfig(
            NoiseOffload.WORKER_MODE ? "worker" : config.getString("offload.mode", "off").toLowerCase(java.util.Locale.ROOT),
            config.getString("offload.secret", ""),
            config.getStringList("offload.workers"),
            config.getInt("offload.max-in-flight", -1),
            Math.max(500L, config.getLong("offload.timeout-ms", 10000L)),
            config.getBoolean("offload.compress", true),
            config.getString("offload.bind", "0.0.0.0"),
            config.getInt("offload.port", 25590),
            config.getInt("offload.threads", -1),
            config.getString("offload.relay", "")
        );
    }
}
