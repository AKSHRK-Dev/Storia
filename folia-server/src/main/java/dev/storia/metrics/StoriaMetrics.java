package dev.storia.metrics;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import dev.storia.cluster.Cluster;
import dev.storia.ramworld.RamWorld;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;
import net.minecraft.server.MinecraftServer;
import org.bukkit.Bukkit;
import org.slf4j.Logger;

/**
 * Anonymous usage statistics for Storia on bStats (https://bstats.org/plugin/bukkit/Storia/34364), sent the same way
 * as bStats' own Metrics class for Bukkit: the server count, player count, versions, OS and two Storia charts. Turned
 * off with {@code enabled: false} in {@code plugins/bStats/config.yml}, like every bStats plugin.
 */
public final class StoriaMetrics {

    private static final Logger LOGGER = LogUtils.getClassLogger();
    private static final int SERVICE_ID = 34364;
    private static final String URL = "https://bStats.org/api/v2/data/bukkit";
    private static final String METRICS_VERSION = "3.1.0";

    private final String serverUuid;
    private final boolean logFailedRequests;
    private final String version = storiaVersion();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        final Thread thread = new Thread(r, "Storia bStats");
        thread.setDaemon(true);
        return thread;
    });

    private StoriaMetrics(final String serverUuid, final boolean logFailedRequests) {
        this.serverUuid = serverUuid;
        this.logFailedRequests = logFailedRequests;
    }

    /** Starts sending statistics; called only when bStats is enabled in its config. */
    public static void start(final String serverUuid, final boolean logFailedRequests) {
        new StoriaMetrics(serverUuid, logFailedRequests).schedule();
    }

    /** The Storia release this jar was built for ("26.2-5"), or "dev" for a local build. */
    public static String storiaVersion() {
        try (InputStream in = StoriaMetrics.class.getResourceAsStream("/storia-version.txt")) {
            if (in != null) {
                final String version = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
                if (!version.isEmpty()) {
                    return version;
                }
            }
        } catch (final IOException ignored) {
            // fall through
        }
        return "dev";
    }

    private void schedule() {
        // The delays bStats asks for: first after 3 to 6 minutes, then every 30 minutes from a random offset.
        final long initialDelay = (long) (1000 * 60 * (3 + Math.random() * 3));
        final long secondDelay = (long) (1000 * 60 * (Math.random() * 30));
        this.scheduler.schedule(this::submit, initialDelay, TimeUnit.MILLISECONDS);
        this.scheduler.scheduleAtFixedRate(this::submit, initialDelay + secondDelay, 1000 * 60 * 30, TimeUnit.MILLISECONDS);
    }

    private void submit() {
        if (MinecraftServer.getServer().hasStopped()) {
            this.scheduler.shutdown();
            return;
        }
        try {
            send(this.data());
        } catch (final Exception ex) {
            if (this.logFailedRequests) {
                LOGGER.warn("Could not submit Storia statistics to bStats", ex);
            }
        }
    }

    private JsonObject data() {
        final JsonObject data = new JsonObject();
        data.addProperty("playerAmount", Bukkit.getOnlinePlayers().size());
        data.addProperty("onlineMode", Bukkit.getOnlineMode() ? 1 : 0);
        data.addProperty("bukkitVersion", Bukkit.getVersion());
        data.addProperty("bukkitName", Bukkit.getName());
        data.addProperty("javaVersion", System.getProperty("java.version"));
        data.addProperty("osName", System.getProperty("os.name"));
        data.addProperty("osArch", System.getProperty("os.arch"));
        data.addProperty("osVersion", System.getProperty("os.version"));
        data.addProperty("coreCount", Runtime.getRuntime().availableProcessors());

        final JsonArray charts = new JsonArray();
        charts.add(simplePie("mode", Cluster.enabled() ? "Cluster worker" : "Single server"));
        charts.add(simplePie("ram_world", RamWorld.get() != null ? "On" : "Off"));

        final JsonObject service = new JsonObject();
        service.addProperty("pluginVersion", this.version);
        service.addProperty("id", SERVICE_ID);
        service.add("customCharts", charts);

        data.add("service", service);
        data.addProperty("serverUUID", this.serverUuid);
        data.addProperty("metricsVersion", METRICS_VERSION);
        return data;
    }

    private static JsonObject simplePie(final String id, final String value) {
        final JsonObject values = new JsonObject();
        values.addProperty("value", value);
        final JsonObject chart = new JsonObject();
        chart.addProperty("chartId", id);
        chart.add("data", values);
        return chart;
    }

    private static void send(final JsonObject data) throws IOException {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
            gzip.write(data.toString().getBytes(StandardCharsets.UTF_8));
        }
        final byte[] body = bytes.toByteArray();
        final HttpURLConnection connection = (HttpURLConnection) URI.create(URL).toURL().openConnection();
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(10_000);
        connection.setRequestMethod("POST");
        connection.addRequestProperty("Accept", "application/json");
        connection.addRequestProperty("Connection", "close");
        connection.addRequestProperty("Content-Encoding", "gzip");
        connection.addRequestProperty("Content-Length", String.valueOf(body.length));
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("User-Agent", "Metrics-Service/1");
        connection.setDoOutput(true);
        try (OutputStream out = connection.getOutputStream()) {
            out.write(body);
        }
        final int status = connection.getResponseCode();
        connection.disconnect();
        if (status < 200 || status >= 300) {
            throw new IOException("bStats answered HTTP " + status);
        }
    }
}
