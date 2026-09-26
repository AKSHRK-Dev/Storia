<div align=center>
    <img src="./storia.png" alt="Storia logo" width="128">
    <h1>Storia</h1>
    <p>A high-efficiency Minecraft server for large player counts, based on <a href="https://github.com/PaperMC/Folia">Folia</a></p>
    <p><b>English</b> | <a href="./README.ja.md">日本語</a></p>
</div>

## Features

- **Regionised multithreading (from Folia)**
  Nearby chunks are grouped into independent "regions" that tick in parallel.
  This scales well on large servers where players spread out (SMP, skyblock, etc.).
- **RAM world (Storia)**
  On startup the world is loaded into RAM (`/dev/shm`) and the server reads and writes it there,
  so disk I/O stops being a bottleneck.
  - Only changed files are written back to disk in the background, at a fixed interval
  - Files are written to a temp file and then atomically renamed, so the on-disk world is never half-written
  - `stop` writes everything to disk
  - If the process is killed, the RAM copy survives as long as the machine stays up; it is detected and recovered to disk on the next start
  - If there is not enough RAM, the server falls back to loading from disk as usual
- **Fast chunk pregeneration (Storia)**
  `/storia pregen` generates chunks ahead of time so players never wait for terrain.
  While it runs, the chunk worker pool is raised to all cores but one (Folia's default is only
  about a quarter of the cores), then restored. Progress survives restarts.
- **Tick guard (Storia)**
  A crowded spot such as spawn stays smooth for the people in it. When a region's tick time passes its target
  (40 ms), mobs in crowded chunks (16+ mobs) re-plan only every 2nd, 4th or 8th tick; they still move, path,
  collide, fall and ride water every tick. Blocks, redstone and hoppers are never touched, and mobs near a player,
  fighting a player, pets and bosses always think every tick. On a test spawn with 1,400 mobs the region went from
  50-55 ms to 32-35 ms per tick (TPS 20) and the players standing nearby were not limited at all.
  `/storia region` lists the crowded chunks so you can find the farm.
- **Per-player budget (Storia)**
  Only players who add load themselves are limited: when their region is over budget, players moving faster than
  12 blocks/s (elytra, ...) get a shorter view distance until they slow down. Players who stand, build or walk in
  a busy place are never limited. **Simulation distance is not touched by default, so redstone and farms keep
  running.** When the heap is nearly full after GC, view distance is lowered for everyone.
- **Faster entity physics (Storia)**
  Entity pushing (the cost of mobs crammed together) is about 3x faster with identical results:
  the same entities are pushed in the same order, and cramming damage and its random roll are unchanged.
  Redstone is not modified.
- **Terrain generation on other machines (Storia)**
  Run Storia on a second machine as an offload *worker* and the main server sends it the heaviest part of
  terrain generation (the noise step: terrain shape, caves, aquifers). Results are identical to local
  generation, and the main server falls back to generating locally whenever a worker is busy, slow or down.
  See [Offloading terrain generation](#offloading-terrain-generation).
- **Faster world generation (Storia)**
  Noise sampling and block counting are optimized. Terrain is **identical to vanilla** for the same seed
  (the noise changes are verified bit-for-bit against the original code).

## Usage

Requires Java 25 or newer.

```sh
java -Xmx8G -jar storia-26.2.jar nogui
```

Download from [Releases](../../releases/latest):

| File | What it is |
| --- | --- |
| `storia-<version>.jar` | The Storia server |
| `storia-worker-<version>.zip` | [Storia Worker](packaging/worker/README.md): generates terrain for your server on another machine |
| `storia-relay-<version>.zip` | [Storia Relay](packaging/relay/README.md): hands terrain work to any number of workers |
| `storia-proxy-<version>.jar` | [Storia Proxy](https://github.com/AKSHRK-Dev/StoriaProxy): Velocity fork with 50 built-in placeholders |

Versions follow Minecraft: `26.2` is the first Storia release for Minecraft 26.2, and later builds for the same
Minecraft version are `26.2-2`, `26.2-3`, and so on. Use the same version on the server, workers and relay.

Development builds are also available as the `storia-jar` artifact in [Actions](../../actions).

### Configuration (`storia.yml`)

Created in the server folder on first start.

```yaml
ram-world:
  enabled: true
  ram-directory: /dev/shm/storia
  sync-interval-seconds: 300   # shorter is safer, longer is more efficient
  min-free-mb: 512             # load from disk if RAM would drop below this
  delete-on-shutdown: true     # delete the RAM copy after a clean shutdown
pregen:
  worker-threads: -1           # -1 = CPU cores - 1 while pregenerating
  max-in-flight: -1            # -1 = worker-threads * 16 chunks queued at once
tick-guard:
  enabled: true
  target-mspt: 40.0            # keep each region below this (a tick has 50 ms)
  crowd-threshold: 16          # mobs per chunk that count as a crowd
  player-radius: 8.0           # mobs this close to a player always think every tick
  max-level: 3                 # thin out down to every 8th tick
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
  fast-mover-speed: 12.0       # only players faster than this (blocks/s) are limited
```

> [!WARNING]
> **If the machine loses power or the OS crashes, changes made since the last sync are lost.**
> You need free RAM as large as the world folder, **in addition to** the JVM heap (check with `df -h /dev/shm`).

### Commands

| Command | Description | Permission |
| --- | --- | --- |
| `/storia status` | Show the RAM world status (paths, usage, last sync) | `storia.command.storia` (op) |
| `/storia sync` | Write the RAM world to disk now | `storia.command.storia` (op) |
| `/storia pregen start <radius> [world] [x z]` | Pregenerate a square of `radius` blocks around spawn (or x z) | `storia.command.storia` (op) |
| `/storia budget` | Tick thread usage, heap, and each player's region load, speed and current distances | `storia.command.storia` (op) |
| `/storia region` | Busiest regions: thread usage, MSPT, TPS, players, chunks, tick guard state and crowded chunks | `storia.command.storia` (op) |
| `/storia offload` | Offload connections, chunks offloaded, reasons chunks were generated locally | `storia.command.storia` (op) |
| `/storia pregen stop` / `resume` / `status` | Stop (progress is saved), resume after a restart, show progress | `storia.command.storia` (op) |

### Chunk generation speed

Pregenerating 3,721 chunks (radius 480 blocks) on a 6-core machine:

| | Time | Chunks/s |
| --- | --- | --- |
| Folia default (1 worker thread on 6 cores) | 3m 21s | 18.5 |
| Storia `/storia pregen` (5 worker threads) | 40s | 94 |

### Entity physics speed

2,000 chickens crammed into one block plus 2,000 spread out and 2,000 dropped items, one region:

| | MSPT |
| --- | --- |
| Folia | 750 |
| Storia | 252 |

Verified on live data: with `-Dstoria.verifyPush=true` every push is also computed the vanilla way and
compared (500,000 checks with cramming on and off, 0 differences).

For normal play (players exploring new terrain), you can raise `chunk-system.worker-threads`
in `config/paper-global.yml` if your CPU has headroom.

## Offloading terrain generation

```
server 1 (players, worlds)  --- noise requests --->  server 2 (offload worker)
  structures, features, light <--- terrain blocks ---   terrain noise
```

1. On server 2, run Storia with **a copy of the same world** (same seed and datapacks), and in `storia.yml`:
   ```yaml
   offload:
     mode: worker
     secret: some-long-random-string
     port: 25590
     threads: -1        # -1 = all cores
   ```
2. On server 1:
   ```yaml
   offload:
     mode: client
     secret: some-long-random-string
     workers: ["192.168.0.20:25590"]   # several workers are fine
   ```
3. `/storia offload` shows the connection, how many chunks were offloaded and why others were generated locally.

- On connect, both servers generate the same probe chunks; a dimension is only offloaded if the results match
  exactly, so a different seed, datapack or build is refused rather than producing different terrain.
- Only the noise step moves. Structures, features (trees, ores), lighting and everything that ticks stay on
  server 1, because they depend on neighbouring chunks or must finish within a 50 ms tick.
- The link is **encrypted and authenticated** (AES-256-GCM with keys derived from the shared secret; the secret
  itself is never sent). A peer with a different secret is refused on its first message.
- The easiest way to run a worker is the **Storia Worker** package from Releases (`-Dstoria.worker=true`: no player
  port, compute only). With a **Storia Relay** in the middle, workers connect to the relay and can join or leave at
  any time; servers just point `offload.workers` at the relay.
- `-Dstoria.verifyOffload=true` on server 1 also generates every offloaded chunk locally and compares them.

Pregenerating 3,721 chunks with server 1 on 3 cores and a worker on 3 other cores:

| | Time |
| --- | --- |
| Server 1 alone | 1m 35s |
| Server 1 + worker | 1m 6s (95% of noise steps offloaded; 0 of 888 verified chunks differed) |

If the worker is killed in the middle, server 1 finishes on its own (the 12 requests in flight were regenerated locally).

## Plugin compatibility

Storia uses the same threading model as Folia, so **only Folia-compatible plugins work**
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

- Storia's own classes: `folia-server/src/main/java/dev/storia/`
- Minecraft changes: commit in `folia-server/src/minecraft/java` → `./gradlew rebuildMinecraftFeaturePatches`
- Paper changes: commit in `paper-server` → `./gradlew rebuildPaperServerFeaturePatches`

## License

Patches are licensed under [PATCHES-LICENSE](./PATCHES-LICENSE).
Storia is a derivative of [PaperMC/Folia](https://github.com/PaperMC/Folia) (and [Paper](https://github.com/PaperMC/Paper)). Thanks to the upstream developers.
