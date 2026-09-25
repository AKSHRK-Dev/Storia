package dev.storia.offload.protocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Offload messages, sent inside {@link SecureChannel} frames. Every message starts with its type byte.
 *
 * <ul>
 * <li>{@code HELLO} (first message from the connecting side): role, compute threads (workers), and a probe
 *     fingerprint per dimension. A server (role SERVER) asks for work to be done; a worker (role WORKER)
 *     offers to do it.</li>
 * <li>{@code WELCOME} (reply to HELLO): accepted or not, with a message.</li>
 * <li>{@code CAPACITY} (to a server, any time): how many threads may work for it and for which dimensions.</li>
 * <li>{@code REQUEST} / {@code RESPONSE}: an id, then an opaque body the relay never needs to read.</li>
 * </ul>
 */
public final class Messages {

    public static final byte HELLO = 1;
    public static final byte WELCOME = 2;
    public static final byte CAPACITY = 3;
    public static final byte REQUEST = 4;
    public static final byte RESPONSE = 5;

    public static final byte ROLE_SERVER = 0;
    public static final byte ROLE_WORKER = 1;

    public static final byte STATUS_OK = 0;
    public static final byte STATUS_ERROR = 1;

    private Messages() {
    }

    public record Hello(byte role, int threads, Map<String, String> probes) {}

    public record Welcome(boolean ok, String message) {}

    public record Capacity(int threads, List<String> dims) {}

    /** A request: {@code dim} is readable so a relay can route it; {@code body} is the rest. */
    public record Request(long id, String dim, byte[] body) {}

    public record Response(long id, byte status, byte[] body) {}

    public static byte type(final byte[] message) throws IOException {
        if (message.length == 0) {
            throw new IOException("empty message");
        }
        return message[0];
    }

    public static byte[] hello(final Hello hello) throws IOException {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final DataOutputStream out = new DataOutputStream(bytes);
        out.writeByte(HELLO);
        out.writeByte(hello.role());
        out.writeInt(hello.threads());
        out.writeInt(hello.probes().size());
        for (final Map.Entry<String, String> probe : hello.probes().entrySet()) {
            out.writeUTF(probe.getKey());
            out.writeUTF(probe.getValue());
        }
        return bytes.toByteArray();
    }

    public static Hello readHello(final byte[] message) throws IOException {
        final DataInputStream in = open(message, HELLO);
        final byte role = in.readByte();
        final int threads = in.readInt();
        final int count = in.readInt();
        if (count < 0 || count > 256) {
            throw new IOException("bad dimension count " + count);
        }
        final Map<String, String> probes = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            probes.put(in.readUTF(), in.readUTF());
        }
        return new Hello(role, threads, probes);
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

    public static byte[] capacity(final Capacity capacity) throws IOException {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final DataOutputStream out = new DataOutputStream(bytes);
        out.writeByte(CAPACITY);
        out.writeInt(capacity.threads());
        out.writeInt(capacity.dims().size());
        for (final String dim : capacity.dims()) {
            out.writeUTF(dim);
        }
        return bytes.toByteArray();
    }

    public static Capacity readCapacity(final byte[] message) throws IOException {
        final DataInputStream in = open(message, CAPACITY);
        final int threads = in.readInt();
        final int count = in.readInt();
        if (count < 0 || count > 256) {
            throw new IOException("bad dimension count " + count);
        }
        final List<String> dims = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            dims.add(in.readUTF());
        }
        return new Capacity(threads, dims);
    }

    public static byte[] request(final Request request) throws IOException {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream(request.body().length + 64);
        final DataOutputStream out = new DataOutputStream(bytes);
        out.writeByte(REQUEST);
        out.writeLong(request.id());
        out.writeUTF(request.dim());
        out.write(request.body());
        return bytes.toByteArray();
    }

    public static Request readRequest(final byte[] message) throws IOException {
        final DataInputStream in = open(message, REQUEST);
        final long id = in.readLong();
        final String dim = in.readUTF();
        return new Request(id, dim, in.readAllBytes());
    }

    public static byte[] response(final Response response) throws IOException {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream(response.body().length + 16);
        final DataOutputStream out = new DataOutputStream(bytes);
        out.writeByte(RESPONSE);
        out.writeLong(response.id());
        out.writeByte(response.status());
        out.write(response.body());
        return bytes.toByteArray();
    }

    public static Response readResponse(final byte[] message) throws IOException {
        final DataInputStream in = open(message, RESPONSE);
        final long id = in.readLong();
        final byte status = in.readByte();
        return new Response(id, status, in.readAllBytes());
    }

    public static byte[] error(final String text) throws IOException {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        new DataOutputStream(bytes).writeUTF(text.length() > 1000 ? text.substring(0, 1000) : text);
        return bytes.toByteArray();
    }

    public static String readError(final byte[] body) {
        try {
            return new DataInputStream(new ByteArrayInputStream(body)).readUTF();
        } catch (final IOException ex) {
            return "unknown error";
        }
    }

    private static DataInputStream open(final byte[] message, final byte expected) throws IOException {
        if (message.length == 0 || message[0] != expected) {
            throw new IOException("expected message type " + expected + " but got " + (message.length == 0 ? "nothing" : message[0]));
        }
        return new DataInputStream(new ByteArrayInputStream(message, 1, message.length - 1));
    }
}
