# Storia Cluster (design)

Status: shipped in Storia 26.2-2 (tested as 26.2-2-beta and 26.2-3-beta). Done: shared world, ownership,
contraption links, local spool for coordinator outages, re-claim after coordinator restarts; placement by view
areas, merging, balancing, player transfers with strict data handover; seamless switching for 26.1/26.2 clients
(verified with protocol-level bots and a real 26.2 client); shared advancements, statistics, time, weather, game
rules, scoreboard, map ids and maps; /stop hands players to other workers first; players whose data cannot be read
are refused instead of overwriting it. Still open: a shared API for plugins. Goal: several Storia servers ("nodes") run **one** world together, each ticking a
different part of it, with automatic placement and seamless movement for players.

## Storia Worker is a cluster node

Done 2026-09-27: Cluster is folded into Storia Worker instead of adding a new program.

- **Storia Worker = a node that runs part of the world**: existing chunks, mobs, redstone and the players in
  that part, not only the noise step of new chunks. Players reach it through Storia Proxy and move between
  workers seamlessly.
- **Storia Relay = the coordinator**: world storage, ownership, links, placement. It has no other job now.
- A new worker needs no world copy: `OP_WORLD_BASE` sends it the world without region, entities, POI and players.
- **Storia (the main server)** stays the entry point for a single-server setup; in a cluster every server is a
  worker, and the names Storia / Worker / Relay / Proxy stay the same for users.
- The terrain-only mode (noise offload, `-Dstoria.worker=true`) is removed and archived in the
  `archive/terrain-offload` branch (both repositories): a worker now needs the
  CPU and RAM of a normal Storia server. The code stays in the git history and in the releases that include it.
- The first 26.2 release and 26.2-1-beta do not include Cluster.

## Principles

1. **Folia regions, lifted to machines.** Folia already groups nearby ticking chunks into regions that never
   interact within a tick. The cluster does the same one level up: a *cluster region* is a group of cells whose
   active areas are close together, and it is always owned by exactly one node. Nodes' active areas are separated
   by non-ticking chunks, so redstone, water, pistons and entities never interact across machines.
2. **One writer per cell.** A cell is one region file (32 x 32 chunks) of one dimension. Only its owner may
   load it for ticking or write it. Ownership is granted and tracked by the coordinator.
3. **The coordinator is the source of truth.** Storia Relay in cluster mode stores the world (vanilla Anvil
   region files, so the world stays a normal Minecraft world), player data and global data, and decides
   ownership. Nodes keep no authoritative world data on disk.
4. **Contraptions are never split.** When a node sees mechanism blocks (redstone, pistons, observers, hoppers,
   slime and honey blocks, rails, ...) in a chunk on the edge of a cell, it reports a *link* between that cell and
   its neighbour. Linked cells form one unit that is always owned by the same node, so a machine built across a
   cell border runs on one machine. Links are stored by the coordinator and survive restarts.
5. **Same results as a single server** wherever the cluster is not in the middle of moving something.
   While a cluster region moves between nodes it is paused for a moment; nothing is lost or duplicated.

## Components

| Component | Role |
| --- | --- |
| Storia Relay (`cluster=true`) | Coordinator: membership, cell ownership and cluster regions, storage (chunks, entities, POI, player data, level data), placement and balancing. Still hands out terrain work to workers. |
| Storia node (`cluster.enabled: true`) | Ticks the cells it owns. Reads and writes world data through the coordinator. Reports its active cells and load. Exports and imports cluster regions when told to. |
| Storia Proxy | Keeps each player's connection. Routes the player to the node that owns their area, switches nodes without a loading screen, and shows areas owned by other nodes through view-only sessions. |

## Phases

1. **Shared world (foundation).** Coordinator storage for chunk/entity/POI data and player data; cell
   ownership with leases; cluster-unique entity IDs; nodes refuse to write cells they do not own.
   Verified with two nodes on one machine, players in separate areas.
2. **Moving cluster regions.** Export (save + release) and import (claim + load) of a group of cells; the
   coordinator merges groups that approach each other onto one node and balances separate groups across nodes.
3. **Seamless switching** (done). Storia Proxy moves a player to another node without respawn/login packets:
   cluster-unique entity IDs, the player's state handed over through the coordinator, entities of the old node
   removed and those of the new node spawned.
4. **Seeing across** (done differently: nodes report their players' *view* areas, so areas merge before anyone
   could see chunks run by another node; no view-only sessions needed).
   Original idea: View-only sessions: a player sees chunks and entities of an area owned by another node
   through a hidden viewer on that node, multiplexed by the proxy.
5. **Global state.** Time, weather, game rules, world border, scoreboards, maps, advancements and a shared
   key-value/messaging API for plugins.

## Robustness

- **Spool.** Writes that cannot reach the coordinator are kept in `cluster-spool/` on the node, in order, and
  replayed when it is back (also after a node restart). While anything is spooled, new writes queue behind it
  and reads see the spooled data first.
- **Coordinator restarts** lose the in-memory ownership table. Nodes claim their cells again when they reconnect,
  and a write refused for "no owner" claims the cell and retries once.
- **Player data** has a holder: only the node that last loaded a player may save them, so a late save from the
  node a player just left is refused instead of rolling the player back.

- **Player data handover.** A node that loads a player becomes their holder. Another node that wants to load
  them waits until the holder has saved them for the last time (released), or up to 10 seconds. A coordinated
  move hands the holder over before the proxy switches, so the next node never loads stale data.
- **/stop** moves the node's players to other nodes first (while regions still tick), then stops.

## Keeping copies in sync

`dev/storia/net/*` and `dev/storia/cluster/protocol/*` are copied into Storia Proxy
(`proxy/src/main/java/dev/storia/...`). Change them here first and copy them over.

## Phase 1 details

### Protocol

Encrypted channel `dev.storia.net.SecureChannel` (shared secret), opened with a `Handshake` HELLO/WELCOME. Request/response with a request id:

- `HELLO(role=NODE, nodeName)` -> `WELCOME(nodeIndex)`: nodeIndex gives the node its entity ID range.
- `READ(type, dim, x, z)` -> `DATA(present, payload)`: payload is the region-file chunk record
  (compression byte + compressed NBT), exactly as stored in `.mca` files.
- `WRITE(type, dim, x, z, payload | delete)` -> `ACK` or `DENIED` (cell not owned by this node).
- `CLAIM(dim, cellX, cellZ)` -> `GRANTED` / `OWNED_BY(node)`; `RELEASE(dim, cellX, cellZ)`.
- `PLAYER_READ(uuid, kind)` / `PLAYER_WRITE(uuid, kind, bytes)` for playerdata, advancements and stats.
- `HEARTBEAT(load)` every second; a node that misses 15 seconds of heartbeats loses its leases.
- `LINK(cellA, cellB)`: a contraption touches the border between two cells. A CLAIM grants the whole linked
  group at once (or nothing); a group is released when every cell in it has been released by its owner.
- `SCOREBOARD(target, change)`: a change to the main scoreboard (objective, score, lock, reset, display slot,
  team, team members) is relayed to every other node, which applies it on its global region without sending it
  back. A starting node sends target `?`; the coordinator asks another node, which sends the whole scoreboard
  to it. `scoreboard.dat` is also shared. Plugin scoreboards stay local. (Folia has no /scoreboard or /team
  commands; changes come from criteria such as `deathCount` and from a scoreboard.dat carried over from
  another server.)

Types: `chunk` (`region/`), `entities` (`entities/`), `poi` (`poi/`).

### Node

- `MoonriseRegionFileIO` data controllers route read/write through the cluster client when cluster mode is on.
- A cell is claimed before its first chunk is loaded and released after its last chunk is unloaded and saved.
- `ServerLevel.ENTITY_COUNTER` starts at `nodeIndex << 24`.
- Player data load/save goes through the coordinator.

### Coordinator storage

`<world>/dimensions/<namespace>/<path>/{region,entities,poi}/r.X.Z.mca` (the Minecraft 26.x layout, where
every dimension, including the overworld, lives under `dimensions/`), written with the vanilla Anvil layout
(4 KiB sectors, location + timestamp tables). Writes are atomic per chunk record.
