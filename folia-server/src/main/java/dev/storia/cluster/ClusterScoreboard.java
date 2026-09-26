package dev.storia.cluster;

import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DynamicOps;
import dev.storia.cluster.protocol.ClusterProtocol;
import io.papermc.paper.threadedregions.RegionizedServer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerScoreboard;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.ReadOnlyScoreInfo;
import net.minecraft.world.scores.Score;
import net.minecraft.world.scores.ScoreAccess;
import net.minecraft.world.scores.ScoreHolder;
import net.minecraft.world.scores.Scoreboard;
import org.slf4j.Logger;

/**
 * Keeps the main scoreboard the same on every node. Each change made on a node (objectives, scores, display slots,
 * teams and their members) is sent through the coordinator to the other nodes, which apply it on their global
 * region. A node that starts asks another node for the whole scoreboard; scoreboard.dat itself is shared too, so
 * the scoreboard also survives a restart of every node.
 */
public final class ClusterScoreboard {

    private static final Logger LOGGER = LogUtils.getClassLogger();
    /** Set while applying a change from another node, so it is not sent back. */
    private static final ThreadLocal<Boolean> APPLYING = ThreadLocal.withInitial(() -> false);
    private static final ExecutorService SENDER = Executors.newSingleThreadExecutor(r -> {
        final Thread thread = new Thread(r, "Storia Cluster scoreboard");
        thread.setDaemon(true);
        return thread;
    });

    private static volatile boolean requested;

    private ClusterScoreboard() {}

    /** Called once a second from the maintenance thread; asks for the current scoreboard once the server runs. */
    static void tick(final ClusterClient client) {
        if (!requested && dev.storia.offload.NoiseOffload.serverStarted && client.connected()) {
            requested = true;
            send(client, "?", new CompoundTag());
        }
    }

    private static boolean shared(final Scoreboard board) {
        final MinecraftServer server = MinecraftServer.getServer();
        return Cluster.enabled() && server != null && dev.storia.offload.NoiseOffload.serverStarted
            && !APPLYING.get() && board == server.getScoreboard(); // plugin scoreboards stay local
    }

    // ---- hooks, called by ServerScoreboard ----

    public static void objective(final Scoreboard board, final Objective objective) {
        if (shared(board)) {
            final CompoundTag op = op("objective");
            op.put("data", encode(Objective.Packed.CODEC, objective.pack()));
            broadcast(op);
        }
    }

    public static void objectiveRemoved(final Scoreboard board, final Objective objective) {
        if (shared(board)) {
            final CompoundTag op = op("objective_remove");
            op.putString("name", objective.getName());
            broadcast(op);
        }
    }

    public static void score(final Scoreboard board, final ScoreHolder owner, final Objective objective, final Score score) {
        if (shared(board)) {
            final CompoundTag op = op("score");
            op.put("data", encode(Scoreboard.PackedScore.CODEC, new Scoreboard.PackedScore(owner.getScoreboardName(), objective.getName(), score.pack())));
            broadcast(op);
        }
    }

    public static void scoreLock(final Scoreboard board, final ScoreHolder owner, final Objective objective) {
        if (shared(board)) {
            final ReadOnlyScoreInfo info = board.getPlayerScoreInfo(owner, objective);
            final CompoundTag op = op("lock");
            op.putString("owner", owner.getScoreboardName());
            op.putString("objective", objective.getName());
            op.putBoolean("locked", info != null && info.isLocked());
            broadcast(op);
        }
    }

    /** A score was reset: of one objective, or of all objectives when {@code objective} is null. */
    public static void scoreReset(final Scoreboard board, final ScoreHolder owner, final Objective objective) {
        if (shared(board)) {
            final CompoundTag op = op("reset");
            op.putString("owner", owner.getScoreboardName());
            if (objective != null) {
                op.putString("objective", objective.getName());
            }
            broadcast(op);
        }
    }

    public static void display(final Scoreboard board, final DisplaySlot slot, final Objective objective) {
        if (shared(board)) {
            final CompoundTag op = op("display");
            op.putString("slot", slot.getSerializedName());
            if (objective != null) {
                op.putString("objective", objective.getName());
            }
            broadcast(op);
        }
    }

    public static void team(final Scoreboard board, final PlayerTeam team) {
        if (shared(board)) {
            final CompoundTag op = op("team");
            op.put("data", encode(PlayerTeam.Packed.CODEC, team.pack()));
            broadcast(op);
        }
    }

    public static void teamRemoved(final Scoreboard board, final PlayerTeam team) {
        if (shared(board)) {
            final CompoundTag op = op("team_remove");
            op.putString("name", team.getName());
            broadcast(op);
        }
    }

    public static void members(final Scoreboard board, final PlayerTeam team, final Collection<String> players, final boolean join) {
        if (shared(board)) {
            final CompoundTag op = op(join ? "join" : "leave");
            op.putString("team", team.getName());
            final ListTag list = new ListTag();
            players.forEach(p -> list.add(StringTag.valueOf(p)));
            op.put("players", list);
            broadcast(op);
        }
    }

    // ---- sending ----

    private static CompoundTag op(final String type) {
        final CompoundTag op = new CompoundTag();
        op.putString("op", type);
        return op;
    }

    private static void broadcast(final CompoundTag op) {
        final ClusterClient client = Cluster.client();
        if (client != null) {
            send(client, "", op);
        }
    }

    /** Sends in order on one thread; region threads never wait for the coordinator. */
    private static void send(final ClusterClient client, final String target, final CompoundTag op) {
        SENDER.execute(() -> {
            try {
                final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                NbtIo.writeCompressed(op, bytes);
                client.request(ClusterProtocol.OP_SCOREBOARD, ClusterProtocol.named(target, bytes.toByteArray()));
            } catch (final IOException ex) {
                LOGGER.debug("Could not send a scoreboard change: {}", ex.toString());
            }
        });
    }

    private static <T> Tag encode(final Codec<T> codec, final T value) {
        return codec.encodeStart(ops(), value).getOrThrow();
    }

    private static <T> T decode(final Codec<T> codec, final Tag tag) {
        return codec.parse(ops(), tag).getOrThrow();
    }

    private static DynamicOps<Tag> ops() {
        return MinecraftServer.getServer().registryAccess().createSerializationContext(NbtOps.INSTANCE);
    }

    // ---- receiving ----

    static void onPush(final ClusterProtocol.Named push) {
        final MinecraftServer server = MinecraftServer.getServer();
        if (server == null) {
            return;
        }
        final String from = push.name();
        RegionizedServer.getInstance().addTask(() -> {
            final ServerScoreboard board = server.getScoreboard();
            if (push.data().length == 0) {
                // another node has started and wants the whole scoreboard
                final ClusterClient client = Cluster.client();
                if (client != null) {
                    send(client, from, snapshot(board));
                }
                return;
            }
            APPLYING.set(true);
            try {
                apply(board, NbtIo.readCompressed(new ByteArrayInputStream(push.data()), NbtAccounter.unlimitedHeap()));
            } catch (final IOException | RuntimeException ex) {
                LOGGER.warn("Could not apply a scoreboard change from {}: {}", from, ex.toString());
            } finally {
                APPLYING.set(false);
            }
        });
    }

    private static void apply(final ServerScoreboard board, final CompoundTag op) {
        switch (op.getStringOr("op", "")) {
            case "objective" -> upsertObjective(board, decode(Objective.Packed.CODEC, op.get("data")));
            case "objective_remove" -> {
                final Objective objective = board.getObjective(op.getStringOr("name", ""));
                if (objective != null) {
                    board.removeObjective(objective);
                }
            }
            case "score" -> setScore(board, decode(Scoreboard.PackedScore.CODEC, op.get("data")));
            case "lock" -> {
                final Objective objective = board.getObjective(op.getStringOr("objective", ""));
                if (objective != null) {
                    final ScoreAccess access = board.getOrCreatePlayerScore(ScoreHolder.forNameOnly(op.getStringOr("owner", "")), objective, true);
                    if (op.getBooleanOr("locked", false)) {
                        access.lock();
                    } else {
                        access.unlock();
                    }
                }
            }
            case "reset" -> {
                final ScoreHolder owner = ScoreHolder.forNameOnly(op.getStringOr("owner", ""));
                if (op.contains("objective")) {
                    final Objective objective = board.getObjective(op.getStringOr("objective", ""));
                    if (objective != null) {
                        board.resetSinglePlayerScore(owner, objective);
                    }
                } else {
                    board.resetAllPlayerScores(owner);
                }
            }
            case "display" -> {
                final DisplaySlot slot = DisplaySlot.CODEC.byName(op.getStringOr("slot", ""));
                if (slot != null) {
                    board.setDisplayObjective(slot, op.contains("objective") ? board.getObjective(op.getStringOr("objective", "")) : null);
                }
            }
            case "team" -> upsertTeam(board, decode(PlayerTeam.Packed.CODEC, op.get("data")), false);
            case "team_remove" -> {
                final PlayerTeam team = board.getPlayerTeam(op.getStringOr("name", ""));
                if (team != null) {
                    board.removePlayerTeam(team);
                }
            }
            case "join", "leave" -> {
                final PlayerTeam team = board.getPlayerTeam(op.getStringOr("team", ""));
                if (team != null) {
                    final List<String> players = new ArrayList<>();
                    op.getListOrEmpty("players").forEach(tag -> tag.asString().ifPresent(players::add));
                    if (op.getStringOr("op", "").equals("join")) {
                        board.addPlayersToTeam(players, team);
                    } else {
                        board.removePlayersFromTeam(players.stream().filter(p -> board.getPlayersTeam(p) == team).toList(), team);
                    }
                }
            }
            case "snapshot" -> applySnapshot(board, op);
            default -> LOGGER.debug("Unknown scoreboard change {}", op);
        }
    }

    private static void upsertObjective(final Scoreboard board, final Objective.Packed packed) {
        final Objective existing = board.getObjective(packed.name());
        if (existing == null) {
            board.addObjective(packed.name(), packed.criteria(), packed.displayName(), packed.renderType(), packed.displayAutoUpdate(),
                packed.numberFormat().orElse(null));
            return;
        }
        if (!existing.getDisplayName().equals(packed.displayName())) {
            existing.setDisplayName(packed.displayName());
        }
        if (existing.getRenderType() != packed.renderType()) {
            existing.setRenderType(packed.renderType());
        }
        if (existing.displayAutoUpdate() != packed.displayAutoUpdate()) {
            existing.setDisplayAutoUpdate(packed.displayAutoUpdate());
        }
        if (!java.util.Objects.equals(existing.numberFormat(), packed.numberFormat().orElse(null))) {
            existing.setNumberFormat(packed.numberFormat().orElse(null));
        }
    }

    private static void setScore(final Scoreboard board, final Scoreboard.PackedScore packed) {
        final Objective objective = board.getObjective(packed.objective());
        if (objective == null) {
            return;
        }
        final ScoreAccess access = board.getOrCreatePlayerScore(ScoreHolder.forNameOnly(packed.owner()), objective, true);
        access.set(packed.score().value());
        access.display(packed.score().display().orElse(null));
        final ReadOnlyScoreInfo info = board.getPlayerScoreInfo(ScoreHolder.forNameOnly(packed.owner()), objective);
        if (info == null || !java.util.Objects.equals(info.numberFormat(), packed.score().numberFormat().orElse(null))) {
            access.numberFormatOverride(packed.score().numberFormat().orElse(null));
        }
        if (access.locked() != packed.score().locked()) {
            if (packed.score().locked()) {
                access.lock();
            } else {
                access.unlock();
            }
        }
    }

    /** Creates the team or updates its settings; members are replaced only when {@code members} is set. */
    private static void upsertTeam(final ServerScoreboard board, final PlayerTeam.Packed packed, final boolean members) {
        PlayerTeam team = board.getPlayerTeam(packed.name());
        final boolean created = team == null;
        if (created) {
            team = board.addPlayerTeam(packed.name());
        }
        packed.displayName().ifPresent(team::setDisplayName);
        team.setColor(packed.color());
        team.setAllowFriendlyFire(packed.allowFriendlyFire());
        team.setSeeFriendlyInvisibles(packed.seeFriendlyInvisibles());
        team.setPlayerPrefix(packed.memberNamePrefix());
        team.setPlayerSuffix(packed.memberNameSuffix());
        team.setNameTagVisibility(packed.nameTagVisibility());
        team.setDeathMessageVisibility(packed.deathMessageVisibility());
        team.setCollisionRule(packed.collisionRule());
        if (created || members) {
            final Set<String> wanted = new HashSet<>(packed.players());
            final List<String> gone = team.getPlayers().stream().filter(p -> !wanted.contains(p)).toList();
            if (!gone.isEmpty()) {
                board.removePlayersFromTeam(gone, team);
            }
            final PlayerTeam target = team;
            final List<String> missing = packed.players().stream().filter(p -> board.getPlayersTeam(p) != target).toList();
            if (!missing.isEmpty()) {
                board.addPlayersToTeam(missing, team);
            }
        }
    }

    // ---- snapshots ----

    private static CompoundTag snapshot(final ServerScoreboard board) {
        final CompoundTag op = op("snapshot");
        final ListTag objectives = new ListTag();
        final ListTag scores = new ListTag();
        for (final Objective objective : board.getObjectives()) {
            objectives.add(encode(Objective.Packed.CODEC, objective.pack()));
            for (final PlayerScoreEntry entry : board.listPlayerScores(objective)) {
                final ReadOnlyScoreInfo info = board.getPlayerScoreInfo(ScoreHolder.forNameOnly(entry.owner()), objective);
                final Score.Packed score = new Score.Packed(entry.value(), info != null && info.isLocked(),
                    java.util.Optional.ofNullable(entry.display()), java.util.Optional.ofNullable(entry.numberFormatOverride()));
                scores.add(encode(Scoreboard.PackedScore.CODEC, new Scoreboard.PackedScore(entry.owner(), objective.getName(), score)));
            }
        }
        final CompoundTag display = new CompoundTag();
        for (final DisplaySlot slot : DisplaySlot.values()) {
            final Objective objective = board.getDisplayObjective(slot);
            if (objective != null) {
                display.putString(slot.getSerializedName(), objective.getName());
            }
        }
        final ListTag teams = new ListTag();
        board.getPlayerTeams().forEach(team -> teams.add(encode(PlayerTeam.Packed.CODEC, team.pack())));
        op.put("objectives", objectives);
        op.put("scores", scores);
        op.put("display", display);
        op.put("teams", teams);
        return op;
    }

    /** Makes this scoreboard equal to the snapshot. */
    private static void applySnapshot(final ServerScoreboard board, final CompoundTag op) {
        final Set<String> objectiveNames = new HashSet<>();
        for (final Tag tag : op.getListOrEmpty("objectives")) {
            final Objective.Packed packed = decode(Objective.Packed.CODEC, tag);
            objectiveNames.add(packed.name());
            upsertObjective(board, packed);
        }
        for (final Objective objective : List.copyOf(board.getObjectives())) {
            if (!objectiveNames.contains(objective.getName())) {
                board.removeObjective(objective);
            }
        }
        final Map<String, Set<String>> owners = new HashMap<>();
        for (final Tag tag : op.getListOrEmpty("scores")) {
            final Scoreboard.PackedScore packed = decode(Scoreboard.PackedScore.CODEC, tag);
            owners.computeIfAbsent(packed.objective(), k -> new HashSet<>()).add(packed.owner());
            setScore(board, packed);
        }
        for (final Objective objective : board.getObjectives()) {
            final Set<String> keep = owners.getOrDefault(objective.getName(), Set.of());
            for (final PlayerScoreEntry entry : List.copyOf(board.listPlayerScores(objective))) {
                if (!keep.contains(entry.owner())) {
                    board.resetSinglePlayerScore(ScoreHolder.forNameOnly(entry.owner()), objective);
                }
            }
        }
        final CompoundTag display = op.getCompoundOrEmpty("display");
        for (final DisplaySlot slot : DisplaySlot.values()) {
            final Objective objective = display.getString(slot.getSerializedName()).map(board::getObjective).orElse(null);
            if (board.getDisplayObjective(slot) != objective) {
                board.setDisplayObjective(slot, objective);
            }
        }
        final Set<String> teamNames = new HashSet<>();
        for (final Tag tag : op.getListOrEmpty("teams")) {
            final PlayerTeam.Packed packed = decode(PlayerTeam.Packed.CODEC, tag);
            teamNames.add(packed.name());
            upsertTeam(board, packed, true);
        }
        for (final PlayerTeam team : List.copyOf(board.getPlayerTeams())) {
            if (!teamNames.contains(team.getName())) {
                board.removePlayerTeam(team);
            }
        }
        LOGGER.info("Storia Cluster: scoreboard copied from another node ({} objectives, {} teams)", objectiveNames.size(), teamNames.size());
    }

    /** "deaths{Bob=2} points{Carol=3} red[Bob,Carol]", sorted, so two nodes can be compared by eye. */
    static String describe(final Scoreboard board) {
        final List<String> parts = new ArrayList<>();
        for (final Objective objective : board.getObjectives()) {
            final List<String> scores = new ArrayList<>();
            board.listPlayerScores(objective).forEach(entry -> scores.add(entry.owner() + "=" + entry.value()));
            scores.sort(null);
            parts.add(objective.getName() + "{" + String.join(",", scores) + "}");
        }
        for (final PlayerTeam team : board.getPlayerTeams()) {
            final List<String> players = new ArrayList<>(team.getPlayers());
            players.sort(null);
            parts.add(team.getName() + "[" + String.join(",", players) + "]");
        }
        parts.sort(null);
        return parts.isEmpty() ? "empty" : String.join(" ", parts);
    }
}
