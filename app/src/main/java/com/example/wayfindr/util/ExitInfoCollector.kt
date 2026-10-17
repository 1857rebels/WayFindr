package com.example.wayfindr.util

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log
import com.example.wayfindr.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Captures WHY the process last died — including the deaths [CrashGuard] can't see.
 *
 * CrashGuard only catches uncaught JVM exceptions. A native SIGSEGV (MapLibre, MediaCodec), an
 * ANR, or an OEM low-memory kill bypass it entirely, which is why those crashes kept landing with
 * no trace. Android's [ApplicationExitInfo] (API 30+) records the reason for each recent process
 * exit — and for native crashes / ANRs it carries the tombstone/trace. On the next launch we read
 * any NEW abnormal exits and write them to a diag/crash-*.log (pull with adb).
 */
object ExitInfoCollector {

    private const val TAG = "ExitInfoCollector"
    private const val PREFS = "wayfindr_exitinfo"
    private const val KEY_LAST_TS = "last_exit_ts"

    /**
     * Tag THIS live process with the build that's running, so when it dies its exit record carries
     * the build that actually crashed — not whatever build happens to be installed when we read the
     * record back on the next launch.
     *
     * Without this, [writeExit] stamped `BuildId.sha12` of the *current* install, which mis-attributed
     * crashes: a native crash on build A, collected after build B was sideloaded, was labelled build B.
     * [ActivityManager.setProcessStateSummary] is a small (≤128 B) blob Android preserves in the exit
     * record; we store the running build's sha12 there and read it back via [ApplicationExitInfo.
     * getProcessStateSummary]. Call once from Application.onCreate.
     */
    fun arm(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val app = context.applicationContext
        // sha12 hashes the APK (blocks first time) — do it off the main thread.
        Thread {
            runCatching {
                val am = app.getSystemService(ActivityManager::class.java) ?: return@runCatching
                am.setProcessStateSummary(BuildId.sha12(app).toByteArray(Charsets.UTF_8))
            }.onFailure { Log.w(TAG, "arm failed: ${it.message}") }
        }.apply { isDaemon = true }.start()
    }

    fun collect(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        runCatching {
            val am = context.getSystemService(ActivityManager::class.java) ?: return
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val lastSeen = prefs.getLong(KEY_LAST_TS, 0L)
            val infos = am.getHistoricalProcessExitReasons(context.packageName, 0, 10)
            val dir = File(context.getExternalFilesDir(null), "diag").apply { mkdirs() }
            var newest = lastSeen

            for (info in infos) {
                if (info.timestamp <= lastSeen) continue
                newest = maxOf(newest, info.timestamp)
                // Only the abnormal exits — a clean user-initiated close isn't a crash.
                if (info.reason !in INTERESTING) continue
                runCatching { writeExit(context, dir, info) }
                    .onFailure { Log.w(TAG, "write exit failed: ${it.message}") }
            }
            prefs.edit().putLong(KEY_LAST_TS, newest).apply()
        }.onFailure { Log.w(TAG, "collect failed: ${it.message}") }
    }

    private val INTERESTING = setOf(
        ApplicationExitInfo.REASON_CRASH,          // JVM crash (CrashGuard also catches; harmless dup)
        ApplicationExitInfo.REASON_CRASH_NATIVE,   // native SIGSEGV — the one we were blind to
        ApplicationExitInfo.REASON_ANR,
        ApplicationExitInfo.REASON_SIGNALED,
        ApplicationExitInfo.REASON_LOW_MEMORY,     // OEM killed us under pressure
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
    )

    private fun writeExit(context: Context, dir: File, info: ApplicationExitInfo) {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(info.timestamp))
        // ANR + native crashes expose a trace/tombstone; other reasons don't.
        val trace = runCatching {
            info.traceInputStream?.bufferedReader()?.use { it.readText() }
        }.getOrNull()

        // The build that ACTUALLY died — carried in the process state summary we armed at startup.
        // "unknown" for exits recorded before arming (older builds) so we never mis-claim a build.
        val crashedBuild = runCatching {
            info.processStateSummary?.toString(Charsets.UTF_8)?.takeIf { it.isNotBlank() }
        }.getOrNull() ?: "unknown"

        val text = buildString {
            appendLine("=== WayfindR process exit (ApplicationExitInfo) ===")
            appendLine("when=$stamp (${info.timestamp})")
            appendLine("exception=${reasonName(info.reason)} — ${info.description ?: ""}")
            appendLine("importance=${info.importance} status=${info.status}")
            appendLine("apk=$crashedBuild")
            appendLine("collected-by=${BuildId.sha12(context)}")
            appendLine("app=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("device=${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("android=${Build.VERSION.RELEASE} (sdk ${Build.VERSION.SDK_INT})")
            if (trace != null) {
                appendLine()
                appendLine("--- trace / tombstone ---")
                append(trace)
            }
        }
        File(dir, "crash-exit-$stamp.log").writeText(text)
    }

    private fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_CRASH -> "JVM_CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "NATIVE_CRASH"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY_KILL"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        else -> "REASON_$reason"
    }
}
