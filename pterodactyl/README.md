# Storia eggs for Pterodactyl / Pelican

[日本語](#日本語) · [English](#english)

| File | What it runs |
| --- | --- |
| [`egg-storia.json`](egg-storia.json) | Storia (the Minecraft server) |
| [`egg-storia-proxy.json`](egg-storia-proxy.json) | Storia Proxy (Velocity fork) |

## 日本語

Pterodactyl や Pelican のパネルで Storia を動かすための egg です。

### 入れ方

1. 上の JSON をダウンロードします（ファイルを開いて「Raw」から保存）。
2. パネルの管理画面で **Nests**（Pelican は **Eggs**）を開き、**Import Egg** でその JSON を選びます。
3. サーバーを作るときに、egg に「Storia」または「Storia Proxy」を選びます。

### 変数

| 変数 | 初期値 | 内容 |
| --- | --- | --- |
| `SERVER_JARFILE` | `server.jar` | 起動する jar の名前 |
| `STORIA_VERSION` | `latest` | `latest` で最新の安定版。`26.2-10` や `26.2-9-beta` のように書くと、その版を入れます |

版を変えたいときは、`STORIA_VERSION` を変えてから **再インストール（Reinstall）** してください。jar だけが入れ替わり、ワールドや設定はそのまま残ります。

### メモ

- Docker イメージは `ghcr.io/pterodactyl/yolks:java_25` です（Storia には Java 25 以上が必要です）。
- Storia は初回の起動で EULA への同意を求めます。パネルに出る確認から同意してください。
- ポートは、パネルで割り当てたものが `server.properties`（Proxy は `velocity.toml` の `bind`）に自動で書き込まれます。
- Storia Cluster の Worker と Relay は、この egg の対象外です。[Storia Cluster](../README.ja.md#storia-cluster) を見て、別に用意してください。

## English

Eggs for running Storia on a Pterodactyl or Pelican panel.

### Install

1. Download the JSON above (open the file and save it from "Raw").
2. In the admin area, open **Nests** (**Eggs** on Pelican) and use **Import Egg** with that file.
3. When creating a server, pick the "Storia" or "Storia Proxy" egg.

### Variables

| Variable | Default | Meaning |
| --- | --- | --- |
| `SERVER_JARFILE` | `server.jar` | Name of the jar to run |
| `STORIA_VERSION` | `latest` | `latest` installs the newest stable release. A version such as `26.2-10` or `26.2-9-beta` installs that one |

To change versions, edit `STORIA_VERSION` and **Reinstall** the server. Only the jar is replaced; your worlds and settings stay.

### Notes

- The Docker image is `ghcr.io/pterodactyl/yolks:java_25` (Storia needs Java 25 or newer).
- Storia asks you to accept the EULA on first start. Accept it from the prompt in the panel.
- The port assigned in the panel is written to `server.properties` (for the proxy, `bind` in `velocity.toml`) automatically.
- Storia Cluster workers and the relay are not covered by these eggs. See [Storia Cluster](../README.md#storia-cluster) and set them up separately.
