# Storia Worker

A Storia Worker is one server of a **Storia Cluster**: several servers run one world together, each the part
where its players are, and players move between them through Storia Proxy without a loading screen. Add a
worker when your players outgrow one machine.

Storia Worker は **Storia Cluster** のサーバー 1 台分です。複数のサーバーで 1 つのワールドを分担して動かし、
プレイヤーは Storia Proxy を通して読み込み画面なしでサーバー間を移動します。1 台で足りなくなったら、ワーカーを
足してください。

```
players --> Storia Proxy --> Storia Worker A --\
                         \-> Storia Worker B ---> Storia Relay: the world, who runs what
                          \-> Storia Worker C --/
```

## Setup / セットアップ

1. **EULA**: read https://aka.ms/MinecraftEULA and, if you agree, create `eula.txt` containing `eula=true`.
2. **`storia.yml`**: set `cluster.coordinator` to your Storia Relay, a unique `cluster.node-name` and the
   relay's `cluster.secret`. No world copy is needed: on first start the worker fetches the world settings
   (level.dat, world generation settings, data packs) from the relay.
   `cluster.coordinator` に Storia Relay の住所、ワーカーごとに違う `cluster.node-name`、Relay と同じ
   `cluster.secret` を設定します。ワールドのコピーは要りません。初回起動時に Relay から取り寄せます。
3. **Proxy forwarding / プロキシの転送**: start once, then in `config/paper-global.yml` set
   `proxies.velocity.enabled: true` and `proxies.velocity.secret` to the proxy's `forwarding.secret`.
   一度起動したあと、`config/paper-global.yml` の `proxies.velocity.enabled` を `true` に、`secret` を
   プロキシの `forwarding.secret` にします。
4. **Start / 起動**: `./start-worker.sh` (Linux/macOS) or `start-worker.bat` (Windows). Java 25 is required.
   Memory: `WORKER_MEMORY=8G ./start-worker.sh`. A worker needs about the CPU and RAM of a normal Storia server.
5. **Storia Proxy**: add the worker to `velocity.toml` under its node name
   (`worker-1 = "worker-host:25565"`) and add it to `try`.

`/storia cluster` shows what this worker runs; `status` in the relay console shows the whole cluster.
Full guide / 詳しいガイド: https://storiamc.com/en-us/docs/cluster/ ・ https://storiamc.com/ja-jp/docs/cluster/
