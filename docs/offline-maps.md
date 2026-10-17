# Offline maps and routing

The step-by-step guides are in the README:

- [Offline maps (Geofabrik → tilemaker → QGIS)](../README.md#%EF%B8%8F-offline-maps-geofabrik--tilemaker--qgis)
- [Offline routing (BRouter)](../README.md#-offline-routing-brouter)

## Why raster, not the vector file from tilemaker

An earlier attempt drew the tilemaker **vector** `.mbtiles` on the phone, one tile at a time
with MapLibre's snapshotter. Text was the wall, for three reasons:

1. **Labels can't cross tile edges.** Each 256 px snapshot places its labels on its own, so a road
   name near an edge is cut in half or dropped. Neighbouring tiles disagree about what to show.
2. **Fonts.** Only one glyph range was bundled (Latin). Names in other scripts, or any missing
   range, render as nothing.
3. **Cost.** One snapshot per tile, on the main thread, is slow and battery-hungry, against the
   whole point of the app (low power, screen off).

A raster file has the labels already drawn, by a desktop renderer that sees the whole map at
once. The phone just reads pictures out of SQLite, like the online tiles it already handles, so
it's fast, has no font issues and uses no GPU work on the phone.

## How the app reads the file

- Raster `.mbtiles` only (PNG/JPG tiles); vector (`pbf`) files are refused with a message.
- Rows are stored TMS-style and flipped on read. Beyond the file's deepest zoom, the parent tile
  is cropped and enlarged, up to 6 levels.
- Outside the file's area, the online tiles fill in when there's signal.
