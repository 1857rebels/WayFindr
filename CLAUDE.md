# Wayfindr

Personal Android companion app for a **Common Remington Mt.Fuji 450** motorcycle.
Single user (just me), Android-only, targeting a **Samsung Galaxy S25 Ultra**. Not a
product to sell — may be open-sourced, but built for my own bike only. No user
personas, no client/enterprise concerns.

## Primary goal

Low-power **navigation projected onto the Common Remington Console Dash** (a small
**round TFT display**) without cooking the phone. The Common Remington app overheats
the phone because it screen-_projects_ — it keeps the OLED lit and mirrors Google
Maps. Wayfindr instead **renders the map off-screen and hardware-encodes H.264**, so
the phone screen can stay **OFF** during the ride. That single architectural
difference is the whole point of the project.

## Dash protocol

- Connect to the Console Dash using the **better-dash** protocol as reference:
  https://github.com/norbertFeron/better-dash
- After an auth handshake, the dash decodes an **H.264/RTP stream over UDP port
  5000**. It does not care what produces the video.
- **Unverified against my hardware:** the handshake/connection details vary by
  firmware. My dash runs firmware **11.63** — the control-plane + auth must be
  validated against it before anything else is trusted. This is the make-or-break
  gate; treat it as Phase 1, step 1.

## Core user flow

1. I share a destination from **Google Maps** into Wayfindr.
2. Wayfindr previews the route, I tap **Send to Dash**.
3. While riding, the dash shows the map; I use the bike's **physical joystick** to
   pan/zoom. The phone screen stays off.

## Tech stack

- **Language:** Kotlin (Android, native).
- **Map rendering:** off-screen render → `MediaCodec` hardware H.264 encode →
  `MediaCodec`/RTP to the dash on UDP/5000.
- **Maps/offline:** offline maps preferred for riding; reuse cached/offline Google
  Maps if feasible, otherwise an OSM stack (MapLibre) as fallback. Decide during
  build — don't over-engineer the map layer up front.
- **Backend:** none. No Firebase, no accounts, no cloud sync (removed on the
  open-source branch).
- **Local persistence:** on-device **SQLite** holds everything.

## Features

1. **Navigation** (primary) — receive shared location, route preview, send to dash,
   joystick pan/zoom, turn-by-turn.
2. **TTS / voice overlay** — toggle per trip: off / chime-only / full. We own the
   TTS layer, so this is just a setting.
3. **Maintenance log** — chain cleaning + lube tracker, service intervals, due
   reminders.
4. **Fuel diary** — fill-ups, mileage/efficiency calculations, cost tracking.
5. **Telemetry / ride history** — distance, duration, map snapshot per ride.
6. **Media controls** — now-playing is sent to the dash's **native** music widget
   (05 0d, see protocol notes below), not drawn into our video frame. Incoming
   calls are drawn into our frame as a banner. Android restricts answering/ending
   calls programmatically — calls are realistically display + reject + alert only.

## Build phasing

Sequenced so standalone, useful parts land first and the risky interoperability work
is isolated:

1. **Phase 1:** Kotlin control plane + auth ported and validated against firmware
   11.63 (stream a static test video to the dash to prove the protocol). In
   parallel, the standalone features (maintenance log, fuel diary, telemetry) — no
   dash dependency, usable day one.
2. **Phase 2:** off-screen MapLibre/map → MediaCodec → dash with screen OFF. Proves
   the power fix.
3. **Phase 3:** GPS + offline routing + turn-by-turn rendering.
4. **Phase 4:** polish — TTS, day/night, reconnect handling, settings, media.

## Hard constraints / non-goals

- **Android only.** No iOS.
- **One bike** (Mt.Fuji 450), **one dash target** (CR Console). No generic
  multi-bike / multi-dash abstraction.
- No personas, no branding-as-product, no team/lab infrastructure, no server-side
  PostGIS unless a real need appears. Keep it lean.
- The dash-streaming core requires **hardware-in-the-loop validation on my bike** —
  that part can't be verified from code alone.

## Reference docs in this repo

- `@docs/HLD-LLD.md` — full architecture (high- and low-level design).
- `@docs/design/` — UI prototype and screen specs (from Claude Design).

## Original (Common Remington) app — decompiled reference

Decompiled (JADX) into a local `RE Org app` folder, outside this repo. Sources in
`app/src/main/java`; obfuscated classes in `bluconnect/`, readable ones under
`com/royalenfield/reprime/`. **Read it to learn what the dash expects** — it's the
ground truth for packet contents. Where to look:

| Topic | File(s) |
|---|---|
| Fixed packet constants (hex strings) | `bluconnect/hbg.java` |
| Hex/string helpers (`n()` = 1 byte/char, >0xFF → `FF`) | `bluconnect/pbh.java` |
| Turn-glyph code table (enum value = dash byte) | `bluconnect/w4p.java` |
| Nav packet field layout (BLE variant) | `bluconnect/o2h.java` `d()`, `q12.java` |
| Now-playing builder | `bluconnect/x9k.java` `A1()` |
| Playback-state → music on/play/pause | `x9k.java` (`e` class), `REForeGroundService.java` (`b` class) |
| Incoming dash commands (compass, units, etc.) | `bluconnect/aaq.java` (`u()` = compass) |
| Compass handler / heading source | `NavigationRootFragment.java` `J()`, `N7()`, `A8()` |
| Periodic status packet (heading, call number) | `REForeGroundService.java` ~L400–640 |

Packet strings in `hbg.java` are full K1G frames:
`[len:2][segcount:2]00000000 0201 0005 "K1G "(4B314720) [seq] [TLVs…]`, TLV =
`type sub len:2 value`.

## Dash protocol — what's been learned (K1G over Wi-Fi, fw 11.63)

Framing/auth/streaming code: `dash/protocol/K1GPacket.kt`, `DashCommands.kt`,
`dash/DashSession.kt`. Video: 526×300 H.264 Baseline L4.1, **4 fps, ~200 kbps**
(matches the original; higher rates overran the dash decoder), 1 s IDR, RTP/UDP 5000.
The dash acks every frame (`09 06 55` IDR → reply L2, `09 04 55` P → reply K2).

**App → dash TLVs (type 05 unless noted):**

| TLV | Meaning | Notes |
|---|---|---|
| `05 02` | Primary turn glyph | Codes from `w4p`: left 20, right 21, sharp L/R 22/23, slight/keep L/R 24/25, U-turn ccw/cw 61/26, fork L/R 6/5, off-ramp L/R 8/7, merge 27, roundabout cw/ccw 10/49, straight 9, destination 0, depart 60, ferry 62/63. `0x0B` is NOT valid. Mapping: `DashGlyph` in `dash/nav/Route.kt`. |
| `05 04` / `05 06` | Distance to turn / unit | unit `0x30` metres, `0x10` km×10 |
| `05 03` / `05 05` / `05 07` | Secondary ("then …") turn glyph / distance / unit | `0xFF` = none (original app's -1); sent only when ≤500 m after the next turn |
| `05 09` / `05 46` | Total remaining / unit | |
| `05 08` | ETA "HHMM" ASCII | |
| `05 0D` | Now-playing | `[album]\0[title]\0[artist]`, each ≤19 chars, 1 byte/char |
| `05 17` | Music session active `55` / inactive `AA` | **Required** or the dash never shows the song name |
| `05 19` | Playing `55` / paused `AA` | |
| `05 22` | Incoming caller name, NUL-terminated | repeat ~1 Hz while ringing |
| `05 2D` | Compass heading, u16 degrees (0 = N) | carried in the 1 Hz heartbeat; dash draws the compass itself |
| `06 04` / `06 0F` / `06 01` / `06 03` | Phone battery % (+100) / charging `55`·`AA` / cell signal `01`·`00` / GPS on `55`·`AA` | 1 Hz heartbeat, live from `PhoneStatus` |
| `05 4C` / `05 1B` | Media / call volume, `0x10` + level 0–10 | 1 Hz heartbeat |
| `05 56` | GPS available `55` | sent when the dash opens compass |
| `06 05` | Projection on `55` / off `AA` | |
| `06 80 xx` | Ack/echo of dash event `xx` | |

**Dash → app events:** `09 00 00 01 <code>`, always echoed back as `06 80 <code>`.
Map mode: `0x14` / `0x13` = joystick left/right (zoom in/out by default; invertible
in Settings), `0x15` = joystick DOWN (map mode, confirmed 2026-10-16), `0x12` = probably map-mode UP, `0x18` = centre click (dash enters its own media mode; `0x05`–`0x0A` follow). Call: `0x06` UP = answer, `0x07` DOWN = reject/hang up. Media mode:
`0x09` / `0x0A` = next/previous track. `0x22` = dash opened its **compass** view.
`0C 05` = low fuel (`55` = low; the original app then shows fuel stations). `0C 14` = ABS mode. `03 05` = dash theme `55` Auto / `AA` Dark (the dash switches light/dark itself; the original app never reads a light sensor). `0F` = AES-256-CBC encrypted telemetry (session key, IV = first 16 bytes); `0C` =
telemetry (fields not mapped yet).

## Wayfindr implementation notes

- **Routing:** Google Routes API v2 (`dash/nav/Router.kt`). A step's
  `navigationInstruction.maneuver` happens at the step's **start** location — anchor
  maneuvers there, not at `endLocation` (doing so showed every turn one step late).
- **Free mode (Settings, two independent toggles, both off by default):**
  - *Free routing* → `dash/nav/FreeRouter.kt`: FOSSGIS Valhalla (`valhalla1.openstreetmap.de`),
    `motorcycle` costing, no key. Valhalla maneuver types are translated to Google maneuver
    names so `DashGlyph`/voice work unchanged. No traffic data, so the 2-min traffic refresh is
    skipped.
  - *Free map tiles* → `TileProvider.freeTiles`: OpenStreetMap tiles (`tiles_osm_v1` cache) with
    the OSM tile policy enforced — no prefetch/route cache, identifying User-Agent, 7-day
    revalidation, "© OpenStreetMap contributors" drawn bottom-centre on the dash. The phone map
    switches to MapLibre + OpenFreeMap (`ui/components/FreeWayfindrMap.kt`).
- **Map:** raster tiles drawn with Android Canvas (`dash/map/MapRenderer.kt`), cached
  by `TileProvider`; on a tile miss it scales a cached neighbouring zoom level.
- **Frame loop:** `DashViewModel` `streamJob` → `tick()` → `redrawFrame()` → `DashEncoder`
  (hardware AVC, low-latency keys incl. Qualcomm `vendor.qti-ext-enc-low-latency.enable`;
  the S25 Ultra is Snapdragon). Zoom does NOT force an IDR: forced zoom IDRs were 65–100 KB (3–4 s of the ~25 KB/s budget) and delayed the new view by seconds.
- **Visible area of the dash:** the 526×300 frame is shown on a non-rectangular TFT, so
  edges are cropped. Overlays assume a centred circle ~frame-height across
  (`SAFE_RADIUS_FRAC` in `MapRenderer`). **Unconfirmed:** the display may be a
  semicircle — which edge is flat isn't recorded yet. Verify before placing overlays
  near edges (ETA box is at lower-right, `ETA_ANGLE_DEG`).
- **Settings** persist in `dash/DashConfig.kt` (SharedPreferences `dash_config`):
  SSID/prefix/password, dark map, invert joystick zoom.
- **Logs:** `data/RideDiagnostics.kt` writes `ride-*.log` (newest 12 kept) to
  `/sdcard/Android/data/com.wayfindr.app/files/diag` — pull with
  `adb pull /sdcard/Android/data/com.wayfindr.app/files/diag`. Crash records
  (`crash-*.log`) land there too. Raw packet logging goes to logcat in debug builds only
  (`util/Dbg.kt`; `adb logcat -s DashSession DashViewModel DashEncoder`).
- **No telemetry:** Firebase (Auth, Firestore, Analytics, Crashlytics, Performance, Remote
  Config, Messaging, App Check) is removed entirely; logs stay on the phone.

## Unverified on hardware

Implemented from the decompiled app but not yet ride-tested: zoom-latency changes,
turn glyphs, now-playing (05 17/05 19), compass heading, ETA box placement. Ride
logs record `zoom → keyframe sent in N ms`, `TX music on 05 17…` and
`compass opened (…)` to help confirm them.

## Working conventions

- Work on a branch, never directly on `main`; fixes from Claude sessions go on
  `claude-fixes`.
- One commit per feature/fix, each buildable on its own, with a message that says
  what changed, why, and exactly how to revert it.
