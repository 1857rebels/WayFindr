package com.example.wayfindr.media

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telecom.TelecomManager
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Answers / rejects the live phone call via TelecomManager — the bit that lets the dash joystick pick
 * up a call. Contrary to the old "Android can't answer calls" note, this has been possible since API
 * 26 (answer) / 28 (end) behind the ANSWER_PHONE_CALLS runtime permission, and we're minSdk 29.
 *
 * Caller name still comes from the notification ([CallInfoProvider]); this class only acts on the
 * ringing/active call. Both methods are safe no-ops without the permission or with nothing ringing.
 */
class CallController(private val context: Context) {

    private val tm = context.getSystemService(TelecomManager::class.java)

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS) ==
            PackageManager.PERMISSION_GRANTED

    /** Accept the currently ringing call. No-op if nothing is ringing or permission isn't granted. */
    fun answer(): Boolean {
        if (!hasPermission()) { Log.i(TAG, "answer: no ANSWER_PHONE_CALLS"); return false }
        return runCatching {
            @Suppress("MissingPermission") tm?.acceptRingingCall(); true
        }.onFailure { Log.w(TAG, "answer failed: ${it.message}") }.getOrDefault(false)
    }

    /** End the current call (rejects a ringing one, hangs up an active one). */
    fun hangup(): Boolean {
        if (!hasPermission()) { Log.i(TAG, "hangup: no ANSWER_PHONE_CALLS"); return false }
        return runCatching {
            @Suppress("MissingPermission") tm?.endCall() ?: false
        }.onFailure { Log.w(TAG, "hangup failed: ${it.message}") }.getOrDefault(false)
    }

    companion object { private const val TAG = "CallController" }
}
