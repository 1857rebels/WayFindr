package com.example.wayfindr.util

import android.content.Context
import java.util.UUID

/**
 * Stable, anonymous per-install identifier, stamped into ride logs so logs from different
 * phones can be told apart without collecting anything personal. Generated once and persisted; survives
 * across launches, regenerates only on reinstall/clear-data.
 */
object DeviceId {
    private const val PREFS = "wayfindr_device"
    private const val KEY = "device_id"

    fun get(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY, null)?.let { return it }
        val id = "dev-" + UUID.randomUUID().toString().take(12)
        prefs.edit().putString(KEY, id).apply()
        return id
    }
}
