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

    /** host:port of a coordinator. */
    record Address(String host, int port) {
        static Address parse(final String address, final int defaultPort) {
            final String trimmed = address.trim();
            final int colon = trimmed.lastIndexOf(':');
            return colon < 0 ? new Address(trimmed, defaultPort) : new Address(trimmed.substring(0, colon), Integer.parseInt(trimmed.substring(colon + 1)));
        }

        /** "relay-a:25590,relay-b:25590": the active relay and its standby, tried in order. */
        static java.util.List<Address> parseList(final String addresses, final int defaultPort) {
            final java.util.List<Address> list = new java.util.ArrayList<>();
            for (final String address : addresses.split(",")) {
                if (!address.isBlank()) {
                    list.add(parse(address, defaultPort));
                }
            }
            return list;
        }

        @Override
        public String toString() {
            return this.host + ":" + this.port;
        }
    }

    private static final Logger LOGGER = LogUtils.getClassLogger();
    private static final long REQUEST_TIMEOUT_MILLIS = 30_000L;

    private final java.util.List<Address> coordinators;
    /** The relay this node is connected to (or last was). */
    private volatile Address coordinator;
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

    ClusterClient(final java.util.List<Address> coordinators, final String secret, final String name) {
        this.coordinators = java.util.List.copyOf(coordinators);
        this.coordinator = this.coordinators.get(0);
        this.secret = secret;
        this.name = name;
    }

    int index() {
        return this.index;
    }

    String coordinatorAddress() {
        return this.coordinator.toString();
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
                LOGGER.warn("Could not reach the cluster coordinator ({}); retrying", ex.getMessage());
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

    /** Connects to the first relay in the list that is the active one (a standby says so and is skipped). */
    private synchronized void open() throws IOException {
        final java.util.List<String> failures = new java.util.ArrayList<>();
        for (final Address address : this.coordinators) {
            try {
                this.open(address);
                return;
            } catch (final IOException ex) {
                failures.add(ex.getMessage() != null && ex.getMessage().startsWith(address.toString()) ? ex.getMessage() : address + ": " + ex.getMessage());
            }
        }
        throw new IOException(String.join("; ", failures));
    }

    private void open(final Address address) throws IOException {
        final Socket socket = new Socket();
        socket.connect(new InetSocketAddress(address.host(), address.port()), 5000);
        socket.setTcpNoDelay(true);
        final SecureChannel channel = SecureChannel.initiate(socket, this.secret, true);
        final Map<String, String> hello = new java.util.HashMap<>(Map.of("node", this.name, "instance", this.instance));
        if (this.index >= 0) {
            hello.put("index", Integer.toString(this.index)); // keep our entity ids on a relay that restarted or took over
        }
        channel.send(Handshake.hello(new Handshake.Hello(ClusterProtocol.ROLE_NODE, 0, hello)));
        final Handshake.Welcome welcome = Handshake.readWelcome(channel.receive());
        if (!welcome.ok()) {
            channel.close();
            throw new IOException(address + " refused this node: " + welcome.message());
        }
        final int index = Integer.parseInt(welcome.message().replace("index=", "").trim());
        if (this.index >= 0 && index != this.index) {
            channel.close();
            throw new IOException("coordinator gave a different node index (" + index + " instead of " + this.index + "); restart this node");
        }
        this.index = index;
        this.coordinator = address;
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
                    LOGGER.info("Reconnected to the cluster coordinator at {}", this.coordinatorAddress());
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
