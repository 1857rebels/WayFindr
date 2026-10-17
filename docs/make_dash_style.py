"""Writes a QGIS-friendly MapLibre/Mapbox GL style for the tilemaker (OpenMapTiles-schema) India
file. Google-like colours, labels from `name:latin` (the only name field tilemaker wrote), legacy
filter/"stops" syntax and plain {field} tokens, which QGIS's style converter understands."""
import json, sys

SRC = "openmaptiles"
FONT = ["Arial Regular"]
FONT_B = ["Arial Bold"]
FONT_I = ["Arial Italic"]
layers = []

def L(**kw):
    kw.setdefault("source", SRC)
    layers.append(kw)

layers.append({"id": "background", "type": "background", "paint": {"background-color": "#f5f3ef"}})

# ── land cover / use ──
L(id="landcover-wood", type="fill", **{"source-layer": "landcover"},
  filter=["in", "class", "wood", "forest"], paint={"fill-color": "#cfe8c4"})
L(id="landcover-grass", type="fill", **{"source-layer": "landcover"},
  filter=["in", "class", "grass", "farmland", "wetland"], paint={"fill-color": "#e3efd6"})
L(id="landuse-residential", type="fill", **{"source-layer": "landuse"}, minzoom=10,
  filter=["in", "class", "residential", "suburb", "neighbourhood"], paint={"fill-color": "#ece9e3"})
L(id="landuse-other", type="fill", **{"source-layer": "landuse"}, minzoom=12,
  filter=["in", "class", "commercial", "retail", "industrial", "hospital", "school", "university"],
  paint={"fill-color": "#efe7e1"})
L(id="park", type="fill", **{"source-layer": "park"}, paint={"fill-color": "#c8e6c0"})

# ── water ──
L(id="water", type="fill", **{"source-layer": "water"}, paint={"fill-color": "#9ccbf0"})
L(id="waterway", type="line", **{"source-layer": "waterway"}, minzoom=8,
  paint={"line-color": "#9ccbf0", "line-width": {"stops": [[8, 0.6], [12, 1.2], [16, 3]]}})

# ── buildings ──
L(id="building", type="fill", **{"source-layer": "building"}, minzoom=14,
  paint={"fill-color": "#e6e2dc", "fill-outline-color": "#d4cfc7"})

# ── boundaries ──
L(id="boundary", type="line", **{"source-layer": "boundary"},
  paint={"line-color": "#a7a2b8", "line-width": {"stops": [[3, 0.6], [10, 1.4]]}, "line-dasharray": [3, 2]})

# ── airports ──
L(id="aeroway", type="fill", **{"source-layer": "aeroway"}, minzoom=11,
  filter=["==", "$type", "Polygon"], paint={"fill-color": "#e6e4ec"})

# ── roads: casing then fill, smallest first so big roads sit on top ──
ROADS = [  # class, fill, casing, width stops (fill), minzoom
    ("path",      "#ffffff", "#d9d4cc", [[15, 0.6], [18, 2]], 15),
    ("track",     "#ffffff", "#d9d4cc", [[14, 0.8], [18, 3]], 14),
    ("service",   "#ffffff", "#d9d4cc", [[14, 0.8], [18, 5]], 14),
    ("minor",     "#ffffff", "#d3cdc4", [[12, 0.6], [14, 2], [18, 10]], 12),
    ("tertiary",  "#ffffff", "#cdc6bb", [[10, 0.6], [14, 3], [18, 14]], 10),
    ("secondary", "#ffffff", "#c4bcae", [[8, 0.6], [12, 2], [14, 4], [18, 16]], 8),
    ("primary",   "#fff3c4", "#e8c56b", [[7, 0.6], [11, 2], [14, 5], [18, 18]], 7),
    ("trunk",     "#fde293", "#e9b04a", [[5, 0.6], [10, 2.2], [14, 6], [18, 20]], 5),
    ("motorway",  "#fdd36a", "#e39a2e", [[4, 0.8], [10, 2.6], [14, 7], [18, 22]], 4),
]
def scaled(stops, k, add=0.0):
    return {"stops": [[z, round(w * k + add, 2)] for z, w in stops]}
for cls, fill, casing, stops, mz in ROADS:
    L(id=f"road-{cls}-casing", type="line", **{"source-layer": "transportation"}, minzoom=max(mz, 10),
      filter=["==", "class", cls], layout={"line-cap": "round", "line-join": "round"},
      paint={"line-color": casing, "line-width": scaled(stops, 1.0, 1.5)})
for cls, fill, casing, stops, mz in ROADS:
    L(id=f"road-{cls}", type="line", **{"source-layer": "transportation"}, minzoom=mz,
      filter=["==", "class", cls], layout={"line-cap": "round", "line-join": "round"},
      paint={"line-color": fill, "line-width": scaled(stops, 1.0)})
L(id="rail", type="line", **{"source-layer": "transportation"}, minzoom=10,
  filter=["==", "class", "rail"], paint={"line-color": "#b9b4ad", "line-width": {"stops": [[10, 0.6], [16, 2]]}})

HALO = {"text-halo-color": "#ffffff", "text-halo-width": 1.6}

# ── labels ──
L(id="water-name", type="symbol", **{"source-layer": "water_name"},
  layout={"text-field": "{name:latin}", "text-font": FONT_I, "text-size": 12},
  paint={"text-color": "#3a78b5", **HALO})
L(id="road-ref", type="symbol", **{"source-layer": "transportation_name"}, minzoom=8, maxzoom=14,
  filter=["in", "class", "motorway", "trunk", "primary"],
  layout={"text-field": "{ref}", "text-font": FONT_B, "text-size": 11, "symbol-placement": "line",
          "symbol-spacing": 400},
  paint={"text-color": "#7a5a12", **HALO})
L(id="road-name", type="symbol", **{"source-layer": "transportation_name"}, minzoom=13,
  layout={"text-field": "{name:latin}", "text-font": FONT, "symbol-placement": "line",
          "text-size": {"stops": [[13, 11], [16, 13], [18, 15]]}, "symbol-spacing": 350},
  paint={"text-color": "#40444a", **HALO})
L(id="poi", type="symbol", **{"source-layer": "poi"}, minzoom=15,
  filter=["in", "class", "fuel", "hospital", "police", "bus", "railway", "college", "school", "shop"],
  layout={"text-field": "{name:latin}", "text-font": FONT, "text-size": 11},
  paint={"text-color": "#6b5e50", **HALO})
L(id="place-minor", type="symbol", **{"source-layer": "place"}, minzoom=12,
  filter=["in", "class", "suburb", "neighbourhood", "quarter", "hamlet", "locality"],
  layout={"text-field": "{name:latin}", "text-font": FONT, "text-size": {"stops": [[12, 11], [16, 14]]}},
  paint={"text-color": "#5f6368", **HALO})
L(id="place-village", type="symbol", **{"source-layer": "place"}, minzoom=10,
  filter=["==", "class", "village"],
  layout={"text-field": "{name:latin}", "text-font": FONT, "text-size": {"stops": [[10, 11], [15, 14]]}},
  paint={"text-color": "#3c4043", **HALO})
L(id="place-town", type="symbol", **{"source-layer": "place"}, minzoom=7,
  filter=["==", "class", "town"],
  layout={"text-field": "{name:latin}", "text-font": FONT_B, "text-size": {"stops": [[7, 11], [12, 15], [15, 17]]}},
  paint={"text-color": "#2d2f31", **HALO})
L(id="place-city", type="symbol", **{"source-layer": "place"}, minzoom=4,
  filter=["==", "class", "city"],
  layout={"text-field": "{name:latin}", "text-font": FONT_B, "text-size": {"stops": [[4, 11], [8, 15], [12, 19], [15, 22]]}},
  paint={"text-color": "#202124", **HALO})

style = {
    "version": 8,
    "name": "Wayfindr dash (India, tilemaker)",
    "sources": {SRC: {"type": "vector", "url": "mbtiles://india.mbtiles", "maxzoom": 14}},
    "glyphs": "https://tiles.openfreemap.org/fonts/{fontstack}/{range}.pbf",
    "layers": layers,
}
json.dump(style, open(sys.argv[1], "w", encoding="utf-8"), indent=1)
print(len(layers), "layers")
