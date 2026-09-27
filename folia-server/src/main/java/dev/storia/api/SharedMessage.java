package dev.storia.api;

import java.nio.charset.StandardCharsets;

/**
 * A message published with {@link StoriaShared#publish(String, byte[])}.
 *
 * @param channel the channel
 * @param node the worker that sent it (empty on a single server)
 * @param data the message
 */
public record SharedMessage(String channel, String node, byte[] data) {

    /**
     * The message as UTF-8 text.
     *
     * @return the text
     */
    public String text() {
        return new String(this.data, StandardCharsets.UTF_8);
    }
}
