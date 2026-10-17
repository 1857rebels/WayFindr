package com.example.wayfindr.media

import android.app.PendingIntent
import android.graphics.Bitmap

/** Now-playing snapshot read from the active MediaSession. [art] may be null. */
data class NowPlaying(
    val title: String,
    val album: String,
    val artist: String,
    val art: Bitmap? = null,
    val playing: Boolean = true,
)

/**
 * A phone call surfaced from the dialer's CALL-category notification.
 * [incoming] true = ringing (overlay + answer/reject); false = active/ongoing (end only).
 * [answerIntent]/[declineIntent] are the dialer's OWN notification actions — firing them is like
 * tapping Answer / Decline, which is far more reliable than TelecomManager across OEMs. declineIntent
 * doubles as "hang up" for an active call.
 */
data class IncomingCall(
    val caller: String,
    val incoming: Boolean = true,
    val answerIntent: PendingIntent? = null,
    val declineIntent: PendingIntent? = null,
)
