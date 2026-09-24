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
```

> [!WARNING]
> **If the machine loses power or the OS crashes, changes made since the last sync are lost.**
> You need free RAM as large as the world folder, **in addition to** the JVM heap (check with `df -h /dev/shm`).

### Commands

| Command | Description | Permission |
| --- | --- | --- |
| `/stolia status` | Show the RAM world status (paths, usage, last sync) | `stolia.command.stolia` (op) |
| `/stolia sync` | Write the RAM world to disk now | `stolia.command.stolia` (op) |

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
