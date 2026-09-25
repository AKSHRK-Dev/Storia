package dev.stolia.offload;

import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.shorts.ShortArrayList;
import it.unimi.dsi.fastutil.shorts.ShortList;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.TerrainAdjustment;
import net.minecraft.world.level.levelgen.structure.pools.JigsawJunction;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;

/**
 * Wire format and chunk plumbing for the NOISE generation step, shared by the client (server 1) and
 * the worker (server 2).
 *
 * <p>The NOISE step only writes: block states into the chunk's sections, the two worldgen heightmaps,
 * and fluid post-processing marks. Its inputs, besides the seed and noise settings both servers share,
 * are the chunk position and the {@link Beardifier} (structure terrain adaptation) captured when the
 * chunk's NoiseChunk was created. Everything here round-trips exactly those.
 */
public final class NoiseCodec {

    private static final Heightmap.Types[] HEIGHTMAPS = {Heightmap.Types.OCEAN_FLOOR_WG, Heightmap.Types.WORLD_SURFACE_WG};

    private NoiseCodec() {
    }

    // ---- request ----------------------------------------------------------------------------------

    public static void writeBeardifier(final DataOutputStream out, final Beardifier beardifier) throws IOException {
        final List<Beardifier.Rigid> pieces = beardifier.stolia$pieces();
        out.writeInt(pieces.size());
        for (final Beardifier.Rigid rigid : pieces) {
            writeBox(out, rigid.box());
            out.writeByte(rigid.terrainAdjustment().ordinal());
            out.writeInt(rigid.groundLevelDelta());
        }
        final List<JigsawJunction> junctions = beardifier.stolia$junctions();
        out.writeInt(junctions.size());
        for (final JigsawJunction junction : junctions) {
            out.writeInt(junction.getSourceX());
            out.writeInt(junction.getSourceGroundY());
            out.writeInt(junction.getSourceZ());
            out.writeInt(junction.getDeltaY());
            out.writeByte(junction.getDestProjection().ordinal());
        }
        final BoundingBox affected = beardifier.stolia$affectedBox();
        out.writeBoolean(affected != null);
        if (affected != null) {
            writeBox(out, affected);
        }
    }

    public static Beardifier readBeardifier(final DataInputStream in) throws IOException {
        final int pieceCount = checkCount(in.readInt());
        final List<Beardifier.Rigid> pieces = new ArrayList<>(pieceCount);
        final TerrainAdjustment[] adjustments = TerrainAdjustment.values();
        for (int i = 0; i < pieceCount; i++) {
            final BoundingBox box = readBox(in);
            final TerrainAdjustment adjustment = adjustments[in.readUnsignedByte()];
            pieces.add(new Beardifier.Rigid(box, adjustment, in.readInt()));
        }
        final int junctionCount = checkCount(in.readInt());
        final List<JigsawJunction> junctions = new ArrayList<>(junctionCount);
        final StructureTemplatePool.Projection[] projections = StructureTemplatePool.Projection.values();
        for (int i = 0; i < junctionCount; i++) {
            final int x = in.readInt();
            final int groundY = in.readInt();
            final int z = in.readInt();
            final int deltaY = in.readInt();
            junctions.add(new JigsawJunction(x, groundY, z, deltaY, projections[in.readUnsignedByte()]));
        }
        final BoundingBox affected = in.readBoolean() ? readBox(in) : null;
        if (pieces.isEmpty() && junctions.isEmpty() && affected == null) {
            return Beardifier.EMPTY;
        }
        return new Beardifier(List.copyOf(pieces), List.copyOf(junctions), affected);
    }

    private static void writeBox(final DataOutputStream out, final BoundingBox box) throws IOException {
        out.writeInt(box.minX());
        out.writeInt(box.minY());
        out.writeInt(box.minZ());
        out.writeInt(box.maxX());
        out.writeInt(box.maxY());
        out.writeInt(box.maxZ());
    }

    private static BoundingBox readBox(final DataInputStream in) throws IOException {
        return new BoundingBox(in.readInt(), in.readInt(), in.readInt(), in.readInt(), in.readInt(), in.readInt());
    }

    private static int checkCount(final int count) throws IOException {
        if (count < 0 || count > 1_000_000) {
            throw new IOException("Bad element count " + count);
        }
        return count;
    }

    // ---- computing ----------------------------------------------------------------------------------

    /** Runs the NOISE step for a detached chunk, exactly as the server would for a fresh chunk without blending. */
    public static ProtoChunk computeDetached(final ServerLevel level, final int chunkX, final int chunkZ, final Beardifier beardifier) {
        final NoiseBasedChunkGenerator generator = (NoiseBasedChunkGenerator) level.getChunkSource().getGenerator();
        final ProtoChunk chunk = new ProtoChunk(new ChunkPos(chunkX, chunkZ), UpgradeData.EMPTY, level, level.palettedContainerFactory(), null);
        generator.stolia$fillDetached(level.getChunkSource().randomState(), beardifier, chunk);
        return chunk;
    }

    // ---- result -----------------------------------------------------------------------------------

    public static byte[] encodeResult(final ChunkAccess chunk) throws IOException {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream(64 * 1024);
        final DataOutputStream out = new DataOutputStream(bytes);
        final LevelChunkSection[] sections = chunk.getSections();
        int nonEmpty = 0;
        for (final LevelChunkSection section : sections) {
            if (!section.hasOnlyAir()) {
                nonEmpty++;
            }
        }
        out.writeInt(nonEmpty);
        for (int i = 0; i < sections.length; i++) {
            if (sections[i].hasOnlyAir()) {
                continue;
            }
            final FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer(8 * 1024));
            sections[i].getStates().write(buf);
            final byte[] data = new byte[buf.readableBytes()];
            buf.readBytes(data);
            out.writeInt(i);
            out.writeInt(data.length);
            out.write(data);
        }
        for (final Heightmap.Types type : HEIGHTMAPS) {
            final long[] raw = chunk.getOrCreateHeightmapUnprimed(type).getRawData();
            out.writeInt(raw.length);
            for (final long value : raw) {
                out.writeLong(value);
            }
        }
        final ShortList[] postProcessing = chunk.getPostProcessing();
        int lists = 0;
        for (final ShortList list : postProcessing) {
            if (list != null && !list.isEmpty()) {
                lists++;
            }
        }
        out.writeInt(lists);
        for (int i = 0; i < postProcessing.length; i++) {
            final ShortList list = postProcessing[i];
            if (list == null || list.isEmpty()) {
                continue;
            }
            out.writeInt(i);
            out.writeInt(list.size());
            for (int j = 0; j < list.size(); j++) {
                out.writeShort(list.getShort(j));
            }
        }
        out.flush();
        return bytes.toByteArray();
    }

    private record SectionData(int index, byte[] data) {}

    private record Decoded(List<SectionData> sections, long[][] heightmaps, List<int[]> postIndex, List<short[]> postShorts) {}

    /**
     * Applies a worker result to the chunk being generated. The whole payload is decoded and validated
     * against scratch copies first, so a corrupt payload leaves the chunk untouched.
     */
    public static void applyResult(final byte[] payload, final ChunkAccess chunk) throws IOException {
        final Decoded decoded = decode(payload, chunk);
        final LevelChunkSection[] sections = chunk.getSections();
        for (final SectionData section : decoded.sections()) {
            final LevelChunkSection target = sections[section.index()];
            target.getStates().read(new FriendlyByteBuf(Unpooled.wrappedBuffer(section.data())));
            target.recalcBlockCounts();
        }
        for (int i = 0; i < HEIGHTMAPS.length; i++) {
            chunk.getOrCreateHeightmapUnprimed(HEIGHTMAPS[i]).setRawData(chunk, HEIGHTMAPS[i], decoded.heightmaps()[i]);
        }
        for (int i = 0; i < decoded.postIndex().size(); i++) {
            chunk.addPackedPostProcess(new ShortArrayList(decoded.postShorts().get(i)), decoded.postIndex().get(i)[0]);
        }
    }

    private static Decoded decode(final byte[] payload, final ChunkAccess chunk) throws IOException {
        final DataInputStream in = new DataInputStream(new java.io.ByteArrayInputStream(payload));
        final LevelChunkSection[] sections = chunk.getSections();
        final int sectionCount = in.readInt();
        if (sectionCount < 0 || sectionCount > sections.length) {
            throw new IOException("Bad section count " + sectionCount);
        }
        final List<SectionData> sectionData = new ArrayList<>(sectionCount);
        for (int i = 0; i < sectionCount; i++) {
            final int index = in.readInt();
            if (index < 0 || index >= sections.length) {
                throw new IOException("Bad section index " + index);
            }
            final byte[] data = new byte[checkCount(in.readInt())];
            in.readFully(data);
            // Validate by decoding into a scratch copy.
            final PalettedContainer<BlockState> scratch = sections[index].getStates().copy();
            final FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(data));
            scratch.read(buf);
            if (buf.isReadable()) {
                throw new IOException("Trailing bytes in section " + index);
            }
            sectionData.add(new SectionData(index, data));
        }
        final long[][] heightmaps = new long[HEIGHTMAPS.length][];
        for (int i = 0; i < HEIGHTMAPS.length; i++) {
            final int length = in.readInt();
            final int expected = chunk.getOrCreateHeightmapUnprimed(HEIGHTMAPS[i]).getRawData().length;
            if (length != expected) {
                throw new IOException("Heightmap size " + length + " != " + expected);
            }
            heightmaps[i] = new long[length];
            for (int j = 0; j < length; j++) {
                heightmaps[i][j] = in.readLong();
            }
        }
        final int lists = in.readInt();
        if (lists < 0 || lists > sections.length) {
            throw new IOException("Bad post-processing list count " + lists);
        }
        final List<int[]> postIndex = new ArrayList<>(lists);
        final List<short[]> postShorts = new ArrayList<>(lists);
        for (int i = 0; i < lists; i++) {
            final int index = in.readInt();
            if (index < 0 || index >= sections.length) {
                throw new IOException("Bad post-processing section " + index);
            }
            final short[] shorts = new short[checkCount(in.readInt())];
            for (int j = 0; j < shorts.length; j++) {
                shorts[j] = in.readShort();
            }
            postIndex.add(new int[]{index});
            postShorts.add(shorts);
        }
        if (in.available() != 0) {
            throw new IOException("Trailing bytes in result");
        }
        return new Decoded(sectionData, heightmaps, postIndex, postShorts);
    }

    // ---- comparison ---------------------------------------------------------------------------------

    /** Hash of everything the NOISE step writes: every block state, both heightmaps and post-processing marks. */
    public static String terrainHash(final ChunkAccess chunk) {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (final NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
        final LevelChunkSection[] sections = chunk.getSections();
        final byte[] four = new byte[4];
        for (int s = 0; s < sections.length; s++) {
            final PalettedContainer<BlockState> states = sections[s].getStates();
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        final int id = Block.getId(states.get(x, y, z));
                        four[0] = (byte) (id >>> 24);
                        four[1] = (byte) (id >>> 16);
                        four[2] = (byte) (id >>> 8);
                        four[3] = (byte) id;
                        digest.update(four);
                    }
                }
            }
        }
        for (final Heightmap.Types type : HEIGHTMAPS) {
            for (final long value : chunk.getOrCreateHeightmapUnprimed(type).getRawData()) {
                digest.update(Long.toString(value).getBytes());
                digest.update((byte) ',');
            }
        }
        final ShortList[] postProcessing = chunk.getPostProcessing();
        for (int i = 0; i < postProcessing.length; i++) {
            final ShortList list = postProcessing[i];
            if (list == null) {
                continue;
            }
            digest.update(("p" + i + ":").getBytes());
            for (int j = 0; j < list.size(); j++) {
                digest.update(Short.toString(list.getShort(j)).getBytes());
                digest.update((byte) ',');
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /**
     * Fingerprint of a dimension's terrain generator: generates two probe chunks and hashes the result.
     * Two servers only agree if seed, noise settings, datapacks and code all produce identical terrain.
     */
    public static String probe(final ServerLevel level) {
        final StringBuilder sb = new StringBuilder();
        sb.append(terrainHash(computeDetached(level, 0, 0, Beardifier.EMPTY)));
        sb.append(terrainHash(computeDetached(level, 1021, -777, Beardifier.EMPTY)));
        sb.append(BlockPos.asLong(level.getMinY(), level.getHeight(), 0));
        return sb.toString();
    }
}
