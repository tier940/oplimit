# バージョンを追加する

`1.20.1/` を雛形にコピーするのが一番早いです。**modern Forge（1.16.5 以降）向け**の手順で、
1.12.2 はビルドもローダー API も別物なので当てはまりません（既存の `1.12.2/` を参照）。

```bash
cp -r 1.20.1 1.21.1
```

コピーされるのは `build.gradle` と `src/` だけです（wrapper・`settings.gradle`・`gradle.properties`
はリポジトリ直下にあり、サブプロジェクトは持ちません）。ルートの `settings.gradle` の
`include` に追加して初めてビルド対象になります。

## 差し替える値

**mod のバージョンや名前は書き換えません。** ルートの `gradle.properties`（`modId` / `modName` /
`modVersion` / `modAuthor`）が唯一の出所で、`mods.toml` へは `processResources` で展開されます。
差し替えるのは Minecraft と Forge に紐づく値だけです。

| ファイル | 箇所 | 1.20.1 での値 |
|---|---|---|
| `build.gradle` | `mappings channel: 'official', version:` | `1.20.1` |
| | `minecraft 'net.minecraftforge:forge:'` | `1.20.1-47.4.22` |
| | `JavaLanguageVersion.of()` | `17`（1.20.5+ は 21） |
| `src/main/resources/META-INF/mods.toml` | `loaderVersion` / forge の `versionRange` | `[47,)` |
| | minecraft の `versionRange` | `[1.20.1,1.20.2)` |
| `src/main/resources/oplimitbypass.mixins.json` | `compatibilityLevel` | `JAVA_17` |
| `src/main/resources/pack.mcmeta` | `pack_format` | `15` |

## コードで確認する箇所

`common/` は触りません。バージョン固有なのは 4 ファイルだけです。

- `server/OpBypassCounter.java` — `PROFILE_READER`（プレイヤーから `GameProfile` を取る）、
  `reloadVanillaOps`（`getOps().load()`）、`setMaxPlayers`
- `mixins/minecraft/MixinPlayerListLimit.java` — **`@Redirect` / `@Inject` のターゲットが最重要**。
  `canPlayerLogin` と `getPlayerCount` は名前も引数も版によって変わります。
- `mixins/minecraft/AccessorPlayerList.java` — `maxPlayers` フィールド名
- `OpLimitBypassMod.java`、`server/OpLimitCommand.java` — Brigadier の API 差分

`@Redirect` は当たらないと起動時に失敗する設定なので、ターゲットのズレは黙って無視されず表面化します。

## refmap に注意

mixin config が宣言する refmap が生成されないと、**ビルドは成功したまま mixin だけが効かない** jar が
できます。`build.gradle` の `compileJava` にこれを検出するチェックを入れてあるので、
コピー時に落とさないでください。annotation processor（`org.spongepowered:mixin:<version>:processor`）が
依存に要ります。Forge は実行時の Mixin は同梱しますが、プロセッサは供給しません。

## 仕上げ

1. ルート `settings.gradle` の `include` に `'1.21.1'` を足す（これだけでビルド対象になります）
2. ルート `README.md` のツリーに 1 行足す
3. `.github/workflows/release.yml` の `Collect jars` と refmap 検証のループに版を足す

CI のビルドは `./gradlew build` を叩くだけなので、追加の設定は要りません。
ただし 1.20.5 以降は JDK 21 が必要で、CI は 17 を入れています。その場合は
`.github/workflows/build.yml` と `release.yml` の `java-version` を上げてください
（RetroFuturaGradle も将来 21 以上を要求する予定なので、いずれ揃います）。
