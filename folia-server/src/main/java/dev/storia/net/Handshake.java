package dev.storia.net;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The first two messages on a {@link SecureChannel}: {@code HELLO} from the connecting side (its role and a few
 * named values, such as the node name) and {@code WELCOME} from the relay (accepted or not, with a message).
 * Everything after that is {@link dev.storia.cluster.protocol.ClusterProtocol}.
 */
public final class Handshake {

    public static final byte HELLO = 1;
    public static final byte WELCOME = 2;

    private Handshake() {
    }

    public record Hello(byte role, int threads, Map<String, String> values) {}

    public record Welcome(boolean ok, String message) {}

    public static byte[] hello(final Hello hello) throws IOException {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final DataOutputStream out = new DataOutputStream(bytes);
        out.writeByte(HELLO);
        out.writeByte(hello.role());
        out.writeInt(hello.threads());
        out.writeInt(hello.values().size());
        for (final Map.Entry<String, String> value : hello.values().entrySet()) {
            out.writeUTF(value.getKey());
            out.writeUTF(value.getValue());
        }
        return bytes.toByteArray();
    }

    public static Hello readHello(final byte[] message) throws IOException {
        final DataInputStream in = open(message, HELLO);
        final byte role = in.readByte();
        final int threads = in.readInt();
        final int count = in.readInt();
        if (count < 0 || count > 256) {
            throw new IOException("bad value count " + count);
        }
        final Map<String, String> values = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            values.put(in.readUTF(), in.readUTF());
        }
        return new Hello(role, threads, values);
    }

    public static byte[] welcome(final Welcome welcome) throws IOException {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final DataOutputStream out = new DataOutputStream(bytes);
        out.writeByte(WELCOME);
        out.writeBoolean(welcome.ok());
        out.writeUTF(welcome.message());
        return bytes.toByteArray();
    }

    public static Welcome readWelcome(final byte[] message) throws IOException {
        final DataInputStream in = open(message, WELCOME);
        return new Welcome(in.readBoolean(), in.readUTF());
    }

    private static DataInputStream open(final byte[] message, final byte expected) throws IOException {
        if (message.length == 0 || message[0] != expected) {
            throw new IOException("expected message type " + expected + " but got " + (message.length == 0 ? "nothing" : message[0]));
        }
        return new DataInputStream(new ByteArrayInputStream(message, 1, message.length - 1));
    }
}
