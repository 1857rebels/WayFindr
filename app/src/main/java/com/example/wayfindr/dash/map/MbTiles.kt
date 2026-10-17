package com.example.wayfindr.dash.map

import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.File

/**
 * A local **raster** .mbtiles file (PNG/JPEG/WebP tiles in SQLite) for the offline dash map.
 *
 * Raster, not vector: labels are already baked into the pictures when the file is made on the
 * PC (see docs/offline-maps.md), so the dash shows text exactly like the Google tiles do, with no
 * on-phone map renderer. Vector files (format "pbf") are rejected with a clear message.
 *
 * Zooms deeper than the file's max zoom are made by enlarging the deepest tile ("overzoom"), so a
 * file built to z16 still works at the riding zoom of 17–19.
 */
class MbTiles private constructor(
    val file: File,
    private val db: SQLiteDatabase,
    val minZoom: Int,
    val maxZoom: Int,
    /** west, south, east, north in degrees, or null when the file doesn't say. */
    private val bounds: DoubleArray?,
) {
    /** True when this file can supply (z, x, y), directly or by overzoom. */
    fun covers(z: Int, x: Int, y: Int): Boolean {
        if (z < minZoom || z > maxZoom + MAX_OVERZOOM) return false
        val b = bounds ?: return true
        val n = (1 shl z).toDouble()
        val west = x / n * 360.0 - 180.0
        val east = (x + 1) / n * 360.0 - 180.0
        val north = Mercator.tileYToLat(y.toDouble(), z)
        val south = Mercator.tileYToLat((y + 1).toDouble(), z)
        return east > b[0] && west < b[2] && north > b[1] && south < b[3]
    }

    /** Decoded tile, or null when the file has none for this spot. Call off the main thread. */
    fun tile(z: Int, x: Int, y: Int): Bitmap? {
        if (!covers(z, x, y)) return null
        if (z <= maxZoom) return read(z, x, y)?.let { decode(it) }
        val dz = z - maxZoom
        val parent = read(maxZoom, x shr dz, y shr dz)?.let { decode(it) } ?: return null
        val r = overzoomRect(parent.width, dz, x, y)
        if (r[2] < 1) return null
        return Bitmap.createScaledBitmap(Bitmap.createBitmap(parent, r[0], r[1], r[2], r[2]), parent.width, parent.width, true)
    }

    private fun read(z: Int, x: Int, y: Int): ByteArray? = runCatching {
        db.rawQuery(
            "SELECT tile_data FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=? LIMIT 1",
            arrayOf(z.toString(), x.toString(), tmsRow(z, y).toString()),
        ).use { c -> if (c.moveToFirst()) c.getBlob(0) else null }
    }.getOrNull()

    private fun decode(bytes: ByteArray): Bitmap? = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

    fun close() = runCatching { db.close() }

    companion object {
        private const val TAG = "MbTiles"
        /** Deepest enlargement of the file's last zoom level (z16 file → usable to z22). */
        const val MAX_OVERZOOM = 6

        /** MBTiles stores rows bottom-up (TMS); the map uses top-down (XYZ). */
        fun tmsRow(z: Int, y: Int): Int = (1 shl z) - 1 - y

        /** [left, top, size] of the part of the parent tile that becomes child (x, y) at +dz. */
        fun overzoomRect(parentPx: Int, dz: Int, x: Int, y: Int): IntArray {
            val size = parentPx shr dz
            val mask = (1 shl dz) - 1
            return intArrayOf((x and mask) * size, (y and mask) * size, size)
        }

        sealed class Result {
            class Ok(val tiles: MbTiles) : Result()
            class Error(val message: String) : Result()
        }

        fun open(file: File): Result {
            if (!file.isFile) return Result.Error("File not found: ${file.name}")
            val db = try {
                SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            } catch (e: Exception) {
                return Result.Error("Not a readable .mbtiles file (${e.message})")
            }
            return try {
                val meta = HashMap<String, String>()
                db.rawQuery("SELECT name, value FROM metadata", null).use { c ->
                    while (c.moveToNext()) meta[c.getString(0)] = c.getString(1) ?: ""
                }
                val format = meta["format"]?.lowercase().orEmpty()
                if (format == "pbf" || format == "mvt") {
                    db.close()
                    return Result.Error("This is a VECTOR .mbtiles (format=$format). The dash needs a raster " +
                        "file with the labels drawn in — see docs/offline-maps.md")
                }
                var minZ = meta["minzoom"]?.toIntOrNull()
                var maxZ = meta["maxzoom"]?.toIntOrNull()
                if (minZ == null || maxZ == null) db.rawQuery(
                    "SELECT MIN(zoom_level), MAX(zoom_level) FROM tiles", null,
                ).use { c -> if (c.moveToFirst()) { minZ = minZ ?: c.getInt(0); maxZ = maxZ ?: c.getInt(1) } }
                val bounds = meta["bounds"]?.split(',')?.mapNotNull { it.trim().toDoubleOrNull() }
                    ?.takeIf { it.size == 4 }?.toDoubleArray()
                Log.i(TAG, "Opened ${file.name}: format=$format z${minZ}–${maxZ} bounds=${bounds?.joinToString()}")
                Result.Ok(MbTiles(file, db, minZ ?: 0, maxZ ?: 0, bounds))
            } catch (e: Exception) {
                db.close()
                Result.Error("Couldn't read the .mbtiles metadata (${e.message})")
            }
        }
    }
}
