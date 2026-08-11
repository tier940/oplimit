# Adding a Minecraft version

Copying `1.20.1/` is the quickest start. These steps target modern Forge (1.16.5 and later); 1.12.2 differs in both its build and its loader API, so use the existing `1.12.2/` as the reference for anything of that era.

```bash
cp -r 1.20.1 1.21.1
```

Only `build.gradle` and `src/` come along: the wrapper, `settings.gradle` and `gradle.properties` live at the repository root and subprojects do not carry their own. Add the directory to `include` in the root `settings.gradle` to bring it into the build.

## Values to change

The mod's name and version are not among them. `modId`, `modName`, `modVersion` and `modAuthor` in the root `gradle.properties` are the only source, expanded into `mods.toml` by `processResources`. What changes is everything tied to Minecraft and Forge.

| File | Setting | Value for 1.20.1 |
|---|---|---|
| `build.gradle` | `mappings channel: 'official', version:` | `1.20.1` |
| | `minecraft 'net.minecraftforge:forge:'` | `1.20.1-47.4.10` |
| | `JavaLanguageVersion.of()` | `17` (21 from 1.20.5) |
| `src/main/resources/META-INF/mods.toml` | `loaderVersion`, forge's `versionRange` | `[47,)` |
| | minecraft's `versionRange` | `[1.20.1,1.20.2)` |
| `src/main/resources/oplimitbypass.mixins.json` | `compatibilityLevel` | `JAVA_17` |
| `src/main/resources/pack.mcmeta` | `pack_format` | `15` |

## Code to review

`common/` needs no changes. Four files are version-specific:

- `server/OpBypassCounter.java` — `PROFILE_READER` (how a player yields its `GameProfile`), `reloadVanillaOps`, `setMaxPlayers`
- `mixins/minecraft/MixinPlayerListLimit.java` — **the `@Redirect` and `@Inject` targets matter most.** `canPlayerLogin` and `getPlayerCount` change name and signature between versions
- `mixins/minecraft/AccessorPlayerList.java` — the `maxPlayers` field name
- `OpLimitBypassMod.java` and `server/OpLimitCommand.java` — Brigadier API differences

A `@Redirect` that misses fails the game at startup, so a target that has moved surfaces immediately rather than being ignored.

Check also how the version reports its player count and MOTD for the server list. Both have moved between versions, and the two current builds need different hooks for the same result; see [`CONTRIBUTING.md`](../CONTRIBUTING.md).

## refmap

If the refmap declared by the mixin config is never generated, the build still succeeds and only the mixins stop working. `build.gradle` carries a check for this, so keep it when copying. The annotation processor (`org.spongepowered:mixin:<version>:processor`) has to be on the dependency list: Forge ships Mixin at runtime but not the processor.

## Finishing up

1. Add `'1.21.1'` to `include` in the root `settings.gradle`; that alone brings it into the build
2. Add a line to the layout in [`CONTRIBUTING.md`](../CONTRIBUTING.md)
3. Add the version to the `Collect jars` and refmap verification loops in `.github/workflows/release.yml`

CI builds with `./gradlew build` and needs no further setup. From 1.20.5 onwards JDK 21 is required, while CI installs 17; raise `java-version` in `.github/workflows/build.yml` and `release.yml` when that time comes. RetroFuturaGradle will eventually require 21 or newer as well, so the two will converge.
