package com.example.wayfindr.util

import android.content.Context
import android.os.Build
import android.util.Log
import com.example.wayfindr.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Last-resort crash trace: writes the fatal stack trace synchronously to a local
 * `diag/crash-*.log` file (pull with adb), then hands off to the previous handler.
 */
object CrashGuard {

    private const val TAG = "CrashGuard"

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // Best-effort, fully synchronous — the process is about to die.
            runCatching { writeCrash(app, thread, error) }
                .onFailure { Log.w(TAG, "failed to persist crash: ${it.message}") }
            // Hand off to the previous handler (ultimately the system) so nothing else changes.
            previous?.uncaughtException(thread, error)
        }
        Log.i(TAG, "Uncaught-exception trace installed")
    }

    private fun writeCrash(context: Context, thread: Thread, error: Throwable) {
        val dir = File(context.getExternalFilesDir(null), "diag").apply { mkdirs() }
        val now = System.currentTimeMillis()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(now))

        val stack = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val text = buildString {
            appendLine("=== WayfindR crash ===")
            appendLine("when=$stamp ($now)")
            appendLine("thread=${thread.name}")
            appendLine("exception=${error.javaClass.name}: ${error.message}")
            appendLine("apk=${BuildId.sha12(context)}")
            appendLine("app=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("device=${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("android=${Build.VERSION.RELEASE} (sdk ${Build.VERSION.SDK_INT})")
            appendLine()
            append(stack)
        }
        File(dir, "crash-$stamp.log").writeText(text)
    }
}
