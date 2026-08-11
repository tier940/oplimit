# OpLimitBypass

`bypassesPlayerLimit` が付いた op を **max-players のカウント対象から除外**する Mixin mod です。
ホワイトリスト運用のサーバで、管理者が枠を食わないようにするのが目的。

```
settings.gradle   ルート。1.12.2 と 1.20.1 をサブプロジェクトとして include
gradlew           wrapper はここだけ(Gradle 8.14.5)
1.12.2/           Forge 1.12.2 + MixinBooter(RetroFuturaGradle)  build.gradle のみ
1.20.1/           Forge 1.20.1 + Mixin(ForgeGradle 6)            build.gradle のみ
common/           両方が srcDir で取り込む共有ソース(単体ではビルドしない)
scripts/          check-common.sh: common/ の制約を機械的に検査
docs/             adding-a-version.md: バージョン追加手順
```

**単一の Gradle マルチプロジェクトビルドです。** ローダーもマッピングも全く違う 2 つですが、どちらも Gradle 8.14.5 / JDK 17 で動くので 1 つのビルドに同居できます。
ops.json の読み書きなど MC に依存しないロジックは `common/` に集約していて、**両方から必要とされます**。
制約は [common/README.md](common/README.md) を参照。

---

## 1. 何をするか

バニラの `bypassesPlayerLimit=true` は「満員でも入れる」だけで、**入った後は枠を 1 つ消費します**。
max-players=20 で bypass op が 3 人いると、一般プレイヤーは 17 人しか入れません。
このフラグは 1.8 で追加されたものなので、1.7.10 には対応していません。

この mod はログイン判定内の `players.size()` を `@Redirect` で差し替えます。

```java
if (接続しようとしている人が bypass op) return Integer.MIN_VALUE;  // 定員判定を必ず通す
return オンラインのうち bypass op でない人数;                        // op は枠を消費しない
```

結果、max-players=20 なら **一般 20 人 + bypass op 3 人 = 23 人**が同時接続できます。
ホワイトリスト判定・BAN 判定はバニラのままで、一切触っていません。

| | 対象クラス | ログイン判定 | 表示人数 |
|---|---|---|---|
| 1.12.2 | `net.minecraft.server.management.PlayerList` | `allowUserToConnect` | `getCurrentPlayerCount` |
| 1.20.1 | `net.minecraft.server.players.PlayerList` | `canPlayerLogin` | `getPlayerCount` |

### 表示人数

表示人数のメソッドも同じ数え方に揃えてあるので、上の例は `23/20` ではなく `20/20` と表示されます。
サーバーリストの ping、query プロトコル、専用サーバの GUI、1.12.2 の `/list` が影響を受けます。

副作用が 2 点あります。

- bypass op **だけ**がオンラインのとき、表示人数は 0 になります。
  「人数が 0 ならサーバを休止する」タイプの mod と併用する場合は影響が出ます。
- 1.12.2 の `/list` は人数だけこの値を使い、名前は全員分を出すので、表示と名前の数が食い違います。

真の接続人数は `/oplimit status` がいつでも出します(`online` が実数 `counted` が枠を消費する人数)。

---

## 2. コマンド(すべて permission level 4、短縮形 `/oplb`)

```
/oplimit status                        max / online / counted / bypassing / 残り枠
/oplimit reload                        ops.json をディスクから読み直す
/oplimit list                          bypassesPlayerLimit=true の一覧
/oplimit bypass <player>               現在値の確認
/oplimit bypass <player> <true|false>  切り替え → ops.json に書き込み → 即時反映
/oplimit max                           現在の max-players
/oplimit max <n>                       max-players を再起動なしで変更
```

### 反映の仕組み

バニラは ops.json を起動時に 1 回読むだけで、手で編集しても再起動するまで反映されず、フラグを切り替えるコマンドも存在しません。この mod は ops.json を自前で読み書きしてオンメモリに保持し、書き込み後は `UserList#readSavedFile()`(1.12.2)/ `StoredUserList#load()`(1.20.1)でバニラ側の op リストも同期させます。ops.json は唯一のソースのままです。

`/oplimit bypass` は ops.json に既にいるプレイヤーにのみ設定できます(先に `/op`)。

`/oplimit max` はメモリ上の値を書き換えるだけなので、恒久化するなら `server.properties` も更新してください。
現在人数より小さい値にしても既存プレイヤーはキックされません(新規接続のみ制限)。

---

## 3. ビルド

```bash
./gradlew build            # 両方
./gradlew :1.12.2:build    # 片方だけ
```

**JDK 17 が 1 つあれば建ちます。** 1.12.2 の mod 本体は Java 8 バイトコードですが、その toolchain は Gradle が自前で用意するので、手元に必要なのは 17 だけです。

両方とも **手書きの `build.gradle`**(各 80 行程度)で、読み方は同じです。
1.12.2 は RetroFuturaGradle 1.4.9 を直接 apply しています。

1.12.2 で唯一こみ入っているのは **refmap** です。mixin は `allowUserToConnect` のような MCP 名でターゲットを書いており、難読化されたサーバ上の SRG 名(`func_148542_a`)へ対応付けるのが refmap です。
アノテーションプロセッサがこれを生成し、jar に同梱されます。**実行時に MixinBooter (10.6+) が mods フォルダに必要です**(`mcmod.info` と `@Mod(dependencies = ...)` の両方に記載済み)。
1.20.1 は Forge が Mixin を同梱するので追加の依存はありません。

mod の識別情報(id / 名前 / バージョン / 作者)は**ルートの `gradle.properties` が唯一の出所**です。
`mcmod.info` と `mods.toml` へは `processResources` で展開され、1.12.2 の `Tags` クラスは
RetroFuturaGradle が生成します。`modVersion` を 1 行変えれば jar 名まで全部追従します。

CI は 2 つです。`build` がルートから `./gradlew build` で両方を建て `check_common` が `common/` の制約を検査します([common/README.md](common/README.md) 参照)。
リリースは GitHub の Actions タブから `Publish Project` を実行します。バージョン(`X.Y.Z`)を入力すると
`gradle.properties` の `modVersion` 書き換え・コミット・タグ付け・ビルド・リリース作成まで走るので、
手元でタグを打つ必要はありません。タグと `modVersion` は常に `X.Y.Z` で、`release_type`
(beta / alpha / release)は添付 jar の名前(`oplimitbypass-1.12.2-1.0.0-beta.jar`)と
pre-release 表示にのみ効きます。

フォーマットは [.editorconfig](.editorconfig) に委ねています(spotless などの検査は入れていません)。

新しいバージョンを追加する手順は [docs/adding-a-version.md](docs/adding-a-version.md) にあります。

---

## 4. Mixin config の登録方法(1.12.2)

`OpLimitCoreMod` は `IFMLLoadingPlugin` と **`IEarlyMixinLoader`** の両方を実装しています
(`ILateMixinLoader` ではありません)。late の時点ではバニラのクラスがほぼロード済みで `PlayerList` を狙う config は弾かれるためです。ASM トランスフォーマーは登録しておらず、コアmodは config を渡すためだけの器です。

> MixinBooter 11.0 で `IEarlyMixinLoader` / `ILateMixinLoader` はどちらも `@Deprecated` になり、
> `MixinConfigs` マニフェスト属性か `IMixinConnector` が推奨になりました。
> ただし 11.13 時点でも早期ローダーの処理は残っているので当面は動きます。

---

## 5. 動作確認と既知の制限

`@Redirect` は `defaultRequire = 1` 相当なので、**当たらなかった場合は起動時に失敗します**。
黙って無効化されて「なぜか枠が減る」状態になるより安全という判断です。

実挙動のテストは、max-players を 1 にして一般プレイヤー 1 人 + bypass op 1 人が同時に入れるか、が一番手っ取り早いです。

refmap の欠落は「ビルドは通るのに mixin だけ効かない」形で出るため、両方の `build.gradle` に、SRG 名が解決できていなければビルドを失敗させるチェックを入れてあります。

### 未検証事項

- **実機テストはしていません。** ビルドは両方とも成功し、jar の中身(refmap の SRG 名解決、
  1.12.2 の reobf)まで確認済みですが、実際のサーバで mixin が適用されるかは未確認です。
- 1.12.2 で `/op` `/deop` を実行すると、その時点のバニラのフラグ値で ops.json が書き直されます。
  op を付け直した直後は `/oplimit bypass <player> true` を実行し直すのが確実です。
