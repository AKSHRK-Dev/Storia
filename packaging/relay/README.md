# Storia Relay

The coordinator of a **Storia Cluster** (Java 21+, no Minecraft server needed). It stores the shared world,
decides which Storia Worker runs which part of it, balances players across workers and tells Storia Proxy when
to move a player.

```
players --> Storia Proxy --> Storia Worker A --\
                         \-> Storia Worker B ---> Storia Relay: the world, who runs what
                          \-> Storia Worker C --/
```

1. Run `./start-relay.sh` (or `start-relay.bat`) once; it creates `relay.properties`. Set `secret=` (8+ characters).
2. Put your world in `cluster-world/` (a normal world folder). From now on only the relay writes to it.
3. Start it again. Type `status` for workers, players and counters, `stop` to quit.
4. Workers: `cluster.coordinator: "relay-host:25590"` and the same `cluster.secret` in `storia.yml`.
5. Storia Proxy: `[cluster]` with the same coordinator and secret in `storia-proxy.toml`.

Standby relay: run a second relay with `role=standby` and `peer=<this relay>`, set `peer=<the standby>` here,
and list both relays in the workers' and Storia Proxy's `coordinator` ("relay-a:25590,relay-b:25590"). If this relay
is lost, type `promote` on the standby. Guide: https://storiamc.com/en-us/docs/relay/#standby

All connections are encrypted and authenticated with the shared secret. Full guide:
https://storiamc.com/en-us/docs/cluster/

---

**Storia Cluster** のまとめ役です（Java 21 以上。Minecraft サーバーは不要）。共有するワールドを保管し、
どの Storia Worker がどこを動かすかを決め、プレイヤーをワーカー間で均等にし、移動のタイミングを Storia Proxy
に伝えます。初回起動で `relay.properties` ができるので、`secret=` を設定し、ワールドを `cluster-world/` に置いて
からもう一度起動してください。コンソールで `status` と入力すると状態が見られます。
詳しくは https://storiamc.com/ja-jp/docs/cluster/ を見てください。
