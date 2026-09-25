# Storia Relay

A small standalone program (Java 21+, no Minecraft files) that sits between Storia servers and any number of
Storia Workers and hands out terrain work.

```
Storia server --\                  /-- Storia Worker A
                 >-- Storia Relay <---- Storia Worker B
Storia server --/                  \-- Storia Worker C   (join / leave any time)
```

1. Run `./start-relay.sh` (or `start-relay.bat`) once; it creates `relay.properties`. Set `secret=` (8+ characters).
2. Start it again. Type `status` for workers, servers and counters, `stop` to quit.
3. Storia servers: `offload.mode: client`, `offload.workers: ["relay-host:25590"]`.
4. Storia Workers: `offload.mode: worker`, `offload.relay: "relay-host:25590"`.

Each request goes to the least busy worker whose terrain matches the requesting server exactly. If a worker
disconnects, its requests are retried on another worker, or returned so the server generates them itself.
All connections are encrypted and authenticated with the shared secret.

---

Storia サーバーと複数の Storia Worker の間で、地形生成の仕事を配る小さなプログラムです（Java 21 以上、Minecraft
のファイルは不要）。ワーカーはいつでも追加・削除できます。初回起動で `relay.properties` ができるので、
`secret=` を設定してからもう一度起動してください。コンソールで `status` と入力すると状態が見られます。
