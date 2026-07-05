# HybridCore Bridge — Minecraft (Spigot / Paper)

The plugin that connects a **Minecraft** (Java Edition) server to your
[HybridCore](https://github.com/HybridMindLabs/HybridCore) site.

It polls the site for queued commands (vote rewards, store purchases, giveaway
prizes, bans, …) and runs them from the console, then confirms the ones it
executed. All delivery, retries and expiry are handled by the site.

## How it works

```
POST {site}/api/bridge/poll   →  { "commands": [ { "id": 12, "command": "give Notch diamond 1" } ] }
   (plugin runs each command from the console, on the main thread)
POST {site}/api/bridge/ack    ←  { "ids": [ 12 ] }
```

Authenticated with a per-server bearer token (`hcb_…`).

## Requirements

- **Spigot / Paper** server, Minecraft **1.16+**, **Java 17+**
- HybridCore **≥ 0.2.0** on the site side

## Building

```bash
mvn clean package
```

The jar is produced at `target/HybridCoreBridge.jar`.

> Gson and the Java HTTP client are provided by the server/JVM, so nothing is
> shaded — the jar stays tiny.

## Installation

1. Drop `HybridCoreBridge.jar` into your server's `plugins/` folder and start the
   server once — it creates `plugins/HybridCoreBridge/config.yml`.

2. **Generate a token** — on the site: **Admin → Servers → (your server) →
   Bridge**, enable it and copy the `hcb_…` token (shown once).

3. **Configure** — edit `plugins/HybridCoreBridge/config.yml`:
   ```yaml
   base-url: "https://your-community.com"
   token: "hcb_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"
   poll-interval-seconds: 5
   debug: false
   ```

4. Restart the server (or reload the plugin).

## Config (`config.yml`)

| Key | Default | Description |
| --- | --- | --- |
| `base-url` | `https://your-community.com` | Site base URL, no trailing slash |
| `token` | `none` | Per-server bridge token (`hcb_…`) |
| `poll-interval-seconds` | `5` | Poll interval in seconds (min 2) |
| `debug` | `false` | Verbose console output |

## Commands

- `/hcbridge poll` (perm `hybridcore.bridge.admin`, default op) — force a poll.

## Notes

- Commands run **exactly as queued by the site** — the site substitutes
  placeholders (`{steamid}`, `{name}`, …) before queueing. Write each reward's
  command for your server (e.g. `give {name} diamond 1`, or a plugin command).
- Minecraft players are identified by name/UUID, not SteamID — if you use the
  site's `{steamid}` placeholder, map it in your reward command/plugin. Prefer
  `{name}` for Minecraft rewards.
- Delivery is **at-least-once**: the site re-sends unacked commands, so keep the
  server reachable. Executed commands are confirmed immediately after running.

## License

Proprietary — © HybridMind Labs.
