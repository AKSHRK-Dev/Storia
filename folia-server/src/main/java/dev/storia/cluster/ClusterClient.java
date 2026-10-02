package dev.storia.cluster;

import com.mojang.logging.LogUtils;
import dev.storia.cluster.protocol.ClusterProtocol;
import dev.storia.net.Handshake;
import dev.storia.net.SecureChannel;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;

/**
 * The node's connection to the cluster coordinator. Requests block the calling thread (region I/O threads,
 * the sweep thread, player data saves) until the response arrives. If the connection drops, it reconnects;
 * requests made meanwhile wait for the new connection.
 */
final class ClusterClient {

    /** host:port of the coordinator. */
    record Address(String host, int port) {
        static Address parse(final String address, final int defaultPort) {
            final int colon = address.lastIndexOf(':');
            return colon < 0 ? new Address(address, defaultPort) : new Address(address.substring(0, colon), Integer.parseInt(address.substring(colon + 1)));
        }
    }

    private static final Logger LOGGER = LogUtils.getClassLogger();
    private static final long REQUEST_TIMEOUT_MILLIS = 30_000L;

    private final Address coordinator;
    private final String secret;
    private final String name;
    private final AtomicLong ids = new AtomicLong();
    /** Identifies this server process, so the relay can tell a reconnect from a second worker with the same name. */
    private final String instance = java.util.UUID.randomUUID().toString();
    private final Map<Long, CompletableFuture<ClusterProtocol.Response>> pending = new ConcurrentHashMap<>();
    private volatile SecureChannel channel;
    private volatile int index = -1;
    private volatile boolean closed;
    /** Pushes are handled one at a time, in the order they arrived (scoreboard and time changes must not reorder). */
    private final java.util.concurrent.ExecutorService pushes = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        final Thread thread = new Thread(r, "Storia Cluster push");
        thread.setDaemon(true);
        return thread;
    });

    ClusterClient(final Address coordinator, final String secret, final String name) {
        this.coordinator = coordinator;
        this.secret = secret;
        this.name = name;
    }

    int index() {
        return this.index;
    }

    String coordinatorAddress() {
        return this.coordinator.host() + ":" + this.coordinator.port();
    }

    /** Connects, retrying for up to {@code waitMillis}. */
    void connect(final long waitMillis) throws IOException {
        final long deadline = System.currentTimeMillis() + waitMillis;
        IOException last = null;
        while (System.currentTimeMillis() < deadline) {
            try {
                this.open();
                return;
            } catch (final IOException ex) {
                last = ex;
                LOGGER.warn("Could not reach the cluster coordinator at {}: {}; retrying", this.coordinatorAddress(), ex.getMessage());
                try {
                    Thread.sleep(2000L);
                } catch (final InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        throw last != null ? last : new IOException("could not connect to the cluster coordinator");
    }

    private synchronized void open() throws IOException {
        final Socket socket = new Socket();
        socket.connect(new InetSocketAddress(this.coordinator.host(), this.coordinator.port()), 5000);
        socket.setTcpNoDelay(true);
        final SecureChannel channel = SecureChannel.initiate(socket, this.secret, true);
        channel.send(Handshake.hello(new Handshake.Hello(ClusterProtocol.ROLE_NODE, 0, Map.of("node", this.name, "instance", this.instance))));
        final Handshake.Welcome welcome = Handshake.readWelcome(channel.receive());
        if (!welcome.ok()) {
            channel.close();
            throw new IOException("coordinator refused this node: " + welcome.message());
        }
        final int index = Integer.parseInt(welcome.message().replace("index=", "").trim());
        if (this.index >= 0 && index != this.index) {
            channel.close();
            throw new IOException("coordinator gave a different node index (" + index + " instead of " + this.index + "); restart this node");
        }
        this.index = index;
        this.channel = channel;
        final Thread reader = new Thread(() -> this.readLoop(channel), "Storia Cluster reader");
        reader.setDaemon(true);
        reader.start();
    }

    private void readLoop(final SecureChannel channel) {
        try {
            while (true) {
                final byte[] message = channel.receive();
                if (ClusterProtocol.type(message) == ClusterProtocol.PUSH) {
                    final ClusterProtocol.Push push = ClusterProtocol.readPush(message);
                    this.pushes.execute(() -> Cluster.onPush(push));
                } else if (ClusterProtocol.type(message) == ClusterProtocol.RESPONSE) {
                    final ClusterProtocol.Response response = ClusterProtocol.readResponse(message);
                    final CompletableFuture<ClusterProtocol.Response> future = this.pending.remove(response.id());
                    if (future != null) {
                        future.complete(response);
                    }
                }
            }
        } catch (final IOException ex) {
            if (this.closed) {
                return;
            }
            LOGGER.error("Lost the connection to the cluster coordinator ({}); reconnecting", ex.getMessage());
            this.channel = null;
            for (final var future : this.pending.values()) {
                future.completeExceptionally(new IOException("connection lost"));
            }
            this.pending.clear();
            this.reconnectLater();
        }
    }

    private void reconnectLater() {
        final Thread thread = new Thread(() -> {
            while (!this.closed && this.channel == null) {
                try {
                    this.open();
                    LOGGER.info("Reconnected to the cluster coordinator");
                    final Thread reclaim = new Thread(Cluster::onReconnect, "Storia Cluster reclaim");
                    reclaim.setDaemon(true);
                    reclaim.start();
                } catch (final IOException ex) {
                    LOGGER.error("Still cannot reach the cluster coordinator: {}", ex.getMessage());
                    try {
                        Thread.sleep(2000L);
                    } catch (final InterruptedException ie) {
                        return;
                    }
                }
            }
        }, "Storia Cluster reconnect");
        thread.setDaemon(true);
        thread.start();
    }

    /** Sends a request and waits for its response. Retries once over a new connection if the old one dropped. */
    ClusterProtocol.Response request(final byte op, final byte[] body) throws IOException {
        IOException failure = null;
        for (int attempt = 0; attempt < 2; ++attempt) {
            final SecureChannel channel = this.awaitChannel();
            final long id = this.ids.incrementAndGet();
            final CompletableFuture<ClusterProtocol.Response> future = new CompletableFuture<>();
            this.pending.put(id, future);
            try {
                channel.send(ClusterProtocol.request(new ClusterProtocol.Request(id, op, body)), ClusterProtocol.compressibleRequest(op));
                return future.get(REQUEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
            } catch (final InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", ex);
            } catch (final ExecutionException | TimeoutException | IOException ex) {
                failure = new IOException("cluster request failed: " + ex.getMessage(), ex);
            } finally {
                this.pending.remove(id);
            }
        }
        throw failure;
    }

    private SecureChannel awaitChannel() throws IOException {
        final long deadline = System.currentTimeMillis() + REQUEST_TIMEOUT_MILLIS;
        while (true) {
            final SecureChannel channel = this.channel;
            if (channel != null) {
                return channel;
            }
            if (this.closed || System.currentTimeMillis() > deadline) {
                throw new IOException("not connected to the cluster coordinator");
            }
            try {
                Thread.sleep(100L);
            } catch (final InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", ex);
            }
        }
    }

    boolean connected() {
        return this.channel != null;
    }

    void close() {
        this.closed = true;
        final SecureChannel channel = this.channel;
        if (channel != null) {
            channel.close();
        }
    }
}
