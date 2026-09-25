<div align=center>
    <img src="./stolia.png" alt="Stolia logo" width="128">
    <h1>Stolia</h1>
    <p>A high-efficiency Minecraft server for large player counts, based on <a href="https://github.com/PaperMC/Folia">Folia</a></p>
    <p><b>English</b> | <a href="./README.ja.md">日本語</a></p>
</div>

## Features

- **Regionised multithreading (from Folia)**
  Nearby chunks are grouped into independent "regions" that tick in parallel.
  This scales well on large servers where players spread out (SMP, skyblock, etc.).
- **RAM world (Stolia)**
  On startup the world is loaded into RAM (`/dev/shm`) and the server reads and writes it there,
  so disk I/O stops being a bottleneck.
  - Only changed files are written back to disk in the background, at a fixed interval
  - Files are written to a temp file and then atomically renamed, so the on-disk world is never half-written
  - `stop` writes everything to disk
  - If the process is killed, the RAM copy survives as long as the machine stays up; it is detected and recovered to disk on the next start
  - If there is not enough RAM, the server falls back to loading from disk as usual
- **Fast chunk pregeneration (Stolia)**
  `/stolia pregen` generates chunks ahead of time so players never wait for terrain.
  While it runs, the chunk worker pool is raised to all cores but one (Folia's default is only
  about a quarter of the cores), then restored. Progress survives restarts.
- **Per-player budget (Stolia)**
  Every player gets a fair share of the region tick threads. If a region's own tick time is too high,
  or the tick threads are saturated and a region uses more than its players' share, only the players
  in that region get a lower view distance; it is restored once the region recovers. Players in other
  regions are never limited because of someone else's lag, and nothing is limited while the server has
  headroom. When the heap is nearly full after GC, view distance is lowered for everyone.
  **Simulation distance is not touched by default, so redstone and farms keep running**; view distance
  is only lowered down to the simulation distance, so set `view-distance` above `simulation-distance`
  to give it room (e.g. 12 / 8). `lower-simulation-distance: true` also lowers simulation distance.
- **Faster entity physics (Stolia)**
  Entity pushing (the cost of mobs crammed together) is about 3x faster with identical results:
  the same entities are pushed in the same order, and cramming damage and its random roll are unchanged.
  Redstone is not modified.
- **Faster world generation (Stolia)**
  Noise sampling and block counting are optimized. Terrain is **identical to vanilla** for the same seed
  (the noise changes are verified bit-for-bit against the original code).

## Usage

Requires Java 25 or newer.

```sh
java -Xmx8G -jar stolia-26.2.jar nogui
```

The latest build can be downloaded from the `stolia-jar` artifact in [Actions](../../actions).

### Configuration (`stolia.yml`)

Created in the server folder on first start.

```yaml
ram-world:
  enabled: true
  ram-directory: /dev/shm/stolia
  sync-interval-seconds: 300   # shorter is safer, longer is more efficient
  min-free-mb: 512             # load from disk if RAM would drop below this
  delete-on-shutdown: true     # delete the RAM copy after a clean shutdown
pregen:
  worker-threads: -1           # -1 = CPU cores - 1 while pregenerating
  max-in-flight: -1            # -1 = worker-threads * 16 chunks queued at once
player-budget:
  enabled: true
  check-interval-ticks: 100    # how often each player is checked
  lower-simulation-distance: false  # true = also lower simulation distance (machines far away stop)
  max-region-mspt: 45.0        # a region ticking slower than this is over budget
  pool-saturated-percent: 85   # tick threads this busy = saturated, so enforce fair shares
  recover-below-percent: 70    # restore once the region is below 70% of its limits
  min-simulation-distance: 4
  min-view-distance: 6
  memory-high-percent: 85      # heap after GC above this lowers everyone's view distance
  memory-low-percent: 70
```

> [!WARNING]
> **If the machine loses power or the OS crashes, changes made since the last sync are lost.**
> You need free RAM as large as the world folder, **in addition to** the JVM heap (check with `df -h /dev/shm`).

### Commands

| Command | Description | Permission |
| --- | --- | --- |
| `/stolia status` | Show the RAM world status (paths, usage, last sync) | `stolia.command.stolia` (op) |
| `/stolia sync` | Write the RAM world to disk now | `stolia.command.stolia` (op) |
| `/stolia pregen start <radius> [world] [x z]` | Pregenerate a square of `radius` blocks around spawn (or x z) | `stolia.command.stolia` (op) |
| `/stolia budget` | Tick thread usage, heap, and each player's region load and current distances | `stolia.command.stolia` (op) |
| `/stolia region` | Busiest regions: thread usage, MSPT, TPS, players, chunks | `stolia.command.stolia` (op) |
| `/stolia pregen stop` / `resume` / `status` | Stop (progress is saved), resume after a restart, show progress | `stolia.command.stolia` (op) |

### Chunk generation speed

Pregenerating 3,721 chunks (radius 480 blocks) on a 6-core machine:

| | Time | Chunks/s |
| --- | --- | --- |
| Folia default (1 worker thread on 6 cores) | 3m 21s | 18.5 |
| Stolia `/stolia pregen` (5 worker threads) | 40s | 94 |

### Entity physics speed

2,000 chickens crammed into one block plus 2,000 spread out and 2,000 dropped items, one region:

| | MSPT |
| --- | --- |
| Folia | 750 |
| Stolia | 252 |

Verified on live data: with `-Dstolia.verifyPush=true` every push is also computed the vanilla way and
compared (500,000 checks with cramming on and off, 0 differences).

For normal play (players exploring new terrain), you can raise `chunk-system.worker-threads`
in `config/paper-global.yml` if your CPU has headroom.

## Plugin compatibility

Stolia uses the same threading model as Folia, so **only Folia-compatible plugins work**
(those with `folia-supported: true` in `plugin.yml`).
`ServerBuildInfo#isBrandCompatible(papermc:folia)` also returns `true`, so plugins that check for Folia work too.

For details on the threading model and recommended settings, see the [Folia README](./FOLIA_README.md).

## Building

```sh
./gradlew applyAllPatches
./gradlew createPaperclipJar
# → folia-server/build/libs/folia-paperclip-*.jar
```

### Making changes

- Stolia's own classes: `folia-server/src/main/java/dev/stolia/`
- Minecraft changes: commit in `folia-server/src/minecraft/java` → `./gradlew rebuildMinecraftFeaturePatches`
- Paper changes: commit in `paper-server` → `./gradlew rebuildPaperServerFeaturePatches`

## License

Patches are licensed under [PATCHES-LICENSE](./PATCHES-LICENSE).
Stolia is a derivative of [PaperMC/Folia](https://github.com/PaperMC/Folia) (and [Paper](https://github.com/PaperMC/Paper)). Thanks to the upstream developers.
