package dev.storia.api;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * A key-value store shared by every worker of a Storia Cluster (see {@link StoriaShared}). Values are bytes; the
 * {@code String} methods store UTF-8 text. Every change reaches the listeners on every worker.
 *
 * <p>Keys are 1 to 100 bytes of UTF-8 (any text, for example {@code "coins." + uuid}); values are at most 1 MiB.
 * Changes to one key are applied in the order the relay receives them. Use {@link #compareAndSet} or
 * {@link #increment} when several workers may change the same key at the same time.
 *
 * <p>An invalid key or a value over 1 MiB throws {@link IllegalArgumentException} right away; failures on the way
 * (the relay is unreachable, ...) complete the returned future exceptionally.
 */
public interface SharedStore {

    /**
     * The namespace of this store.
     *
     * @return the namespace
     */
    String namespace();

    /**
     * Reads a value.
     *
     * @param key the key
     * @return the value, or {@code null} if there is none
     */
    CompletableFuture<byte[]> get(String key);

    /**
     * Stores a value, or deletes the key if {@code value} is {@code null}.
     *
     * @param key the key
     * @param value the value, or null to delete
     * @return completes when the relay has stored it
     */
    CompletableFuture<Void> set(String key, byte[] value);

    /**
     * Stores {@code value} only if the key currently holds {@code expected} ({@code null} = the key does not exist).
     * The check and the change happen together on the relay, so two workers can never both succeed.
     *
     * @param key the key
     * @param expected the value the key must have now, or null for "absent"
     * @param value the new value, or null to delete
     * @return whether the value was changed
     */
    CompletableFuture<Boolean> compareAndSet(String key, byte[] expected, byte[] value);

    /**
     * Adds {@code delta} to a counter stored as decimal text (a missing key counts as 0), atomically.
     *
     * @param key the key
     * @param delta the amount to add (may be negative)
     * @return the new value
     */
    CompletableFuture<Long> increment(String key, long delta);

    /**
     * The keys that start with {@code prefix}, sorted.
     *
     * @param prefix the prefix, or an empty string for all keys
     * @return the keys
     */
    CompletableFuture<List<String>> keys(String prefix);

    /**
     * Calls {@code listener} whenever a key of this store changes on any worker, including this one.
     *
     * @param listener runs on a Storia thread
     * @return cancel it to stop listening
     */
    Subscription listen(Consumer<SharedChange> listener);

    /**
     * Reads a UTF-8 text value.
     *
     * @param key the key
     * @return the text, or null if there is none
     */
    default CompletableFuture<String> getString(final String key) {
        return this.get(key).thenApply(value -> value == null ? null : new String(value, StandardCharsets.UTF_8));
    }

    /**
     * Stores UTF-8 text, or deletes the key if {@code value} is {@code null}.
     *
     * @param key the key
     * @param value the text, or null to delete
     * @return completes when stored
     */
    default CompletableFuture<Void> setString(final String key, final String value) {
        return this.set(key, value == null ? null : value.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Deletes a key.
     *
     * @param key the key
     * @return completes when deleted
     */
    default CompletableFuture<Void> delete(final String key) {
        return this.set(key, null);
    }
}
