# common

`1.12.2/` と `1.20.1/` の両方から `sourceSets.main.java.srcDir` で取り込まれる共有ソースです。
ここ単体ではビルドしません(build.gradle は置いていません)。
取り込みは `1.12.2/build.gradle` と `1.20.1/build.gradle` の双方から。

## 制約は覚えなくてよい

**`./scripts/check-common.sh`** が、各バージョンが実際に提供する依存だけを渡して共有ソースを 2 回コンパイルします。破れば落ちます。

- MC の型を参照しない → Minecraft をクラスパスに置いていないので落ちる
- Java 8 の API まで → `--release 8` で落ちる(1.12.2 は Jabel ビルド。**構文**は新しくてよい)
- アノテーションは `org.jetbrains.annotations` のみ → 両ビルドが同じ版を宣言しているので使える
  (`javax.annotation` はクラスパスに無いので落ちる)
- Gson 2.8 で通る → 1.12.2 同梱の 2.8.0 と 1.20.1 の 2.10.1 の両方でコンパイルする

CI でも `common/**` の変更時に同じスクリプトが走ります(`.github/workflows/check_common.yml`)。

## バージョン依存の唯一の接点

共有コードが MC バージョンから必要とするのは「オンラインプレイヤーから `GameProfile` をどう取るか」だけです。`OpBypassRegistry.ProfileReader` として、各バージョンの `OpBypassCounter.PROFILE_READER` がサーバ起動時の `init()` で 1 度だけ渡します。以降 mixin もコマンドも `OpBypassRegistry` を直接呼べます。

## 翻訳

`src/main/resources/assets/oplimitbypass/lang/*.json` が翻訳の原本です。1.20.1 はこれをそのまま同梱し、1.12.2 は `.lang`(key=value)形式に変換して同梱します(1.12.2 のクライアントは JSON を読めないため)。変換はビルド時の `generateLang` タスクが行うので、編集するのは JSON だけです。

`OpLimitLang` はサーバ側で両形式を読めるので、どちらの jar でも同じ文字列が出ます。

## 唯一の非自明な回避策

Gson 2.8 には静的な `JsonParser.parseReader` が無く、2.10 では非推奨のインスタンスメソッドが残ります。
両方で通るのは非推奨側だけなので `parseJson()` に隔離して `@SuppressWarnings("deprecation")` を付けています。
