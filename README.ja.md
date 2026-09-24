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
```

> [!WARNING]
> **停電や OS のクラッシュが起きると、最後の同期から後の変更は失われます。**
> ワールドのサイズと同じ量の空き RAM が、JVM のヒープとは**別に**必要です（`df -h /dev/shm` で確認できます）。

### コマンド

| コマンド | 説明 | 権限 |
| --- | --- | --- |
| `/stolia status` | RAM ワールドの状態（場所・使用量・最後に同期した時刻）を表示 | `stolia.command.stolia`（OP） |
| `/stolia sync` | 今すぐ RAM ワールドをディスクへ書き戻す | `stolia.command.stolia`（OP） |

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
