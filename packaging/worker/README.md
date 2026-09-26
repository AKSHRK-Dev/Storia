# Storia Worker

Storia Worker computes the heaviest part of terrain generation (terrain noise: shape, caves, aquifers) for a
Storia server on another machine. It opens no player port and never changes your world.

## Setup / セットアップ

1. **Same world settings / 同じワールド設定**: copy your Storia server's world folder into a `world/` folder next to
   `storia.jar`, **without** the chunk folders `region`, `entities` and `poi` (the seed is in
   `dimensions/minecraft/overworld/data/`, so `level.dat` alone is not enough):
   `rsync -a --exclude region --exclude entities --exclude poi server:/path/to/world/ world/`
   メインの Storia サーバーのワールドフォルダを、`region`・`entities`・`poi` フォルダを**除いて** `world/` にコピーします
   （シードは `dimensions/minecraft/overworld/data/` にあるので、`level.dat` だけでは足りません）。
2. **EULA**: read https://aka.ms/MinecraftEULA and, if you agree, create `eula.txt` containing `eula=true`.
3. **Secret / 合言葉**: set `offload.secret` in `storia.yml` (at least 8 characters, the same on every machine).
4. **Start / 起動**: `./start-worker.sh` (Linux/macOS) or `start-worker.bat` (Windows). Java 25 is required.
   Memory: `WORKER_MEMORY=6G ./start-worker.sh`.
5. **On the Storia server / メインサーバー側** (`storia.yml`):
   ```yaml
   offload:
     mode: client
     secret: "the same secret"
     workers: ["worker-host:25590"]   # or your relay's address
   ```

When the server connects, both sides generate the same probe chunks. If your seed or datapacks differ, the
worker refuses that dimension instead of producing different terrain. `/storia offload` on the server shows
the connection and statistics. Traffic is encrypted and authenticated with the secret.

### Using a Storia Relay / Relay を使う場合

Set `offload.relay: "relay-host:25590"` here and point the server's `offload.workers` at the relay. Workers can
then join and leave at any time.
