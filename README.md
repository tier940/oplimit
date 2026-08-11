<h1 align="center">OpLimitBypass</h1>
<h1 align="center">
    <a href="https://github.com/tier940/oplimit/releases"><img src="https://img.shields.io/badge/Available%20for-MC%201.12.2%20%7C%201.20.1-informational?style=for-the-badge" alt="Supported Versions"></a>
    <a href="https://github.com/tier940/oplimit/releases"><img src="https://img.shields.io/github/v/release/tier940/oplimit?include_prereleases&sort=semver&style=for-the-badge&label=Release" alt="Release"></a>
    <a href="https://github.com/tier940/oplimit/releases"><img src="https://img.shields.io/github/downloads/tier940/oplimit/total?sort=semver&logo=github&label=&style=for-the-badge&color=2d2d2d&labelColor=545454&logoColor=FFFFFF" alt="GitHub"></a>
</h1>

**A server-side mod that stops operators flagged with `bypassesPlayerLimit` from consuming a max-players slot, and adds a maintenance mode for closing the server to everyone else.**

---

## About

Vanilla lets an operator with `bypassesPlayerLimit` join a full server, but once connected they still occupy one of the `max-players` slots. On a server with a cap of 20, three such operators leave room for only 17 other players.

This mod excludes them from the count, so the cap applies to everyone else exactly as configured. With `max-players=20`, twenty players plus three bypassing operators can be online at once. Whitelist and ban checks are untouched.

The mod runs on the server only. Clients do not need to install it.

## Features

### Player limit
- Operators with `bypassesPlayerLimit` no longer occupy a slot
- The reported player count matches the rule being enforced, so a full server never advertises `23/20`
- `max-players` can be changed at runtime without a restart

### Maintenance mode
- Closes the server to everyone except bypassing operators and a named allow list
- Disconnects players who are no longer permitted, and reports `0/0` with a maintenance MOTD
- The allow list is stored in `oplimit-maintenance.json` and survives a restart

### Operator flag management
- Reads and writes `bypassesPlayerLimit` in `ops.json` directly, applied immediately
- Vanilla only reads `ops.json` at startup and offers no command for this flag

### Localisation
- English and Japanese
- Messages are sent as translation keys with a server-resolved fallback, so clients render their own language where possible

## Requirements

| | Minecraft | Loader | Required |
|---|---|---|---|
| 1.12.2 | 1.12.2 | Forge | [MixinBooter](https://www.curseforge.com/minecraft/mc-mods/mixin-booter) 10.6+ |
| 1.20.1 | 1.20.1 | Forge 47+ | — |

Forge 1.20.1 bundles Mixin, so no extra dependency is needed there.

## Commands

All commands require permission level 4.

| Command | Description |
|---|---|
| `/oplimit status` | Current cap, online count, and free slots |
| `/oplimit reload` | Re-read `ops.json` and the maintenance list from disk |
| `/oplimit list` | Operators with `bypassesPlayerLimit` set |
| `/oplimit bypass <player>` | Show the flag for a player |
| `/oplimit bypass <player> <true\|false>` | Set the flag and apply it immediately |
| `/oplimit max` | Show `max-players` |
| `/oplimit max <n>` | Change `max-players` until the next restart (`0` is allowed) |
| `/oplimit maintenance` | Show whether maintenance mode is on |
| `/oplimit maintenance <on\|off>` | Enter or leave maintenance mode |
| `/oplimit maintenance list` | Show the maintenance allow list |
| `/oplimit maintenance <add\|remove> <player>` | Edit the maintenance allow list |

Running `/oplimit` with no arguments shows the status.

## Configuration

### ops.json

`ops.json` remains the single source of truth. The mod parses it directly, caches the flag in memory, writes changes back through an atomic rename, and asks vanilla to re-read the file so both views stay in sync.

`/oplimit bypass` only sets the flag on players already listed there; use `/op` first.

### oplimit-maintenance.json

The maintenance allow list sits next to `ops.json` and uses the same shape. An empty file is created on first start.

```json
[
  {
    "uuid": "11111111-2222-3333-4444-555555555555",
    "name": "Honon"
  }
]
```

UUIDs come from the server's profile cache, so anyone who has ever connected is recorded with one. A player the server has never seen is stored by name alone, and the UUID is filled in when they first log in. Edits made by hand are picked up by `/oplimit reload`.

### Server language

The language used for the server-resolved text is set with a JVM argument:

```
-Doplimit.lang=ja_jp
```

Unset or unrecognised values fall back to `en_us`.

## Notes

- While maintenance mode is on, the server reports `0/0`: players who are still permitted do not occupy a slot either.
- Maintenance mode itself is held in memory. Restarting the server leaves maintenance mode, and `max-players` returns to the value in `server.properties`. The allow list is unaffected.
- Enabling maintenance mode disconnects the operator running the command unless they are permitted to stay. Running it from the console is unaffected.
- When only bypassing operators are online the reported count is `0`, which matters if you also run a mod that idles the server at zero players.
- On 1.12.2, `/list` takes its count from this mod but still names every player, so the two can disagree.
- On 1.12.2, running `/op` or `/deop` rewrites `ops.json` with vanilla's view of the flag. Re-apply with `/oplimit bypass <player> true` afterwards.

## Building

```bash
./gradlew build            # both versions
./gradlew :1.12.2:build    # one version
```

A single JDK 17 is enough. The 1.12.2 mod targets Java 8 bytecode, but Gradle provisions that toolchain itself.

See [`CONTRIBUTING.md`](CONTRIBUTING.md) for the project layout, and [`docs/adding-a-version.md`](docs/adding-a-version.md) for porting to another Minecraft version.
