package com.example.wayfindr.dash.nav

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.speech.tts.TextToSpeech
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

enum class VoiceMode { OFF, CHIME, FULL }

/**
 * Turn-by-turn voice guidance. We own this layer (CLAUDE.md feature 2), so it's a plain
 * per-trip setting: OFF (silent), CHIME (a beep before each turn), or FULL (spoken
 * directions). Singleton so the RouteScreen toggle and the nav loop in [DashViewModel]
 * share one engine + persisted mode.
 *
 * Announcements run through [maybeAnnounce]: as the rider approaches a maneuver it fires
 * once at a FAR range ("in 400 m, turn left onto X") and once NEAR it ("turn left now"),
 * de-duped per maneuver so it never nags.
 */
class VoiceManager private constructor(context: Context) {

    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("voice", Context.MODE_PRIVATE)

    private val _mode = MutableStateFlow(
        runCatching { VoiceMode.valueOf(prefs.getString(KEY_MODE, VoiceMode.CHIME.name)!!) }
            .getOrDefault(VoiceMode.CHIME)
    )
    val mode = _mode.asStateFlow()

    fun setMode(m: VoiceMode) {
        prefs.edit().putString(KEY_MODE, m.name).apply()
        _mode.value = m
        if (m == VoiceMode.OFF) tts?.stop()
        else if (m == VoiceMode.FULL) ensureTts()  // warm up so the first turn isn't silent
    }

    // ── TTS engine (lazy; created only when FULL is actually used) ──────────────
    @Volatile private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = false
    private var tone: ToneGenerator? = null

    private fun ensureTts() {
        if (tts != null) return
        tts = TextToSpeech(app) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                tts?.language = Locale.getDefault()
                // Without explicit attributes the nav voice can land on the earpiece/phone speaker
                // instead of the connected BT helmet. NAVIGATION_GUIDANCE routes to the active media
                // output (BT A2DP / speaker) and ducks music while speaking — exactly what we want.
                tts?.setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
            } else Log.w(TAG, "TextToSpeech init failed: $status")
        }
    }

    private fun chime(reason: String = "") {
        com.example.wayfindr.data.RideDiagnostics.log("voice", "chime ${reason}".trim())
        runCatching {
            if (tone == null) tone = ToneGenerator(AudioManager.STREAM_MUSIC, 80)
            tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 180)
        }.onFailure { Log.w(TAG, "chime failed: ${it.message}") }
    }

    /**
     * One-off spoken status alert (e.g. "Dash disconnected") — distinct from turn guidance. Speaks
     * a real phrase even in CHIME mode (a status alert needs words), routed to BT via the TTS audio
     * attributes; stays silent only when voice is fully OFF.
     */
    fun alert(text: String) {
        if (_mode.value == VoiceMode.OFF) return
        speak(text)
    }

    private fun speak(text: String) {
        ensureTts()
        com.example.wayfindr.data.RideDiagnostics.log("voice", "speak \"$text\" (ready=$ttsReady)")
        if (ttsReady) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, text.hashCode().toString())
        else chime("(TTS not ready: $text)") // engine not ready yet → at least beep
    }

    // ── Announcement scheduling ────────────────────────────────────────────────
    private val announcer = TurnAnnouncer(FAR_M, NEAR_M, ARRIVE_M)

    /**
     * Call every nav tick. [maneuver] is the upcoming turn (null = none), [distanceM] the
     * distance to it, [remainingM] distance to the destination.
     */
    fun maybeAnnounce(maneuver: Maneuver?, distanceM: Double, remainingM: Double) {
        if (_mode.value == VoiceMode.OFF) return

        val full = _mode.value == VoiceMode.FULL
        val why = "%.0f m to turn, %.0f m left".format(distanceM, remainingM)
        when (announcer.onTick(maneuver, distanceM, remainingM)) {
            TurnAnnouncer.Event.ARRIVE -> if (full) speak("You have arrived at your destination") else chime("arrive ($why)")
            TurnAnnouncer.Event.NEAR -> if (full) speak("Now, ${turnPhrase(maneuver!!)}") else chime("near turn ($why)")
            TurnAnnouncer.Event.FAR -> if (full) speak("In ${roundDist(distanceM)}, ${turnPhrase(maneuver!!)}") else chime("far turn ($why)")
            TurnAnnouncer.Event.NONE -> Unit
        }
    }

    /** Reset trip state when a NEW destination is set or nav ends — never on a route refresh. */
    fun resetTrip() = announcer.reset()

    /** Announce a completed waypoint without ending guidance for the rest of the itinerary. */
    fun announceNextStop(name: String) {
        if (_mode.value == VoiceMode.OFF) return
        if (_mode.value == VoiceMode.FULL) speak("Waypoint reached. Continuing to $name") else chime()
    }

    fun shutdown() {
        runCatching { tts?.stop(); tts?.shutdown() }
        tts = null; ttsReady = false
        runCatching { tone?.release() }; tone = null
    }

    private fun turnPhrase(m: Maneuver): String = when (m.type) {
        ManeuverType.TURN_LEFT    -> "turn left"
        ManeuverType.TURN_RIGHT   -> "turn right"
        ManeuverType.SLIGHT_LEFT  -> "keep left"
        ManeuverType.SLIGHT_RIGHT -> "keep right"
        ManeuverType.SHARP_LEFT   -> "sharp left"
        ManeuverType.SHARP_RIGHT  -> "sharp right"
        ManeuverType.UTURN        -> "make a U-turn"
        ManeuverType.ROUNDABOUT   -> "enter the roundabout"
        ManeuverType.ARRIVE       -> "you have arrived"
        else                      -> "continue straight"
    }

    private fun roundDist(m: Double): String = when {
        m >= 1000 -> "%.1f kilometers".format(m / 1000.0)
        m >= 500  -> "500 meters"
        m >= 200  -> "200 meters"
        else      -> "${(m / 50).toInt() * 50} meters"
    }

    companion object {
        private const val TAG = "VoiceManager"
        private const val FAR_M = 450.0
        private const val NEAR_M = 60.0
        private const val ARRIVE_M = 30.0
        private const val KEY_MODE = "mode"

        @Volatile private var instance: VoiceManager? = null
        fun get(context: Context): VoiceManager =
            instance ?: synchronized(this) {
                instance ?: VoiceManager(context).also { instance = it }
            }
    }
}
