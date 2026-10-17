package com.example.wayfindr.media

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Reads the phone's active now-playing session (any app — Spotify, YT Music, etc.) via
 * MediaSessionManager and exposes it as [nowPlaying]. Requires the user to grant notification access
 * once (the same grant powers call notifications); without it MediaSessionManager refuses, so we
 * fail soft and emit null.
 *
 * Feeds the dash's now-playing card (TLV 05 0d) + album art (05 40), matching the dash.
 */
class MediaInfoProvider(private val context: Context) {

    private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)
    val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying.asStateFlow()

    private val msm = context.getSystemService(MediaSessionManager::class.java)
    private val component = ComponentName(context, WayfindrNotificationListener::class.java)
    private var controller: MediaController? = null

    private val controllerCb = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) { publish() }
        override fun onPlaybackStateChanged(state: PlaybackState?) { publish() }
        override fun onSessionDestroyed() { bind(null) }
    }

    private val sessionsCb = MediaSessionManager.OnActiveSessionsChangedListener { list -> bind(list) }

    fun start() {
        if (!isAccessGranted(context)) {
            Log.i(TAG, "no notification access — now-playing disabled")
            com.example.wayfindr.data.RideDiagnostics.log("media", "now-playing: NO notification access")
            return
        }
        runCatching {
            msm?.addOnActiveSessionsChangedListener(sessionsCb, component)
            val sessions = msm?.getActiveSessions(component)
            com.example.wayfindr.data.RideDiagnostics.log("media", "now-playing: ${sessions?.size ?: 0} active media session(s)")
            bind(sessions)
        }.onFailure {
            Log.w(TAG, "start failed: ${it.message}")
            com.example.wayfindr.data.RideDiagnostics.log("media", "now-playing start FAILED: ${it.message}")
        }
    }

    fun stop() {
        runCatching { msm?.removeOnActiveSessionsChangedListener(sessionsCb) }
        controller?.unregisterCallback(controllerCb)
        controller = null
        _nowPlaying.value = null
    }

    /** Skip the active media session forward/back — driven by the dash joystick in media mode. */
    fun skipNext(): Boolean = runCatching { controller?.transportControls?.skipToNext() != null }.getOrDefault(false)
    fun skipPrevious(): Boolean = runCatching { controller?.transportControls?.skipToPrevious() != null }.getOrDefault(false)

    /** Bind to the top active session (the one currently driving playback). */
    private fun bind(list: List<MediaController>?) {
        val top = list?.firstOrNull()
        if (top?.sessionToken == controller?.sessionToken) { publish(); return }
        controller?.unregisterCallback(controllerCb)
        controller = top
        controller?.registerCallback(controllerCb)
        publish()
    }

    private fun publish() {
        val md = controller?.metadata
        if (md == null) { _nowPlaying.value = null; return }
        val title = md.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        val album = md.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty()
        val artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty()
        if (title.isBlank() && artist.isBlank()) { _nowPlaying.value = null; return }
        val art = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val playing = controller?.playbackState?.state == PlaybackState.STATE_PLAYING
        _nowPlaying.value = NowPlaying(title, album, artist, art, playing)
    }

    companion object {
        private const val TAG = "MediaInfoProvider"

        /** True once the user enables Wayfindr under Settings → Notification access. */
        fun isAccessGranted(context: Context): Boolean {
            val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
                ?: return false
            return flat.split(":").any { it.contains(context.packageName) }
        }

        /** Intent to the system screen where the user flips the grant on. */
        fun accessSettingsIntent() = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
