package com.example.wayfindr.data

import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Append-only ride/connection log written to the app's EXTERNAL files dir so it can be pulled
 * off the phone after a ride without root:
 *
 *   adb pull /sdcard/Android/data/com.wayfindr.app/files/diag
 *
 * Its main job is to localize the dash's "Timeout!": every connection stage is stamped with a
 * +millis offset from the session start, so the gap between "READY", "first video frame sent"
 * and "dash decoded first IDR" is visible after the fact. Also records GPS quality, reroutes and
 * frame/thermal stats. A no-op until [start] opens a session file, and every write is guarded so
 * logging can never crash a ride.
 */
object RideDiagnostics {
    private const val KEEP_FILES = 12
    private val clock = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val lock = Any()

    @Volatile private var dir: File? = null
    @Volatile private var file: File? = null
    @Volatile private var sessionStartMs = 0L

    /** Point the logger at <externalFilesDir>/diag. Call once (e.g. from connect()). */
    fun init(context: Context) {
        if (dir != null) return
        runCatching { File(context.getExternalFilesDir(null), "diag").apply { mkdirs() } }
            .onSuccess { dir = it }
    }

    /** Open a fresh session file and rotate old ones. */
    fun start(reason: String) {
        val d = dir ?: return
        synchronized(lock) {
            sessionStartMs = System.currentTimeMillis()
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            file = File(d, "ride-$stamp.log")
            rotate(d)
            raw("==== session start: $reason — ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) ====")
        }
    }

    /** Record one event, stamped with wall clock + offset from session start. */
    fun log(tag: String, msg: String) {
        if (file == null) return
        synchronized(lock) {
            val rel = if (sessionStartMs > 0) "+%6dms".format(System.currentTimeMillis() - sessionStartMs) else "         "
            raw("$rel  [$tag] $msg")
        }
    }

    fun stop(reason: String) {
        if (file == null) return
        synchronized(lock) {
            raw("==== session end: $reason ====")
            file = null
        }
    }

    private fun raw(line: String) {
        val f = file ?: return
        runCatching { f.appendText("${clock.format(Date())}  $line\n") }
    }

    private fun rotate(d: File) {
        val logs = d.listFiles { x -> x.name.startsWith("ride-") && x.name.endsWith(".log") } ?: return
        if (logs.size <= KEEP_FILES) return
        logs.sortedBy { it.lastModified() }.dropLast(KEEP_FILES).forEach { runCatching { it.delete() } }
    }
}
