package dev.storia.api;

import java.nio.charset.StandardCharsets;

/**
 * A key of a {@link SharedStore} changed.
 *
 * @param namespace the store
 * @param key the key
 * @param value the new value, or {@code null} if the key was deleted
 * @param node the worker that made the change (empty on a single server)
 */
public record SharedChange(String namespace, String key, byte[] value, String node) {

    /**
     * The new value as UTF-8 text.
     *
     * @return the text, or null if the key was deleted
     */
    public String text() {
        return this.value == null ? null : new String(this.value, StandardCharsets.UTF_8);
    }
}
