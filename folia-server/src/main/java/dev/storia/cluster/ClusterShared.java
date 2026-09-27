package dev.storia.cluster;

import com.mojang.logging.LogUtils;
import dev.storia.api.SharedChange;
import dev.storia.api.SharedMessage;
import dev.storia.api.SharedStore;
import dev.storia.api.StoriaShared;
import dev.storia.api.Subscription;
import dev.storia.cluster.protocol.ClusterProtocol;
import dev.storia.cluster.protocol.SharedData;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import org.slf4j.Logger;

/**
 * The server side of {@link StoriaShared}. In a cluster every call goes to Storia Relay, and changes and messages come
 * back from it to every worker (including this one), in the order the relay received them. On a single server the same
 * store lives in {@code storia-shared/} and changes and messages are delivered here directly.
 *
 * <p>Changes and messages are sent in the order the plugin made them (one thread); reads run on a small pool. Plugin
 * listeners run on their own thread, one at a time, so a slow listener never holds up the cluster.
 */
public final class ClusterShared implements StoriaShared {

    private static final Logger LOGGER = LogUtils.getClassLogger();

    private final ClusterClient client;
    private final SharedData local;
    private final String node;
    private final ExecutorService writes = executor(1, "Storia shared writes");
    private final ExecutorService reads = executor(4, "Storia shared reads");
    private final ExecutorService events = executor(1, "Storia shared events");
    private final Map<String, List<Consumer<SharedChange>>> listeners = new ConcurrentHashMap<>();
    private final Map<String, List<Consumer<SharedMessage>>> subscribers = new ConcurrentHashMap<>();
    private final Map<String, SharedStore> stores = new ConcurrentHashMap<>();

    private ClusterShared(final ClusterClient client, final String node) {
        this.client = client;
        this.node = node;
        this.local = client == null ? new SharedData(Path.of("storia-shared")) : null;
    }

    private static volatile ClusterShared instance;

    /** Installs the shared data for plugins: through the relay in a cluster, local otherwise. */
    static void install(final ClusterClient client, final String node) {
        instance = new ClusterShared(client, node == null ? "" : node);
        StoriaShared.Holder.install(instance);
    }

    private static ExecutorService executor(final int threads, final String name) {
        return Executors.newFixedThreadPool(threads, r -> {
            final Thread thread = new Thread(r, name);
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override
    public boolean clustered() {
        return this.client != null;
    }

    @Override
    public String nodeName() {
        return this.node;
    }

    @Override
    public SharedStore store(final String namespace) {
        SharedData.checkNamespace(namespace);
        return this.stores.computeIfAbsent(namespace, Store::new);
    }

    @Override
    public CompletableFuture<Void> publish(final String channel, final byte[] message) {
        SharedData.checkChannel(channel);
        SharedData.checkValue(message);
        final byte[] data = message.clone();
        return this.run(this.writes, () -> {
            if (this.client == null) {
                this.onMessage(new SharedMessage(channel, "", data));
            } else {
                this.request(ClusterProtocol.OP_PUBLISH, new ClusterProtocol.Kv(channel, "", data, null, 0L));
            }
            return null;
        });
    }

    @Override
    public Subscription subscribe(final String channel, final Consumer<SharedMessage> handler) {
        SharedData.checkChannel(channel);
        final List<Consumer<SharedMessage>> list = this.subscribers.computeIfAbsent(channel, k -> new CopyOnWriteArrayList<>());
        list.add(handler);
        return () -> list.remove(handler);
    }

    // ---- events from the relay ----

    static void onPush(final byte op, final ClusterProtocol.Event event) {
        final ClusterShared shared = instance;
        if (shared == null) {
            return;
        }
        if (op == ClusterProtocol.PUSH_KV_CHANGE) {
            shared.onChange(new SharedChange(event.scope(), event.key(), event.value(), event.node()));
        } else if (op == ClusterProtocol.PUSH_MESSAGE) {
            shared.onMessage(new SharedMessage(event.scope(), event.node(), event.value() == null ? new byte[0] : event.value()));
        }
    }

    private void onChange(final SharedChange change) {
        final List<Consumer<SharedChange>> list = this.listeners.get(change.namespace());
        if (list != null && !list.isEmpty()) {
            this.events.execute(() -> list.forEach(listener -> call(listener, change, "listener of " + change.namespace())));
        }
    }

    private void onMessage(final SharedMessage message) {
        final List<Consumer<SharedMessage>> list = this.subscribers.get(message.channel());
        if (list != null && !list.isEmpty()) {
            this.events.execute(() -> list.forEach(handler -> call(handler, message, "subscriber of " + message.channel())));
        }
    }

    private static <T> void call(final Consumer<T> consumer, final T value, final String what) {
        try {
            consumer.accept(value);
        } catch (final Throwable ex) {
            LOGGER.warn("A plugin's {} failed", what, ex);
        }
    }

    // ---- requests ----

    @FunctionalInterface
    private interface Call<T> {
        T run() throws IOException;
    }

    private <T> CompletableFuture<T> run(final ExecutorService executor, final Call<T> call) {
        final CompletableFuture<T> future = new CompletableFuture<>();
        executor.execute(() -> {
            try {
                future.complete(call.run());
            } catch (final Throwable ex) {
                future.completeExceptionally(ex);
            }
        });
        return future;
    }

    private ClusterProtocol.Response request(final byte op, final ClusterProtocol.Kv kv) throws IOException {
        final ClusterProtocol.Response response = this.client.request(op, ClusterProtocol.kv(kv));
        if (response.status() == ClusterProtocol.ERROR) {
            throw new IOException(ClusterProtocol.readString(response.body()));
        }
        return response;
    }

    private final class Store implements SharedStore {
        private final String namespace;

        Store(final String namespace) {
            this.namespace = namespace;
        }

        @Override
        public String namespace() {
            return this.namespace;
        }

        @Override
        public CompletableFuture<byte[]> get(final String key) {
            SharedData.checkKey(key);
            return ClusterShared.this.run(ClusterShared.this.reads, () -> {
                if (ClusterShared.this.client == null) {
                    return ClusterShared.this.local.get(this.namespace, key);
                }
                final ClusterProtocol.Response response = ClusterShared.this.request(ClusterProtocol.OP_KV_GET, new ClusterProtocol.Kv(this.namespace, key, null, null, 0L));
                return response.status() == ClusterProtocol.OK ? response.body() : null;
            });
        }

        @Override
        public CompletableFuture<Void> set(final String key, final byte[] value) {
            SharedData.checkKey(key);
            SharedData.checkValue(value);
            final byte[] copy = value == null ? null : value.clone();
            return ClusterShared.this.run(ClusterShared.this.writes, () -> {
                if (ClusterShared.this.client == null) {
                    ClusterShared.this.local.set(this.namespace, key, copy);
                    ClusterShared.this.onChange(new SharedChange(this.namespace, key, copy, ""));
                } else {
                    ClusterShared.this.request(ClusterProtocol.OP_KV_SET, new ClusterProtocol.Kv(this.namespace, key, copy, null, 0L));
                }
                return null;
            });
        }

        @Override
        public CompletableFuture<Boolean> compareAndSet(final String key, final byte[] expected, final byte[] value) {
            SharedData.checkKey(key);
            SharedData.checkValue(value);
            final byte[] want = expected == null ? null : expected.clone();
            final byte[] copy = value == null ? null : value.clone();
            return ClusterShared.this.run(ClusterShared.this.writes, () -> {
                if (ClusterShared.this.client == null) {
                    final boolean changed = ClusterShared.this.local.compareAndSet(this.namespace, key, want, copy);
                    if (changed) {
                        ClusterShared.this.onChange(new SharedChange(this.namespace, key, copy, ""));
                    }
                    return changed;
                }
                final ClusterProtocol.Response response = ClusterShared.this.request(ClusterProtocol.OP_KV_CAS, new ClusterProtocol.Kv(this.namespace, key, copy, want, 0L));
                return Boolean.parseBoolean(ClusterProtocol.readString(response.body()));
            });
        }

        @Override
        public CompletableFuture<Long> increment(final String key, final long delta) {
            SharedData.checkKey(key);
            return ClusterShared.this.run(ClusterShared.this.writes, () -> {
                if (ClusterShared.this.client == null) {
                    final long value = ClusterShared.this.local.increment(this.namespace, key, delta);
                    ClusterShared.this.onChange(new SharedChange(this.namespace, key, Long.toString(value).getBytes(StandardCharsets.UTF_8), ""));
                    return value;
                }
                final ClusterProtocol.Response response = ClusterShared.this.request(ClusterProtocol.OP_KV_INCR, new ClusterProtocol.Kv(this.namespace, key, null, null, delta));
                return Long.parseLong(ClusterProtocol.readString(response.body()));
            });
        }

        @Override
        public CompletableFuture<List<String>> keys(final String prefix) {
            final String start = prefix == null ? "" : prefix;
            return ClusterShared.this.run(ClusterShared.this.reads, () -> {
                if (ClusterShared.this.client == null) {
                    return ClusterShared.this.local.keys(this.namespace, start);
                }
                final ClusterProtocol.Response response = ClusterShared.this.request(ClusterProtocol.OP_KV_KEYS, new ClusterProtocol.Kv(this.namespace, start, null, null, 0L));
                return ClusterProtocol.readStrings(response.body());
            });
        }

        @Override
        public Subscription listen(final Consumer<SharedChange> listener) {
            final List<Consumer<SharedChange>> list = ClusterShared.this.listeners.computeIfAbsent(this.namespace, k -> new CopyOnWriteArrayList<>());
            list.add(listener);
            return () -> list.remove(listener);
        }
    }
}
