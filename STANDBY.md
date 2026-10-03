# Storia Relay standby (design)

Status: phase 1 (replication, `promote`, relay lists) is in 26.2-8-beta. Phase 2 (automatic failover) is not built yet.

Today Storia Relay is the one part of a Storia Cluster that has no spare. Workers keep running while it is away
(writes wait in `cluster-spool/`), but new players cannot log in, and if the relay's machine or disk is lost, the
world is lost with it unless there is a backup. A **standby relay** keeps a live copy of everything the relay stores
and takes over when the relay is gone.

## Goals

- **No acknowledged write is lost.** When a worker is told "stored", the write is on both relays.
- **Workers and Storia Proxy find the active relay by themselves**, from a list of relay addresses.
- **Never two active relays** writing the same world (no split brain), even when the network between the relays
  breaks.
- The usual case costs little: one extra LAN round trip per write, on the worker's I/O threads (never the tick).

Not goals (for now): more than one standby, relays in different data centers, replacing backups.

## What the relay holds

| State | Where | Needs copying? |
| --- | --- | --- |
| Chunks, entities, POI | `cluster-world/dimensions/**/r.X.Z.mca` (+ `.mcc`) | yes, per chunk record |
| Player data, advancements, statistics | `cluster-world/players/**` | yes, whole file |
| Maps, scoreboard, command storage | `cluster-world/data/**` | yes, whole file |
| Plugin shared data | `cluster-world/storia-shared/**` | yes, the resulting value |
| Map id counter | `storia-cluster-counters.txt` | yes, the resulting value |
| Contraption links | `storia-cluster-links.txt` | yes, each new link |
| Players' last positions | `storia-cluster-positions.txt` | yes, best effort |
| Base files (level.dat, data packs) | `cluster-world/*` | copied once at sync, and on change |
| Cell owners, released cells, player holders, transfers, connected nodes | memory | **no**: rebuilt by workers when they reconnect (they already do this after a relay restart) |

Every copied change is an **idempotent "set key = value"**: a chunk record, a whole file, a key's new value. A CAS
or increment is copied as its result, not as the operation. So applying a change twice is harmless, and only the
order per key matters.

## Roles and terms

- Each relay has a **role** (`active` or `standby`) and the **term**, a number stored in
  `storia-cluster-term.txt`. Every promotion raises the term by one.
- Only the active relay accepts workers and Storia Proxy. A standby answers their HELLO with
  `Welcome(false, "standby; active is <host:port>")`, and they try the next address.
- The two relays talk over the same encrypted channel (new role `ROLE_REPLICA`), with the same secret.

## Replication

1. **Joining.** The standby connects to the active relay as `ROLE_REPLICA`, sending its term.
2. **Live stream first.** The active relay starts sending every change to it at once, each with a sequence number.
   The standby applies them and remembers which keys they touched.
3. **Snapshot.** Meanwhile the active relay walks its storage and sends every chunk record (read through
   `AnvilStore`, one record at a time under the region lock, so never torn), every file and every shared value. The
   standby **skips any snapshot item whose key the live stream has already set**, because the live value is newer.
   Unchanged data is skipped by comparing record timestamps and file hashes, so a standby that was only briefly
   away catches up quickly.
4. **In sync.** When the snapshot is done, the active relay marks the standby *in sync*.
5. **Semi-synchronous writes.** From then on, a write is acknowledged to the worker only when it is stored locally
   **and** the standby has confirmed it. The standby writes in arrival order on one thread per key range, and
   confirms by sequence number.
6. **Standby lost.** If the standby does not confirm within 2 s, the active relay drops it to *out of sync*, logs a
   warning, and goes on alone, acknowledging writes after the local store only. The standby rejoins at step 1.

A write that the worker sent but that was never acknowledged (the active relay died first) is still in the worker's
memory or `cluster-spool/`, and is sent again to the new active relay. Writes are idempotent, so this is safe.

## Failover

### Phase 1: manual (first release)

- The operator types `promote` on the standby's console (or runs it with `--promote`). It refuses unless it was
  *in sync* when it last heard from the active relay, unless `promote force` is used.
- The standby raises the term, becomes active and starts accepting workers. Workers, which have been spooling
  since the old relay went away, connect to the next address, claim their cells again and send their spool.
  Storia Proxy reconnects the same way.
- **The old relay comes back** with the old term. Before accepting anyone, an active relay asks its peer for its
  term; if the peer has a higher one, it becomes a standby and syncs from it (step 1). Writes it had that the new
  active relay lacks are only unacknowledged ones, which workers sent again, so it may drop them.

Why manual first: it is simple and cannot split the world. A person checks that the old relay is really gone (not
just cut off) before promoting.

### Phase 2: automatic, with the workers as witnesses

- The standby promotes itself only when **it** cannot reach the active relay **and a majority of the connected
  workers** report (through a small status connection they keep to the standby) that they cannot reach it either,
  for 10 s.
- The active relay **steps down** when it has lost a majority of the workers for 10 s: it stops acknowledging
  writes (workers spool), so a relay cut off from the cluster can never keep writing on its own.
- Together these guarantee that at most one relay acknowledges writes at any time. The term breaks any remaining
  tie: a worker never accepts a relay with a lower term than one it has seen.
- With one worker, there is no majority to vote; automatic failover needs at least two workers, otherwise it falls
  back to manual.

## Configuration

```properties
# relay.properties on both machines
role=active            # or: standby
peer=relay-b:25590     # the other relay
```

```yaml
# storia.yml on each worker: the relays to try, in order
cluster:
  coordinator: "relay-a:25590,relay-b:25590"
```

```toml
# storia-proxy.toml
[cluster]
coordinator = "relay-a:25590,relay-b:25590"
```

A single address keeps today's behaviour exactly.

## Costs

- Every write: one extra round trip to the standby (well under 1 ms on a LAN) and its disk write. Writes run on
  the worker's I/O threads, so ticks are not affected; chunk saving gets slightly slower.
- The active relay sends each write twice (to its disk and to the standby): about double its outgoing network for
  writes.
- The standby needs the same disk space as the active relay.

## Commands and status

- Relay console: `status` shows the role, term, standby (in sync / syncing n% / out of sync, lag in writes).
- `promote` and `promote force` on a standby.
- `/storia cluster` on a worker shows which relay it is connected to.

## Tests to pass before release

1. Kill -9 the active relay during a pregeneration burst; promote; every block placed before the kill is on the
   new relay, and the workers' spool drains there.
2. Cut the network between the relays only (iptables): no promotion in phase 2; the active relay keeps going alone.
3. The old relay restarts after a promotion: it becomes a standby, syncs, and the world is identical (compare every
   chunk record and file).
4. Standby restarts during heavy writes: it catches up without stopping the active relay.
5. Write latency on a worker with and without a standby (chunk save time, pregeneration rate).

## Rollout

- Beta 1: replication, `promote`, relay lists on workers and Storia Proxy (phase 1).
- Beta 2: automatic failover (phase 2), after the phase 1 tests have run on a real server for a while.
