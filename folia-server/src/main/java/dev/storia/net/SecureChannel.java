package dev.storia.net;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * An encrypted, authenticated connection keyed by a shared secret. The secret itself never crosses the wire.
 *
 * <p>Handshake (plaintext): each side sends {@code MAGIC, VERSION} and a fresh 32-byte random nonce. Both sides
 * then derive per-direction AES-256 keys:
 * <pre>
 *   psk  = PBKDF2-HMAC-SHA256(secret, "storia-offload-psk", 200000 iterations)   (salt name kept from the first protocol)
 *   prk  = HMAC-SHA256(psk, initiatorNonce || responderNonce)
 *   key  = HMAC-SHA256(prk, "i2r") for initiator -> responder, HMAC-SHA256(prk, "r2i") for the other way
 * </pre>
 * Every frame after that is AES-256-GCM with a 96-bit IV made from a per-direction frame counter, so frames cannot
 * be forged, replayed, reordered or read without the secret. A peer with a different secret fails on its first
 * frame. Payloads may be deflated before encryption.
 */
public final class SecureChannel implements Closeable {

    public static final String MAGIC = "STORIA-OFFLOAD";
    public static final int VERSION = 2;
    private static final int MAX_FRAME = 64 * 1024 * 1024;
    private static final int FLAG_DEFLATED = 1;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Map<String, byte[]> PSK_CACHE = new ConcurrentHashMap<>();

    private final Socket socket;
    private final DataInputStream in;
    private final DataOutputStream out;
    private final SecretKeySpec sendKey;
    private final SecretKeySpec receiveKey;
    private final Cipher sendCipher;
    private final Cipher receiveCipher;
    private final boolean compress;
    private long sendCounter;
    private long receiveCounter;

    private SecureChannel(final Socket socket, final DataInputStream in, final DataOutputStream out,
                          final byte[] sendKey, final byte[] receiveKey, final boolean compress) throws GeneralSecurityException {
        this.socket = socket;
        this.in = in;
        this.out = out;
        this.sendKey = new SecretKeySpec(sendKey, "AES");
        this.receiveKey = new SecretKeySpec(receiveKey, "AES");
        this.sendCipher = Cipher.getInstance("AES/GCM/NoPadding");
        this.receiveCipher = Cipher.getInstance("AES/GCM/NoPadding");
        this.compress = compress;
    }

    /** Opens the channel as the side that connected. */
    public static SecureChannel initiate(final Socket socket, final String secret, final boolean compress) throws IOException {
        return handshake(socket, secret, compress, true);
    }

    /** Opens the channel as the side that accepted the connection. */
    public static SecureChannel respond(final Socket socket, final String secret, final boolean compress) throws IOException {
        return handshake(socket, secret, compress, false);
    }

    private static SecureChannel handshake(final Socket socket, final String secret, final boolean compress, final boolean initiator) throws IOException {
        if (secret == null || secret.length() < 8) {
            throw new IOException("secret must be at least 8 characters");
        }
        socket.setTcpNoDelay(true);
        final DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream(), 64 * 1024));
        final DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream(), 64 * 1024));
        final byte[] ownNonce = new byte[32];
        RANDOM.nextBytes(ownNonce);
        out.writeUTF(MAGIC);
        out.writeInt(VERSION);
        out.write(ownNonce);
        out.flush();
        final String magic = in.readUTF();
        final int version = in.readInt();
        if (!MAGIC.equals(magic)) {
            throw new IOException("not a Storia peer");
        }
        if (version != VERSION) {
            throw new IOException("protocol version mismatch: this side " + VERSION + ", peer " + version + " (update both to the same Storia release)");
        }
        final byte[] peerNonce = new byte[32];
        in.readFully(peerNonce);
        final byte[] initiatorNonce = initiator ? ownNonce : peerNonce;
        final byte[] responderNonce = initiator ? peerNonce : ownNonce;
        try {
            final byte[] prk = hmac(psk(secret), concat(initiatorNonce, responderNonce));
            final byte[] i2r = hmac(prk, "i2r".getBytes(StandardCharsets.US_ASCII));
            final byte[] r2i = hmac(prk, "r2i".getBytes(StandardCharsets.US_ASCII));
            return new SecureChannel(socket, in, out, initiator ? i2r : r2i, initiator ? r2i : i2r, compress);
        } catch (final GeneralSecurityException ex) {
            throw new IOException("crypto setup failed", ex);
        }
    }

    private static byte[] psk(final String secret) {
        return PSK_CACHE.computeIfAbsent(secret, s -> {
            try {
                final PBEKeySpec spec = new PBEKeySpec(s.toCharArray(), "storia-offload-psk".getBytes(StandardCharsets.US_ASCII), 200_000, 256);
                return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            } catch (final GeneralSecurityException ex) {
                throw new IllegalStateException(ex);
            }
        });
    }

    private static byte[] hmac(final byte[] key, final byte[] data) throws GeneralSecurityException {
        final Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }

    private static byte[] concat(final byte[] a, final byte[] b) {
        final byte[] result = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }

    private static GCMParameterSpec iv(final long counter) {
        return new GCMParameterSpec(128, ByteBuffer.allocate(12).putInt(0).putLong(counter).array());
    }

    /** Sends one frame. Safe to call from several threads. */
    public void send(final byte[] payload) throws IOException {
        final byte[] plain = this.pack(payload);
        synchronized (this.out) {
            final byte[] sealed;
            try {
                this.sendCipher.init(Cipher.ENCRYPT_MODE, this.sendKey, iv(this.sendCounter++));
                sealed = this.sendCipher.doFinal(plain);
            } catch (final GeneralSecurityException ex) {
                throw new IOException("encryption failed", ex);
            }
            this.out.writeInt(sealed.length);
            this.out.write(sealed);
            this.out.flush();
        }
    }

    /** Receives one frame. Call from a single reader thread. */
    public byte[] receive() throws IOException {
        final int length = this.in.readInt();
        if (length < 17 || length > MAX_FRAME) {
            throw new IOException("bad frame length " + length);
        }
        final byte[] sealed = new byte[length];
        this.in.readFully(sealed);
        final byte[] plain;
        try {
            this.receiveCipher.init(Cipher.DECRYPT_MODE, this.receiveKey, iv(this.receiveCounter++));
            plain = this.receiveCipher.doFinal(sealed);
        } catch (final AEADBadTagException ex) {
            throw new IOException("authentication failed (wrong secret, or the data was tampered with)");
        } catch (final GeneralSecurityException ex) {
            throw new IOException("decryption failed", ex);
        }
        return unpack(plain);
    }

    private byte[] pack(final byte[] payload) {
        if (!this.compress || payload.length <= 256) {
            final byte[] plain = new byte[payload.length + 1];
            System.arraycopy(payload, 0, plain, 1, payload.length);
            return plain;
        }
        final Deflater deflater = new Deflater(Deflater.BEST_SPEED);
        try {
            deflater.setInput(payload);
            deflater.finish();
            final ByteArrayOutputStream bytes = new ByteArrayOutputStream(payload.length / 3 + 64);
            bytes.write(FLAG_DEFLATED);
            bytes.writeBytes(ByteBuffer.allocate(4).putInt(payload.length).array());
            final byte[] buffer = new byte[16 * 1024];
            while (!deflater.finished()) {
                bytes.write(buffer, 0, deflater.deflate(buffer));
            }
            return bytes.toByteArray();
        } finally {
            deflater.end();
        }
    }

    private static byte[] unpack(final byte[] plain) throws IOException {
        if (plain.length < 1) {
            throw new IOException("empty frame");
        }
        if ((plain[0] & FLAG_DEFLATED) == 0) {
            return Arrays.copyOfRange(plain, 1, plain.length);
        }
        if (plain.length < 5) {
            throw new IOException("truncated frame");
        }
        final int rawLength = ByteBuffer.wrap(plain, 1, 4).getInt();
        if (rawLength < 0 || rawLength > MAX_FRAME) {
            throw new IOException("bad raw length " + rawLength);
        }
        final Inflater inflater = new Inflater();
        try {
            inflater.setInput(plain, 5, plain.length - 5);
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
                throw new IOException("truncated compressed frame");
            }
            return payload;
        } catch (final DataFormatException ex) {
            throw new IOException("corrupt compressed frame", ex);
        } finally {
            inflater.end();
        }
    }

    public String remoteAddress() {
        return String.valueOf(this.socket.getRemoteSocketAddress());
    }

    @Override
    public void close() {
        try {
            this.socket.close();
        } catch (final IOException ignored) {
            // closing anyway
        }
    }
}
