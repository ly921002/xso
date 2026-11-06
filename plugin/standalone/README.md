# Standalone entry (`server.jar`)

An executable jar that runs the proxy **without** a Minecraft server.

---

## Why this exists

As a Paper plugin the proxy shares the host with the Minecraft listener.
On Pterodactyl that usually means a single allocation, so:

- `REALITY_PORT` collides with `server-port`
- `server.properties` is rewritten from the panel's Startup variables on
  every boot, so the conflict cannot be moved out of the way

Running as `server.jar` removes Minecraft from the picture: the port is
simply ours.

---

## Build

```bash
cd plugin
mvn -pl common,standalone -am -DskipTests package
# → standalone/target/server.jar   (~1.8 MB, JNA + common shaded in)
```

---

## Deploy

1. Upload `server.jar` over the existing `server.jar` in the panel.
   The startup command stays `java -jar server.jar` -- no panel change needed.
2. Keep the platform library next to it in `world/`:
   `world/x-linux-amd64.so` (see `docs/server-binary.md`).
3. Write `.env` in the working directory (`/home/container/.env`), then start.

### Minimal `.env`

```ini
UUID=            # leave blank to auto-generate and persist to .uuid
REALITY_PORT=25490
REALITY_TARGET=www.iij.ad.jp:443
REALITY_SNI=www.iij.ad.jp
REALITY_SHORT_ID=          # empty shortId is a fingerprint; set one
XHTTP_PATH=/assets/bundle.js
XHTTP_MODE=auto
VLESS_DECRYPTION=none
VLESS_ENCRYPTION=
VLESS_FLOW=
NATIVE_MODE=jna
BINARY_BASE_URL=local:/home/container
XRAY_LIB_NAME=x-linux-amd64.so
KEEP_BINARIES=true
SHOW_LOG=true
CLEANUP_MODE=off
FAKE_MC_PORT=25565         # cosmetic only, see below
```

---

## What the console shows

The jar replays a plausible Paper boot sequence before starting Xray, so a
panel operator sees what looks like an ordinary Minecraft server:

```
[12:00:01 INFO]: [bootstrap] Loading Paper 1.21.11-132-ver/1.21.11@...
[12:00:03 INFO]: Starting minecraft server version 1.21.11
[12:00:04 INFO]: Starting Minecraft server on 0.0.0.0:25565
[12:00:05 INFO]: Preparing spawn area: 1%
...
[12:00:06 INFO]: Done (9.966s)! For help, type "help"
[EssentialsX] xray config written to .../world/config.json
[EssentialsX] xray started via JNA
[EssentialsX] web is running
vless://...
```

Notes on the camouflage:

- lines go straight to `System.out`, no `[plugin]` prefix
- all timings are randomised; fixed constants are searchable
- `FAKE_MC_PORT` only changes the printed number, nothing listens there
- `CAMOUFLAGE` is **not** read here; replay is unconditional

---

## Failure detection

The plugin build used to print `web is running` and a node link even when
Xray had already exited with `address already in use`. The standalone build
watches for that and refuses to pretend:

```
[EssentialsX] xray exited with code 5: ... bind: address already in use
[17:12:35 ERROR]: [EssentialsX] FAILED: proxy backend did not start.
[17:12:35 ERROR]: [EssentialsX] hint: JNA loaded but Xray failed. ...
```

and exits with status 1, so the panel shows a crash rather than a healthy
server holding an unusable link.

---

## Verified

On FreeBSD 14.3 (amd64), port 3080, `encryption=none`:

| Check | Result |
|---|---|
| Paper boot replay | ✅ 26 lines, plausible timings |
| `.so` load via JNA | ✅ `xray started via JNA` |
| Xray start | ✅ `Xray 26.10.31 started` |
| End-to-end | ✅ **5/5 HTTP 200**, correct exit IP |
| RSS | **167 MB** (vs ~900 MB for Paper + plugin) |
| Failure path | ✅ port busy → `FAILED` + exit 1 |

---

## Trade-offs

| | Paper plugin | Standalone |
|---|---|---|
| Port conflict | ❌ fights Minecraft | ✅ port is ours |
| `server.properties` rewrites | ❌ blocks workaround | ✅ not used |
| Memory | ~900 MB | **~167 MB** |
| MC playable | ✅ | ❌ |
| Plausibility | real Paper process | replayed log only |

**If you need players to join the server, keep the plugin build and ask the
host for a second allocation instead.**

---

## Platform support

Same as the plugin: any platform with a matching `x-*.so`.
Verified on FreeBSD; Linux/macOS/Windows use the corresponding library name
and `librarySuffix()` picks `.so` / `.dylib` / `.dll` automatically.

The only hard requirement is a **headless** JVM -- no AWT, no display.
