package com.example.wayfindr.util

import android.util.Log
import com.example.wayfindr.BuildConfig

/**
 * Debug-only logging. Every call is a no-op in a release (non-debuggable) build — the message
 * lambda isn't even evaluated — so values that would be privacy- or security-sensitive in
 * logcat / bug reports never reach a shipped build: GPS coordinates, shared-location text and
 * resolved URLs, and raw dash control/RTP packet bytes (which carry
 * the rider's route and destination).
 *
 * Use [android.util.Log] directly only for warnings/errors that carry no sensitive payload.
 */
object Dbg {
    inline fun d(tag: String, msg: () -> String) { if (BuildConfig.DEBUG) Log.d(tag, msg()) }
    inline fun i(tag: String, msg: () -> String) { if (BuildConfig.DEBUG) Log.i(tag, msg()) }
}
