package com.example.wayfindr.viewmodel

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.location.Geocoder
import android.location.LocationManager
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.wayfindr.data.SharedLocation
import com.example.wayfindr.dash.map.LocationTracker
import com.example.wayfindr.dash.nav.GeoPoint
import com.example.wayfindr.dash.nav.Route
import com.example.wayfindr.dash.nav.Router
import com.example.wayfindr.util.Dbg
import com.example.wayfindr.util.LocationParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class RouteState(
    val destination: SharedLocation? = null,
    /** Ordered itinerary; the final item is [destination]. */
    val waypoints: List<SharedLocation> = emptyList(),
    val currentLocation: Pair<Double, Double>? = null,
    val currentBearing: Float = 0f,
    val locationRefreshKey: Long = 0L,
    val isResolving: Boolean = false,
    val pendingNavigate: Boolean = false,
    // Real routing results
    val route: Route? = null,
    val routing: Boolean = false,
    val distanceText: String? = null,   // "218 km"
    val durationText: String? = null,   // "4h 50m"
    val etaText: String? = null,        // "13:32"
    /** Why the last search found nothing (shown under the search box); null = fine. */
    val searchNote: String? = null,
)

class RouteViewModel(app: Application) : AndroidViewModel(app) {
    private val TAG = "RouteViewModel"
    private val _state = MutableStateFlow(RouteState())
    val state = _state.asStateFlow()

    private val lm = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val locationTracker = LocationTracker(app)
    private val repo = com.example.wayfindr.data.SyncRepository.get(app)

    private val _saved = MutableStateFlow<List<com.example.wayfindr.data.SavedLocation>>(emptyList())
    /** Saved destinations the rider can tap to load + navigate again. */
    val saved = _saved.asStateFlow()

    init {
        reloadSaved()
        viewModelScope.launch {
            locationTracker.location.collect { location ->
                if (location != null) {
                    _state.value = _state.value.copy(
                        currentLocation = location.latitude to location.longitude,
                        currentBearing = if (location.hasBearing()) location.bearing else _state.value.currentBearing,
                    )
                }
            }
        }
        // Reflect saves made locally OR synced in from another device.
        viewModelScope.launch { repo.revision.collect { reloadSaved() } }
        // App asleep (10 min, no screen, no dash link): stop GPS; restart it on wake if it was on.
        viewModelScope.launch {
            com.example.wayfindr.util.IdleSleep.asleep.collect { asleep ->
                if (asleep) { gpsBeforeSleep = locationTracker.isRunning; locationTracker.stop() }
                else if (gpsBeforeSleep) { gpsBeforeSleep = false; locationTracker.start() }
            }
        }
    }

    private var gpsBeforeSleep = false

    fun startLocationUpdates() = locationTracker.start()

    @SuppressLint("MissingPermission")
    fun refreshCurrentLocation() {
        locationTracker.start()
        val location = locationTracker.lastKnown() ?: return
        _state.value = _state.value.copy(
            currentLocation = location.latitude to location.longitude,
            currentBearing = if (location.hasBearing()) location.bearing else _state.value.currentBearing,
            locationRefreshKey = _state.value.locationRefreshKey + 1,
        )
    }

    override fun onCleared() {
        locationTracker.stop()
        super.onCleared()
    }

    private fun reloadSaved() = viewModelScope.launch {
        _saved.value = withContext(Dispatchers.IO) { repo.savedLocations() }
    }

    /** Save the complete route as one reusable itinerary with ordered stops. */
    fun saveCurrentDestination(name: String, note: String = "") {
        val stops = _state.value.waypoints.ifEmpty {
            listOfNotNull(_state.value.destination)
        }.filter { it.lat != null && it.lng != null }
        if (stops.isEmpty()) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repo.addSavedItinerary(name.ifBlank { stops.last().name }, stops, note) }
        }
    }

    fun renameSaved(loc: com.example.wayfindr.data.SavedLocation, name: String, note: String) =
        viewModelScope.launch { withContext(Dispatchers.IO) { repo.renameSaved(loc, name, note) } }

    fun deleteSaved(loc: com.example.wayfindr.data.SavedLocation) =
        viewModelScope.launch { withContext(Dispatchers.IO) { repo.deleteSaved(loc) } }

    /** Add a saved place to the itinerary (or start one when it is empty). */
    fun selectSaved(loc: com.example.wayfindr.data.SavedLocation) {
        val stops = loc.waypoints.ifEmpty {
            listOf(SharedLocation(name = loc.name, lat = loc.lat, lng = loc.lng))
        }
        _state.value = _state.value.copy(
            destination = stops.last(), waypoints = stops, isResolving = false,
            pendingNavigate = false, route = null,
        )
        computeRoute(stops)
    }

    fun handleSharedText(text: String) {
        // Google Maps direction shares can include several stops. Parse those before the
        // legacy single-place parser, whose coordinate extraction deliberately returns one match.
        val directStops = LocationParser.parseStops(text)
        if (directStops.size > 1) {
            loadSharedStops(directStops, pendingNavigate = true)
            return
        }
        val loc = LocationParser.parse(text)
        _state.value = RouteState(
            destination = loc,
            waypoints = if (loc.lat != null && loc.lng != null) listOf(loc) else emptyList(),
            isResolving = loc.needsExpansion,
            pendingNavigate = true,
        )
        if (loc.lat != null && loc.lng != null) {
            computeRoute(listOf(loc))
        } else if (loc.url != null) {
            viewModelScope.launch {
                // Short maps.app.goo.gl links often expand into a directions URL only after
                // following redirects. Check its complete stop list before treating it as a pin.
                val expandedStops = LocationParser.resolveStops(loc.url)
                if (expandedStops.size > 1) {
                    loadSharedStops(expandedStops, pendingNavigate = true)
                    return@launch
                }
                val (urlCoords, resolvedName) = LocationParser.resolve(loc.url)
                val name = when {
                    loc.name.isNotBlank() && loc.name != "Loading…" -> loc.name
                    !resolvedName.isNullOrBlank() -> resolvedName
                    urlCoords != null -> "Dropped pin"
                    else -> "Shared location"
                }
                // Named places (e.g. "Third Wave Coffee, … Hyderabad") have no coords in
                // the URL — geocode the resolved address to get lat/lng.
                val coords = urlCoords ?: resolvedName?.let { geocode(it) }
                val resolved = loc.copy(
                    name = name, lat = coords?.first, lng = coords?.second, needsExpansion = false,
                )
                _state.value = _state.value.copy(destination = resolved, waypoints = listOf(resolved), isResolving = false)
                if (coords != null) computeRoute(listOf(resolved))
                else Log.w(TAG, "No coords for '$name' (url+geocode both empty)")
            }
        }
    }

    /** Geocode an address/place name to coordinates via the device's geocoder backend. */
    @Suppress("DEPRECATION")
    private suspend fun geocode(query: String): Pair<Double, Double>? = withContext(Dispatchers.IO) {
        try {
            if (!Geocoder.isPresent()) {
                Log.w(TAG, "No geocoder backend present")
                return@withContext null
            }
            com.example.wayfindr.data.ApiUsage.record(com.example.wayfindr.data.ApiUsage.GEOCODE)
            val results = Geocoder(getApplication(), Locale.getDefault()).getFromLocationName(query, 1)
            val a = results?.firstOrNull() ?: run {
                Log.w(TAG, "Geocoder: no result for '$query'")
                return@withContext null
            }
            Dbg.i(TAG) { "Geocoded '$query' → ${a.latitude},${a.longitude}" }
            a.latitude to a.longitude
        } catch (e: Exception) {
            Log.w(TAG, "Geocoder failed: ${e.message}")
            null
        }
    }

    @SuppressLint("MissingPermission")
    private fun computeRoute(stops: List<SharedLocation>) {
        val validStops = stops.filter { it.lat != null && it.lng != null }
        val destination = validStops.lastOrNull() ?: return
        val origin = runCatching {
            lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        }.getOrNull() ?: return

        _state.value = _state.value.copy(
            currentLocation = origin.latitude to origin.longitude,
            routing = true,
        )
        viewModelScope.launch {
            val r = Router.route(
                GeoPoint(origin.latitude, origin.longitude),
                GeoPoint(destination.lat!!, destination.lng!!),
                validStops.dropLast(1).map { GeoPoint(it.lat!!, it.lng!!) },
            )
            _state.value = if (r != null) _state.value.copy(
                route = r,
                routing = false,
                distanceText = fmtKm(r.totalMeters),
                durationText = fmtDuration(r.totalSeconds),
                etaText = fmtEta(r.totalSeconds),
            ) else _state.value.copy(routing = false)
        }
    }

    /** Route the current stops again, e.g. after the rider switches the routing source. */
    fun recomputeRoute() {
        val stops = _state.value.waypoints.ifEmpty { listOfNotNull(_state.value.destination) }
        if (stops.isNotEmpty()) computeRoute(stops)
    }

    fun onNavigated() { _state.value = _state.value.copy(pendingNavigate = false) }
    fun clear() { _state.value = RouteState() }

    /** Clear the loaded itinerary without dropping the live location shown on the map. */
    fun clearDestinations() {
        _state.value = _state.value.copy(
            destination = null,
            waypoints = emptyList(),
            pendingNavigate = false,
            route = null,
            routing = false,
            distanceText = null,
            durationText = null,
            etaText = null,
        )
    }

    /** Direct search for a destination name/address, with the search that fits the Routing setting. */
    fun search(query: String) {
        if (query.isBlank()) return
        _state.value = _state.value.copy(isResolving = true, routing = false, searchNote = null)
        viewModelScope.launch {
            val source = Router.source
            val hit = com.example.wayfindr.dash.nav.PlaceSearch.search(
                getApplication(), query, source, _state.value.currentLocation, _saved.value)
            if (hit != null) {
                addWaypoint(SharedLocation(name = hit.name, lat = hit.lat, lng = hit.lng))
            } else {
                _state.value = _state.value.copy(isResolving = false,
                    searchNote = "${com.example.wayfindr.dash.nav.PlaceSearch.label(source)}: " +
                        (com.example.wayfindr.dash.nav.PlaceSearch.lastError ?: "no match"))
            }
        }
    }

    /** Handle a manual map pin / click. */
    fun selectLocation(lat: Double, lng: Double) {
        addWaypoint(SharedLocation(name = "Dropped pin", lat = lat, lng = lng), resolving = true)
        viewModelScope.launch {
            val name = reverseGeocode(lat, lng) ?: "Dropped pin"
            val stops = _state.value.waypoints.map {
                if (it.lat == lat && it.lng == lng) it.copy(name = name) else it
            }
            _state.value = _state.value.copy(destination = stops.lastOrNull(), waypoints = stops, isResolving = false)
            computeRoute(stops)
        }
    }

    private fun loadSharedStops(stops: List<SharedLocation>, pendingNavigate: Boolean) {
        _state.value = RouteState(
            destination = stops.last(),
            waypoints = stops,
            isResolving = true,
            pendingNavigate = pendingNavigate,
        )
        viewModelScope.launch {
            val resolved = stops.map { stop ->
                if (stop.lat != null && stop.lng != null) stop
                else geocode(stop.name)?.let { (lat, lng) ->
                    stop.copy(lat = lat, lng = lng, needsExpansion = false)
                } ?: stop.copy(needsExpansion = false)
            }
            val complete = resolved.all { it.lat != null && it.lng != null }
            _state.value = _state.value.copy(
                destination = resolved.last(), waypoints = resolved, isResolving = false,
            )
            if (complete) computeRoute(resolved)
            else Log.w(TAG, "Could not resolve every shared route stop")
        }
    }

    private fun addWaypoint(location: SharedLocation, resolving: Boolean = false) {
        val stops = _state.value.waypoints + location
        if (stops.size > MAX_STOPS) {
            Log.w(TAG, "Maximum of $MAX_STOPS stops reached")
            _state.value = _state.value.copy(isResolving = false)
            return
        }
        _state.value = _state.value.copy(
            destination = location, waypoints = stops, isResolving = resolving,
            pendingNavigate = false, route = null,
        )
        if (!resolving) computeRoute(stops)
    }

    fun removeWaypoint(index: Int) {
        val stops = _state.value.waypoints.toMutableList()
        if (index !in stops.indices) return
        stops.removeAt(index)
        _state.value = _state.value.copy(
            destination = stops.lastOrNull(), waypoints = stops, route = null,
            distanceText = null, durationText = null, etaText = null,
        )
        if (stops.isNotEmpty()) computeRoute(stops)
    }

    /** Move a stop while preserving every other stop's order, then recalculate the route. */
    fun moveWaypoint(fromIndex: Int, toIndex: Int) {
        val stops = _state.value.waypoints.toMutableList()
        if (fromIndex !in stops.indices || toIndex !in stops.indices || fromIndex == toIndex) return
        val stop = stops.removeAt(fromIndex)
        stops.add(toIndex, stop)
        _state.value = _state.value.copy(
            destination = stops.lastOrNull(), waypoints = stops, route = null,
            distanceText = null, durationText = null, etaText = null,
        )
        computeRoute(stops)
    }

    /** Reverse geocode coordinates to a friendly address/name. */
    @Suppress("DEPRECATION")
    private suspend fun reverseGeocode(lat: Double, lng: Double): String? = withContext(Dispatchers.IO) {
        try {
            if (!Geocoder.isPresent()) return@withContext null
            com.example.wayfindr.data.ApiUsage.record(com.example.wayfindr.data.ApiUsage.REVERSE_GEOCODE)
            val results = Geocoder(getApplication(), Locale.getDefault()).getFromLocation(lat, lng, 1)
            val a = results?.firstOrNull() ?: return@withContext null
            // Use the feature name (e.g. business name) if available, else the street address.
            a.featureName ?: a.getAddressLine(0)
        } catch (e: Exception) {
            Log.w(TAG, "Reverse geocode failed: ${e.message}")
            null
        }
    }

    private fun fmtKm(m: Double) = "%.0f km".format(m / 1000.0)
    private fun fmtDuration(sec: Double): String {
        val total = (sec / 60.0).toInt()
        return if (total >= 60) "${total / 60}h ${total % 60}m" else "${total}m"
    }
    private fun fmtEta(sec: Double): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(System.currentTimeMillis() + (sec * 1000).toLong()))

    private companion object { const val MAX_STOPS = 26 }
}
