package dev.storia.cluster.protocol;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The key-value store behind {@code dev.storia.api.SharedStore}: one folder per namespace, one file per key (the file
 * name is the key in hex), written through a temporary file and an atomic rename. Used by Storia Relay for a cluster
 * and by a single Storia server for itself. No Minecraft classes.
 *
 * <p>All changes to a namespace are serialized, so compare-and-set and increments are atomic.
 */
public final class SharedData {

    public static final int MAX_KEY_BYTES = 100;
    public static final int MAX_VALUE_BYTES = 1024 * 1024;
    private static final Pattern NAMESPACE = Pattern.compile("[a-z0-9_.-]{1,64}");
    private static final Pattern CHANNEL = Pattern.compile("[a-z0-9_.:-]{1,64}");
    private static final HexFormat HEX = HexFormat.of();

    private final Path root;
    private final Map<String, Map<String, byte[]>> namespaces = new ConcurrentHashMap<>();

    public SharedData(final Path root) {
        this.root = root;
    }

    public static void checkNamespace(final String namespace) {
        if (namespace == null || !NAMESPACE.matcher(namespace).matches()) {
            throw new IllegalArgumentException("namespace must be 1 to 64 characters of a-z 0-9 _ . - : " + namespace);
        }
    }

    public static void checkChannel(final String channel) {
        if (channel == null || !CHANNEL.matcher(channel).matches()) {
            throw new IllegalArgumentException("channel must be 1 to 64 characters of a-z 0-9 _ . : - : " + channel);
        }
    }

    public static void checkKey(final String key) {
        final int length = key == null ? 0 : key.getBytes(StandardCharsets.UTF_8).length;
        if (length < 1 || length > MAX_KEY_BYTES || key.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("key must be 1 to " + MAX_KEY_BYTES + " bytes of UTF-8: " + key);
        }
    }

    public static void checkValue(final byte[] value) {
        if (value != null && value.length > MAX_VALUE_BYTES) {
            throw new IllegalArgumentException("value is larger than 1 MiB (" + value.length + " bytes)");
        }
    }

    public byte[] get(final String namespace, final String key) throws IOException {
        checkKey(key);
        final byte[] value = this.namespace(namespace).get(key);
        return value == null ? null : value.clone();
    }

    /** Stores or deletes ({@code value == null}) a key. */
    public void set(final String namespace, final String key, final byte[] value) throws IOException {
        checkKey(key);
        checkValue(value);
        final Map<String, byte[]> map = this.namespace(namespace);
        synchronized (map) {
            this.store(namespace, map, key, value);
        }
    }

    /** Changes the key only if it holds {@code expected} ({@code null} = absent). */
    public boolean compareAndSet(final String namespace, final String key, final byte[] expected, final byte[] value) throws IOException {
        checkKey(key);
        checkValue(value);
        final Map<String, byte[]> map = this.namespace(namespace);
        synchronized (map) {
            if (!Arrays.equals(map.get(key), expected)) {
                return false;
            }
            this.store(namespace, map, key, value);
            return true;
        }
    }

    /** Adds {@code delta} to a counter kept as decimal text; returns the new value and the stored bytes. */
    public long increment(final String namespace, final String key, final long delta) throws IOException {
        checkKey(key);
        final Map<String, byte[]> map = this.namespace(namespace);
        synchronized (map) {
            final byte[] old = map.get(key);
            final long current;
            try {
                current = old == null ? 0L : Long.parseLong(new String(old, StandardCharsets.UTF_8).trim());
            } catch (final NumberFormatException ex) {
                throw new IllegalArgumentException("the value of " + key + " is not a number");
            }
            final long next = Math.addExact(current, delta);
            this.store(namespace, map, key, Long.toString(next).getBytes(StandardCharsets.UTF_8));
            return next;
        }
    }

    public List<String> keys(final String namespace, final String prefix) throws IOException {
        final List<String> keys = new ArrayList<>();
        for (final String key : this.namespace(namespace).keySet()) {
            if (key.startsWith(prefix)) {
                keys.add(key);
            }
        }
        keys.sort(null);
        return keys;
    }

    private void store(final String namespace, final Map<String, byte[]> map, final String key, final byte[] value) throws IOException {
        final Path folder = this.root.resolve(namespace);
        final Path file = folder.resolve(HEX.formatHex(key.getBytes(StandardCharsets.UTF_8)) + ".v");
        if (value == null) {
            Files.deleteIfExists(file);
            map.remove(key);
            return;
        }
        Files.createDirectories(folder);
        final Path temp = folder.resolve(file.getFileName() + ".tmp");
        Files.write(temp, value);
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (final AtomicMoveNotSupportedException ex) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
        map.put(key, value.clone());
    }

    /** The keys and values of a namespace, read from disk the first time. */
    private Map<String, byte[]> namespace(final String namespace) throws IOException {
        checkNamespace(namespace);
        final Map<String, byte[]> cached = this.namespaces.get(namespace);
        if (cached != null) {
            return cached;
        }
        synchronized (this.namespaces) {
            final Map<String, byte[]> again = this.namespaces.get(namespace);
            if (again != null) {
                return again;
            }
            final Map<String, byte[]> map = new ConcurrentHashMap<>();
            final Path folder = this.root.resolve(namespace);
            if (Files.isDirectory(folder)) {
                try (Stream<Path> files = Files.list(folder)) {
                    for (final Path file : (Iterable<Path>) files::iterator) {
                        final String name = file.getFileName().toString();
                        if (!name.endsWith(".v")) {
                            continue;
                        }
                        try {
                            map.put(new String(HEX.parseHex(name.substring(0, name.length() - 2)), StandardCharsets.UTF_8), Files.readAllBytes(file));
                        } catch (final IllegalArgumentException ignored) {
                            // not one of ours
                        }
                    }
                }
            }
            this.namespaces.put(namespace, map);
            return map;
        }
    }
}
