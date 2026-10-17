package com.example.wayfindr.ui.components

import org.json.JSONArray
import org.json.JSONObject

/**
 * MapLibre style for the phone map in Offline mode: the raster .mbtiles file on top, with
 * Google's tiles underneath. That's the same fallback the dash uses outside the file; it needs
 * internet and shows blank without it. Inside the file's area it works with no network at all.
 */
internal fun offlineStyleJson(mbtilesPath: String?): String {
    val sources = JSONObject().put("google", JSONObject()
        .put("type", "raster")
        .put("tiles", JSONArray().put("https://mt1.google.com/vt/lyrs=m&hl=en&x={x}&y={y}&z={z}"))
        .put("tileSize", 256)
        .put("maxzoom", 20))
    val layers = JSONArray()
        .put(JSONObject().put("id", "bg").put("type", "background")
            .put("paint", JSONObject().put("background-color", "#f5f3ef")))
        .put(JSONObject().put("id", "google").put("type", "raster").put("source", "google"))
    if (mbtilesPath != null) {
        // MapLibre reads the file's own metadata (zoom range, bounds) and enlarges past its max zoom.
        sources.put("offline", JSONObject()
            .put("type", "raster")
            .put("url", "mbtiles://$mbtilesPath")
            .put("tileSize", 256))
        layers.put(JSONObject().put("id", "offline").put("type", "raster").put("source", "offline"))
    }
    return JSONObject().put("version", 8).put("sources", sources).put("layers", layers).toString()
}
