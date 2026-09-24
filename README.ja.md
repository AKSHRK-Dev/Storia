<div align=center>
    <img src="./stolia.png" alt="Stolia logo" width="128">
    <h1>Stolia</h1>
    <p><a href="https://github.com/PaperMC/Folia">Folia</a> をベースにした、大人数向けの高効率 Minecraft サーバーソフトウェア</p>
    <p><a href="./README.md">English</a> | <b>日本語</b></p>
</div>

## 特徴

- **リージョン単位のマルチスレッド（Folia 由来）**
  近くにあるチャンクを「リージョン」にまとめ、リージョンごとに並列で tick します。
  プレイヤーが広く散らばる大人数サーバー（SMP・スカイブロックなど）でよく伸びます。
- **RAM ワールド（Stolia 独自）**
  起動時にワールドを RAM（`/dev/shm`）へ読み込み、サーバーは RAM 上のワールドを読み書きします。
  ディスク I/O がボトルネックにならなくなります。
  - 一定間隔で、変わったファイルだけをバックグラウンドでディスクへ書き戻します
  - 一時ファイルに書いてから置き換えるので、ディスク上のワールドが書きかけの状態で残りません
  - `stop` のときは全部ディスクへ書き戻します
  - プロセスが強制終了しても、マシンが動いていれば RAM 上のデータは残ります。次の起動時にそれを検知してディスクへ復旧します
  - RAM が足りないときは、自動で通常どおりディスクから起動します
- **高速なチャンク事前生成（Stolia 独自）**
  `/stolia pregen` で、プレイヤーが行く前にチャンクを生成しておけます。
  生成中だけワーカースレッドを「コア数−1」本に増やし（Folia のデフォルトはコア数の約1/4）、終わったら元に戻します。
  進み具合は保存されるので、再起動しても続きから再開できます。
- **ワールド生成の高速化（Stolia 独自）**
  ノイズ計算とブロック数の数え方を最適化しています。同じシードなら**地形はバニラと完全に同じ**です
  （ノイズの変更は、元のコードと出力がビット単位で一致することを確認済み）。

## 使い方

Java 25 以上が必要です。

```sh
java -Xmx8G -jar stolia-26.2.jar nogui
```

最新のビルドは [Actions](../../actions) の成果物（`stolia-jar`）からダウンロードできます。

### 設定（`stolia.yml`）

初回起動時にサーバーのフォルダに作られます。

```yaml
ram-world:
  enabled: true
  ram-directory: /dev/shm/stolia
  sync-interval-seconds: 300   # 短くするほど安全、長くするほど効率的
  min-free-mb: 512             # RAM の空きがこれを下回りそうならディスクで起動
  delete-on-shutdown: true     # 正常終了時に RAM 上のコピーを削除する
pregen:
  worker-threads: -1           # -1 = 事前生成中は「コア数−1」本
  max-in-flight: -1            # -1 = worker-threads × 16 チャンクを同時に処理
```

> [!WARNING]
> **停電や OS のクラッシュが起きると、最後の同期から後の変更は失われます。**
> ワールドのサイズと同じ量の空き RAM が、JVM のヒープとは**別に**必要です（`df -h /dev/shm` で確認できます）。

### コマンド

| コマンド | 説明 | 権限 |
| --- | --- | --- |
| `/stolia status` | RAM ワールドの状態（場所・使用量・最後に同期した時刻）を表示 | `stolia.command.stolia`（OP） |
| `/stolia sync` | 今すぐ RAM ワールドをディスクへ書き戻す | `stolia.command.stolia`（OP） |
| `/stolia pregen start <半径> [ワールド] [x z]` | スポーン（または x z）を中心に、半径（ブロック）の正方形を事前生成 | `stolia.command.stolia`（OP） |
| `/stolia pregen stop` / `resume` / `status` | 停止（進み具合は保存）／再起動後に再開／進み具合を表示 | `stolia.command.stolia`（OP） |

### チャンク生成の速さ

6コアのマシンで 3,721 チャンク（半径 480 ブロック）を事前生成した結果：

| | 時間 | チャンク/秒 |
| --- | --- | --- |
| Folia のデフォルト（6コアでワーカー1本） | 3分21秒 | 18.5 |
| Stolia の `/stolia pregen`（ワーカー5本） | 40秒 | 94 |

通常のプレイ中（プレイヤーが新しい場所を探索するとき）も、CPU に余裕があれば
`config/paper-global.yml` の `chunk-system.worker-threads` を増やすと速くなります。

## プラグインの互換性

Stolia は Folia と同じスレッドモデルなので、**Folia 対応のプラグインだけが動きます**
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

- Stolia 独自のクラス: `folia-server/src/main/java/dev/stolia/`
- Minecraft 側の変更: `folia-server/src/minecraft/java` でコミット → `./gradlew rebuildMinecraftFeaturePatches`
- Paper 側の変更: `paper-server` でコミット → `./gradlew rebuildPaperServerFeaturePatches`

## ライセンス

パッチは [PATCHES-LICENSE](./PATCHES-LICENSE) に従います。
Stolia は [PaperMC/Folia](https://github.com/PaperMC/Folia)（および [Paper](https://github.com/PaperMC/Paper)）の派生プロジェクトです。上流の開発者に感謝します。
