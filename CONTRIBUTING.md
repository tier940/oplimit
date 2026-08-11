# Contributing

## Layout

```
settings.gradle   Gradle multi-project root; includes 1.12.2 and 1.20.1
gradlew           the only wrapper (Gradle 8.14.5)
gradle.properties Gradle settings and the mod's identity
1.12.2/           Forge 1.12.2, RetroFuturaGradle; build.gradle and src only
1.20.1/           Forge 1.20.1, ForgeGradle 6; build.gradle and src only
common/           shared sources, pulled in by both; never built alone
scripts/          check-common.sh
docs/             this file and adding-a-version.md
```

The two versions use entirely different loaders and mappings, but both run on Gradle 8.14.5 and JDK 17, so they live in one build.

```bash
./gradlew build            # both versions
./gradlew :1.12.2:build    # one version
```

A single JDK 17 is enough. The 1.12.2 mod targets Java 8 bytecode; Gradle provisions that toolchain itself.

Both `build.gradle` files are hand-written and read alike, roughly eighty lines each. 1.12.2 applies RetroFuturaGradle 1.4.9 directly.

## Mod identity

`modId`, `modName`, `modVersion` and `modAuthor` in the root `gradle.properties` are the only place these are written. They are expanded into `mcmod.info` and `mods.toml` by `processResources`, and RetroFuturaGradle generates the `Tags` class for 1.12.2. Changing `modVersion` alone updates the jar names too.

## Shared code

`common/` holds everything that does not depend on Minecraft: reading and writing `ops.json`, the maintenance list, and the translation table. Both builds add it with `sourceSets.main.java.srcDir`, so the classes are compiled into each jar rather than shared at runtime.

The rules for what may go in there are enforced by `./scripts/check-common.sh`, which compiles the shared sources twice with only the dependencies each version actually provides:

- **No Minecraft types.** Minecraft is not on the classpath, so any reference fails to compile.
- **Java 8 APIs only.** `--release 8` rejects anything newer. Modern *syntax* is fine; 1.12.2 builds through Jabel.
- **Only `org.jetbrains.annotations`.** Both builds declare it. `javax.annotation` is not on the classpath.
- **Gson 2.8 compatible.** Compiled against the 2.8.0 that 1.12.2 ships and the 2.10.1 that 1.20.1 ships.

CI runs the same script whenever `common/` changes.

The one thing shared code needs from a Minecraft version is how to get a `GameProfile` out of an online player. Each version supplies that as `OpBypassCounter.PROFILE_READER`, handed over once at server start; everything else calls `OpBypassRegistry` directly.

Gson 2.8 has no static `JsonParser.parseReader`, and 2.10 deprecates the instance method. The deprecated form is the only spelling that compiles against both, so it is isolated in `parseJson()`.

## Mixins

| | Target class | Login check | Reported count |
|---|---|---|---|
| 1.12.2 | `net.minecraft.server.management.PlayerList` | `allowUserToConnect` | `getCurrentPlayerCount` |
| 1.20.1 | `net.minecraft.server.players.PlayerList` | `canPlayerLogin` | `getPlayerCount`, `MinecraftServer#buildPlayerStatus` |

Both versions redirect the online-player count inside the login check, and adjust the reported count the same way so the advertised figure matches the rule being enforced.

The third mixin differs by version because the same result needs a different hook:

- **1.20.1** counts the player list directly in `MinecraftServer#buildPlayerStatus` instead of going through `getPlayerCount()`, so the ping needs its own hook. Its MOTD is rebuilt every five seconds, so changing it needs no mixin.
- **1.12.2** routes the ping through `getCurrentPlayerCount()`, which is already covered, but copies the MOTD into the status response once at startup, so changing it at runtime needs a tick hook.

`@Redirect` fails the game at startup when it does not apply, which is deliberate: a silently disabled mixin would look like slots going missing for no reason.

### refmap

Mixin targets are written with MCP names such as `allowUserToConnect`. The refmap maps them onto the SRG names an obfuscated server actually has (`func_148542_a`). The annotation processor generates it and it is packaged into the jar.

A missing refmap is the one failure that builds cleanly and only shows up as mixins that never apply, so both `build.gradle` files fail the build when the refmap holds no resolved SRG names.

Forge ships Mixin at runtime but not its annotation processor, so `org.spongepowered:mixin:<version>:processor` is declared explicitly on 1.20.1.

### Mixin config on 1.12.2

`OpLimitCoreMod` implements `IFMLLoadingPlugin` and `IEarlyMixinLoader`, not `ILateMixinLoader`: by the late stage most vanilla classes are already loaded and a config targeting `PlayerList` is rejected. It registers no ASM transformer and exists only to hand the config over.

The build targets MixinBooter 10.7. From 11.0 both `IEarlyMixinLoader` and `ILateMixinLoader` are deprecated in favour of the `MixinConfigs` manifest attribute or `IMixinConnector`. Moving to 11.x also means adding `com.cleanroommc:cleanmix` to the annotation processor path, since the obfuscation processor lives there rather than in MixinBooter itself.

## Localisation

Translations live in `common/src/main/resources/assets/oplimitbypass/lang/` as JSON. The `.lang` form that 1.12.2 needs is generated from the same files at build time, so the two cannot drift apart.

Messages are sent as translation keys with the server-resolved string as a fallback, so a client that has the mod renders its own language and everyone else still reads the message.

## Formatting

Code style is left to [`.editorconfig`](.editorconfig); there is no formatting check in CI.

## CI

`build` compiles both versions from the repository root. `check_common` runs the shared-source checks. Releases are published from the Actions tab with `Publish Project`.

Give it a version of the form `X.Y.Z` and it rewrites `modVersion`, commits, tags, builds, and publishes; nothing needs tagging by hand. The git tag is always `vX.Y.Z`, while `modVersion` carries the release type: version `1.0.0` with type `beta` produces tag `v1.0.0`, `modVersion` `1.0.0-beta`, and jars named to match. Anything other than `release` is published as a pre-release.
