package dev.storia.cluster.protocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Storia Cluster messages between nodes and the coordinator (Storia Relay with cluster mode on).
 * No Minecraft classes, so the relay can use it too. Sent over {@link dev.storia.offload.protocol.SecureChannel}
 * after the offload HELLO with role {@link #ROLE_NODE}.
 *
 * <p>Every message starts with a type byte. Requests carry a request id that the response repeats;
 * pushes are unsolicited messages from the coordinator.
 */
public final class ClusterProtocol {

    /** HELLO role for cluster nodes (offload uses 0 = server, 1 = worker). */
    public static final byte ROLE_NODE = 2;
    /** HELLO role for Storia Proxy instances that route players between nodes. */
    public static final byte ROLE_PROXY = 3;

    public static final byte REQUEST = 20;
    public static final byte RESPONSE = 21;
    public static final byte PUSH = 22;

    // request ops
    public static final byte OP_READ = 1;
    public static final byte OP_WRITE = 2;
    public static final byte OP_CLAIM = 3;
    public static final byte OP_RELEASE = 4;
    public static final byte OP_PLAYER_READ = 5;
    public static final byte OP_PLAYER_WRITE = 6;
    public static final byte OP_HEARTBEAT = 7;
    public static final byte OP_STATUS = 8;
    public static final byte OP_LINK = 9;
    /** Node -> coordinator, every second: active cells (with player counts) and player positions. */
    public static final byte OP_ACTIVE = 10;
    /** Proxy -> coordinator: which node should this player join? */
    public static final byte OP_ROUTE = 11;
    /** Node -> coordinator: the player's data is saved and the player may move. */
    public static final byte OP_TRANSFER_READY = 12;

    // pushes, coordinator -> node
    /** Save, unload and release these cells soon (another node takes them over). */
    public static final byte PUSH_EVICT = 1;
    /** Save this player now and report OP_TRANSFER_READY; the player is moving to another node. */
    public static final byte PUSH_PREPARE = 2;
    // pushes, coordinator -> proxy
    /** Move this player to that node's server. */
    public static final byte PUSH_MOVE = 3;

    // response status
    public static final byte OK = 0;
    public static final byte NOT_FOUND = 1;
    public static final byte DENIED = 2;
    public static final byte WAIT = 3;
    public static final byte ERROR = 4;

    // storage types
    public static final byte TYPE_CHUNK = 0;
    public static final byte TYPE_ENTITIES = 1;
    public static final byte TYPE_POI = 2;

    public static final String[] TYPE_FOLDERS = {"region", "entities", "poi"};

    private ClusterProtocol() {}

    public record Request(long id, byte op, byte[] body) {}

    public record Response(long id, byte status, byte[] body) {}

    public record Push(byte op, byte[] body) {}

    /** A chunk record position: storage type, dimension ("minecraft:overworld") and chunk coordinates. */
    public record ChunkKey(byte type, String dimension, int x, int z) {
        public int cellX() {
            return this.x >> 5;
        }

        public int cellZ() {
            return this.z >> 5;
        }
    }

    /** A cell is one region file (32 x 32 chunks) of one dimension. Ownership is per cell. */
    public record Cell(String dimension, int x, int z) {
        public static Cell of(final String dimension, final int chunkX, final int chunkZ) {
            return new Cell(dimension, chunkX >> 5, chunkZ >> 5);
        }
    }

    public static byte type(final byte[] message) throws IOException {
        if (message.length == 0) {
            throw new IOException("empty message");
        }
        return message[0];
    }

    public static byte[] request(final Request request) {
        return write(out -> {
            out.writeByte(REQUEST);
            out.writeLong(request.id());
            out.writeByte(request.op());
            writeBytes(out, request.body());
        });
    }

    public static Request readRequest(final byte[] message) throws IOException {
        final DataInputStream in = open(message, REQUEST);
        return new Request(in.readLong(), in.readByte(), readBytes(in));
    }

    public static byte[] response(final Response response) {
        return write(out -> {
            out.writeByte(RESPONSE);
            out.writeLong(response.id());
            out.writeByte(response.status());
            writeBytes(out, response.body());
        });
    }

    public static Response readResponse(final byte[] message) throws IOException {
        final DataInputStream in = open(message, RESPONSE);
        return new Response(in.readLong(), in.readByte(), readBytes(in));
    }

    public static byte[] push(final Push push) {
        return write(out -> {
            out.writeByte(PUSH);
            out.writeByte(push.op());
            writeBytes(out, push.body());
        });
    }

    public static Push readPush(final byte[] message) throws IOException {
        final DataInputStream in = open(message, PUSH);
        return new Push(in.readByte(), readBytes(in));
    }

    // ---- bodies ----

    public static byte[] chunkKey(final ChunkKey key) {
        return write(out -> writeChunkKey(out, key));
    }

    public static ChunkKey readChunkKey(final byte[] body) throws IOException {
        return readChunkKey(new DataInputStream(new ByteArrayInputStream(body)));
    }

    /** WRITE body: key, then a flag (1 = delete) and the chunk record (compression byte + compressed NBT). */
    public static byte[] write(final ChunkKey key, final byte[] record) {
        return write(out -> {
            writeChunkKey(out, key);
            out.writeBoolean(record == null);
            if (record != null) {
                writeBytes(out, record);
            }
        });
    }

    public record Write(ChunkKey key, byte[] record) {}

    public static Write readWrite(final byte[] body) throws IOException {
        final DataInputStream in = new DataInputStream(new ByteArrayInputStream(body));
        final ChunkKey key = readChunkKey(in);
        final boolean delete = in.readBoolean();
        return new Write(key, delete ? null : readBytes(in));
    }

    public static byte[] cell(final Cell cell) {
        return write(out -> {
            out.writeUTF(cell.dimension());
            out.writeInt(cell.x());
            out.writeInt(cell.z());
        });
    }

    public static Cell readCell(final byte[] body) throws IOException {
        final DataInputStream in = new DataInputStream(new ByteArrayInputStream(body));
        return new Cell(in.readUTF(), in.readInt(), in.readInt());
    }

    /** LINK body: two cells that a contraption spans. */
    public static byte[] link(final Cell a, final Cell b) {
        return write(out -> {
            writeCell(out, a);
            writeCell(out, b);
        });
    }

    public static Cell[] readLink(final byte[] body) throws IOException {
        final DataInputStream in = new DataInputStream(new ByteArrayInputStream(body));
        return new Cell[] {readCell(in), readCell(in)};
    }

    /** A list of cells, e.g. the whole linked group granted by a CLAIM. */
    public static byte[] cells(final java.util.Collection<Cell> cells) {
        return write(out -> {
            out.writeInt(cells.size());
            for (final Cell cell : cells) {
                writeCell(out, cell);
            }
        });
    }

    public static java.util.List<Cell> readCells(final byte[] body) throws IOException {
        if (body == null || body.length == 0) {
            return java.util.List.of();
        }
        final DataInputStream in = new DataInputStream(new ByteArrayInputStream(body));
        final int count = in.readInt();
        if (count < 0 || count > 1_000_000) {
            throw new IOException("bad cell count " + count);
        }
        final java.util.List<Cell> cells = new java.util.ArrayList<>(count);
        for (int i = 0; i < count; ++i) {
            cells.add(readCell(in));
        }
        return cells;
    }

    private static void writeCell(final DataOutputStream out, final Cell cell) throws IOException {
        out.writeUTF(cell.dimension());
        out.writeInt(cell.x());
        out.writeInt(cell.z());
    }

    private static Cell readCell(final DataInputStream in) throws IOException {
        return new Cell(in.readUTF(), in.readInt(), in.readInt());
    }

    /** PLAYER_READ / PLAYER_WRITE: uuid, kind ("data", "advancements", "stats"), and for writes the bytes. */
    public static byte[] player(final String uuid, final String kind, final byte[] data) {
        return write(out -> {
            out.writeUTF(uuid);
            out.writeUTF(kind);
            out.writeBoolean(data != null);
            if (data != null) {
                writeBytes(out, data);
            }
        });
    }

    public record Player(String uuid, String kind, byte[] data) {}

    public static Player readPlayer(final byte[] body) throws IOException {
        final DataInputStream in = new DataInputStream(new ByteArrayInputStream(body));
        final String uuid = in.readUTF();
        final String kind = in.readUTF();
        return new Player(uuid, kind, in.readBoolean() ? readBytes(in) : null);
    }

    public static byte[] heartbeat(final double mspt, final int players, final int cells) {
        return write(out -> {
            out.writeDouble(mspt);
            out.writeInt(players);
            out.writeInt(cells);
        });
    }

    public record Heartbeat(double mspt, int players, int cells) {}

    public static Heartbeat readHeartbeat(final byte[] body) throws IOException {
        final DataInputStream in = new DataInputStream(new ByteArrayInputStream(body));
        return new Heartbeat(in.readDouble(), in.readInt(), in.readInt());
    }

    /** A player on a node: uuid, dimension and chunk position. */
    public record PlayerPos(String uuid, String dimension, int chunkX, int chunkZ) {}

    /** OP_ACTIVE body. */
    public record Active(java.util.Map<Cell, Integer> cells, java.util.List<PlayerPos> players) {}

    public static byte[] active(final Active active) {
        return write(out -> {
            out.writeInt(active.cells().size());
            for (final var entry : active.cells().entrySet()) {
                writeCell(out, entry.getKey());
                out.writeInt(entry.getValue());
            }
            out.writeInt(active.players().size());
            for (final PlayerPos player : active.players()) {
                out.writeUTF(player.uuid());
                out.writeUTF(player.dimension());
                out.writeInt(player.chunkX());
                out.writeInt(player.chunkZ());
            }
        });
    }

    public static Active readActive(final byte[] body) throws IOException {
        final DataInputStream in = new DataInputStream(new ByteArrayInputStream(body));
        final int cellCount = in.readInt();
        if (cellCount < 0 || cellCount > 1_000_000) {
            throw new IOException("bad cell count");
        }
        final java.util.Map<Cell, Integer> cells = new java.util.HashMap<>();
        for (int i = 0; i < cellCount; ++i) {
            cells.put(readCell(in), in.readInt());
        }
        final int playerCount = in.readInt();
        if (playerCount < 0 || playerCount > 100_000) {
            throw new IOException("bad player count");
        }
        final java.util.List<PlayerPos> players = new java.util.ArrayList<>(playerCount);
        for (int i = 0; i < playerCount; ++i) {
            players.add(new PlayerPos(in.readUTF(), in.readUTF(), in.readInt(), in.readInt()));
        }
        return new Active(cells, players);
    }

    /** PUSH_MOVE body: player uuid and target node name. */
    public static byte[] move(final String uuid, final String node) {
        return write(out -> {
            out.writeUTF(uuid);
            out.writeUTF(node);
        });
    }

    public static String[] readMove(final byte[] body) throws IOException {
        final DataInputStream in = new DataInputStream(new ByteArrayInputStream(body));
        return new String[] {in.readUTF(), in.readUTF()};
    }

    public static byte[] string(final String value) {
        return write(out -> out.writeUTF(value));
    }

    public static String readString(final byte[] body) throws IOException {
        return new DataInputStream(new ByteArrayInputStream(body)).readUTF();
    }

    // ---- helpers ----

    private static void writeChunkKey(final DataOutputStream out, final ChunkKey key) throws IOException {
        out.writeByte(key.type());
        out.writeUTF(key.dimension());
        out.writeInt(key.x());
        out.writeInt(key.z());
    }

    private static ChunkKey readChunkKey(final DataInputStream in) throws IOException {
        final byte type = in.readByte();
        if (type < 0 || type >= TYPE_FOLDERS.length) {
            throw new IOException("bad storage type " + type);
        }
        return new ChunkKey(type, in.readUTF(), in.readInt(), in.readInt());
    }

    public static void writeBytes(final DataOutputStream out, final byte[] bytes) throws IOException {
        final byte[] value = bytes == null ? new byte[0] : bytes;
        out.writeInt(value.length);
        out.write(value);
    }

    public static byte[] readBytes(final DataInputStream in) throws IOException {
        final int length = in.readInt();
        if (length < 0 || length > 64 * 1024 * 1024) {
            throw new IOException("bad length " + length);
        }
        final byte[] bytes = new byte[length];
        in.readFully(bytes);
        return bytes;
    }

    private static DataInputStream open(final byte[] message, final byte expected) throws IOException {
        if (message.length == 0 || message[0] != expected) {
            throw new IOException("expected message type " + expected);
        }
        final DataInputStream in = new DataInputStream(new ByteArrayInputStream(message));
        in.readByte();
        return in;
    }

    @FunctionalInterface
    private interface Writer {
        void write(DataOutputStream out) throws IOException;
    }

    private static byte[] write(final Writer writer) {
        try {
            final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            final DataOutputStream out = new DataOutputStream(bytes);
            writer.write(out);
            out.flush();
            return bytes.toByteArray();
        } catch (final IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
