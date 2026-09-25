package dev.stolia.offload;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/** Length-prefixed frames, optionally deflated. {@code [int length][byte flags][payload]}. */
final class Frames {

    static final String MAGIC = "STOLIA-OFFLOAD";
    static final int PROTOCOL_VERSION = 1;
    static final int MAX_FRAME = 64 * 1024 * 1024;
    private static final int FLAG_DEFLATED = 1;

    private Frames() {
    }

    static void write(final DataOutputStream out, final byte[] payload, final boolean compress) throws IOException {
        byte[] body = payload;
        int flags = 0;
        if (compress && payload.length > 256) {
            final Deflater deflater = new Deflater(Deflater.BEST_SPEED);
            try {
                deflater.setInput(payload);
                deflater.finish();
                final ByteArrayOutputStream compressed = new ByteArrayOutputStream(payload.length / 3 + 64);
                final byte[] chunk = new byte[16 * 1024];
                while (!deflater.finished()) {
                    compressed.write(chunk, 0, deflater.deflate(chunk));
                }
                body = compressed.toByteArray();
                flags |= FLAG_DEFLATED;
            } finally {
                deflater.end();
            }
        }
        synchronized (out) {
            out.writeInt(body.length + 1 + (flags & FLAG_DEFLATED) * 4);
            out.writeByte(flags);
            if ((flags & FLAG_DEFLATED) != 0) {
                out.writeInt(payload.length);
            }
            out.write(body);
            out.flush();
        }
    }

    static byte[] read(final DataInputStream in) throws IOException {
        final int length = in.readInt();
        if (length < 1 || length > MAX_FRAME) {
            throw new IOException("Bad frame length " + length);
        }
        final int flags = in.readUnsignedByte();
        if ((flags & FLAG_DEFLATED) == 0) {
            final byte[] payload = new byte[length - 1];
            in.readFully(payload);
            return payload;
        }
        final int rawLength = in.readInt();
        if (rawLength < 0 || rawLength > MAX_FRAME) {
            throw new IOException("Bad raw length " + rawLength);
        }
        final byte[] body = new byte[length - 5];
        in.readFully(body);
        final Inflater inflater = new Inflater();
        try {
            inflater.setInput(body);
            final byte[] payload = new byte[rawLength];
            int offset = 0;
            while (offset < rawLength) {
                final int n = inflater.inflate(payload, offset, rawLength - offset);
                if (n == 0 && (inflater.finished() || inflater.needsInput())) {
                    break;
                }
                offset += n;
            }
            if (offset != rawLength) {
                throw new IOException("Truncated compressed frame");
            }
            return payload;
        } catch (final DataFormatException ex) {
            throw new IOException("Corrupt compressed frame", ex);
        } finally {
            inflater.end();
        }
    }

    static boolean secretMatches(final String expected, final String actual) {
        return java.security.MessageDigest.isEqual(
            expected.getBytes(java.nio.charset.StandardCharsets.UTF_8), actual.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
