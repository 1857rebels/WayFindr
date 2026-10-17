package com.example.wayfindr.dash.map

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

/**
 * Where offline .mbtiles files live: the app's own external folder,
 * `/sdcard/Android/data/com.wayfindr.app/files/maps/`. Files can be copied there over USB/adb,
 * or imported from anywhere with the file picker in Settings (copied in, since SQLite needs a
 * real file path).
 */
object OfflineMapFiles {
    fun dir(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "maps").apply { mkdirs() }

    fun list(context: Context): List<File> =
        dir(context).listFiles { f -> f.isFile && f.name.endsWith(".mbtiles", ignoreCase = true) }
            ?.sortedByDescending { it.lastModified() }.orEmpty()

    /** The configured file, or the newest one in the folder when none is configured yet. */
    fun resolve(context: Context, name: String): File? =
        if (name.isNotBlank()) File(dir(context), name).takeIf { it.isFile } else list(context).firstOrNull()

    /** Copies a picked document into [dir]; returns the new file, or null on failure. Blocking. */
    fun import(context: Context, uri: Uri): File? = runCatching {
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            ?.replace(Regex("[^A-Za-z0-9._-]"), "_")
            ?.let { if (it.endsWith(".mbtiles", true)) it else "$it.mbtiles" }
            ?: "offline-${System.currentTimeMillis()}.mbtiles"
        val out = File(dir(context), name)
        val tmp = File(dir(context), "$name.part")
        context.contentResolver.openInputStream(uri)!!.use { input -> tmp.outputStream().use { input.copyTo(it, 1 shl 20) } }
        if (out.exists()) out.delete()
        tmp.renameTo(out)
        out
    }.getOrNull()
}
