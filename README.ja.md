<div align=center>
    <img src="./storia.png" alt="Storia logo" width="128">
    <h1>Storia</h1>
    <p><a href="https://github.com/PaperMC/Folia">Folia</a> をベースにした、大人数向けの高効率 Minecraft サーバーソフトウェア</p>
    <p><a href="./README.md">English</a> | <b>日本語</b></p>
</div>

## 特徴

- **リージョン単位のマルチスレッド（Folia 由来）**
  近くにあるチャンクを「リージョン」にまとめ、リージョンごとに並列で tick します。
  プレイヤーが広く散らばる大人数サーバー（SMP・スカイブロックなど）でよく伸びます。
- **RAM ワールド（Storia 独自）**
  起動時にワールドを RAM（`/dev/shm`）へ読み込み、サーバーは RAM 上のワールドを読み書きします。
  ディスク I/O がボトルネックにならなくなります。
  - 一定間隔で、変わったファイルだけをバックグラウンドでディスクへ書き戻します
  - 一時ファイルに書いてから置き換えるので、ディスク上のワールドが書きかけの状態で残りません
  - `stop` のときは全部ディスクへ書き戻します
  - プロセスが強制終了しても、マシンが動いていれば RAM 上のデータは残ります。次の起動時にそれを検知してディスクへ復旧します
  - RAM が足りないときは、自動で通常どおりディスクから起動します
- **高速なチャンク事前生成（Storia 独自）**
  `/storia pregen` で、プレイヤーが行く前にチャンクを生成しておけます。
  生成中だけワーカースレッドを「コア数−1」本に増やし（Folia のデフォルトはコア数の約1/4）、終わったら元に戻します。
  進み具合は保存されるので、再起動しても続きから再開できます。
- **Tick Guard（Storia 独自）**
  初期地点のような混み合った場所でも、そこにいる人は快適なままです。リージョンの処理時間が目標（40 ms）を超えると、
  密集したチャンク（16 体以上）のモブは判断を 2・4・8 ティックに 1 回に減らします。移動・経路・当たり判定・落下・水流は
  毎ティックそのままです。ブロック・レッドストーン・ホッパーには一切触れず、プレイヤーの近くのモブ、プレイヤーと戦っている
  モブ、ペット、ボスは常に毎ティック判断します。モブ 1,400 体の初期地点のテストでは、1 ティック 50〜55 ms が 32〜35 ms
  （TPS 20）になり、近くに立っているプレイヤーは一切制限されませんでした。`/storia region` で密集チャンクがわかります。
- **1人ごとの予算（Storia 独自）**
  制限するのは、自分で負荷を増やしている人だけです。リージョンが重いときに、毎秒 12 ブロックより速く移動している人
  （エリトラなど）の描画距離を、速度が落ちるまで短くします。混み合った場所で立っている・建築している・歩いているだけの人は
  一切制限しません。**デフォルトではシミュレーション距離に触らないので、回路やファームは止まりません。**
  GC 後のヒープがいっぱいに近づいたときは、全員の描画距離を下げます。
- **エンティティの物理演算の高速化（Storia 独自）**
  モブが密集したときの押し合い判定が約3倍速くなります。押す相手・順番・窒息ダメージ（乱数を含む）は
  バニラと完全に同じです。レッドストーンには手を加えていません。
- **地形生成を別のマシンに任せる（Storia 独自）**
  2台目のマシンで Storia を「ワーカー」として動かすと、メインサーバーは地形生成で一番重い部分
  （ノイズ段階：地形の形・洞窟・帯水層）をそちらに任せます。結果は自分で生成した場合と同じです。
  ワーカーが混んでいる・遅い・落ちているときは、自動でメインサーバーが自分で生成します。
  [地形生成を別のマシンに任せる](#地形生成を別のマシンに任せる) を参照してください。
- **ワールド生成の高速化（Storia 独自）**
  ノイズ計算とブロック数の数え方を最適化しています。同じシードなら**地形はバニラと完全に同じ**です
  （ノイズの変更は、元のコードと出力がビット単位で一致することを確認済み）。

## 使い方

Java 25 以上が必要です。

```sh
java -Xmx8G -jar storia-26.2.jar nogui
```

[Releases](../../releases/latest) からダウンロードできます：

| ファイル | 内容 |
| --- | --- |
| `storia-<version>.jar` | Storia サーバー本体 |
| `storia-worker-<version>.zip` | [Storia Worker](packaging/worker/README.md)：別のマシンで地形生成を手伝う |
| `storia-relay-<version>.zip` | [Storia Relay](packaging/relay/README.md)：複数のワーカーに仕事を配る |
| `storia-proxy-<version>.jar` | [Storia Proxy](https://github.com/AKSHRK-Dev/StoriaProxy)：プレースホルダー 50 個入りの Velocity フォーク |

バージョンは Minecraft に合わせています。`26.2` は Minecraft 26.2 向けの最初のリリースで、同じ Minecraft バージョンの
2 回目以降は `26.2-2`、`26.2-3` … となります。サーバー・ワーカー・リレーは同じバージョンにそろえてください。

開発版は [Actions](../../actions) の成果物（`storia-jar`）からもダウンロードできます。

### 設定（`storia.yml`）

初回起動時にサーバーのフォルダに作られます。

```yaml
ram-world:
  enabled: true
  ram-directory: /dev/shm/storia
  sync-interval-seconds: 300   # 短くするほど安全、長くするほど効率的
  min-free-mb: 512             # RAM の空きがこれを下回りそうならディスクで起動
  delete-on-shutdown: true     # 正常終了時に RAM 上のコピーを削除する
pregen:
  worker-threads: -1           # -1 = 事前生成中は「コア数−1」本
  max-in-flight: -1            # -1 = worker-threads × 16 チャンクを同時に処理
tick-guard:
  enabled: true
  target-mspt: 40.0            # 各リージョンをこの値より下に保つ（1 ティックは 50 ms）
  crowd-threshold: 16          # 1 チャンクにこの数以上のモブがいると密集
  player-radius: 8.0           # プレイヤーからこの距離以内のモブは毎ティック判断する
  max-level: 3                 # 8 ティックに 1 回まで間引く
player-budget:
  enabled: true
  check-interval-ticks: 100    # 各プレイヤーをチェックする間隔
  lower-simulation-distance: false  # true = シミュレーション距離も下げる（遠くの装置は止まる）
  max-region-mspt: 45.0        # tick がこれより遅いリージョンは予算オーバー
  pool-saturated-percent: 85   # tick スレッドがこれ以上忙しいときは、取り分を守らせる
  recover-below-percent: 70    # 上限の 70% を下回ったら元に戻す
  min-simulation-distance: 4
  min-view-distance: 6
  memory-high-percent: 85      # GC 後のヒープがこれを超えたら全員の描画距離を下げる
  memory-low-percent: 70
  fast-mover-speed: 12.0       # これより速く（毎秒のブロック数）移動している人だけ制限する
```

> [!WARNING]
> **停電や OS のクラッシュが起きると、最後の同期から後の変更は失われます。**
> ワールドのサイズと同じ量の空き RAM が、JVM のヒープとは**別に**必要です（`df -h /dev/shm` で確認できます）。

### コマンド

| コマンド | 説明 | 権限 |
| --- | --- | --- |
| `/storia status` | RAM ワールドの状態（場所・使用量・最後に同期した時刻）を表示 | `storia.command.storia`（OP） |
| `/storia sync` | 今すぐ RAM ワールドをディスクへ書き戻す | `storia.command.storia`（OP） |
| `/storia pregen start <半径> [ワールド] [x z]` | スポーン（または x z）を中心に、半径（ブロック）の正方形を事前生成 | `storia.command.storia`（OP） |
| `/storia budget` | tick スレッドの使用率・ヒープ・各プレイヤーのリージョンの負荷・速度・今の距離 | `storia.command.storia`（OP） |
| `/storia region` | 重いリージョン一覧（スレッド使用率・MSPT・TPS・人数・チャンク数・Tick Guard の状態・密集チャンク） | `storia.command.storia`（OP） |
| `/storia offload` | ワーカーとの接続・任せたチャンク数・自分で生成した理由 | `storia.command.storia`（OP） |
| `/storia pregen stop` / `resume` / `status` | 停止（進み具合は保存）／再起動後に再開／進み具合を表示 | `storia.command.storia`（OP） |

### チャンク生成の速さ

6コアのマシンで 3,721 チャンク（半径 480 ブロック）を事前生成した結果：

| | 時間 | チャンク/秒 |
| --- | --- | --- |
| Folia のデフォルト（6コアでワーカー1本） | 3分21秒 | 18.5 |
| Storia の `/storia pregen`（ワーカー5本） | 40秒 | 94 |

### エンティティの物理演算の速さ

1マスに詰め込んだニワトリ 2,000 羽＋散らばったニワトリ 2,000 羽＋落ちているアイテム 2,000 個（1リージョン）：

| | MSPT |
| --- | --- |
| Folia | 750 |
| Storia | 252 |

実際のデータで検証済み：`-Dstoria.verifyPush=true` で起動すると、押し合いのたびにバニラの方法でも計算して比べます
（窒息ダメージあり・なしで計 50 万回、差分 0）。

通常のプレイ中（プレイヤーが新しい場所を探索するとき）も、CPU に余裕があれば
`config/paper-global.yml` の `chunk-system.worker-threads` を増やすと速くなります。

## 地形生成を別のマシンに任せる

```
サーバー①（プレイヤー・ワールド） ── ノイズの依頼 ──▶ サーバー②（ワーカー）
  構造物・装飾・光                ◀── 地形のブロック ──   地形ノイズの計算
```

1. サーバー②で、**同じワールドのコピー**（同じシード・データパック）を使って Storia を起動し、`storia.yml` を次のようにします：
   ```yaml
   offload:
     mode: worker
     secret: 長くてランダムな文字列
     port: 25590
     threads: -1        # -1 = 全コア
   ```
2. サーバー①：
   ```yaml
   offload:
     mode: client
     secret: 長くてランダムな文字列
     workers: ["192.168.0.20:25590"]   # 複数でも OK
   ```
3. `/storia offload` で、接続状態・任せたチャンク数・自分で生成した理由を確認できます。

- 接続時に両方のサーバーで同じ試験用チャンクを生成し、結果が完全に一致したディメンションだけを任せます。
  シード・データパック・ビルドが違う場合は、違う地形を作る代わりに接続を断ります。
- 任せるのはノイズ段階だけです。構造物・装飾（木・鉱石）・光・tick するものはすべてサーバー①に残ります。
  隣のチャンクに依存するか、50ms の tick 内に終わらせる必要があるためです。
- 通信は**暗号化・認証されています**（合言葉から作った鍵で AES-256-GCM。合言葉そのものは送りません）。
  合言葉が違う相手は、最初のメッセージで接続を断ります。
- ワーカーは Releases の **Storia Worker** パックを使うのが簡単です（`-Dstoria.worker=true`：プレイヤー用のポートを
  開かず、計算だけをします）。間に **Storia Relay** を置くと、ワーカーは Relay につなぎに行くので、いつでも追加・削除
  できます。サーバー側は `offload.workers` に Relay のアドレスを書くだけです。
- サーバー①を `-Dstoria.verifyOffload=true` で起動すると、任せたチャンクを自分でも生成して比べます。

サーバー①に3コア、ワーカーに別の3コアを割り当てて 3,721 チャンクを事前生成した結果：

| | 時間 |
| --- | --- |
| サーバー①だけ | 1分35秒 |
| サーバー①＋ワーカー | 1分6秒（ノイズ段階の 95% を任せた。検証した 888 チャンクの差分は 0） |

途中でワーカーを強制終了しても、サーバー①が最後まで生成しました（処理中だった 12 件は自分で生成し直し）。

## プラグインの互換性

Storia は Folia と同じスレッドモデルなので、**Folia 対応のプラグインだけが動きます**
（`plugin.yml` に `folia-supported: true` があるもの）。
`ServerBuildInfo#isBrandCompatible(papermc:folia)` も `true` を返すので、Folia かどうかを確認するプラグインでも動きます。

スレッドモデルやおすすめの設定については、[Folia の README](./FOLIA_README.md) を参照してください。

## ビルド方法

```sh
./gradlew applyAllPatches
./gradlew createPaperclipJar
# → folia-server/build/libs/folia-paperclip-*.jar
```

### 変更の追加

- Storia 独自のクラス: `folia-server/src/main/java/dev/storia/`
- Minecraft 側の変更: `folia-server/src/minecraft/java` でコミット → `./gradlew rebuildMinecraftFeaturePatches`
- Paper 側の変更: `paper-server` でコミット → `./gradlew rebuildPaperServerFeaturePatches`

## ライセンス

パッチは [PATCHES-LICENSE](./PATCHES-LICENSE) に従います。
Storia は [PaperMC/Folia](https://github.com/PaperMC/Folia)（および [Paper](https://github.com/PaperMC/Paper)）の派生プロジェクトです。上流の開発者に感謝します。
