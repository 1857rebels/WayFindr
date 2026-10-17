package com.example.wayfindr.viewmodel

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.PowerManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.wayfindr.dash.DarkSchedule
import com.example.wayfindr.dash.DashKeepAliveService
import com.example.wayfindr.dash.DashSession
import com.example.wayfindr.dash.DashState
import com.example.wayfindr.dash.DashWifiManager
import com.example.wayfindr.dash.WifiConnStatus
import com.example.wayfindr.dash.map.LocationTracker
import com.example.wayfindr.dash.map.MapRenderer
import com.example.wayfindr.dash.map.Mercator
import com.example.wayfindr.dash.map.TileProvider
import com.example.wayfindr.dash.nav.GeoPoint
import com.example.wayfindr.dash.nav.Route
import com.example.wayfindr.dash.nav.Router
import com.example.wayfindr.dash.nav.TrafficInterval
import com.example.wayfindr.dash.protocol.DashCommands
import com.example.wayfindr.dash.video.DashEncoder
import com.example.wayfindr.dash.video.NalProcessor
import com.example.wayfindr.dash.video.RtpPacketizer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update

enum class ConnStage { OFFLINE, WIFI, AUTH, STREAMING, ERROR }

/**
 * GPS health shown to the rider so a bad signal degrades *visibly* instead of the marker just
 * freezing. GOOD = fresh, accurate. WEAK = imprecise, slow, or briefly stale (urban canyon /
 * next to a building) → marker holds, dash warns. LOST = no accepted fix for 12 s → grey
 * marker and "GPS lost".
 */
enum class GpsStatus { GOOD, WEAK, LOST }

data class DashUiState(
    val stage: ConnStage = ConnStage.OFFLINE,
    val frameCount: Int = 0,
    val lastButton: String? = null,
    val ssid: String = "",            // empty until a dash is discovered/paired (see DashConfig)
    val wifiPassword: String = "12345678",  // CR Console factory passphrase; rider-overridable
    val destinationName: String? = null,
    val errorMessage: String? = null,
    val retryAttempt: Int = 0,        // >0 while auto-retrying the flaky auth handshake
    val mapZoom: Int = 19,
    val remainingKm: Double? = null,
    val etaMinutes: Int? = null,
    val maneuver: String? = null,
    val hasGps: Boolean = false,
    val gpsStatus: GpsStatus = GpsStatus.LOST,
    val hasRoute: Boolean = false,
    val offRoute: Boolean = false,
    val recalculating: Boolean = false, // off-route long enough / actively rerouting → ETA frozen, pill shows "Recalculating"
    val headingUp: Boolean = true,
    val mapDark: Boolean = false,       // effective (auto schedule or manual)
    val mapDarkAuto: Boolean = true,
    val followMode: Boolean = true,
    val thermal: String = "OK",
    val needsWifiOn: Boolean = false,   // Wi‑Fi radio is off → UI prompts to enable it
    // For the in-app Google Map view (mirrors the dash viewport)
    val riderLat: Double? = null,
    val riderLng: Double? = null,
    val riderBearing: Float = 0f,
    val camLat: Double? = null,
    val camLng: Double? = null,
    val camBearing: Float = 0f,
    val destLatLng: Pair<Double, Double>? = null,
    val routePoints: List<GeoPoint> = emptyList(),
    val routeTraffic: List<TrafficInterval> = emptyList(),
)

class DashViewModel(app: Application) : AndroidViewModel(app) {
    private val _ui = MutableStateFlow(DashUiState())
    val ui = _ui.asStateFlow()

    // The EXACT frame being H.264-encoded to the dash, published for the in-app "Dash view"
    // so the phone preview is pixel-identical to the bike screen (same renderer, not a second
    // map engine). A fresh immutable snapshot per redraw — the encoder's own bitmap is mutated
    // on a background thread and recycled on teardown, so handing that out would tear/crash.
    private val _previewFrame = MutableStateFlow<Bitmap?>(null)
    val previewFrame = _previewFrame.asStateFlow()

    private val session     = DashSession(viewModelScope).also { s ->
        s.phoneStatusProvider = { com.example.wayfindr.dash.PhoneStatus.read(getApplication()) }
    }
    /** Live dash → app data fields, for the "Dash data" tab. */
    val dashFields get() = session.dashFields
    private val wifiManager = DashWifiManager(app, viewModelScope)
    private val dashConfig  = com.example.wayfindr.dash.DashConfig.get(app)
    private val voice        = com.example.wayfindr.dash.nav.VoiceManager.get(app)
    private val repo         = com.example.wayfindr.data.SyncRepository.get(app)
    private val recorder     = com.example.wayfindr.data.RideRecorder()
    private var recordJob: Job? = null
    private val tiles       = TileProvider(app, viewModelScope)
    private val location    = LocationTracker(app)
    private val mapRenderer = MapRenderer(tiles)
    private val mediaInfo   = com.example.wayfindr.media.MediaInfoProvider(app)
    private val callController = com.example.wayfindr.media.CallController(app)
    private var mediaObserveJob: Job? = null
    private val powerManager = app.getSystemService(Application.POWER_SERVICE) as PowerManager

    private var encoder: DashEncoder? = null
    private var streamJob: Job? = null
    private var tickerJob: Job? = null
    // Even presentation clock (ms) feeding the RTP 90 kHz timestamp — advances by exactly one
    // frame interval per encoded frame so the dash gets an evenly-spaced timeline (NOT wall clock,
    // which inherited the loop's jitter). Paired with absolute-grid send pacing below.
    @Volatile private var videoPtsMs = 0L
    // Set on a joystick zoom; cleared when the zoom's keyframe leaves the phone, so the ride log
    // shows how much of the press→dash lag is ours vs. the dash's own decode/display.
    @Volatile private var zoomPressedAtMs = 0L
    // Picker input time, so the ride log shows how long the list change took to leave the phone.
    @Volatile private var pickerInputAtMs = 0L
    private var lastLoneDownMs = 0L
    // "DOWN again" hint on the dash, shown while a lone DOWN waits for its partner.
    @Volatile private var downHintUntilMs = 0L
    // Short confirmation drawn on the dash (e.g. after "Cancel next stop") until noticeUntilMs.
    @Volatile private var notice: List<String>? = null
    @Volatile private var noticeUntilMs = 0L

    private fun showNotice(vararg lines: String) {
        notice = lines.toList()
        noticeUntilMs = System.currentTimeMillis() + NOTICE_MS
        lastSignature = ""
        wakeFrameLoop()
    }

    // ── On-dash saved-destination picker (joystick-driven, free-roam only) ──
    // LEFT = up, RIGHT = down, long-press RIGHT = open (free roam) / select (picker open).
    @Volatile private var pickerItems: List<com.example.wayfindr.data.SavedLocation>? = null
    @Volatile private var pickerIndex = 0
    /**
     * Page of the on-dash menu: ROOT = "Source ›", "Cancel ›" (only with a destination set) and
     * saved destinations; SOURCE = map/routing choice; CANCEL = skip next stop / end navigation.
     */
    private enum class MenuPage { ROOT, SOURCE, CANCEL }
    @Volatile private var menuPage = MenuPage.ROOT
    @Volatile private var pickerLastInputMs = 0L
    // State just before the last RIGHT tap, so a gesture that follows it (second tap of a
    // double-tap, or a long press right after a tap) can undo that tap's zoom / list move.
    private val pickerGesture by lazy { PickerGesture(BTN_JOY_RIGHT, BTN_JOY_LEFT, BTN_JOY_DOWN, DOUBLE_TAP_MS, DOUBLE_DOWN_MS) }
    private var zoomBeforeRightTap = 0
    private var sendPaceMs = 0.0    // EMA of the real frame-send interval — exposes cadence jitter
    private var sendPaceMaxMs = 0L  // worst interval since the last diag line
    // Backstop so a dropped/never-reached connection can't drain the battery forever: if we don't
    // reach STREAMING within this window, give up completely (stop the Wi‑Fi reconnect loop, the
    // foreground service, GPS and the encoder). Cancelled the moment streaming starts.
    private var giveupJob: Job? = null
    private var connLostAlertJob: Job? = null
    private var connLostAlerted = false

    private var userWantsConnection = false
    private var authAttempts = 0       // bounded auto-retry of the (flaky) auth handshake

    // ── Navigation/map state read by the 4 fps frame loop ──
    @Volatile private var destLat: Double? = null
    @Volatile private var destLng: Double? = null
    @Volatile private var routeWaypoints: List<GeoPoint> = emptyList()
    @Volatile private var routeStops: List<com.example.wayfindr.data.SharedLocation> = emptyList()
    @Volatile private var activeStopIndex = 0
    // A reroute starts at the current stop, so its leg zero may be a later itinerary stop.
    @Volatile private var routeLegOffset = 0
    @Volatile private var route: Route? = null
    @Volatile private var panX = 0f
    @Volatile private var panY = 0f
    @Volatile private var zoom = 19          // nav-level zoom on the dash (street-level default)
    @Volatile private var headingUp = true
    @Volatile private var mapDark = false
    @Volatile private var followMode = true
    @Volatile private var lastManualPanAt = 0L

    // Smoothed camera — eased toward the latest GPS target every frame so the map
    // glides at 8 fps instead of jumping once per 1 Hz fix (the "cheap"/laggy feel).
    private var camLat = 0.0
    private var camLng = 0.0
    private var camHdg = 0f
    private var camInit = false
    private var lastTickNs = 0L          // for framerate-independent smoothing

    // Dead-reckoning: last GPS fix + its velocity, so the camera keeps gliding forward
    // between the 1 Hz fixes instead of easing to a stop (the "laggy" feel at speed).
    private var fixLat = 0.0
    private var fixLng = 0.0
    private var fixBearing = 0f
    private var fixSpeed = 0f             // m/s
    private var fixWallMs = 0L
    private var lastFixTime = 0L
    private var gpsFixIntervalMs = 0.0   // EMA of the gap between fixes — exposes screen-off GPS throttling

    // GPS health surfaced to the dash + phone (computed each tick from accuracy + fix age).
    @Volatile private var gpsStatus = GpsStatus.LOST
    // Held marker position while stationary — deadbands sub-accuracy GPS wander so a parked bike's
    // dot doesn't drift/jitter. Updated to the live fix while moving; reset on teardown.
    private var heldRider: Pair<Double, Double>? = null

    // Smoothed rider position shown on the dash frame (locked to the camera centre so the
    // marker stays put and the map slides under it). null = no GPS.
    @Volatile private var frameRiderLat: Double? = null
    @Volatile private var frameRiderLng: Double? = null

    // Stable ETA (Google-like): the raw estimate jitters with instantaneous speed, so we
    // smooth it and only recompute the absolute arrival clock occasionally — no per-frame churn.
    private var smoothEtaSec = 0.0
    private var etaRoute: com.example.wayfindr.dash.nav.Route? = null   // route the ETA was last based on
    @Volatile private var etaArrivalMs = 0L
    private var lastArrivalCalcMs = 0L
    // The minutes-remaining shown to the rider, refreshed on the same 5 s cadence as the arrival
    // clock so it stops ticking/bouncing every second ("tacky ETA"). null = no active ETA.
    @Volatile private var displayedEtaMin: Int? = null

    // Off-route → reroute, debounced. Now feasible because the app keeps cellular
    // internet while bound to the dash (per-socket binding), so Router can run mid-ride.
    @Volatile private var offRouteSince = 0L
    @Volatile private var lastRerouteAt = 0L
    @Volatile private var rerouting = false
    @Volatile private var trafficRefreshInFlight = false
    @Volatile private var lastTrafficRefreshAt = 0L
    // Routes API bill guard (automatic calls only — a destination the rider picks is never blocked).
    @Volatile private var rerouteStreak = 0          // reroutes since last stably back on route → backoff
    @Volatile private var autoRouteCalls = 0         // automatic reroute + traffic calls for this destination
    @Volatile private var autoRouteCapLogged = false
    // Debounce for the "Recalculating" state (ignore 1-frame off-route GPS blips).
    @Volatile private var offRouteStreakStart = 0L
    // Keep "Recalculating" on screen a beat after it clears, so a fast reroute is still seen.
    @Volatile private var recalcHoldUntil = 0L
    // Last lateral distance from the route (m) — logged so flyover/off-route thresholds are tunable.
    @Volatile private var lastSnapDist = -1.0
    // Rolling average of actual moving speed (m/s), held through stops — drives a STEADY ETA
    // instead of one that swings with instantaneous speed at every brake/light.
    private var smoothSpeedMps = 0.0

    // Map-matched rider position: snapped onto the route while on it (kills the GPS
    // lane/road jitter), raw GPS when genuinely off-route. Drives the marker + camera.
    @Volatile private var matchedLat: Double? = null
    @Volatile private var matchedLng: Double? = null

    // Frame cache (avoid the expensive redraw when nothing changed)
    private var frameBitmap: Bitmap? = null
    private var lastSignature = ""
    // Zoom-level box on the dash: shown for ZOOM_BADGE_MS after each zoom.
    @Volatile private var zoomBadgeUntilMs = 0L
    // Lets joystick input cut the frame loop's sleep short so the change is drawn at once.
    private val frameWake = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
    private fun wakeFrameLoop() { frameWake.trySend(Unit) }
    // Recent joystick presses for the optional bottom-centre "DDRR" box.
    private val joyTrail = JoystickTrail()
    private var lastRedrawAt = 0L

    // Ride diagnostics: one-shot "first frame" marker + a throttle for the periodic ride line.
    @Volatile private var loggedFirstFrame = false
    private var lastDiagMs = 0L

    companion object {
        private const val MANUAL_IDLE_MS = 8_000L
        private const val FORCE_REDRAW_MS = 2_000L
        // How long to keep trying to (re)connect before giving up and freeing all background
        // resources. Covers the flaky initial handshake AND a mid-ride drop; if the dash is truly
        // gone (parked / powered off / out of range) we stop draining instead of retrying forever.
        // 2 min (was 45 s): if the dash AP hangs mid-ride it only comes back after a bike power-cycle,
        // and 45 s wasn't enough to safely pull over + restart before Wayfindr gave up. With the
        // WiFi layer now retrying through an AP-missing window, this keeps trying long enough that a
        // power-cycle auto-rejoins with no manual step. Still bounded so a truly-gone dash frees power.
        private const val RECONNECT_GIVEUP_MS = 120_000L
        // Alert the rider after this long of sustained (non-transient) link loss — long enough to skip
        // a brief blip that auto-recovers, short enough that they're not riding blind for minutes.
        private const val CONN_LOST_ALERT_MS = 12_000L
        private const val SMOOTH_TAU = 0.20      // camera smoothing time constant (s) — tighter so the map trails the bike less
        // GPS bearing is only meaningful while genuinely moving; below this it's noise that
        // would spin the heading-up map (the "turns to the opposite direction when I stop" bug).
        private const val BEARING_MIN_SPEED = 1.1f   // m/s (~4 km/h)
        // GPS health on the dash. 4 s was too tight next to tall buildings: the chip
        // flipped to "lost" during a slow/multipath lock we still had. 4–12 s is WEAK
        // (last fix still usable); LOST only after 12 s with no accepted update.
        private const val GPS_WEAK_AGE_MS = 4_000L
        private const val GPS_LOST_AGE_MS = 12_000L
        private const val WAYPOINT_ARRIVE_M = 35.0
        private const val WAYPOINT_PROGRESS_TOLERANCE_M = 25.0
        private const val TRAFFIC_REFRESH_MS = 120_000L
        // Show the dash's "then ..." turn only when it follows the next turn this closely.
        private const val SECONDARY_TURN_MAX_GAP_M = 500.0
        // Routes API bill guard. Reroute waits back off 6 → 12 → 24 → 48 → 96 → 120 s while the
        // rider stays off-route (or GPS keeps claiming so), and reset after a minute back on route.
        // Hard cap on automatic calls per destination so a bad-GPS ride can't run up a bill.
        private const val REROUTE_BASE_MS = 6_000L
        private const val REROUTE_MAX_MS = 120_000L
        private const val REROUTE_STREAK_RESET_MS = 60_000L
        private const val MAX_AUTO_ROUTE_CALLS = 40
        // Lead the displayed position by the pipeline latency (camera ease + H.264 encode + RTP +
        // dash decode/display ≈ 0.5 s) so the dash shows where the bike IS, not where it was.
        private const val DISPLAY_LEAD_S = 0.4
        // Cap on how far ahead the constant-velocity predictor projects (s of travel). Bounds the
        // over-shoot if a fix is missed across a real stop / a long dropout; below this it tracks
        // the rider's actual motion so the camera never freezes between sparse (screen-off) fixes.
        private const val MAX_PREDICT_S = 3.0
        // Motion-adaptive frame cadence — the heart of the RE-style power/stability model.
        // Measuringthe dash showed it projects the map at just 2–4 fps with two
        // profiles (q2g: 4 fps/200 kbps "frameToSend"=250 ms, and 2 fps/100 kbps =500 ms) and switches
        // between them. CR gates on battery; we gate on MOTION instead (smarter for our screen-off
        // power goal, and the user explicitly didn't want a battery gate): stream the fast profile
        // while the map is actually changing (driving / panning / zooming), drop to the slow profile
        // when it's static. A few fps is plenty for a map AND never overruns the dash decoder — which
        // is what caused the blink/stutter at our old fixed 24 fps. ACTIVE is the knob to raise if
        // joystick panning feels choppy (at the cost of more decoder load).
        // ACTIVE was 250 ms (4 fps, matched) but that 250 ms quantum IS the ~0.25 s position lag
        // ("catch-up") felt while moving. Raised to 100 ms (10 fps) — comfortably under the 12 fps the
        // field log proved stable on this dash, so position tracks ~2.5× tighter with no overrun risk.
        // IDLE stays low: a static map has no lag to fix and 2 fps saves power.
        // Back to 4 fps to MATCH the dash exactly (measured at ~3.7 fps). The
        // 12 fps experiment was smoother but ~3× the 2.4 GHz WiFi load, which stresses the dash AP and
        // its BT coexistence; 4 fps is the proven-stable envelope the dash decoder never overruns.
        private const val FRAME_MS_ACTIVE = 250L   // 4 fps while moving — matched
        private const val FRAME_MS_IDLE   = 250L   // keep a steady 4 fps while connected; avoids dash idle stalls
        // Stay on the fast profile this long after the last visible change, so brief pauses (e.g.
        // between joystick nudges, or a GPS fix gap) don't flap the cadence back and forth.
        private const val MOTION_HOLD_MS  = 1_500L
        private const val MAX_AUTH_ATTEMPTS = 4   // the fw 11.63 handshake often needs a couple of tries
        // Dash joystick codes for call control while a call card is showing. UP = answer, DOWN =
        // reject (intuitive accept/decline; verified on the bike that UP is the natural answer press,
        // not IN). The HOME button is left alone — it does the dash's own exit-to-home.
        private const val BTN_ANSWER = 0x06       // joystick UP
        private const val BTN_REJECT = 0x07       // joystick DOWN
        // MAP-mode joystick codes (the dash forwards these directly while projecting, no media-control
        // mode needed) — confirmed in the ride log: 0x13/0x14 with callRinging=false during the map.
        // These give DIRECT zoom like the dash. In/out guess; swap if the ride feels wrong.
        private const val BTN_MAP_ZOOM_IN  = 0x14
        private const val BTN_MAP_ZOOM_OUT = 0x13
        // MEDIA-mode codes (after pressing IN to enter the dash's audio controls): L/R skip tracks on
        // the phone's player, NOT map zoom. Map zoom stays on the direct map-mode codes above.
        private const val BTN_MEDIA_NEXT = 0x09   // media-mode RIGHT → next track
        private const val BTN_MEDIA_PREV = 0x0A   // media-mode LEFT  → previous track
        // PHYSICAL joystick directions in map mode (ride logs + rider: LEFT zooms in by default).
        private const val BTN_JOY_LEFT  = BTN_MAP_ZOOM_IN    // 0x14
        private const val BTN_JOY_RIGHT = BTN_MAP_ZOOM_OUT   // 0x13
        // Map-mode joystick DOWN: 0x15 (rider pressed down ~11× in the 2026-10-16 12:46 ride log;
        // the dash sends one event per press, nothing while held). Map-mode UP is most likely 0x12:
        // seen twice in ride logs and it completes the run 12/13/14/15 = up/right/left/down (unconfirmed).
        // CENTRE click is 0x18 — the dash uses it to enter its own media controls, so we leave it alone.
        private const val BTN_JOY_DOWN = 0x15
        // Destination-list gesture (Settings → "Destination list: double-tap"): press DOWN, or
        // double-tap LEFT or RIGHT (same direction twice within DOUBLE_TAP_MS).
        private const val DOUBLE_TAP_MS = 500L
        private const val ZOOM_BADGE_MS = 3_000L
        private const val BIG_FRAME_BYTES = 30_000
        // DOWN must be pressed twice within this to open the list.
        // 3 s (was 1.5 s): the dash drops about half of quick second presses (2026-10-16 logs: on
        // every miss only one 0x15 arrived), so a lone DOWN shows a hint and waits long enough
        // for a deliberate re-press.
        private const val DOUBLE_DOWN_MS = 3_000L
        private const val PICKER_TIMEOUT_MS = 30_000L
        private const val DASH_MIN_ZOOM = 11
        private const val DASH_MAX_ZOOM = 20
        /** How long a dash notice ("Skipped …") stays up. */
        private const val NOTICE_MS = 4_000L
        /** On-dash Source page rows, in order. */
        private val MENU_MAP_SOURCES = listOf(
            com.example.wayfindr.dash.MapSource.GOOGLE to "Google Maps",
            com.example.wayfindr.dash.MapSource.OSM to "OSM",
            com.example.wayfindr.dash.MapSource.OFFLINE to "Offline",
        ).filter { it.first in com.example.wayfindr.dash.MapSource.available }
        private val MENU_ROUTING_SOURCES = listOf(
            com.example.wayfindr.dash.RoutingSource.GOOGLE to "Google Maps",
            com.example.wayfindr.dash.RoutingSource.OSM to "OSM",
            com.example.wayfindr.dash.RoutingSource.BROUTER to "Offline (BRouter)",
        ).filter { it.first in com.example.wayfindr.dash.RoutingSource.available }
    }

    /** Project a lat/lng forward [distM] metres along [bearingDeg] (great-circle). */
    private fun project(lat: Double, lng: Double, bearingDeg: Double, distM: Double): Pair<Double, Double> {
        val r = 6_371_000.0
        val br = Math.toRadians(bearingDeg)
        val dr = distM / r
        val lat1 = Math.toRadians(lat); val lng1 = Math.toRadians(lng)
        val lat2 = Math.asin(Math.sin(lat1) * Math.cos(dr) + Math.cos(lat1) * Math.sin(dr) * Math.cos(br))
        val lng2 = lng1 + Math.atan2(
            Math.sin(br) * Math.sin(dr) * Math.cos(lat1),
            Math.cos(dr) - Math.sin(lat1) * Math.sin(lat2),
        )
        return Math.toDegrees(lat2) to Math.toDegrees(lng2)
    }

    /**
     * Distance (m) to dead-reckon ahead of the last GPS fix, given the time since it arrived.
     *
     * CONSTANT velocity (distance = speed × time), capped at [MAX_PREDICT_S]. The old version
     * tapered off after ~1 s, so the camera DECELERATED to a near-stop between fixes and then
     * jumped when the next one landed — the "stuck then teleport" feel, especially with the screen
     * off when GPS callbacks slow to multi-second gaps. Projecting at the true speed keeps the
     * camera (and the constant [DISPLAY_LEAD_S] lead) moving smoothly through the gap, so each new
     * fix lands where the camera already is — no jump. The cap bounds over-shoot if the rider
     * stops/turns mid-gap; the next fix (with speed ~0 → predictor off) then eases it back.
     */
    private fun predictedDistance(speedMps: Float, elapsedSec: Double): Double =
        speedMps * elapsedSec.coerceIn(0.0, MAX_PREDICT_S)

    init {
        // Reflect the rider's stored dash WiFi config (SSID may be blank until discovered).
        mapDark = DarkSchedule.effective(dashConfig.mapDarkAuto.value, dashConfig.mapDarkMode.value)
        _ui.update {
            it.copy(ssid = dashConfig.ssid, wifiPassword = dashConfig.password,
                mapDark = mapDark, mapDarkAuto = dashConfig.mapDarkAuto.value)
        }
        // Effective dark map = clock schedule (auto) or the manual switch. The clock flow sleeps
        // until the next 7 pm / 5 am boundary (two wake-ups a day), so auto flips mid-ride without
        // polling; a setting change applies at once.
        viewModelScope.launch {
            val clock = flow {
                while (true) { emit(Unit); delay(DarkSchedule.msUntilNextChange() + 1_000) }
            }
            combine(dashConfig.mapDarkAuto, dashConfig.mapDarkMode, clock) { auto, manual, _ ->
                auto to DarkSchedule.effective(auto, manual)
            }.distinctUntilChanged().collect { (auto, dark) ->
                if (dark != mapDark) {
                    com.example.wayfindr.data.RideDiagnostics.log("map",
                        "dark map ${if (dark) "on" else "off"} (${if (auto) "auto 7 pm–5 am" else "manual"})")
                }
                mapDark = dark
                lastSignature = ""
                _ui.update { it.copy(mapDark = dark, mapDarkAuto = auto) }
            }
        }

        // Dash map tiles: Google, free OpenStreetMap, or the offline file ("Map source" setting).
        // Offline uses Google tiles outside the file's area, so the route cache still works there.
        viewModelScope.launch {
            dashConfig.mapSource.collect { src ->
                val free = src == com.example.wayfindr.dash.MapSource.OSM
                if (tiles.freeTiles != free) com.example.wayfindr.data.RideDiagnostics.log("tiles",
                    "dash tiles: ${if (free) "OpenStreetMap (free, no route pre-cache)" else "Google"}")
                tiles.freeTiles = free
                lastSignature = ""
            }
        }

        // Offline raster map (.mbtiles): open/close the file when the setting or file changes.
        viewModelScope.launch {
            combine(dashConfig.mapSource, dashConfig.offlineMapFile) { src, name ->
                (src == com.example.wayfindr.dash.MapSource.OFFLINE) to name }
                .distinctUntilChanged().collect { (on, name) ->
                    val old = tiles.offline
                    tiles.offline = null
                    withContext(Dispatchers.IO) {
                        old?.close()
                        if (!on) return@withContext
                        val file = com.example.wayfindr.dash.map.OfflineMapFiles.resolve(getApplication(), name)
                        when (val r = file?.let { com.example.wayfindr.dash.map.MbTiles.open(it) }) {
                            is com.example.wayfindr.dash.map.MbTiles.Companion.Result.Ok -> {
                                tiles.offline = r.tiles
                                com.example.wayfindr.data.RideDiagnostics.log("tiles",
                                    "offline map: ${r.tiles.file.name} z${r.tiles.minZoom}–${r.tiles.maxZoom}")
                            }
                            is com.example.wayfindr.dash.map.MbTiles.Companion.Result.Error ->
                                com.example.wayfindr.data.RideDiagnostics.log("tiles", "offline map not used: ${r.message}")
                            null -> com.example.wayfindr.data.RideDiagnostics.log("tiles", "offline map on, but no .mbtiles file")
                        }
                    }
                    // Keep the dash zoom inside the file's levels; put it back when Offline is turned off.
                    val range = zoomRange()
                    if (zoom !in range) {
                        if (zoomBeforeOfflineClamp == null) zoomBeforeOfflineClamp = zoom
                        com.example.wayfindr.data.RideDiagnostics.log("tiles", "zoom $zoom → ${zoom.coerceIn(range)} (offline map has z${range.first}–${range.last})")
                        setZoom(zoom.coerceIn(range))
                    } else if (tiles.offline == null) zoomBeforeOfflineClamp?.let {
                        zoomBeforeOfflineClamp = null
                        setZoom(it)
                    }
                    lastSignature = ""
                }
        }

        // When we connect to a previously-unknown dash by prefix, learn + persist its exact
        // SSID so subsequent connects target it directly (no system picker again).
        wifiManager.onSsidResolved = { learned ->
            dashConfig.ssid = learned
            _ui.update { it.copy(ssid = learned) }
        }

        viewModelScope.launch {
            combine(wifiManager.state, session.state) { ws, st ->
                ws.status == WifiConnStatus.CONNECTED || st == DashState.STREAMING
            }.distinctUntilChanged().collect { com.example.wayfindr.util.IdleSleep.setDashLinked(it) }
        }
        viewModelScope.launch {
            com.example.wayfindr.util.IdleSleep.asleep.collect { asleep ->
                if (asleep && session.state.value != DashState.STREAMING) sleepNow()
            }
        }
        viewModelScope.launch {
            wifiManager.state.collect { ws ->
                com.example.wayfindr.data.RideDiagnostics.log(
                    "wifi",
                    ws.status.toString() +
                            (ws.ssid.takeIf { it.isNotBlank() }?.let { " ssid=$it" } ?: "") +
                            (ws.error?.let { " err=$it" } ?: ""),
                )
                when (ws.status) {
                    WifiConnStatus.CONNECTED -> {
                        refreshStage()
                        if (userWantsConnection &&
                            session.state.value in listOf(DashState.IDLE, DashState.ERROR)
                        ) {
                            delay(1_200)
                            // Re-check: the user may have hit Disconnect during the delay.
                            if (userWantsConnection &&
                                wifiManager.state.value.status == WifiConnStatus.CONNECTED
                            ) session.connect(_ui.value.ssid, wifiManager.network)
                        }
                    }
                    WifiConnStatus.ERROR -> {
                        // Dash is gone for good (bike powered off / out of range) → the ride is
                        // over. Save it even though the user never tapped Disconnect.
                        stopRecording()
                        // …and release everything connect() acquired (wake/wifi locks, GPS,
                        // encoder loop). These used to leak when a connection ENDED on failure
                        // rather than via the Disconnect button, draining the battery in the
                        // background until the app was force-closed.
                        userWantsConnection = false
                        releaseBackgroundResources()
                        session.disconnect()   // wifi status stays ERROR → stage remains ERROR
                        _ui.update { it.copy(errorMessage = ws.error) }; refreshStage()
                        com.example.wayfindr.data.RideDiagnostics.stop("wifi error / dash gone")
                    }
                    else -> refreshStage()
                }
            }
        }

        viewModelScope.launch {
            session.state.collect { state ->
                com.example.wayfindr.data.RideDiagnostics.log("session", "→ $state")
                refreshStage()
                // Battery backstop: count down to a full give-up unless/until we're actually
                // streaming. Reaching STREAMING cancels it; any non-streaming state (re)arms it.
                if (state == DashState.STREAMING) { cancelReconnectGiveup(); cancelConnLostAlert() }
                else if (userWantsConnection) { armReconnectGiveup(); armConnLostAlert() }
                when (state) {
                    DashState.READY -> { authAttempts = 0; _ui.update { it.copy(retryAttempt = 0) }; startStream() }
                    DashState.STREAMING -> {
                        authAttempts = 0; _ui.update { it.copy(retryAttempt = 0, errorMessage = null) }
                    }
                    // The handshake to fw 11.63 is flaky — a single failed attempt is normal.
                    // Auto-retry (WiFi is already up) instead of making the rider re-tap Connect.
                    DashState.ERROR -> {
                        if (userWantsConnection &&
                            wifiManager.state.value.status == WifiConnStatus.CONNECTED &&
                            authAttempts < MAX_AUTH_ATTEMPTS
                        ) {
                            authAttempts++
                            _ui.update { it.copy(retryAttempt = authAttempts, errorMessage = null) }
                            delay(1_500)
                            if (userWantsConnection && wifiManager.state.value.status == WifiConnStatus.CONNECTED)
                                session.connect(_ui.value.ssid, wifiManager.network)
                        } else {
                            _ui.update { it.copy(retryAttempt = 0) }
                            // Retries exhausted while the WiFi link is still up → give up fully now
                            // (don't wait out the timer). giveUp() also stops the Wi‑Fi reconnect loop.
                            if (userWantsConnection && authAttempts >= MAX_AUTH_ATTEMPTS &&
                                wifiManager.state.value.status == WifiConnStatus.CONNECTED
                            ) {
                                giveUp("auth exhausted after $authAttempts attempts")
                            }
                        }
                    }
                    else -> {}
                }
            }
        }

        session.onError = { msg ->
            com.example.wayfindr.data.RideDiagnostics.log("error", msg)
            _ui.update { it.copy(errorMessage = msg) }; refreshStage()
        }
        // Joystick handling. While a call card is on the dash, the joystick answers/rejects the call
        // (IN = answer, DOWN = reject) instead of zooming; otherwise RIGHT/LEFT zoom the map. The
        // separate HOME button is intentionally untouched — it does the dash's native exit; its code
        // just falls through to the logged-else branch. Codes verified fw 11.63: IN=0x18, RIGHT=0x09,
        // LEFT=0x0A, DOWN=0x07. Every unmatched code is shown in ui.lastButton so we can retune.
        session.onButton = { btn ->
            val code = btn.toInt() and 0xFF
            when (code) {   // physical directions: 0x15 down, 0x13 right, 0x14 left
                BTN_JOY_DOWN -> 'D'; BTN_JOY_RIGHT -> 'R'; BTN_JOY_LEFT -> 'L'; else -> null
            }?.let { if (dashConfig.showJoystickTrail.value) joyTrail.add(it, System.currentTimeMillis()) }
            val call = com.example.wayfindr.media.CallInfoProvider.incomingCall.value
            val ringing = call?.incoming == true
            val active  = call != null && !call.incoming
            val pickerLabel = if (!ringing) handlePickerInput(code) else null
            val label = pickerLabel ?: when {
                // Ringing: IN answers, DOWN rejects. Active call: DOWN hangs up (fixes "can't end").
                ringing && code == BTN_ANSWER -> { answerCall(call!!); "Call answered" }
                ringing && code == BTN_REJECT -> { endCall(call!!);    "Call rejected" }
                active  && code == BTN_REJECT -> { endCall(call!!);    "Call ended" }
                // Map/media controls run whenever a call ISN'T ringing (still usable during an active
                // call — only DOWN is reserved for hang-up, and that's not a map/media code anyway).
                // "Invert zoom" setting swaps which joystick direction zooms in.
                !ringing && code == zoomInCode()  -> { zoomIn();  "Zoom in (map)" }
                !ringing && code == zoomOutCode() -> { zoomOut(); "Zoom out (map)" }
                !ringing && code == BTN_MEDIA_NEXT -> { mediaInfo.skipNext(); "Next track" }
                !ringing && code == BTN_MEDIA_PREV -> { mediaInfo.skipPrevious(); "Previous track" }
                code == (DashCommands.EVT_COMPASS.toInt() and 0xFF) -> "Compass (dash)"
                else -> "code 0x${code.toString(16).uppercase()}"
            }
            // Persist every button code so untethered tests are diagnosable from the remote pull
            // (the on-screen readout is invisible with the phone screen off / projecting).
            com.example.wayfindr.data.RideDiagnostics.log("button",
                "0x%02X (ringing=%b active=%b) → %s".format(code, ringing, active, label))
            _ui.update { it.copy(lastButton = label) }
        }
    }

    private fun refreshStage() {
        val wifi = wifiManager.state.value.status
        val dash = session.state.value
        val stage = when {
            dash == DashState.STREAMING -> ConnStage.STREAMING
            dash == DashState.ERROR || wifi == WifiConnStatus.ERROR -> ConnStage.ERROR
            dash == DashState.AUTHENTICATING || dash == DashState.CONNECTING || dash == DashState.READY -> ConnStage.AUTH
            wifi == WifiConnStatus.REQUESTING || wifi == WifiConnStatus.CONNECTED -> ConnStage.WIFI
            else -> ConnStage.OFFLINE
        }
        _ui.update { it.copy(stage = stage) }
    }

    // ── Connection ─────────────────────────────────────────────────────────

    fun connect() {
        userWantsConnection = true
        authAttempts = 0
        _ui.update { it.copy(errorMessage = null, retryAttempt = 0, needsWifiOn = false) }

        // The dash link needs the Wi‑Fi radio on (WifiNetworkSpecifier joins it as a separate
        // per-app network — the phone's current Wi‑Fi/cellular is untouched). Android 10+ won't
        // let us enable Wi‑Fi for the user, so if it's off, surface it immediately and let the UI
        // open the Wi‑Fi panel — far better than a silent 30s timeout.
        if (!wifiManager.isWifiEnabled()) {
            userWantsConnection = false
            _ui.update { it.copy(stage = ConnStage.ERROR, needsWifiOn = true,
                errorMessage = "Turn on Wi‑Fi to connect to the dash") }
            return
        }
        com.example.wayfindr.data.RideDiagnostics.init(getApplication())
        com.example.wayfindr.data.RideDiagnostics.start("connect")
        // Stamp the running build's identity into the log so a pulled ride is matched 1:1 to the
        // pushed APK — never again analyse a ride and not know which build produced it.
        com.example.wayfindr.data.RideDiagnostics.log(
            "build", "apk=${com.example.wayfindr.util.BuildId.sha12(getApplication())}" +
                    " vc=${com.example.wayfindr.BuildConfig.VERSION_CODE} name=${com.example.wayfindr.BuildConfig.VERSION_NAME}",
        )
        com.example.wayfindr.data.RideDiagnostics.log(
            "connect", "ssid='${dashConfig.ssid}' needsDiscovery=${dashConfig.needsDiscovery} dest=${_ui.value.destinationName}",
        )
        loggedFirstFrame = false
        DashKeepAliveService.start(getApplication())
        location.start()
        startRecording()

        // Start a lightweight background ticker (4 Hz) to keep the phone map preview up-to-date
        // during the connection handshake. This ensures the rider sees their location immediately
        // while Wi‑Fi and Auth are spinning up.
        tickerJob?.cancel()
        tickerJob = viewModelScope.launch(Dispatchers.Default) {
            while (isActive && session.state.value != DashState.STREAMING) {
                tick()
                delay(250)
            }
        }

        // We must know the EXACT dash SSID before connecting — the dash validates it inside
        // the encrypted auth handshake (DashAuth), and Android redacts the SSID of a network
        // we've already joined. So if we don't have it stored, find it from a WiFi scan.
        if (dashConfig.needsDiscovery) {
            wifiManager.findDashSsid(dashConfig.ssidPrefix)?.let { found ->
                dashConfig.ssid = found
                _ui.update { it.copy(ssid = found) }
            }
        }

        when {
            wifiManager.state.value.status == WifiConnStatus.CONNECTED ->
                session.connect(_ui.value.ssid, wifiManager.network)
            // Known SSID (stored or just found by scan) → exact connect + correct auth.
            dashConfig.ssid.isNotBlank() ->
                wifiManager.connect(dashConfig.ssid, dashConfig.password)
            // Couldn't find it in scan results — fall back to prefix discovery so we at
            // least associate (auth may still need the SSID; a rescan usually fixes it).
            else ->
                wifiManager.connect(dashConfig.ssidPrefix, dashConfig.password, prefixMatch = true)
        }
    }

    fun disconnect() {
        userWantsConnection = false
        cancelReconnectGiveup()
        com.example.wayfindr.data.RideDiagnostics.log("connect", "user disconnect")
        stopRecording()        // a connect→disconnect session = one saved ride
        session.disconnect()
        wifiManager.disconnect()
        releaseBackgroundResources()
        refreshStage()
        com.example.wayfindr.data.RideDiagnostics.stop("disconnect")
    }

    /**
     * Release everything that keeps the CPU/GPS/WiFi awake: the foreground service
     * (PARTIAL_WAKE_LOCK + WifiLock), GPS updates, and the encoder/stream loop. Called
     * whenever the connection has ended so the app stops draining the battery in the
     * background — on an explicit disconnect, on a terminal connection failure (dash
     * unreachable / handshake exhausted), and when the ViewModel is cleared.
     */
    private fun releaseBackgroundResources() {
        teardown()                                   // stream loop + encoder + frame bitmap
        location.stop()                              // stop GPS updates
        DashKeepAliveService.stop(getApplication())  // release wake + wifi locks
    }

    /**
     * Complete stop of all background work — including the Wi‑Fi manager's reconnect loop, which
     * [releaseBackgroundResources] alone does NOT stop (that loop re-joins the dash AP every few
     * seconds and was the main source of drain after the dash went away). The session stays in
     * ERROR so the Dash screen still shows "Couldn't connect" + Try again.
     */
    private fun giveUp(reason: String) {
        cancelReconnectGiveup()
        userWantsConnection = false
        session.disconnect()
        wifiManager.disconnect()       // stops the indefinite AP reconnect loop
        releaseBackgroundResources()
        refreshStage()
        com.example.wayfindr.data.RideDiagnostics.log("error", "gave up: $reason — background released")
        com.example.wayfindr.data.RideDiagnostics.stop(reason)
    }

    /** App asleep (IdleSleep): stop any connect/reconnect attempt and release GPS, locks, loops. */
    private fun sleepNow() {
        com.example.wayfindr.data.RideDiagnostics.log("sleep", "app asleep — stopping background work")
        if (userWantsConnection) giveUp("app asleep (idle ${com.example.wayfindr.util.IdleSleep.IDLE_MS / 60_000} min)")
        else { wifiManager.disconnect(); releaseBackgroundResources() }
    }

    /** Start the give-up countdown if it isn't already running (idempotent). */
    private fun armReconnectGiveup() {
        if (giveupJob?.isActive == true) return
        giveupJob = viewModelScope.launch {
            delay(RECONNECT_GIVEUP_MS)
            if (session.state.value != DashState.STREAMING) giveUp("reconnect window elapsed")
        }
    }

    private fun cancelReconnectGiveup() { giveupJob?.cancel(); giveupJob = null }

    // Non-silent disconnect alert: this dash-AP-hang failure only recovers after the rider
    // power-cycles the bike, so they must KNOW the link dropped. Fire once after a sustained loss
    // (not a transient blip) via vibration + a BT-routed voice cue, so it carries through the helmet
    // with the phone screen off. Re-armed each non-streaming state; cancelled (and reset) on recovery.
    private fun armConnLostAlert() {
        if (connLostAlertJob?.isActive == true || connLostAlerted) return
        connLostAlertJob = viewModelScope.launch {
            delay(CONN_LOST_ALERT_MS)
            if (session.state.value != DashState.STREAMING && userWantsConnection) {
                connLostAlerted = true
                com.example.wayfindr.data.RideDiagnostics.log("connect", "sustained link loss → rider alert (vibrate + voice)")
                runCatching {
                    val v = getApplication<Application>().getSystemService(android.os.Vibrator::class.java)
                    v?.vibrate(android.os.VibrationEffect.createWaveform(longArrayOf(0, 300, 200, 300, 200, 300), -1))
                }
                voice.alert("Dash disconnected. Restart the dash to reconnect.")
            }
        }
    }

    private fun cancelConnLostAlert() {
        connLostAlertJob?.cancel(); connLostAlertJob = null
        if (connLostAlerted) {
            connLostAlerted = false
            voice.alert("Dash reconnected")
        }
    }

    // ── Ride recording (the connected session) ───────────────────────────────
    private fun startRecording() {
        if (recorder.isRecording) return
        recorder.start()
        recordJob = viewModelScope.launch {
            location.location.collect { loc ->
                if (loc != null) {
                    recorder.add(loc.latitude, loc.longitude, loc.speed, loc.accuracy, loc.time)
                    // Dash compass heading: GPS direction of travel, only trusted when moving.
                    session.updateGps(loc.bearing.takeIf { loc.hasBearing() && loc.speed >= BEARING_MIN_SPEED })
                }
            }
        }
    }

    private fun stopRecording() {
        recordJob?.cancel(); recordJob = null
        if (!recorder.isRecording) return
        val ride = recorder.stop() ?: return   // null = trivial session, don't save
        repo.addRide(ride)   // self-scoped on the repo (survives ViewModel teardown)
        android.util.Log.i("DashViewModel", "Ride saved: ${"%.1f".format(ride.distanceKm)} km, ${ride.durationSec}s")
    }

    // ── Dash WiFi config (Settings) ──────────────────────────────────────────
    fun setSsid(s: String) { dashConfig.ssid = s.trim(); _ui.update { it.copy(ssid = s.trim()) } }
    fun setWifiPassword(p: String) { dashConfig.password = p; _ui.update { it.copy(wifiPassword = p) } }
    /** Forget the paired dash so the next connect rediscovers any RE_* dash by prefix. */
    fun forgetDash() { dashConfig.forgetDash(); _ui.update { it.copy(ssid = "") } }

    // ── Destination + routing ───────────────────────────────────────────────

    fun prefetchTiles(lat: Double, lng: Double) {
        val loc = location.location.value
        tiles.prefetch(lat, lng, loc?.latitude, loc?.longitude)
    }

    fun setDestinations(stops: List<com.example.wayfindr.data.SharedLocation>) {
        val validStops = stops.filter { it.lat != null && it.lng != null }
        val finalDestination = validStops.lastOrNull()
        val name = finalDestination?.name ?: "WayfindR"
        val lat = finalDestination?.lat
        val lng = finalDestination?.lng
        routeStops = validStops
        // New destination picked by the rider → fresh Routes API budget and reroute backoff.
        autoRouteCalls = 0; autoRouteCapLogged = false; rerouteStreak = 0; lastRerouteAt = 0L
        activeStopIndex = 0
        routeLegOffset = 0
        routeWaypoints = validStops.dropLast(1).map { GeoPoint(it.lat!!, it.lng!!) }
        _ui.update { it.copy(
            destinationName = name, hasRoute = false,
            destLatLng = if (lat != null && lng != null) lat to lng else null,
            routePoints = emptyList(),
            routeTraffic = emptyList(),
        ) }
        destLat = lat
        destLng = lng
        route = null
        // Prepare the same renderer-backed preview used by the physical dash before the Wi-Fi
        // handshake finishes, so DashScreen does not switch to a different map presentation.
        if (frameBitmap == null) {
            frameBitmap = newFrameBitmap()
        }
        lastSignature = ""
        progressM = 0.0
        smoothEtaSec = 0.0; etaArrivalMs = 0L; displayedEtaMin = null   // fresh ETA for the new route
        voice.resetTrip()   // fresh announcements for the new route
        session.updateRouteCard(name)
        if (lat != null && lng != null) {
            val loc = location.lastKnown()
            tiles.prefetch(lat, lng, loc?.latitude, loc?.longitude)
            fetchRoute(finalDestination!!.lat!!, finalDestination.lng!!, routeWaypoints)
        }
    }

    /** Backwards-compatible single-destination entry point. */
    fun setDestination(name: String, lat: Double?, lng: Double?) =
        setDestinations(listOf(com.example.wayfindr.data.SharedLocation(name, lat, lng)))

    /** Drop the destination/route → free roam. The map keeps streaming and follows the rider. */
    fun exitNavigation() {
        destLat = null
        destLng = null
        routeWaypoints = emptyList()
        routeStops = emptyList()
        activeStopIndex = 0
        routeLegOffset = 0
        route = null
        progressM = 0.0
        offRouteSince = 0L
        panX = 0f; panY = 0f; followMode = true
        smoothEtaSec = 0.0; etaArrivalMs = 0L; displayedEtaMin = null
        voice.resetTrip()
        lastSignature = ""   // force a redraw with no route line
        _ui.update { it.copy(
            destinationName = null,
            hasRoute = false,
            destLatLng = null,
            routePoints = emptyList(),
            routeTraffic = emptyList(),
            remainingKm = null,
            etaMinutes = null,
            maneuver = null,
            offRoute = false,
            followMode = true,
        ) }
        session.updateRouteCard("WayfindR")   // dash card → name + 0.0 km, nav off
    }

    /** Compute the road route now (while internet is reachable) and cache it. */
    private fun fetchRoute(destLatV: Double, destLngV: Double, waypoints: List<GeoPoint>) {
        val loc = location.lastKnown()
        if (loc == null) {
            android.util.Log.w("DashViewModel", "fetchRoute: no origin location yet")
            return
        }
        viewModelScope.launch {
            val r = Router.route(GeoPoint(loc.latitude, loc.longitude), GeoPoint(destLatV, destLngV), waypoints)
            if (r != null) {
                route = r
                lastTrafficRefreshAt = System.currentTimeMillis()
                tiles.prefetchRoute(r.geometry)
                _ui.update { it.copy(hasRoute = true, routePoints = r.geometry, routeTraffic = r.traffic) }
                android.util.Log.i("DashViewModel", "Route ready: ${r.geometry.size} pts, ${r.totalMeters.toInt()} m")
            } else {
                android.util.Log.w("DashViewModel", "Router returned null")
            }
        }
    }

    // ── Map controls ────────────────────────────────────────────────────────

    /**
     * Joystick input for the on-dash destination picker. Returns a log label when the event was
     * consumed by the picker (or opened it), or null to let the normal map/media handling run.
     * Runs on the main thread (DashSession posts onButton there).
     */
    private fun handlePickerInput(code: Int): String? {
        val now = System.currentTimeMillis()
        val doubleTapMode = dashConfig.pickerDoubleTap.value
        // Is this the "open / select" gesture? If it follows a plain RIGHT tap closely, that tap's
        // effect (a zoom-out, or a move down the list) gets undone below.
        val g = pickerGesture.onCode(code, now, doubleTapMode)
        if (code == BTN_JOY_DOWN && pickerItems == null && !g.activate) {
            val gap = if (lastLoneDownMs > 0L) "${now - lastLoneDownMs} ms after the previous lone DOWN" else "first"
            com.example.wayfindr.data.RideDiagnostics.log("picker",
                "single DOWN ($gap) — waiting up to ${DOUBLE_DOWN_MS} ms for a second press")
            lastLoneDownMs = now
            if (canOpenPicker()) { downHintUntilMs = now + DOUBLE_DOWN_MS; wakeFrameLoop() }
        }
        if (code == BTN_JOY_DOWN && g.activate) downHintUntilMs = 0L
        // Plain RIGHT tap: remember the state before it acts, in case it turns out to be the first
        // half of a double-tap (or is followed by a long-press code).
        if (g.plainTap) { zoomBeforeRightTap = zoom }
        val activate = g.activate
        val undoPrevTap = g.undoPrevTap
        val gesture = if (code == BTN_JOY_DOWN) "Double DOWN" else "Double-tap"

        if (pickerItems != null) {
            // Menu open: RIGHT opens/uses the highlighted row, LEFT goes back a page (closes from
            // the first page), DOWN (0x15) moves the highlight down, wrapping and skipping section
            // headers. UP is deliberately unused (rider's choice).
            pickerLastInputMs = now
            pickerInputAtMs = now
            wakeFrameLoop()   // show the move on the very next frame, not at the next 250 ms grid point
            return when (code) {
                BTN_JOY_RIGHT -> "Picker RIGHT → " + selectPickerItem()
                BTN_JOY_LEFT -> if (menuPage != MenuPage.ROOT) {
                    // Back to the row that opened this page.
                    pickerIndex = if (menuPage == MenuPage.CANCEL && hasDestination()) 1 else 0
                    menuPage = MenuPage.ROOT; "Picker back → menu"
                } else { closePicker("cancelled (LEFT)"); "Picker cancelled" }
                BTN_JOY_DOWN, BTN_REJECT -> {
                    pickerIndex = nextMenuIndex(menuRows(), pickerIndex); "Picker down → $pickerIndex"
                }
                else -> "Picker: ignored 0x%02X".format(code)
            }
        }
        if (activate) {
            if (!canOpenPicker()) {
                com.example.wayfindr.data.RideDiagnostics.log("picker",
                    "$gesture ignored — not streaming to the dash")
                return null
            }
            if (undoPrevTap && zoom != zoomBeforeRightTap) setZoom(zoomBeforeRightTap)   // undo the tap's zoom
            openPicker()
            wakeFrameLoop()
            return "$gesture → open destination picker"
        }
        return null
    }

    /** Any time we're streaming — with a route set, picking an entry replaces it. (Free-roam-only
     *  blocked the rider's double-tap mid-route on 2026-10-16.) */
    private fun canOpenPicker() = session.state.value == DashState.STREAMING || debugInjecting

    /** True while a debug-build adb broadcast is feeding a joystick code (see [debugButtonRx]). */
    @Volatile private var debugInjecting = false

    /**
     * Debug builds only: `adb shell am broadcast -a com.wayfindr.DASH_BUTTON -p com.wayfindr.app
     * --ei code 21` feeds a dash joystick code (21 = DOWN 0x15, 19 = RIGHT 0x13, 20 = LEFT 0x14)
     * through the same handler as the bike, so the on-dash menu can be exercised without the dash.
     * `--ez dump true` logs the trip state ([debug] lines in the ride log and logcat).
     * `--ez snap true` draws one dash frame at the last GPS fix into files/diag/snap.png.
     */
    private val debugButtonRx: android.content.BroadcastReceiver? =
        if (!com.example.wayfindr.BuildConfig.DEBUG) null else object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context, intent: android.content.Intent) {
                val code = intent.getIntExtra("code", -1)
                if (code >= 0) {
                    debugInjecting = true
                    try { session.onButton?.invoke(code.toByte()) } finally { debugInjecting = false }
                }
                if (intent.getBooleanExtra("snap", false)) {
                    // Draw one dash frame at the last fix and save it as diag/snap.png (no dash needed).
                    val loc = location.lastKnown()
                    if (frameBitmap == null) frameBitmap = newFrameBitmap()
                    if (loc != null) redrawFrame(loc.latitude, loc.longitude, 0f, 0f, null)
                    val dir = getApplication<android.app.Application>().getExternalFilesDir("diag")
                    frameBitmap?.let { bmp ->
                        java.io.File(dir, "snap.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    }
                    android.util.Log.i("DashViewModel", "DEBUG snap saved (fix=${loc != null})")
                }
                if (intent.getBooleanExtra("dump", false)) {
                    val r = route
                    val msg = "stops=${routeStops.map { it.name }} active=$activeStopIndex " +
                        "dest=${destLat?.let { "%.5f".format(it) }},${destLng?.let { "%.5f".format(it) }} " +
                        "route=${r?.geometry?.size ?: 0} pts ${r?.totalMeters?.toInt() ?: 0} m " +
                        "end=${r?.geometry?.lastOrNull()?.let { "%.5f,%.5f".format(it.lat, it.lng) }} " +
                        "menu=${if (pickerItems == null) "closed" else "$menuPage@$pickerIndex"} " +
                        "notice=${notice?.takeIf { System.currentTimeMillis() < noticeUntilMs }}" +
                        " zoom=$zoom range=${zoomRange()} map=${dashConfig.mapSource.value}"
                    android.util.Log.i("DashViewModel", "DEBUG $msg")
                    com.example.wayfindr.data.RideDiagnostics.log("debug", msg)
                }
            }
        }

    init {
        // Declared after the property it registers (init blocks run in declaration order).
        debugButtonRx?.let {
            val filter = android.content.IntentFilter("com.wayfindr.DASH_BUTTON")
            val app = getApplication<android.app.Application>()
            if (android.os.Build.VERSION.SDK_INT >= 33) app.registerReceiver(it, filter, android.content.Context.RECEIVER_EXPORTED)
            else app.registerReceiver(it, filter)
        }
    }

    private fun openPicker() {
        viewModelScope.launch {
            // Newest first (the query orders by created_ms DESC), below the "Source" row.
            val saved = withContext(Dispatchers.IO) { runCatching { repo.savedLocations() }.getOrDefault(emptyList()) }
            menuPage = MenuPage.ROOT
            pickerIndex = 0
            pickerLastInputMs = System.currentTimeMillis()
            pickerItems = saved
            com.example.wayfindr.data.RideDiagnostics.log("picker", "opened with ${saved.size} destination(s)")
        }
    }

    private fun closePicker(reason: String) {
        if (pickerItems == null) return
        pickerItems = null
        menuPage = MenuPage.ROOT
        com.example.wayfindr.data.RideDiagnostics.log("picker", "closed: $reason")
    }

    private fun hasDestination() = destLat != null && destLng != null

    /** Stops still ahead, the active one first. */
    private fun stopsAhead() = routeStops.drop(activeStopIndex)

    /** "Cancel next stop" only makes sense with another stop after the one being ridden to. */
    private fun canSkipStop() = hasDestination() && stopsAhead().size >= 2

    /** Rows before the saved destinations on ROOT: "Source", and "Cancel" while navigating. */
    private fun rootFixedRows() = if (hasDestination()) 2 else 1

    /** Rows of the menu page showing now. */
    private fun menuRows(): List<MapRenderer.MenuRow> = when (menuPage) {
        MenuPage.ROOT -> listOf(MapRenderer.MenuRow("Source", submenu = true)) +
            (if (hasDestination()) listOf(MapRenderer.MenuRow("Cancel", submenu = true)) else emptyList()) +
            (pickerItems ?: emptyList()).map { MapRenderer.MenuRow(it.name) }
        MenuPage.CANCEL -> cancelRows().map { MapRenderer.MenuRow(it.first) }
        MenuPage.SOURCE -> {
            val map = dashConfig.mapSource.value
            val routing = dashConfig.routingSource.value
            listOf(MapRenderer.MenuRow("Map source", header = true)) +
                MENU_MAP_SOURCES.map { (s, label) -> MapRenderer.MenuRow(label, active = s == map) } +
                MapRenderer.MenuRow("Routing source", header = true) +
                MENU_ROUTING_SOURCES.map { (s, label) -> MapRenderer.MenuRow(label, active = s == routing) }
        }
    }

    /** CANCEL page rows (label, action id). */
    private fun cancelRows(): List<Pair<String, String>> = buildList {
        if (canSkipStop()) add("Cancel next stop: ${stopsAhead().first().name}" to "skip")
        if (hasDestination()) add("Cancel navigation" to "end")
    }

    /** Next row the cursor can land on (wrapping, skipping headers). */
    private fun nextMenuIndex(rows: List<MapRenderer.MenuRow>, from: Int): Int {
        var i = from
        repeat(rows.size) {
            i = (i + 1) % rows.size
            if (!rows[i].header) return i
        }
        return from
    }

    private fun selectPickerItem(): String {
        val items = pickerItems ?: return "Picker: nothing to select"
        if (menuPage == MenuPage.SOURCE) {
            // Rows: 0 header, 1..3 map sources, 4 header, 5..7 routing sources. Stays on the page
            // so the green tick moves to the new choice.
            val mapAt = pickerIndex - 1
            val routeAt = pickerIndex - 2 - MENU_MAP_SOURCES.size
            return when {
                mapAt in MENU_MAP_SOURCES.indices -> {
                    val (s, label) = MENU_MAP_SOURCES[mapAt]
                    dashConfig.setMapSource(s)
                    com.example.wayfindr.data.RideDiagnostics.log("picker", "map source → $label (from the dash)")
                    "Picker → map source $label"
                }
                routeAt in MENU_ROUTING_SOURCES.indices -> {
                    val (s, label) = MENU_ROUTING_SOURCES[routeAt]
                    dashConfig.setRoutingSource(s)
                    com.example.wayfindr.data.RideDiagnostics.log("picker",
                        "routing source → $label (from the dash; used from the next route/reroute)")
                    "Picker → routing source $label"
                }
                else -> "Picker: nothing to select"
            }
        }
        if (menuPage == MenuPage.CANCEL) {
            return when (cancelRows().getOrNull(pickerIndex)?.second) {
                "skip" -> {
                    val ahead = stopsAhead()
                    closePicker("skipped stop ${ahead.first().name}")
                    com.example.wayfindr.data.RideDiagnostics.log("picker",
                        "cancel next stop ${ahead.first().name} → routing to ${ahead.drop(1).first().name} (from the dash)")
                    // Re-plan from here through the stops after it (a new route request).
                    setDestinations(ahead.drop(1))
                    showNotice("Skipped ${ahead.first().name}", "→ ${ahead.drop(1).first().name}")
                    "Picker → skipped ${ahead.first().name}"
                }
                "end" -> {
                    closePicker("navigation cancelled")
                    com.example.wayfindr.data.RideDiagnostics.log("picker", "navigation cancelled (from the dash)")
                    exitNavigation()
                    showNotice("Navigation cancelled")
                    "Picker → navigation cancelled"
                }
                else -> { menuPage = MenuPage.ROOT; pickerIndex = 0; "Picker: nothing to cancel" }
            }
        }
        if (pickerIndex == 0) {
            menuPage = MenuPage.SOURCE
            pickerIndex = 1
            return "Picker → Source page"
        }
        if (pickerIndex == 1 && hasDestination()) {
            menuPage = MenuPage.CANCEL
            pickerIndex = 0
            return "Picker → Cancel page"
        }
        val loc = items.getOrNull(pickerIndex - rootFixedRows())
        if (loc == null) { closePicker("cancel selected"); return "Picker cancelled" }
        closePicker("selected ${loc.name}")
        // Same stops the app uses when a saved place is tapped (multi-stop itineraries included).
        setDestinations(loc.waypoints.ifEmpty {
            listOf(com.example.wayfindr.data.SharedLocation(name = loc.name, lat = loc.lat, lng = loc.lng))
        })
        return "Picker → navigate to ${loc.name}"
    }

    private fun zoomInCode()  = if (dashConfig.invertZoom.value) BTN_MAP_ZOOM_OUT else BTN_MAP_ZOOM_IN
    private fun zoomOutCode() = if (dashConfig.invertZoom.value) BTN_MAP_ZOOM_IN else BTN_MAP_ZOOM_OUT
    fun zoomIn()  = setZoom(zoom + 1)
    fun zoomOut() = setZoom(zoom - 1)
    /**
     * Dash zoom range: 11–20, narrowed to the offline file's own zoom levels while it's the map
     * source, so the joystick can't zoom into enlarged (blurry) or missing tiles.
     */
    private fun zoomRange(): IntRange {
        val off = tiles.offline ?: return DASH_MIN_ZOOM..DASH_MAX_ZOOM
        val lo = off.minZoom.coerceIn(DASH_MIN_ZOOM, DASH_MAX_ZOOM)
        return lo..off.maxZoom.coerceIn(lo, DASH_MAX_ZOOM)
    }

    /** Zoom before the offline range pulled it in, restored when the offline map is turned off. */
    @Volatile private var zoomBeforeOfflineClamp: Int? = null

    private fun setZoom(z: Int) {
        val clamped = z.coerceIn(zoomRange())
        if (clamped == zoom) return
        zoom = clamped
        _ui.update { it.copy(mapZoom = zoom) }
        // Make zoom feel instant: force a redraw on the very next tick (the renderer bridges the
        // gap with scaled neighbouring-zoom tiles), and warm the real tiles for the new level so
        // the scaled view sharpens in quickly.
        lastSignature = ""
        zoomBadgeUntilMs = System.currentTimeMillis() + ZOOM_BADGE_MS
        wakeFrameLoop()
        // No forced IDR here any more: zoom keyframes came out 65–100 KB (ride 2026-10-16), i.e.
        // 3–4 s of the dash's ~25 KB/s video budget in one burst, and the new view took seconds
        // to appear. An ordinary frame stays in budget; the 1 s periodic IDR sharpens it after.
        zoomPressedAtMs = System.currentTimeMillis()
        if (camInit) tiles.prefetchZoom(camLat, camLng, zoom)
    }
    fun panBy(dx: Float, dy: Float) = manualPan(dx, dy)
    fun recenter() {
        panX = 0f; panY = 0f; followMode = true
        _ui.update { it.copy(followMode = true) }
    }
    fun toggleHeadingUp() {
        headingUp = !headingUp
        _ui.update { it.copy(headingUp = headingUp) }
    }

    fun setMapDark(dark: Boolean) { dashConfig.setMapDarkMode(dark); dashConfig.setMapDarkAuto(false) }
    fun setMapDarkAuto(auto: Boolean) = dashConfig.setMapDarkAuto(auto)

    private fun manualPan(dx: Float, dy: Float) {
        panX += dx; panY += dy
        followMode = false
        lastManualPanAt = System.currentTimeMillis()
        _ui.update { it.copy(followMode = false) }
    }

    // ── Video + nav loop ────────────────────────────────────────────────────

    private fun startStream() {
        com.example.wayfindr.data.RideDiagnostics.log("stream", "startStream — encoder up, RTP→dash beginning")
        videoPtsMs = 0L
        val packetizer = RtpPacketizer { rtpPkt -> session.sendRtp(rtpPkt) }
        val nalProc    = NalProcessor { nal, _ ->
            // Even, monotonic presentation timestamp (set per frame by the send loop) instead of
            // wall clock — so the dash sees a clean, evenly-spaced timeline regardless of tiny
            // send-time wobble. All NALs of one frame share the same value.
            packetizer.packetize(nal, endOfAU = true, wallClockMs = videoPtsMs)
        }
        val onEncoded: (ByteArray, Boolean) -> Unit = { annexB, isKey ->
            nalProc.process(annexB)
            // First encoded frame leaving the phone — its delay after READY is the prime suspect
            // for the dash's "Timeout!" (the dash waits for video and gives up if it's late).
            if (!loggedFirstFrame) {
                loggedFirstFrame = true
                com.example.wayfindr.data.RideDiagnostics.log("stream", "first video frame sent (key=$isKey, ${annexB.size}B)")
            }
            if (annexB.size > BIG_FRAME_BYTES) com.example.wayfindr.data.RideDiagnostics.log("stream",
                "big frame: ${annexB.size / 1024} KB (key=$isKey) — budget is ~6 KB/frame")
            val pickAt = pickerInputAtMs
            if (pickAt > 0L) {
                pickerInputAtMs = 0L
                com.example.wayfindr.data.RideDiagnostics.log("picker",
                    "list change → frame sent in ${System.currentTimeMillis() - pickAt} ms (key=$isKey, ${annexB.size}B)")
            }
            val zoomAt = zoomPressedAtMs
            if (zoomAt > 0L) {
                zoomPressedAtMs = 0L
                com.example.wayfindr.data.RideDiagnostics.log("button",
                    "zoom → first frame sent in ${System.currentTimeMillis() - zoomAt} ms (key=$isKey, ${annexB.size}B)")
            }
            // Atomic update: this runs on the encoder's callback thread, concurrent with the
            // frame loop's _ui writes — a plain copy() read-modify-write would drop updates.
            _ui.update { it.copy(frameCount = it.frameCount + 1) }
        }
        encoder?.release()
        encoder = DashEncoder(onEncoded).also { it.prepare() }

        // Handover from the handshake ticker to the high-performance stream loop.
        tickerJob?.cancel(); tickerJob = null
        frameBitmap = newFrameBitmap()
        lastSignature = ""
        // Fresh camera so it snaps to the first fix instead of gliding from a stale spot.
        camInit = false; lastTickNs = 0L; lastFixTime = 0L; heldRider = null

        session.startStreaming()
        startMediaForwarding()
        location.location.value?.let { tiles.prefetch(it.latitude, it.longitude) }

        streamJob = viewModelScope.launch(Dispatchers.Default) {
            var lastPrefetch = 0L
            var failures = 0
            // Motion-adaptive, absolute-grid pacing (RE-style). Each iteration tick() tells us whether
            // the map actually changed; we hold the fast profile (FRAME_MS_ACTIVE) for MOTION_HOLD_MS
            // after the last change, then settle to the slow profile (FRAME_MS_IDLE). The grid sleeps
            // only the REMAINDER to the next frame so render+encode work doesn't make the cadence
            // wobble — uneven arrival is what makes the dash decoder jitter/teleport. videoPtsMs
            // advances by the interval actually used so RTP timestamps stay proportional to real time.
            var lastChangeMs = System.currentTimeMillis()
            var idleBitrate = false   // track the live profile so we only poke the encoder on a switch
            var liveBitrate = DashEncoder.BITRATE   // what the encoder was last configured/asked for
            var bitrateEncoder: DashEncoder? = null // the encoder that bitrate applies to (rebuilt on errors)
            var frameMs = FRAME_MS_ACTIVE
            var nextFrameMs = System.currentTimeMillis()
            var lastSendMs = System.currentTimeMillis()
            // The loop must NEVER die silently: the session's heartbeats keep the dash
            // connected, so a dead frame loop = frozen map with the connection "up".
            while (isActive && session.state.value == DashState.STREAMING) {
                try {
                    val changed = tick()
                    val nowMs = System.currentTimeMillis()
                    if (changed) lastChangeMs = nowMs
                    // Keep a steady connected-dash cadence. The dash can stop advancing its
                    // projection when the sender switches to the idle profile at standstill.
                    val active = true
                    frameMs = FRAME_MS_ACTIVE
                    val bmp = frameBitmap
                    val enc = encoder
                    if (bmp != null && enc != null) {
                        // Bitrate follows the "Higher video quality" setting; poke the encoder only on change.
                        val wantBitrate = if (dashConfig.highQualityVideo.value) DashEncoder.BITRATE_HQ else DashEncoder.BITRATE
                        if (idleBitrate || enc !== bitrateEncoder || wantBitrate != liveBitrate) {
                            enc.requestBitrate(wantBitrate)
                            liveBitrate = wantBitrate; bitrateEncoder = enc
                            idleBitrate = false
                            com.example.wayfindr.data.RideDiagnostics.log("stream", "video bitrate → ${wantBitrate / 1000} kbps")
                        }
                        // Scales a 2× "sharper map" frame down to 526×300 (plain copy at 1×).
                        enc.renderBitmap(bmp)
                        // Advance the presentation clock by THIS frame's interval BEFORE draining, so the
                        // frame(s) pulled this iteration carry an evenly-spaced RTP timestamp.
                        videoPtsMs += frameMs
                        enc.drain()
                    }
                    failures = 0
                    // Track the real send cadence (EMA + worst) so the ride log proves it's even.
                    val sendNow = System.currentTimeMillis()
                    val gap = sendNow - lastSendMs
                    lastSendMs = sendNow
                    sendPaceMs = if (sendPaceMs <= 0.0) gap.toDouble() else sendPaceMs + (gap - sendPaceMs) * 0.1
                    if (gap > sendPaceMaxMs) sendPaceMaxMs = gap
                    // Warm the tile cache ahead of the rider every ~20 s.
                    if (sendNow - lastPrefetch > 20_000) {
                        lastPrefetch = sendNow
                        location.location.value?.let { tiles.prefetch(it.latitude, it.longitude) }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failures++
                    android.util.Log.e("DashViewModel", "Frame loop error #$failures", e)
                    if (failures >= 3 && isActive) {   // never rebuild after teardown cancelled us
                        // MediaCodec is in an error state — rebuild the encoder so the
                        // stream recovers. The fresh encoder re-emits SPS/PPS, which the
                        // NAL processor bundles into the next IDR for the dash decoder.
                        runCatching { encoder?.release() }
                        encoder = runCatching { DashEncoder(onEncoded).also { it.prepare() } }
                            .onFailure { android.util.Log.e("DashViewModel", "Encoder rebuild failed", it) }
                            .getOrNull()
                        lastSignature = "" // force a full redraw on the next tick
                        failures = 0
                    }
                }
                // Sleep only the remaining time to the next grid point.
                nextFrameMs += frameMs
                val sleepMs = nextFrameMs - System.currentTimeMillis()
                if (sleepMs > 1) {
                    // Joystick input wakes us early; re-anchor the grid so the next frame follows.
                    if (kotlinx.coroutines.withTimeoutOrNull(sleepMs) { frameWake.receive() } != null)
                        nextFrameMs = System.currentTimeMillis()
                } else if (sleepMs < -frameMs) {
                    // Fell more than a frame behind (render spike) — re-anchor so we don't fire a
                    // burst of catch-up frames (which would itself look like a jump).
                    nextFrameMs = System.currentTimeMillis()
                }
            }
        }
    }

    /**
     * Compute nav state, push nav-info to the dash, and redraw the frame only if it changed.
     * @return true if the visible map content changed this tick (drives the motion-adaptive cadence).
     */
    private fun tick(): Boolean {
        // Revert to follow mode after the rider stops nudging the joystick.
        if (!followMode && System.currentTimeMillis() - lastManualPanAt > MANUAL_IDLE_MS) {
            panX = 0f; panY = 0f; followMode = true
            _ui.update { it.copy(followMode = true) }
        }

        val loc = location.location.value
        val r = route
        val dLat = destLat; val dLng = destLng

        // Default to raw GPS; map-matching below snaps it onto the route when on it.
        matchedLat = loc?.latitude
        matchedLng = loc?.longitude

        var remainingM: Double? = null
        var etaSec: Double? = null
        // 1. Calculate the TARGET heading (where we want the camera to point).
        // Priority: GPS bearing (if moving) > Route orientation > Last camera heading.
        var targetHeading = when {
            loc != null && loc.hasBearing() && loc.speed >= BEARING_MIN_SPEED -> normalizeHeading(loc.bearing)
            camInit -> normalizeHeading(camHdg)
            else -> 0f
        }

        var offRoute = false
        var recalculating = false

        if (r != null && loc != null) {
            val ns = trackProgress(r, GeoPoint(loc.latitude, loc.longitude))
            // If GPS bearing is weak or we are at low speed, use the route's current
            // segment bearing as the target so the map points the right way immediately.
            if (!loc.hasBearing() || loc.speed < BEARING_MIN_SPEED) {
                targetHeading = normalizeHeading(ns.heading)
            }
            if (advanceWaypointIfReached(loc, ns)) {
                // The route geometry already continues to the next leg; reset the displayed ETA
                // immediately while the tracker carries on from the same cumulative progress.
                smoothEtaSec = 0.0; etaArrivalMs = 0L; displayedEtaMin = null
            }
            // Keep the dash distance/ETA for the complete itinerary. Waypoint progression still
            // updates maneuver guidance, but the rider's total remaining distance must not reset
            // to the current leg after a multi-stop route is loaded.
            remainingM = ns.remainingM
            // Keep the visible marker on the rendered route when GPS is close enough to make
            // that match trustworthy. Once outside this band, retain raw GPS so a parallel road
            // is not falsely pulled onto the route.
            if (ns.snapDist <= 35.0) {
                matchedLat = ns.snapped.lat
                matchedLng = ns.snapped.lng
            }
            // Heading-aware off-route. A flyover (or its service road) runs PARALLEL to and almost
            // on top of the route, so flat GPS can sit well past the lateral threshold while the
            // rider is still effectively on the line. Distance alone then cries "off-route" and
            // triggers a sudden reroute. So in the ambiguous band we require the heading to ALSO
            // disagree (a genuine divergence / wrong turn); a clearly different road (very far) is
            // off-route regardless. This is the main flyover-confusion mitigation.
            lastSnapDist = ns.snapDist
            val headingKnown = loc.hasBearing() && loc.speed >= BEARING_MIN_SPEED
            val headingOff = headingKnown && angleDelta(loc.bearing, ns.heading) > 50f
            offRoute = when {
                ns.snapDist > 150.0 -> true       // unmistakably a different road → off regardless
                ns.snapDist > 70.0  -> headingOff // parallel/flyover band: off ONLY if heading diverges
                else -> false                     // (stopped here → not off-route; decide once moving)
            }
            val nowMs = System.currentTimeMillis()

            // "Recalculating" = off the line for >0.8 s (ignores a 1-frame GPS blip), OR a reroute
            // request is in flight. While true the distance is measured against a route we've left,
            // so the ETA is meaningless — we FREEZE it (and the pill says "Recalculating") rather
            // than let the number lurch until the fresh route lands. recalcHoldUntil keeps the pill
            // up a beat after the new route lands so a fast reroute is still visible to the rider.
            if (offRoute) { if (offRouteStreakStart == 0L) offRouteStreakStart = nowMs }
            else offRouteStreakStart = 0L
            val rawRecalc = rerouting || (offRouteStreakStart != 0L && nowMs - offRouteStreakStart > 800)
            if (rawRecalc) recalcHoldUntil = nowMs + 1_500
            recalculating = rawRecalc || nowMs < recalcHoldUntil

            if (!recalculating) {
                // ETA from GOOGLE's traffic-aware duration for the part of the route still ahead
                // (route.totalSeconds × remaining / total; the 2-min traffic refresh keeps it fresh).
                // It used to be remaining ÷ our own rolling speed, floored at 3 m/s before we'd
                // moved — Udupi (~400 km) read 37 h against Google's 7 h 37 m.
                if (loc.speed > 0.5f) {
                    smoothSpeedMps = if (smoothSpeedMps <= 0.0) loc.speed.toDouble()
                    else smoothSpeedMps + (loc.speed - smoothSpeedMps) * 0.05
                }
                val rawEta = if (r.totalMeters > 0.0 && r.totalSeconds > 0.0)
                    r.totalSeconds * (ns.remainingM / r.totalMeters).coerceIn(0.0, 1.0)
                else ns.remainingM / smoothSpeedMps.coerceAtLeast(8.0)   // no duration from Google
                smoothEtaSec = if (smoothEtaSec <= 0.0 || r !== etaRoute) rawEta
                    else smoothEtaSec + (rawEta - smoothEtaSec) * 0.08
                if (r !== etaRoute) { etaRoute = r; etaArrivalMs = 0L }   // refresh the shown value now
                // Refresh the displayed minutes + arrival clock only every 5 s so they read steady
                // (like Google Maps) instead of flickering every second.
                if (etaArrivalMs == 0L || nowMs - lastArrivalCalcMs > 5_000) {
                    etaArrivalMs = nowMs + (smoothEtaSec * 1000).toLong()
                    displayedEtaMin = Math.round(smoothEtaSec / 60.0).toInt()
                    lastArrivalCalcMs = nowMs
                }
            }
            etaSec = if (smoothEtaSec > 0.0) smoothEtaSec else null
            // Feed the dash's own turn-by-turn widget: the next turn's glyph, distance to it,
            // total remaining and the (held) arrival time. No turn left → destination flag.
            val (pv, pu) = toDashDistance(ns.nextTurnM)
            val (tv, tu) = toDashDistance(ns.remainingM)
            val etaHHMM = etaSec?.let {
                val arrival = java.util.Calendar.getInstance().apply { add(java.util.Calendar.SECOND, it.toInt()) }
                "%02d%02d".format(arrival.get(java.util.Calendar.HOUR_OF_DAY), arrival.get(java.util.Calendar.MINUTE))
            }
            val glyph = ns.nextManeuver?.dashCode ?: com.example.wayfindr.dash.nav.DashGlyph.DESTINATION
            // "Then ..." turn: only for back-to-back junctions (the turn after the next one comes
            // within SECONDARY_TURN_MAX_GAP_M), like Google Maps' secondary instruction. The
            // distance sent is the gap between the two turns. Otherwise "none".
            val next = ns.nextManeuver
            val second = ns.secondManeuver
            val gap = if (next != null && second != null) second.cumulativeMeters - next.cumulativeMeters else null
            val (secGlyph, secDist, secUnit) = if (second != null && gap != null && gap <= SECONDARY_TURN_MAX_GAP_M) {
                val (sv, su) = toDashDistance(gap)
                Triple(second.dashCode, sv, su)
            } else Triple(DashCommands.NAV_SECONDARY_NONE, 0, DashCommands.NAV_UNIT_METERS)
            session.updateNavInfo(glyph, pv, pu, tv, tu, etaHHMM, secGlyph, secDist, secUnit)
            // Spoken/chime turn guidance (no-op when voice mode is OFF).
            // Voice output must never be allowed to cancel the navigation ticker. TTS providers
            // can throw during initialization or audio-route changes while the map must continue.
            runCatching {
                voice.maybeAnnounce(ns.nextManeuver, ns.nextTurnM, ns.remainingM)
            }.onFailure {
                android.util.Log.w("DashViewModel", "Voice guidance failed: ${it.message}")
            }
        } else if (loc != null && dLat != null && dLng != null) {
            remainingM = GeoPoint.distMeters(
                GeoPoint(loc.latitude, loc.longitude), GeoPoint(dLat, dLng)
            )
        }

        // Recompute the route if the rider has clearly left it for a few seconds.
        maybeReroute(offRoute, loc)
        maybeRefreshTraffic(loc)

        // Periodic ride snapshot (~15 s) — GPS quality, motion, frame throughput and thermal,
        // so a post-ride read shows where the map stuttered (weak GPS) or the dash struggled.
        val nowD = System.currentTimeMillis()
        if (nowD - lastDiagMs > 15_000) {
            lastDiagMs = nowD
            com.example.wayfindr.data.RideDiagnostics.log(
                "ride",
                "gps=" + (loc?.let { "acc=%.0fm spd=%.1fm/s (%.0fkm/h)".format(it.accuracy, it.speed, it.speed * 3.6) } ?: "none") +
                        " ridden=%.2fkm".format(recorder.distanceSoFarM / 1000.0) +
                        " fixGap=%.0fms".format(gpsFixIntervalMs) +
                        " frames=${_ui.value.frameCount} thermal=${_ui.value.thermal}" +
                        " pace=%.0fms/max=%dms".format(sendPaceMs, sendPaceMaxMs) +  // send cadence (≈250ms active / 500ms idle)
                        " remaining=" + (remainingM?.let { "%.1fkm".format(it / 1000.0) } ?: "-") +
                        " offRoute=$offRoute" +
                        (if (lastSnapDist >= 0) " snap=%.0fm".format(lastSnapDist) else ""),
            )
            sendPaceMaxMs = 0L  // reset the worst-case window each diag line
        }

        // ── Predictive, framerate-independent camera (CarPlay-smooth) ──
        // Capture each fresh GPS fix + its velocity for dead-reckoning.
        if (loc != null && loc.time != lastFixTime) {
            lastFixTime = loc.time
            // Track the GAP between fixes (EMA): if this balloons past ~1 s with the screen off,
            // GPS callbacks are being throttled — that's what the predictor has to bridge and the
            // root of any residual "stuck then teleport". Logged in the periodic ride line.
            val nowWall = System.currentTimeMillis()
            if (fixWallMs > 0L) {
                val gap = (nowWall - fixWallMs).toDouble()
                gpsFixIntervalMs = if (gpsFixIntervalMs <= 0.0) gap else gpsFixIntervalMs + (gap - gpsFixIntervalMs) * 0.3
            }
            fixLat = matchedLat ?: loc.latitude; fixLng = matchedLng ?: loc.longitude
            fixBearing = loc.bearing; fixSpeed = loc.speed
            fixWallMs = nowWall
        }

        // GPS health after applying this tick's fix, so a just-accepted update isn't still
        // classified from the previous gap. LOST is "no usable lock", not "slow for a few seconds".
        val fixAgeMs = if (fixWallMs > 0L) System.currentTimeMillis() - fixWallMs else Long.MAX_VALUE
        gpsStatus = when {
            loc == null || fixAgeMs > GPS_LOST_AGE_MS -> GpsStatus.LOST
            loc.accuracy > 25f || gpsFixIntervalMs > 2_500 || fixAgeMs > GPS_WEAK_AGE_MS -> GpsStatus.WEAK
            else -> GpsStatus.GOOD
        }

        // Extrapolate where the rider IS NOW (last fix + velocity·elapsed), so the camera
        // doesn't trail the bike between 1 Hz fixes. Only predict while genuinely moving.
        val rider: Pair<Double, Double>? = when {
            loc == null -> null
            fixSpeed > 0.5f -> {
                // Dead-reckon forward from the last fix so the camera keeps GLIDING through GPS
                // gaps instead of freezing then snapping ("stuck then jump"). predictedDistance
                // runs at full speed for the first ~1 s (the normal 1 Hz fix gap) then tapers, so
                // a long dropout — or a brake/stop the predictor hasn't heard about yet — can't
                // fling the camera far ahead; the next fix then needs only a tiny eased correction.
                // elapsed = time since the fix arrived; + DISPLAY_LEAD_S leads the marker past
                // "now" to cancel the camera-ease + encode + RTP + dash-decode/display latency, so
                // the dash tracks the bike instead of trailing ~0.5 s behind. The predictor's taper
                // still caps how far a long dropout / unannounced brake can fling it.
                val elapsed = (System.currentTimeMillis() - fixWallMs) / 1000.0
                // Weak-GPS guard: a noisy fix has an unreliable position AND bearing/speed, so
                // extrapolating from it flings the view ("teleport"). Scale the prediction down as
                // accuracy worsens — but only for GENUINELY poor fixes: full up to 25 m, none above
                // 50 m. The old 12 m cutoff scaled prediction DOWN at the moderate accuracy that's
                // normal at speed, so the predictor undershot → the camera lagged → each new fix
                // snap-corrected forward = the "jumping when moving fast". Widening it keeps the
                // dead-reckon gliding through the 1 Hz gaps at speed while still guarding bad fixes.
                val conf = ((50f - (loc.accuracy)) / 25f).toDouble().coerceIn(0.0, 1.0)
                val dist = predictedDistance(fixSpeed, elapsed + DISPLAY_LEAD_S) * conf
                // Remember where the bike actually is, so that when it stops the marker holds the
                // real fix (not the lead-projected point ahead of it).
                heldRider = fixLat to fixLng
                project(fixLat, fixLng, fixBearing.toDouble(), dist)
            }
            else -> {
                // Stopped: hold position and IGNORE sub-accuracy GPS wander, so a parked bike's dot
                // doesn't drift or jitter. Only move the held point if the fix steps clearly outside
                // its own error radius (i.e. the bike really moved a little / GPS re-converged).
                val cand = matchedLat!! to matchedLng!!
                val h = heldRider
                val band = loc.accuracy.coerceIn(8f, 30f).toDouble()
                if (h == null || GeoPoint.distMeters(
                        GeoPoint(h.first, h.second), GeoPoint(cand.first, cand.second)
                    ) > band
                ) { heldRider = cand; cand } else h
            }
        }

        // Follow mode: track the rider. If GPS is lost, HOLD the last position instead of
        // jumping to the destination or (0,0). Manual pan mode tracks the panned centre.
        val targetLat = if (followMode) (rider?.first ?: camLat) else (rider?.first ?: camLat)
        val targetLng = if (followMode) (rider?.second ?: camLng) else (rider?.second ?: camLng)

        // ...but if we have NO camera state yet and NO rider, then and only then snap to dest.
        val finalTargetLat = if (!camInit && rider == null) (dLat ?: targetLat) else targetLat
        val finalTargetLng = if (!camInit && rider == null) (dLng ?: targetLng) else targetLng

        // Time-based smoothing: alpha derived from the real frame interval + a time
        // constant, so motion is equally smooth at any (dynamic) frame rate.
        val nowNs = System.nanoTime()
        val dt = if (lastTickNs == 0L) 0.042 else ((nowNs - lastTickNs) / 1e9).coerceIn(0.0, 0.5)
        lastTickNs = nowNs
        // Adaptive smoothing: ease HARDER (bigger time constant) when the fix is noisy so weak-GPS
        // jitter doesn't reach the camera, and stay snappy when the signal is clean. This is the
        // other half of the "stuck then jump" fix — the predictor bridges gaps, this absorbs the
        // lateral noise that makes a poor signal jitter.
        val acc = loc?.accuracy ?: 12f
        val tau = when {
            acc > 25f -> SMOOTH_TAU * 2.0   // poor signal → heavy smoothing
            acc > 12f -> SMOOTH_TAU * 1.5   // fair
            else      -> SMOOTH_TAU         // good → responsive
        }
        val a = if (camInit) (1.0 - Math.exp(-dt / tau)) else 1.0

        if (!camInit) {
            camLat = finalTargetLat
            camLng = finalTargetLng
            camHdg = normalizeHeading(targetHeading)
            camInit = true
        } else {
            camLat += (finalTargetLat - camLat) * a
            camLng += (finalTargetLng - camLng) * a
            // Smoothly rotate toward the target heading.
            val dh = (((targetHeading - camHdg) % 360f) + 540f) % 360f - 180f
            camHdg = normalizeHeading(camHdg + dh * a.toFloat())
        }
        frameRiderLat = if (rider != null) camLat else null
        frameRiderLng = if (rider != null) camLng else null

        val centerLat = if (camInit) camLat else 0.0
        val centerLng = if (camInit) camLng else 0.0
        val travelHeading = normalizeHeading(targetHeading)
        val camHeading = normalizeHeading(if (camInit) camHdg else travelHeading)

        val sig = buildString {
            // High resolution (6 dp ≈ 0.1 m, 0.1° heading) so every smoothed step redraws
            // for buttery motion. Safe from standstill jitter because the camera is fed the
            // SMOOTHED position (which settles and stops), not raw GPS.
            append("%.6f".format(centerLat)); append("%.6f".format(centerLng))
            append(zoom); append(panX.toInt()); append(panY.toInt())
            append(if (headingUp) (camHeading * 10).toInt() else 0)
            append(if (mapDark) 1 else 0)
            append((travelHeading * 10).toInt())
            append(remainingM?.let { (it / 100).toInt() } ?: -1) // 100 m resolution to avoid jitter
            append(if (r != null) r.geometry.size else 0)
            // ETA text is rendered into the encoded dash frame. Include its changing values here;
            // otherwise the phone UI can update while the dash keeps receiving an old bitmap
            // until a joystick action changes the map.
            append(displayedEtaMin ?: -1)
            append(etaArrivalMs / 1_000L)
            // The dash frame contains time-dependent content. A wall-clock token guarantees a
            // fresh Canvas render at standstill even when GPS and route geometry are unchanged.
            append(System.currentTimeMillis() / 1_000L)
            append(if (recalculating) 1 else 0)
            // Include the incoming-call caller so a call arriving forces an immediate redraw of the
            // banner even when the map is static (otherwise it'd wait for the 2 s forced refresh).
            append(com.example.wayfindr.media.CallInfoProvider.incomingCall.value?.takeIf { it.incoming }?.caller ?: "")
        }
        val now = System.currentTimeMillis()
        // A genuine content change (not the periodic forced refresh) is what marks the map as
        // "moving" for the adaptive cadence — a forced redraw of identical content stays "idle".
        val contentChanged = sig != lastSignature
        // Always refresh the source bitmap before encoding. The dash must receive a newly drawn
        // frame while stationary too; otherwise its projection can appear frozen until joystick input.
        lastSignature = sig
        lastRedrawAt = now
        // The encoded raster map follows the smoothed camera heading. ETA/GPS overlays remain
        // screen-space in MapRenderer, while cached tile labels rotate with this world layer.
        // Picker housekeeping: drop it after inactivity, or if a destination got set elsewhere.
        if (pickerItems != null) {
            if (System.currentTimeMillis() - pickerLastInputMs > PICKER_TIMEOUT_MS) closePicker("timeout")
            else if (!canOpenPicker()) closePicker("destination set / not streaming")
        }
        redrawFrame(centerLat, centerLng, camHeading, travelHeading, remainingM)

        // Publish nav figures to the phone UI.
        _ui.update { it.copy(
            hasGps = loc != null,
            gpsStatus = gpsStatus,
            riderLat = matchedLat,
            riderLng = matchedLng,
            riderBearing = travelHeading,
            camLat = centerLat,
            camLng = centerLng,
            camBearing = if (headingUp) camHeading else 0f,
            mapZoom = zoom,
            remainingKm = remainingM?.let { d -> d / 1000.0 },
            etaMinutes = if (etaSec != null) displayedEtaMin else null,
            maneuver = null,
            offRoute = offRoute,
            recalculating = recalculating,
        ) }
        updateThermal()

        return contentChanged
    }

    /**
     * Reroute when off the line for >2 s, with a backed-off wait between attempts and a per-
     * destination cap on automatic calls (see takeAutoRouteCall). Routes from
     * the live GPS position to the saved destination and swaps the polyline in. Needs
     * internet — available now because only the dash sockets are bound to the dash WiFi.
     */
    private fun maybeReroute(offRoute: Boolean, loc: android.location.Location?) {
        val finalStop = routeStops.lastOrNull()
        val dLat = finalStop?.lat ?: destLat
        val dLng = finalStop?.lng ?: destLng
        val now = System.currentTimeMillis()
        if (!offRoute || loc == null || dLat == null || dLng == null) {
            offRouteSince = 0L
            // Back on route for a while → next miss starts from the short wait again.
            if (rerouteStreak > 0 && now - lastRerouteAt > REROUTE_STREAK_RESET_MS) rerouteStreak = 0
            return
        }
        if (offRouteSince == 0L) offRouteSince = now
        // 2 s off-line confirms a real miss (not a GPS wobble). The wait between attempts backs off
        // while the rider stays off-route, so a stuck/noisy GPS can't fire a call every few seconds.
        val cooldown = (REROUTE_BASE_MS shl rerouteStreak.coerceAtMost(5)).coerceAtMost(REROUTE_MAX_MS)
        if (now - offRouteSince < 2_000 || now - lastRerouteAt < cooldown || rerouting) return
        if (!takeAutoRouteCall("reroute")) return
        rerouteStreak++
        lastRerouteAt = now
        rerouting = true
        val offSec = (now - offRouteSince) / 1000
        android.util.Log.i("DashViewModel", "Off-route ${offSec}s → rerouting")
        com.example.wayfindr.data.RideDiagnostics.log("reroute", "off-route ${offSec}s → requesting new route")
        val startMs = System.currentTimeMillis()
        viewModelScope.launch {
            val r = Router.route(GeoPoint(loc.latitude, loc.longitude), GeoPoint(dLat, dLng), routeWaypoints)
            val took = System.currentTimeMillis() - startMs
            if (r != null) {
                route = r
                lastTrafficRefreshAt = System.currentTimeMillis()
                progressM = 0.0
                routeLegOffset = activeStopIndex
                offRouteSince = 0L
                tiles.prefetchRoute(r.geometry)
                _ui.update { it.copy(hasRoute = true, routePoints = r.geometry, routeTraffic = r.traffic) }
                android.util.Log.i("DashViewModel", "Reroute ok: ${r.geometry.size} pts, ${r.totalMeters.toInt()} m")
                com.example.wayfindr.data.RideDiagnostics.log("reroute", "new route in ${took}ms (${r.geometry.size} pts, ${r.totalMeters.toInt()} m)")
            } else {
                android.util.Log.w("DashViewModel", "Reroute failed (no internet?)")
                com.example.wayfindr.data.RideDiagnostics.log("reroute", "FAILED after ${took}ms (no internet?)")
            }
            rerouting = false
        }
    }

    /** Move to the next itinerary stop once the rider reaches the end of the active route leg. */
    private fun advanceWaypointIfReached(loc: android.location.Location, nav: NavState): Boolean {
        val stops = routeStops
        val index = activeStopIndex
        if (index >= stops.lastIndex) return false // Final destination keeps the normal arrival behavior.
        val stop = stops[index]
        val lat = stop.lat ?: return false
        val lng = stop.lng ?: return false
        // Use both the actual stop distance and the leg progress. The second check avoids advancing
        // merely because two roads happen to pass close to the waypoint before reaching it.
        val closeToStop = GeoPoint.distMeters(GeoPoint(loc.latitude, loc.longitude), GeoPoint(lat, lng)) <= WAYPOINT_ARRIVE_M
        val legIndex = index - routeLegOffset
        val reachedLegEnd = route?.legEndCumulative?.getOrNull(legIndex)?.let { nav.progressM >= it - WAYPOINT_PROGRESS_TOLERANCE_M } ?: false
        if (!closeToStop && !reachedLegEnd) return false

        activeStopIndex = index + 1
        val next = stops[activeStopIndex]
        destLat = next.lat
        destLng = next.lng
        routeWaypoints = stops.subList(activeStopIndex + 1, stops.lastIndex)
            .map { GeoPoint(it.lat!!, it.lng!!) }
        voice.resetTrip()
        voice.announceNextStop(next.name)
        session.updateRouteCard(next.name)
        _ui.update { it.copy(destinationName = next.name, destLatLng = next.lat!! to next.lng!!) }
        android.util.Log.i("DashViewModel", "Waypoint ${index + 1}/${stops.size} reached; continuing to ${next.name}")
        return true
    }

    private fun remainingToActiveStop(r: Route, nav: NavState): Double {
        val legIndex = activeStopIndex - routeLegOffset
        val end = r.legEndCumulative.getOrNull(legIndex) ?: r.totalMeters
        return (end - nav.progressM).coerceAtLeast(0.0)
    }

    /** Refresh the active route every two minutes so congestion colours and ETA stay current. */
    private fun maybeRefreshTraffic(loc: android.location.Location?) {
        if (loc == null || route == null || rerouting || trafficRefreshInFlight) return
        // Free/offline routing has no traffic data, so a refresh would only load the router.
        if (dashConfig.routingSource.value != com.example.wayfindr.dash.RoutingSource.GOOGLE) return
        val now = System.currentTimeMillis()
        if (now - lastTrafficRefreshAt < TRAFFIC_REFRESH_MS) return
        val final = routeStops.lastOrNull() ?: return
        val finalLat = final.lat ?: return
        val finalLng = final.lng ?: return
        lastTrafficRefreshAt = now
        if (!takeAutoRouteCall("traffic refresh")) return
        trafficRefreshInFlight = true
        viewModelScope.launch {
            val refreshed = Router.route(
                GeoPoint(loc.latitude, loc.longitude), GeoPoint(finalLat, finalLng), routeWaypoints,
            )
            if (refreshed != null) {
                route = refreshed
                progressM = 0.0
                routeLegOffset = activeStopIndex
                // No tile prefetch here: a traffic refresh is the same road. Prefetching on every
                // 2-min refresh downloaded ~600 new tiles each time on a long route, even parked.
                _ui.update { it.copy(routePoints = refreshed.geometry, routeTraffic = refreshed.traffic) }
                android.util.Log.i("DashViewModel", "Traffic refreshed: ${refreshed.traffic.size} route intervals")
            }
            trafficRefreshInFlight = false
        }
    }

    /** Spend one automatic Routes API call from this destination's budget; false = over the cap. */
    private fun takeAutoRouteCall(kind: String): Boolean {
        if (autoRouteCalls >= MAX_AUTO_ROUTE_CALLS) {
            if (!autoRouteCapLogged) {
                autoRouteCapLogged = true
                com.example.wayfindr.data.RideDiagnostics.log("reroute",
                    "auto Routes API cap ($MAX_AUTO_ROUTE_CALLS) reached — no more $kind/reroutes for this destination")
            }
            return false
        }
        autoRouteCalls++
        com.example.wayfindr.data.RideDiagnostics.log("reroute", "$kind: auto Routes call $autoRouteCalls/$MAX_AUTO_ROUTE_CALLS")
        return true
    }

    private fun normalizeHeading(degrees: Float): Float {
        var value = degrees % 360f
        if (value < 0f) value += 360f
        return value
    }

    /** 2 when the "Sharper map" setting is on: the frame is drawn at 1052×600 and scaled down. */
    private fun renderScale() = if (dashConfig.sharperMap.value) 2 else 1

    private fun newFrameBitmap(): Bitmap = renderScale().let { s ->
        Bitmap.createBitmap(DashEncoder.WIDTH * s, DashEncoder.HEIGHT * s, Bitmap.Config.ARGB_8888)
    }

    private fun redrawFrame(
        centerLat: Double,
        centerLng: Double,
        cameraHeading: Float,
        travelHeading: Float,
        remainingM: Double?,
    ) {
        // Follow the "Sharper map" setting: swap the frame size and tile resolution together.
        // (The old bitmap isn't recycled — the encoder may still be reading it this tick.)
        val scale = renderScale()
        tiles.hiDpi = scale > 1
        var bmp = frameBitmap ?: return
        if (bmp.width != DashEncoder.WIDTH * scale) { bmp = newFrameBitmap(); frameBitmap = bmp }
        // Glanceable ETA — minutes remaining + a stable 12-hour arrival clock. Both come
        // from the smoothed estimate so they don't flicker every second.
        val mins = displayedEtaMin
        val arriving = mins != null && mins <= 0
        val recalculating = _ui.value.recalculating
        // Off-route / rerouting: tell the rider the ETA is being recomputed instead of showing a
        // frozen or wildly-swinging number.
        val etaPrimary = when {
            recalculating && route != null -> "Recalculating"
            mins != null && etaArrivalMs > 0L -> when {
                arriving   -> "Arriving"
                mins >= 60 -> "${mins / 60}h ${mins % 60}m"
                else       -> "$mins min"
            }
            else -> null
        }
        // 12-hour arrival clock; hidden once arriving or while recalculating.
        val etaSecondary = if (etaPrimary != null && !arriving && !recalculating)
            java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault()).format(java.util.Date(etaArrivalMs))
        else null
        val frame = MapRenderer.Frame(
            centerLat = centerLat,
            centerLng = centerLng,
            zoom = zoom,
            panX = panX,
            panY = panY,
            headingUp = headingUp,
            cameraHeading = cameraHeading,
            riderHeading = travelHeading,
            riderLat = frameRiderLat,
            riderLng = frameRiderLng,
            destLat = destLat,
            destLng = destLng,
            destinations = routeStops.mapNotNull { stop ->
                if (stop.lat != null && stop.lng != null) GeoPoint(stop.lat, stop.lng) else null
            },
            destName = _ui.value.destinationName,
            route = route?.geometry ?: emptyList(),
            traffic = route?.traffic ?: emptyList(),
            maneuverText = null, // turn-by-turn maneuver banner removed
            remainingText = remainingM?.let { fmtDist(it) },
            // Top-down (heading-up) nav view. The 3D perspective tilt is DISABLED: warping
            // flat raster tiles via setPolyToPoly stretches the baked-in map labels and
            // skews the angle (you can't get true Google-Maps 3D without vector tiles).
            tilt3d = false,
            etaPrimary = etaPrimary,
            etaSecondary = etaSecondary,
            pickerRows = pickerItems?.let { menuRows() },
            pickerTitle = when (menuPage) { MenuPage.SOURCE -> "SOURCE"; MenuPage.CANCEL -> "CANCEL"; MenuPage.ROOT -> "SETTINGS" },
            pickerSelectHint = when (menuPage) {
                MenuPage.SOURCE -> "Down: next · Right: use · Left: back"
                MenuPage.CANCEL -> "Down: next · Right: cancel · Left: back"
                MenuPage.ROOT -> "Down: next · Right: go · Left: close"
            },
            pickerIndex = pickerIndex,
            zoomLabel = if (System.currentTimeMillis() < zoomBadgeUntilMs) "Zoom $zoom" else null,
            // The router that made the current route (BRouter may have fallen back to OSM);
            // with no route, the one the next route will use.
            routingBadge = route?.source ?: dashConfig.routingSource.value,
            notice = notice?.takeIf { pickerItems == null && System.currentTimeMillis() < noticeUntilMs },
            downHint = if (pickerItems == null && System.currentTimeMillis() < downHintUntilMs) "▼ again: settings" else null,
            joystickTrail = if (dashConfig.showJoystickTrail.value) joyTrail.text(System.currentTimeMillis()) else null,
            attribution = if (tiles.freeTiles) TileProvider.OSM_ATTRIBUTION else null,
            phoneBattery = if (dashConfig.showPhoneBattery.value) session.latestPhoneStatus?.let { it.batteryPct to it.charging } else null,
            gpsWeak = gpsStatus == GpsStatus.WEAK,
            gpsLost = gpsStatus == GpsStatus.LOST,
            dark = mapDark,
        )
        val canvas = Canvas(bmp)
        // Draw in LOGICAL 526×300 coordinates at any render scale, so layout is identical.
        canvas.scale(scale.toFloat(), scale.toFloat())
        mapRenderer.draw(canvas, frame, DashEncoder.WIDTH, DashEncoder.HEIGHT)
        // The dash shows ONLY our video frame while projecting — it won't overlay its native
        // now-playing/call cards (confirmed: 05 0d/05 22 are sent fine but never displayed mid-map).
        // So we draw them INTO the frame ourselves, as the CLAUDE.md design always intended.
        drawDashOverlays(canvas, DashEncoder.WIDTH, DashEncoder.HEIGHT)
        // Publish a snapshot for the in-app preview (immutable copy — see _previewFrame).
        _previewFrame.value = bmp.copy(Bitmap.Config.ARGB_8888, false)
    }

    // Overlay paints — created once (never per-frame). ~50% smaller than the first cut, per feedback.
    private val ovBg by lazy { android.graphics.Paint().apply { color = 0xCC0B0D0E.toInt(); isAntiAlias = true } }
    private val ovTitle by lazy { android.graphics.Paint().apply {
        color = android.graphics.Color.WHITE; isAntiAlias = true; textSize = 15f
        typeface = android.graphics.Typeface.DEFAULT_BOLD; textAlign = android.graphics.Paint.Align.CENTER } }
    private val ovSub by lazy { android.graphics.Paint().apply {
        color = 0xFFCFCFCF.toInt(); isAntiAlias = true; textSize = 10f; textAlign = android.graphics.Paint.Align.CENTER } }
    private val ovAccent by lazy { android.graphics.Paint().apply {
        color = 0xFFF2A93B.toInt(); isAntiAlias = true; textSize = 10f; textAlign = android.graphics.Paint.Align.CENTER } }

    /**
     * Draw the INCOMING-call banner onto the projected frame (the dash shows only our video while
     * projecting, so this is the only way it's visible mid-ride). Incoming only — outgoing/active
     * calls don't set CallInfoProvider, so this never fires for them. Now-playing has no overlay; it
     * goes to the dash's audio screen via the 05 0d TLV instead. Centred in the round display's safe zone.
     */
    private fun drawDashOverlays(canvas: Canvas, w: Int, h: Int) {
        val call = com.example.wayfindr.media.CallInfoProvider.incomingCall.value
        if (call == null || !call.incoming) return   // overlay only for a ringing call, not active/outgoing
        val cx = w / 2f; val cy = h / 2f
        val half = h * 0.28f   // ~half the previous width; stays inside the round display
        canvas.drawRoundRect(cx - half, cy - 26f, cx + half, cy + 26f, 10f, 10f, ovBg)
        canvas.drawText("Incoming call", cx, cy - 11f, ovSub)
        canvas.drawText(ellipsizeOverlay(call.caller, 16), cx, cy + 5f, ovTitle)
        canvas.drawText("UP answer · DOWN reject", cx, cy + 19f, ovAccent)
    }

    private fun ellipsizeOverlay(s: String, max: Int) = if (s.length > max) s.take(max - 1) + "…" else s

    /** Answer via the dialer's own notification action (most reliable); fall back to TelecomManager. */
    private fun answerCall(c: com.example.wayfindr.media.IncomingCall) {
        val sent = c.answerIntent?.let { runCatching { it.send() }.isSuccess } ?: false
        if (!sent) callController.answer()
        com.example.wayfindr.data.RideDiagnostics.log("media", "answer (notifAction=$sent)")
    }

    /** Reject (ringing) or hang up (active) via the dialer's action; fall back to TelecomManager. */
    private fun endCall(c: com.example.wayfindr.media.IncomingCall) {
        val sent = c.declineIntent?.let { runCatching { it.send() }.isSuccess } ?: false
        if (!sent) callController.hangup()
        com.example.wayfindr.data.RideDiagnostics.log("media", "end/reject (notifAction=$sent)")
    }

    // ── Monotonic route-progress tracker ────────────────────────────────────
    // Snapping to the GLOBALLY nearest segment makes the remaining distance flicker
    // (a winding route can pass near the start again, so it jumps to ~straight-line).
    // Instead we advance progress along the route, searching a window AHEAD of where
    // we already are, only re-acquiring globally if we're clearly off-route.
    @Volatile private var progressM = 0.0

    private data class NavState(
        val remainingM: Double, val nextTurnM: Double, val heading: Float, val offRoute: Boolean,
        val snapped: GeoPoint, val snapDist: Double,
        val nextManeuver: com.example.wayfindr.dash.nav.Maneuver?,
        val progressM: Double,
        /** The turn after [nextManeuver] (null = none), for the dash's "then ..." field. */
        val secondManeuver: com.example.wayfindr.dash.nav.Maneuver? = null,
    )

    private data class Match(val cum: Double, val dist: Double, val bearing: Float, val proj: GeoPoint)

    private fun trackProgress(r: Route, pos: GeoPoint): NavState {
        val geom = r.geometry
        val cum = r.cumulative

        fun search(lo: Double, hi: Double): Match {
            var bestDist = Double.MAX_VALUE; var bestCum = progressM; var bestBearing = 0f; var bestProj = pos
            for (i in 0 until geom.size - 1) {
                if (cum[i + 1] < lo || cum[i] > hi) continue
                val (proj, t) = GeoPoint.projectOnSegment(pos, geom[i], geom[i + 1])
                val d = GeoPoint.distMeters(pos, proj)
                if (d < bestDist) {
                    bestDist = d
                    bestCum = cum[i] + GeoPoint.distMeters(geom[i], geom[i + 1]) * t
                    bestBearing = GeoPoint.bearing(geom[i], geom[i + 1]).toFloat()
                    bestProj = proj
                }
            }
            return Match(bestCum, bestDist, bestBearing, bestProj)
        }

        var m = search(progressM - 60.0, progressM + 1000.0)
        if (m.dist > 80.0) {
            val g = search(0.0, r.totalMeters) // off-window → re-acquire globally
            if (g.dist < m.dist) m = g
        }
        progressM = maxOf(progressM - 25.0, m.cum) // mostly forward, tolerate small GPS slide

        val remaining = (r.totalMeters - progressM).coerceAtLeast(0.0)
        val nextMan = r.maneuvers.firstOrNull {
            it.cumulativeMeters > progressM + 1.0 && it.type != com.example.wayfindr.dash.nav.ManeuverType.DEPART
        }
        val nextTurn = nextMan?.let { (it.cumulativeMeters - progressM).coerceAtLeast(0.0) } ?: remaining
        val secondMan = nextMan?.let { n ->
            r.maneuvers.firstOrNull {
                it.cumulativeMeters > n.cumulativeMeters + 1.0 && it.type != com.example.wayfindr.dash.nav.ManeuverType.DEPART
            }
        }
        return NavState(remaining, nextTurn, m.bearing, m.dist > 70.0, m.proj, m.dist, nextMan, progressM, secondMan)
    }

    private fun updateThermal() {
        val status = runCatching { powerManager.currentThermalStatus }.getOrDefault(PowerManager.THERMAL_STATUS_NONE)
        val label = when (status) {
            PowerManager.THERMAL_STATUS_NONE, PowerManager.THERMAL_STATUS_LIGHT -> "OK"
            PowerManager.THERMAL_STATUS_MODERATE -> "Warm"
            PowerManager.THERMAL_STATUS_SEVERE, PowerManager.THERMAL_STATUS_CRITICAL -> "Hot"
            else -> "Throttling"
        }
        if (label != _ui.value.thermal) _ui.update { it.copy(thermal = label) }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** metres → (value, dash unit). <1 km → metres (0x30); else km×10 (0x10). */
    private fun toDashDistance(meters: Double): Pair<Int, Int> =
        if (meters < 1000.0) meters.toInt().coerceIn(0, 0xFFFF) to DashCommands.NAV_UNIT_METERS
        else (meters / 100.0).toInt().coerceIn(0, 0xFFFF) to DashCommands.NAV_UNIT_KM_TENTHS

    private fun fmtDist(m: Double): String =
        if (m < 1000) "${m.toInt()} m" else "%.1f km".format(m / 1000.0)

    /** Smallest absolute angle (0..180°) between two compass bearings. */
    private fun angleDelta(a: Float, b: Float): Float {
        val d = (((a - b) % 360f) + 540f) % 360f - 180f
        return kotlin.math.abs(d)
    }

    private fun teardown() {
        // cancel() only REQUESTS a stop — a frame already mid-draw keeps running on its worker
        // thread. Freeing its bitmap/encoder underneath it was the "Canvas: trying to use a
        // recycled bitmap" crash (2026-08-27 crash log). So: detach the resources now, and release
        // the encoder only once both loops have actually finished.
        val loops = listOfNotNull(tickerJob, streamJob)
        tickerJob = null; streamJob = null
        loops.forEach { it.cancel() }
        stopMediaForwarding()
        val oldEncoder = encoder
        encoder = null
        // Never recycle the frame bitmap by hand: a cancelled tick may still be drawing on it.
        // Dropping the reference lets GC free it once nothing uses it.
        frameBitmap = null
        _previewFrame.value = null   // drop the stale dash frame so the preview falls back to the live map
        if (oldEncoder != null) {
            if (loops.isEmpty()) oldEncoder.release()
            else {
                val running = java.util.concurrent.atomic.AtomicInteger(loops.size)
                // invokeOnCompletion fires even if viewModelScope itself is being cancelled.
                loops.forEach { job ->
                    job.invokeOnCompletion { if (running.decrementAndGet() == 0) runCatching { oldEncoder.release() } }
                }
            }
        }
    }

    /**
     * Bridge the phone's now-playing session + incoming-call notification onto the dash (TLV 05 0d /
     * 05 22), matching the dash. Needs notification access; without it MediaInfoProvider
     * stays silent and the dash simply shows no media card. Album art (05 40) is captured in the
     * protocol but not auto-sent yet (fragmentation unverified — see DashCommands.albumArtFrames).
     */
    private fun startMediaForwarding() {
        val granted = com.example.wayfindr.media.MediaInfoProvider.isAccessGranted(getApplication())
        com.example.wayfindr.data.RideDiagnostics.log("media", "forwarding start — notification access=$granted")
        mediaInfo.start()
        mediaObserveJob?.cancel()
        mediaObserveJob = viewModelScope.launch {
            launch {
                mediaInfo.nowPlaying.collect { np ->
                    com.example.wayfindr.data.RideDiagnostics.log("media", "now-playing observed: ${np?.title ?: "(none)"}")
                    session.updateNowPlaying(np?.title, np?.album.orEmpty(), np?.artist.orEmpty(), np?.playing ?: false)
                }
            }
            launch {
                com.example.wayfindr.media.CallInfoProvider.incomingCall.collect { call ->
                    com.example.wayfindr.data.RideDiagnostics.log("media", "call observed: ${call?.caller ?: "(none)"}")
                    session.updateCall(call?.caller)
                }
            }
        }
    }

    private fun stopMediaForwarding() {
        mediaObserveJob?.cancel(); mediaObserveJob = null
        mediaInfo.stop()
        session.updateNowPlaying(null, "", "", false)
        session.updateCall(null)
    }

    override fun onCleared() {
        super.onCleared()
        debugButtonRx?.let { runCatching { getApplication<android.app.Application>().unregisterReceiver(it) } }
        stopRecording()        // save the in-progress ride if the app is closed mid-session
        session.disconnect()
        wifiManager.disconnect()
        // Streaming can't continue once the ViewModel is gone, so make sure the foreground
        // service + wake/wifi locks + GPS don't outlive it (otherwise they'd hold the CPU/WiFi
        // awake with nothing producing frames).
        releaseBackgroundResources()
        voice.shutdown()
        com.example.wayfindr.data.RideDiagnostics.stop("app closed")
    }
}
