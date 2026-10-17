<div align="center">

# 🧭 WayfindR

**Screen-off motorcycle navigation for the Common Remington Console Dash.**

The map is drawn off-screen and streamed to the bike's round TFT as H.264 video,
so your phone stays dark, cool and charged for the whole ride.

[![Platform](https://img.shields.io/badge/platform-Android%2010%2B-3DDC84?logo=android&logoColor=white)](#-install)
[![Kotlin](https://img.shields.io/badge/Kotlin-Jetpack%20Compose-7F52FF?logo=kotlin&logoColor=white)](#-under-the-hood)
[![Maps](https://img.shields.io/badge/maps-OSM%20%C2%B7%20offline%20%C2%B7%20Google-2B7BB9)](#%EF%B8%8F-maps-and-routing)
[![No account](https://img.shields.io/badge/account-none%20needed-success)](#-privacy)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue)](LICENSE)

[Install](#-install) · [Features](#-features) · [Joystick & dash menu](#%EF%B8%8F-using-the-joystick-and-dash-menu) · [Google Maps key](#-optional-google-maps-key) · [Offline maps](#%EF%B8%8F-offline-maps-geofabrik--tilemaker--qgis) · [Offline routing](#-offline-routing-brouter) · [Build](#%EF%B8%8F-build-from-source)

</div>

> [!WARNING]
> **Independent community project. Not affiliated with, endorsed by or supported by Common Remington.**
> WayfindR only sends a video stream to the Console **display** over Wi-Fi and reads the
> joystick. It never talks to the ECU, engine, ABS or anything you ride with, and it can't
> change the bike. The worst realistic outcome is that the dash doesn't show the stream or
> needs a power cycle. The dash protocol is verified on firmware **11.63**; other firmware
> versions may behave differently.

---

## 💡 Why

The official app mirrors Google Maps onto the dash by **screen projection**: the phone's
screen stays lit and its picture is copied to the dash. In a tank bag in the sun, that cooks
the phone and drains the battery.

WayfindR does it differently:

```
 your destination ──▶ route ──▶ map drawn off-screen ──▶ hardware H.264 encoder ──▶ Wi-Fi ──▶ dash
                                (phone screen OFF)        (MediaCodec, ~200 kbps)   (RTP/UDP)
```

The dash only decodes video; it doesn't care what draws it. So the phone screen can be **off
the entire ride**.

---

## ✨ Features

### 🏍️ On the dash

| | |
|---|---|
| **Turn-by-turn** | The dash's own turn arrows, distance to the turn, and a "then …" arrow when two turns come close together. |
| **ETA box** | Time to arrival, distance left and arrival clock, at a glance. |
| **Live map** | Heading-up map while navigating, your route line, traffic colours (Google routing) and destination pins. |
| **Joystick control** | Zoom with left/right (can be swapped). Press **down twice** for the on-dash menu. See [Using the joystick](#%EF%B8%8F-using-the-joystick-and-dash-menu). |
| **On-dash menu** | Pick a saved destination, skip the next stop, cancel navigation, or switch map and routing source, without touching the phone. Each action shows a short confirmation. |
| **Routing badge** | A small **G / O / BR** box shows which router planned the current route. |
| **Auto night map** | Dark map from 7 pm to 5 am automatically, or set light/dark by hand. |
| **Rider status** | Phone battery and charging, "GPS weak" / "GPS lost" pills, and the dash's compass heading. |
| **Music and calls** | Now-playing appears in the dash's own music widget; incoming calls show the caller's name, and you can reject from the joystick. |
| **Voice** | Off, a chime before turns, or full spoken directions (on-device text-to-speech). |

### 📱 On the phone

| | |
|---|---|
| **Share to navigate** | Share a place from Google Maps (or any app) to WayfindR, check the route, tap **Start navigation**. |
| **Search** | Type a place name: Google, or free OpenStreetMap search (Photon, then Nominatim). |
| **Saved destinations** | Save places and multi-stop trips, then start them from the phone or the dash menu. |
| **Rerouting** | Leave the route and a new one is worked out automatically. |
| **Ride history** | Every connect-to-disconnect session is saved: distance, time, average and top speed, and a track map. |
| **Garage** | Maintenance items with km/month intervals and **due reminders**, service visits with costs and invoices, scheduled services, and a **glovebox** for documents (insurance, registration) with expiry reminders. |
| **Fuel diary** | Fill-ups, automatic mileage (km/l), efficiency trend and cost tracking. |
| **Live data** | Values the dash reports (battery voltage, air temperature, low fuel, ABS mode) and a monthly count of map/routing requests. |
| **In-app updates** | Checks GitHub Releases on launch and offers a one-tap update. |

### 🗺️ Maps and routing

Choose each one independently, on the phone's Route screen or from the [dash menu](#%EF%B8%8F-switch-map-or-routing-source):

| Map source | Needs | Notes |
|---|---|---|
| **OSM** *(default)* | Internet | OpenStreetMap tiles on the dash, OpenFreeMap on the phone. Free, no key. |
| **Offline** | A `.mbtiles` file on the phone | No signal needed. See [Offline maps](#%EF%B8%8F-offline-maps-geofabrik--tilemaker--qgis). |
| **Google** | Your own API key | Hidden until a key is set. See [Google Maps key](#-optional-google-maps-key). |

| Routing | Needs | Notes |
|---|---|---|
| **OSM** *(default)* | Internet | FOSSGIS Valhalla server, motorcycle profile. Free, no key, no live traffic. |
| **BRouter** | The free BRouter app + your area | Works fully offline. Falls back to OSM if it fails. See [Offline routing](#-offline-routing-brouter). |
| **Google** | Your own API key | Two-wheeler routes with live traffic, refreshed every 2 minutes. |

### 🔒 Privacy

- **No account, no sign-in, no cloud.** Everything is stored on the phone in SQLite.
- **No analytics or crash reporting.** Crash and ride logs are written only to the phone
  (`Android/data/com.wayfindr.app/files/diag`).
- The only network requests are for map tiles, routes, place search and the release check.

---

## 📦 Install

1. Download the latest APK from **[Releases](https://github.com/1857rebels/WayFindr/releases/latest)**.
2. Open it on your phone and allow "install unknown apps" when asked.
3. Turn on the bike, open WayfindR and tap **Open dash view**. It finds the dash's `RE_…`
   Wi-Fi network by itself and remembers it.

Releases are signed with the same key, so new versions install over the old one and keep
your data.

> [!TIP]
> Get notified of new versions with **[Obtainium](https://github.com/ImranR98/Obtainium)**:
> *Add app* → paste `https://github.com/1857rebels/WayFindr`.

**Requirements:** Android 10 or newer (arm64), and a Common Remington Console Dash.

---

## 🕹️ Using the joystick and dash menu

While WayfindR is streaming to the dash, the bike's left-hand joystick controls the map:

| Joystick | What it does |
|---|---|
| **Left** | Zoom in |
| **Right** | Zoom out |
| **Down, Down** (within 3 s) | Open the dash menu |
| **Centre click** | The dash's own music controls (not used by WayfindR) |

Left/right can be swapped in the phone's Settings (**Invert joystick zoom**). The phone
Settings also has **Double-tap left/right opens destination list** if you'd rather open the
menu that way.

> [!NOTE]
> The dash sometimes drops a quick second press. After the first **Down**, a
> **"▼ again: destinations"** hint appears for 3 seconds; press **Down** once more while it's
> showing.

### Open the dash menu (SETTINGS)

Press **Down** twice. The menu, titled **SETTINGS**, opens over the map:

```
 SETTINGS
 ▸ Source           ›     map and routing source
   Cancel           ›     only while navigating
   Home                   your saved destinations,
   Office                 newest first
   Weekend loop
 Down: next · Right: go · Left: close
```

Inside any menu page:

| Joystick | What it does |
|---|---|
| **Down** | Move the highlight to the next row (wraps around to the top) |
| **Right** | Open or use the highlighted row |
| **Left** | Go back one page; on the first page, close the menu |

The menu closes by itself after 30 seconds without a press.

### 🧭 Choose a different destination

1. Press **Down, Down** to open the menu.
2. Press **Down** until your saved destination is highlighted.
3. Press **Right**. Navigation starts to that place straight away, replacing any current
   route. A multi-stop trip starts from its first stop.

Destinations come from **Saved destinations** in the app: share or search a place on the
phone, then tap **Save this destination**. Save them before you lose signal.

### 🗺️ Switch map or routing source

1. Open the menu and, with **Source** highlighted (it's the first row), press **Right**.
2. The **SOURCE** page lists *Map source* (OSM, Offline, Google*) and *Routing source*
   (OSM, Offline (BRouter), Google*). A tick marks the current choice.
3. Press **Down** to the one you want and **Right** to use it. You stay on the page, so you
   can change both. Press **Left** to go back.

The map changes at once; a new routing choice is used from the next route or reroute.
\*Google appears only in builds with a [Google Maps key](#-optional-google-maps-key).

### ✖️ Cancel the next stop or the whole route

1. While navigating, open the menu, press **Down** once to highlight **Cancel**, then
   **Right**.
2. The **CANCEL** page shows:
   - **Cancel next stop: *name*** (only on a multi-stop trip): skip that stop and route
     straight to the one after it.
   - **Cancel navigation**: stop navigating and return to the free-roam map.
3. Highlight one with **Down** and press **Right**. A confirmation shows on the dash for 4
   seconds. Press **Left** instead to go back without cancelling.

### ⚙️ Phone settings

On the phone, tap the **⚙ gear** at the top right of the Home screen. That's where the dash
Wi-Fi, video quality, keep-awake and screen-off options, dark map, joystick options, phone
battery on the dash, voice, media and call permissions, and **Import offline map** live.
Map and routing source are on the **Route** screen, under the map.

---

## 🔑 Optional: Google Maps key

WayfindR works fully without Google. A key adds the **Google** map source (phone and dash) and
**Google** routing with live traffic. You pay Google for what you use, under their free monthly
credit.

> [!NOTE]
> The key is built into the app, so this needs a build from source. The ready-made APK
> from Releases has no key and runs on OSM and offline only.

1. **Create a project** in the [Google Cloud Console](https://console.cloud.google.com/).
2. **Enable two APIs:** *Maps SDK for Android* and *Routes API*
   (*APIs & Services → Library*).
3. **Create a key:** *APIs & Services → Credentials → Create credentials → API key*.
4. **Restrict it** (strongly recommended, so a leaked key can't run up a bill):
   - *Application restrictions → Android apps*: package `com.wayfindr.app` and your signing
     certificate's SHA-1. Get the SHA-1 with `./gradlew signingReport`.
   - *API restrictions*: only *Maps SDK for Android* and *Routes API*.
   - Optionally set a budget alert under *Billing*.
5. **Add the key to the project.** Create `secrets.properties` in the repo root:

   ```properties
   MAPS_API_KEY=your-key-here
   ```

   This file is git-ignored. **Never commit it.**
6. **Rebuild.** The Google options now appear in the app, next to OSM.

Without `secrets.properties` the build uses the placeholder `add gmaps api key here`, and the
app hides every Google option and uses OSM.

---

## 🗺️ Offline maps (Geofabrik → tilemaker → QGIS)

The dash needs a **raster** `.mbtiles` file: map pictures with the labels already drawn on
them. You make one on a PC, for free, in three stages:

```
 Geofabrik .osm.pbf ──tilemaker──▶ vector .mbtiles ──QGIS (style + export)──▶ raster .mbtiles ──▶ phone
     (raw OSM data)                  (no pictures yet)                          (what the app reads)
```

<details>
<summary><b>Why not use the vector file directly?</b></summary>

Drawing vector tiles on the phone, one tile at a time, breaks labels: names get cut at tile
edges, scripts without bundled fonts show nothing, and it costs a lot of battery. A raster
file has its labels drawn once on the PC, by a renderer that sees the whole map, so the phone
only has to read pictures. That's fast, needs no fonts and keeps the GPU idle.
</details>

### What you need

- [Docker Desktop](https://www.docker.com/products/docker-desktop/) (for tilemaker)
- [QGIS](https://qgis.org/) 3.x (free)
- [Python 3](https://www.python.org/) (to generate the map style)
- Disk space: a state takes a few GB while you work; a whole country, 10 GB or more.

### Step 1: Download OpenStreetMap data from Geofabrik

1. Go to **[download.geofabrik.de](https://download.geofabrik.de/)** and find your region
   (e.g. *Asia → India → Southern Zone*).
2. Download the **`.osm.pbf`** file. Pick the smallest region that covers where you ride.
3. Put it in a working folder, e.g. `C:\maps\`.

**Coastal areas only:** also download
**[water-polygons-split-4326.zip](https://osmdata.openstreetmap.de/download/water-polygons-split-4326.zip)**
and unzip it into `C:\maps\coastline\`, so that `C:\maps\coastline\water_polygons.shp`
exists. Without it, the sea shows as land.

### Step 2: Convert to vector tiles with tilemaker

Open PowerShell in your working folder and run (one line):

```powershell
docker run --rm -v "${PWD}:/data" -v "${PWD}/coastline:/usr/src/app/coastline" ghcr.io/systemed/tilemaker:master --input /data/your-region-latest.osm.pbf --output /data/your-region.mbtiles --store /data/tmp
```

- Replace `your-region-latest.osm.pbf` with your file's name.
- **Only want part of it?** Add `--bbox minLon,minLat,maxLon,maxLat` to the end, for example
  `--bbox 74.0,11.5,78.6,18.5` for roughly Karnataka. It's faster and the file is smaller.
- `--store /data/tmp` keeps the working data on disk so big regions don't run out of memory.
  Delete the `tmp` folder afterwards.
- A state takes minutes; a whole country can take an hour or more.

The result, `your-region.mbtiles`, is a **vector** file. The app refuses it, so carry on to QGIS.

### Step 3: Make the map style

tilemaker writes place names only into the `name:latin` field, so ready-made styles (OSM
Bright, OpenFreeMap) show **no labels at all**. This repo includes a style made for it, with
Google-like colours:

```bash
python docs/make_dash_style.py wayfindr-dash-style.json
```

### Step 4: Style it in QGIS

1. *Layer → Add Layer → Add Vector Tile Layer… → New → New Generic Connection*.
   Give it a name, choose **File** and pick your `your-region.mbtiles`. Click *OK*, then *Add*.
2. Open the layer's properties: *Symbology → Style → Load Style… → From file*.
   Choose `wayfindr-dash-style.json`, keep **Symbology** and **Labels** ticked, and click
   *Load Style*.
3. You should see roads, water and labels. If there are no labels, the style didn't load:
   repeat step 2 and check that **Labels** is ticked.

### Step 5: Export the raster `.mbtiles`

1. Zoom the QGIS map to the area you want.
2. *Processing Toolbox → Raster tools → **Generate XYZ tiles (MBTiles)***.
3. Set:

   | Setting | Value |
   |---|---|
   | Extent | *Use map canvas extent* |
   | Minimum zoom | `8` |
   | Maximum zoom | `14`–`17` (see the table below) |
   | Tile width / height | `256` |
   | Metatile size | `4` or more (stops labels breaking at tile edges) |
   | Tile format | `JPG`, quality `85` (smallest), or `PNG` (sharper, larger) |
   | Output file | e.g. `my-area.mbtiles` |

4. Click *Run*. Large areas at high zoom can take hours.

**Choosing the maximum zoom.** The dash rides at about zoom 19. Below that, the app enlarges
the deepest level it has, which makes text bigger and softer. Each extra level makes the file
about **4× bigger**:

| Max zoom | Rough size per 1,000 km² (JPG) | Text on the dash |
|---|---|---|
| 14 | ~4 MB | very blurry close up; fine zoomed out |
| 15 | ~15 MB | blurry |
| 16 | ~55 MB | large and soft |
| **17** | ~220 MB | **readable** |
| 18 | ~900 MB | close to Google |

A good mix is a whole-state file at zoom 14 for the overview, and a zoom-17 file for the
roads you ride most. One file is used at a time.

### Step 6: Put it on the phone

Either:

- **Copy over USB** to `Android/data/com.wayfindr.app/files/maps/`, or
- In the app, **Settings → Import offline map** and pick the file. It's copied in, so you need
  room for a second copy while it imports.

Then choose **Offline** as the map source on the Route screen (or from the
[dash menu](#%EF%B8%8F-switch-map-or-routing-source)). Outside the file's area, the map fills in from
online tiles when there's signal.

---

## 🧭 Offline routing (BRouter)

[BRouter](https://github.com/abrensch/brouter) works out routes on the phone, with no
internet, and WayfindR uses it directly.

1. **Install BRouter** from [F-Droid](https://f-droid.org/packages/btools.routingapp/) or
   Google Play (package `btools.routingapp`).
2. **Download your area:** open BRouter → *Download manager* → select the 5°×5° squares that
   cover where you ride (e.g. Karnataka is mostly `E75_N10` and `E75_N15`) → *Download*.
   Do this once; update whenever you like.
3. **Choose BRouter** as the routing source on WayfindR's Route screen (or from the
   [dash menu](#%EF%B8%8F-switch-map-or-routing-source)).

WayfindR asks BRouter for a car route (`car-fast`, retrying with `car-eco` if that fails) and
turns BRouter's turn hints into dash arrows, voice and chimes. Multi-stop trips and reroutes
work too. Compared with online routing there's **no live traffic** (the ETA uses typical
speeds) and turn texts don't include road names.

If BRouter isn't installed, is missing your area, or errors, the ride log says why and
WayfindR tries the free OSM server instead.

> [!TIP]
> For a fully offline ride, use **Offline** maps + **BRouter** routing. Save your
> destinations before you lose signal, since searching for a place needs internet.

---

## 🛠️ Build from source

```bash
git clone https://github.com/1857rebels/WayFindr.git
cd WayFindr
./gradlew :app:assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/`. No keys or accounts are needed; add
`secrets.properties` for [Google](#-optional-google-maps-key).

**Release builds** are signed with the key described in a git-ignored
`wayfindr-signing.properties` at the repo root:

```properties
storeFile=C:/path/to/your-release.jks
storePassword=…
keyAlias=…
keyPassword=…
```

Without it, release builds fall back to the debug key.

## Honorable Mentions

The dash protocol work builds on [**better-dash**](https://github.com/norbertFeron/better-dash).

---

## 🚧 Status

The core works end to end on a real Console Dash (firmware 11.63): connect, stream with the
screen off, route, reroute, joystick zoom and the on-dash menu. Garage, fuel diary and ride
history are complete.

Still being confirmed on real rides: some turn glyphs, now-playing, compass heading, offline
map sharpness at riding zoom, and BRouter turn arrows on the dash.

## 🤝 Contributing

Issues and pull requests are welcome, especially:

- whether the handshake works on **other dash firmware versions**,
- packet captures for dash fields that are still unknown,
- battery and temperature numbers from real screen-off rides.

## 📄 License

[MIT](LICENSE). Map data © [OpenStreetMap](https://www.openstreetmap.org/copyright)
contributors. BRouter, Valhalla, Photon, Nominatim and OpenFreeMap are separate projects
with their own terms.
