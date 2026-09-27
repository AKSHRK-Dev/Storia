package dev.storia.api;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Data and messages shared by every server of a Storia Cluster, for plugins.
 *
 * <p>In a cluster, several Storia Workers run one world, and each runs its own copy of every plugin. A plugin's
 * own files and memory are therefore per worker. Use this API for anything that must be the same on every
 * worker: balances, claims, homes, cooldowns, counters, and messages between the copies of a plugin.
 *
 * <ul>
 * <li>{@link #store(String)}: a key-value store per namespace (use your plugin's name), kept by Storia Relay, with
 *     compare-and-set, counters and change notifications on every worker.</li>
 * <li>{@link #publish(String, byte[])} / {@link #subscribe(String, Consumer)}: messages to the same plugin on every
 *     worker, including this one.</li>
 * </ul>
 *
 * <p>On a single Storia server (no cluster) the same API works locally: data is kept in {@code storia-shared/} in the
 * server folder and messages reach this server's subscribers, so a plugin needs no second code path.
 *
 * <p>Every call is asynchronous and never blocks: results come as {@link CompletableFuture}s, and listeners run on a
 * Storia thread, not on a region thread. On Folia, schedule work that touches the world or a player on the right
 * region (for example with {@code Bukkit.getGlobalRegionScheduler()} or the player's scheduler). Do not call
 * {@code join()} on a region thread.
 */
public interface StoriaShared {

    /**
     * The shared data and messages of this server.
     *
     * @return the instance
     * @throws IllegalStateException if called before the server has started
     */
    static StoriaShared get() {
        final StoriaShared shared = Holder.instance;
        if (shared == null) {
            throw new IllegalStateException("Storia shared data is not available yet (the server is still starting)");
        }
        return shared;
    }

    /**
     * Whether this server is a worker of a Storia Cluster. If not, data and messages stay on this server.
     *
     * @return true in a cluster
     */
    boolean clustered();

    /**
     * The name of this worker ({@code cluster.node-name}), or an empty string on a single server.
     *
     * @return the name
     */
    String nodeName();

    /**
     * The key-value store of a namespace. Use your plugin's name in lower case, for example {@code "myplugin"}.
     *
     * @param namespace 1 to 64 characters: {@code a-z 0-9 _ . -}
     * @return the store
     */
    SharedStore store(String namespace);

    /**
     * Sends a message to every subscriber of the channel on every worker, including this one.
     *
     * @param channel 1 to 64 characters: {@code a-z 0-9 _ . : -}, for example {@code "myplugin:announce"}
     * @param message at most 1 MiB
     * @return completes when the relay has taken the message (at once on a single server)
     */
    CompletableFuture<Void> publish(String channel, byte[] message);

    /**
     * Sends a UTF-8 text message; see {@link #publish(String, byte[])}.
     *
     * @param channel the channel
     * @param message the text
     * @return completes when the relay has taken the message
     */
    default CompletableFuture<Void> publish(final String channel, final String message) {
        return this.publish(channel, message.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * Receives the messages of a channel, from every worker, in the order the relay received them.
     *
     * @param channel the channel
     * @param handler runs on a Storia thread
     * @return cancel it to stop receiving
     */
    Subscription subscribe(String channel, Consumer<SharedMessage> handler);

    /** Set by the server; not for plugins. */
    final class Holder {
        static volatile StoriaShared instance;

        private Holder() {
        }

        /**
         * Installs the implementation. Called once by the server.
         *
         * @param shared the implementation
         */
        public static void install(final StoriaShared shared) {
            instance = shared;
        }
    }
}
