package com.example.wayfindr.dash

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Per-rider dash WiFi configuration, persisted on-device.
 *
 * Wayfindr is meant to work on ANY Common Remington Console dash, not just the author's.
 * Every dash advertises a different SSID (e.g. `RE_P0RP_260525`, `RE_XXXX_yymmdd`) but
 * they all share the `RE_` prefix and the factory passphrase `12345678`. So out of the
 * box we connect by PREFIX (see [DashWifiManager]) — the rider just picks their dash from
 * the system dialog once — and then we remember that exact SSID here for direct reconnects.
 *
 * Everything is overridable in Settings for dashes that don't fit the defaults.
 */
/** Map source for the dash and phone maps. OFFLINE = the .mbtiles file, Google tiles outside it. */
enum class MapSource(val label: String) {
    GOOGLE("Google"), OSM("OSM"), OFFLINE("Offline");
    companion object {
        /** Choices shown in the app and on the dash: no Google without an API key. */
        val available: List<MapSource> get() = values().filter { it != GOOGLE || GoogleKey.present }
    }
}

/** Routing: Google Routes API, free OSM (FOSSGIS Valhalla server), or BRouter on the phone (offline). */
enum class RoutingSource(val label: String) {
    GOOGLE("Google"), OSM("OSM"), BROUTER("BRouter");
    companion object {
        /** Choices shown in the app and on the dash: no Google without an API key. */
        val available: List<RoutingSource> get() = values().filter { it != GOOGLE || GoogleKey.present }
    }
}

class DashConfig private constructor(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("dash_config", Context.MODE_PRIVATE)
    private val _mapDark = MutableStateFlow(prefs.getBoolean(KEY_MAP_DARK, false))
    /** Night look for the dash raster map. Same cached light tiles, recolored at draw time. */
    val mapDarkMode: StateFlow<Boolean> = _mapDark.asStateFlow()
    private val _mapDarkAuto = MutableStateFlow(prefs.getBoolean(KEY_MAP_DARK_AUTO, true))
    /** Dark map by the clock (7 pm–5 am, [DarkSchedule]); when off, [mapDarkMode] is used. */
    val mapDarkAuto: StateFlow<Boolean> = _mapDarkAuto.asStateFlow()
    private val _routingSource = MutableStateFlow(
        prefs.getString(KEY_ROUTING_SOURCE, null)?.let { runCatching { RoutingSource.valueOf(it) }.getOrNull() }
            // First run after the switch to one setting: carry over the old two toggles.
            ?: when {
                prefs.getBoolean(KEY_OFFLINE_ROUTING, false) -> RoutingSource.BROUTER
                prefs.getBoolean(KEY_FREE_ROUTING, false) -> RoutingSource.OSM
                else -> RoutingSource.GOOGLE
            }.let { if (it == RoutingSource.GOOGLE && !GoogleKey.present) RoutingSource.OSM else it })
    /** Who works out routes: Google Routes API, free OSM/Valhalla server, or BRouter on the phone. */
    val routingSource: StateFlow<RoutingSource> = _routingSource.asStateFlow()
    private val _mapSource = MutableStateFlow(
        prefs.getString(KEY_MAP_SOURCE, null)?.let { runCatching { MapSource.valueOf(it) }.getOrNull() }
            // First run after the switch to one setting: carry over the old two toggles.
            ?: when {
                prefs.getBoolean(KEY_OFFLINE_MAP, false) -> MapSource.OFFLINE
                prefs.getBoolean(KEY_FREE_MAPS, false) -> MapSource.OSM
                else -> MapSource.GOOGLE
            }.let { if (it == MapSource.GOOGLE && !GoogleKey.present) MapSource.OSM else it })
    /** Where the dash AND phone maps come from: Google, OpenStreetMap, or the offline file. */
    val mapSource: StateFlow<MapSource> = _mapSource.asStateFlow()
    private val _offlineMapFile = MutableStateFlow(prefs.getString(KEY_OFFLINE_MAP_FILE, "") ?: "")
    /** File name inside OfflineMapFiles.dir(); blank = newest file there. */
    val offlineMapFile: StateFlow<String> = _offlineMapFile.asStateFlow()

    init { com.example.wayfindr.dash.nav.Router.source = _routingSource.value }
    private val _invertZoom = MutableStateFlow(prefs.getBoolean(KEY_INVERT_ZOOM, false))
    /** Swap the dash joystick's zoom directions (default: LEFT = in, RIGHT = out). */
    val invertZoom: StateFlow<Boolean> = _invertZoom.asStateFlow()
    private val _sharperMap = MutableStateFlow(prefs.getBoolean(KEY_SHARPER_MAP, false))
    /** Draw the dash frame at 2× with 512 px "retina" tiles, then scale down to 526×300. */
    val sharperMap: StateFlow<Boolean> = _sharperMap.asStateFlow()
    private val _highQualityVideo = MutableStateFlow(prefs.getBoolean(KEY_HQ_VIDEO, false))
    /** Encode the dash video at a higher bitrate (less blocky; more load on the dash decoder). */
    val highQualityVideo: StateFlow<Boolean> = _highQualityVideo.asStateFlow()
    private val _pickerDoubleTap = MutableStateFlow(prefs.getBoolean(KEY_PICKER_DOUBLE_TAP, false))
    /** Destination-list gesture: a double DOWN (0x15) always opens it; true also allows a double-tap left/right. */
    val pickerDoubleTap: StateFlow<Boolean> = _pickerDoubleTap.asStateFlow()
    private val _showJoystickTrail = MutableStateFlow(prefs.getBoolean(KEY_JOY_TRAIL, false))
    /** Show recent joystick presses ("DDRR") bottom-centre on the dash. */
    val showJoystickTrail: StateFlow<Boolean> = _showJoystickTrail.asStateFlow()
    private val _showPhoneBattery = MutableStateFlow(prefs.getBoolean(KEY_PHONE_BATTERY, true))
    /** Phone battery % (+ charging bolt) above the ETA box on the dash. */
    val showPhoneBattery: StateFlow<Boolean> = _showPhoneBattery.asStateFlow()

    /** Broadest match across Console variants; rider-overridable. */
    var ssidPrefix: String
        get() = prefs.getString(KEY_PREFIX, DEFAULT_PREFIX) ?: DEFAULT_PREFIX
        set(v) = prefs.edit().putString(KEY_PREFIX, v).apply()

    /** The exact SSID once learned/entered. Empty = not yet known → discover by prefix. */
    var ssid: String
        get() = prefs.getString(KEY_SSID, "") ?: ""
        set(v) = prefs.edit().putString(KEY_SSID, v).apply()

    var password: String
        get() = prefs.getString(KEY_PASSWORD, DEFAULT_PASSWORD) ?: DEFAULT_PASSWORD
        set(v) = prefs.edit().putString(KEY_PASSWORD, v).apply()

    /** True until a specific dash has been identified — connect by prefix discovery. */
    val needsDiscovery: Boolean get() = ssid.isBlank()

    /** Forget the learned dash so the next connect re-runs prefix discovery. */
    fun forgetDash() { ssid = "" }

    fun setMapDarkMode(dark: Boolean) {
        prefs.edit().putBoolean(KEY_MAP_DARK, dark).apply()
        _mapDark.value = dark
    }

    fun setRoutingSource(choice: RoutingSource) {
        val source = if (choice == RoutingSource.GOOGLE && !GoogleKey.present) RoutingSource.OSM else choice
        prefs.edit().putString(KEY_ROUTING_SOURCE, source.name).apply()
        _routingSource.value = source
        com.example.wayfindr.dash.nav.Router.source = source
    }

    fun setMapSource(choice: MapSource) {
        val source = if (choice == MapSource.GOOGLE && !GoogleKey.present) MapSource.OSM else choice
        prefs.edit().putString(KEY_MAP_SOURCE, source.name).apply()
        _mapSource.value = source
    }

    fun setOfflineMapFile(name: String) {
        prefs.edit().putString(KEY_OFFLINE_MAP_FILE, name).apply()
        _offlineMapFile.value = name
    }


    fun setMapDarkAuto(auto: Boolean) {
        prefs.edit().putBoolean(KEY_MAP_DARK_AUTO, auto).apply()
        _mapDarkAuto.value = auto
    }

    fun setInvertZoom(invert: Boolean) {
        prefs.edit().putBoolean(KEY_INVERT_ZOOM, invert).apply()
        _invertZoom.value = invert
    }

    fun setSharperMap(on: Boolean) {
        prefs.edit().putBoolean(KEY_SHARPER_MAP, on).apply()
        _sharperMap.value = on
    }

    fun setHighQualityVideo(on: Boolean) {
        prefs.edit().putBoolean(KEY_HQ_VIDEO, on).apply()
        _highQualityVideo.value = on
    }

    fun setPickerDoubleTap(on: Boolean) {
        prefs.edit().putBoolean(KEY_PICKER_DOUBLE_TAP, on).apply()
        _pickerDoubleTap.value = on
    }

    fun setShowJoystickTrail(on: Boolean) {
        prefs.edit().putBoolean(KEY_JOY_TRAIL, on).apply()
        _showJoystickTrail.value = on
    }

    fun setShowPhoneBattery(on: Boolean) {
        prefs.edit().putBoolean(KEY_PHONE_BATTERY, on).apply()
        _showPhoneBattery.value = on
    }

    companion object {
        private const val KEY_PREFIX   = "ssid_prefix"
        private const val KEY_SSID     = "ssid"
        private const val KEY_PASSWORD = "password"
        private const val KEY_MAP_DARK = "map_dark"
        private const val KEY_MAP_DARK_AUTO = "map_dark_auto"
        private const val KEY_FREE_ROUTING = "free_routing"   // old toggle, read once for migration
        private const val KEY_ROUTING_SOURCE = "routing_source"
        private const val KEY_FREE_MAPS = "free_maps"   // old toggle, read once for migration
        private const val KEY_OFFLINE_MAP = "offline_map_mbtiles"   // old toggle, read once for migration
        private const val KEY_MAP_SOURCE = "map_source"
        private const val KEY_OFFLINE_MAP_FILE = "offline_map_file"
        private const val KEY_OFFLINE_ROUTING = "offline_routing_brouter"   // old toggle, read once for migration
        private const val KEY_INVERT_ZOOM = "invert_zoom"
        private const val KEY_SHARPER_MAP = "sharper_map"
        private const val KEY_HQ_VIDEO = "hq_video"
        private const val KEY_PICKER_DOUBLE_TAP = "picker_double_tap"
        private const val KEY_JOY_TRAIL = "joystick_trail"
        private const val KEY_PHONE_BATTERY = "phone_battery"
        const val DEFAULT_PREFIX   = "RE_"
        const val DEFAULT_PASSWORD = "12345678"

        @Volatile private var instance: DashConfig? = null
        fun get(context: Context): DashConfig =
            instance ?: synchronized(this) {
                instance ?: DashConfig(context).also { instance = it }
            }
    }
}
