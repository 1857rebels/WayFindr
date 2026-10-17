package com.example.wayfindr.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.wayfindr.dash.DashConfig
import com.example.wayfindr.dash.MapSource
import androidx.compose.runtime.key
import com.example.wayfindr.dash.nav.GeoPoint
import com.example.wayfindr.dash.nav.TrafficInterval
import com.example.wayfindr.dash.nav.TrafficSpeed
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.plugins.annotation.LineManager
import org.maplibre.android.plugins.annotation.LineOptions
import org.maplibre.android.plugins.annotation.SymbolManager
import org.maplibre.android.plugins.annotation.SymbolOptions

/**
 * In-app phone map. Google Maps by default; with "Free map tiles" on, MapLibre + OpenFreeMap
 * (free, keyless, open data) as on the `original` branch. Same parameters either way.
 */
@Composable
fun WayfindrMap(
    riderLat: Double?,
    riderLng: Double?,
    dest: Pair<Double, Double>?,
    destinations: List<Pair<Double, Double>> = emptyList(),
    routePoints: List<GeoPoint>,
    trafficIntervals: List<TrafficInterval> = emptyList(),
    hasLocationPermission: Boolean,
    modifier: Modifier = Modifier,
    fitRoute: Boolean = false,
    navMode: Boolean = false,
    riderBearing: Float = 0f,
    recenterKey: Long = 0L,
    followRider: Boolean = true,
    centerOnRiderInitially: Boolean = false,
    camLat: Double? = null,
    camLng: Double? = null,
    camZoom: Float? = null,
    camBearing: Float? = null,
    showTraffic: Boolean = true,
    onMapClick: ((com.google.android.gms.maps.model.LatLng) -> Unit)? = null,
) {
    val ctx = LocalContext.current
    val config = remember { DashConfig.get(ctx) }
    val source by config.mapSource.collectAsState()
    val offlineName by config.offlineMapFile.collectAsState()
    // Offline file's zoom levels (Google scale), read once per file, so the map stops at them.
    val offlineZoom: IntRange? = if (source != MapSource.OFFLINE) null else remember(offlineName) {
        val file = com.example.wayfindr.dash.map.OfflineMapFiles.resolve(ctx, offlineName)
        (file?.let { com.example.wayfindr.dash.map.MbTiles.open(it) } as? com.example.wayfindr.dash.map.MbTiles.Companion.Result.Ok)
            ?.tiles?.let { mb -> (mb.minZoom..mb.maxZoom).also { mb.close() } }
    }
    val styleJson: String? = when (source) {
        MapSource.GOOGLE -> null
        MapSource.OSM -> ""   // OpenFreeMap vector style by URL
        MapSource.OFFLINE -> remember(offlineName) {
            offlineStyleJson(com.example.wayfindr.dash.map.OfflineMapFiles.resolve(ctx, offlineName)?.absolutePath)
        }
    }
    // New style = new map view (MapLibre loads its style once per view).
    if (styleJson != null) key(styleJson) { FreeWayfindrMap(
        styleJson, riderLat, riderLng, dest, destinations, routePoints, trafficIntervals, modifier, fitRoute,
        navMode, riderBearing, recenterKey, followRider, centerOnRiderInitially,
        camLat, camLng, camZoom, camBearing, onMapClick, offlineZoom,
    ) } else GoogleWayfindrMap(
        riderLat, riderLng, dest, destinations, routePoints, trafficIntervals, hasLocationPermission,
        modifier, fitRoute, navMode, riderBearing, recenterKey, followRider, centerOnRiderInitially,
        camLat, camLng, camZoom, camBearing, showTraffic, onMapClick,
    )
}

// Free, keyless, redistributable vector basemap.
private const val STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
// MapLibre zoom is one level lower than Google's for the same scale (512 px vs 256 px tiles).
private const val ZOOM_OFFSET = 1.0
private const val FOLLOW_ZOOM = 15.5 - ZOOM_OFFSET
private const val RIDER_ICON = "rider-chevron"
private const val DEST_ICON = "dest-pin"

@Composable
private fun FreeWayfindrMap(
    /** "" = OpenFreeMap vector style; otherwise a full style JSON (offline raster). */
    styleJson: String,
    riderLat: Double?,
    riderLng: Double?,
    dest: Pair<Double, Double>?,
    destinations: List<Pair<Double, Double>>,
    routePoints: List<GeoPoint>,
    trafficIntervals: List<TrafficInterval>,
    modifier: Modifier,
    fitRoute: Boolean,
    navMode: Boolean,
    riderBearing: Float,
    recenterKey: Long,
    followRider: Boolean,
    centerOnRiderInitially: Boolean,
    camLat: Double?,
    camLng: Double?,
    camZoom: Float?,
    camBearing: Float?,
    onMapClick: ((com.google.android.gms.maps.model.LatLng) -> Unit)?,
    /** Google-scale zoom levels to stay within (offline file), or null for no limit. */
    zoomLimit: IntRange? = null,
) {
    val context = LocalContext.current
    remember { MapLibre.getInstance(context) }
    // TextureView mode so the map clips/animates with Compose navigation. onCreate runs once
    // here and onDestroy once in onDispose — routing them through the lifecycle observer
    // destroyed the native MapView twice and SIGSEGV'd on fast page switches (v1.3.1 hotfix).
    val mapView = remember {
        MapView(context, MapLibreMapOptions().textureMode(true)).apply { onCreate(null) }
    }
    // Set before teardown: the style loads over the network and its callbacks can land after
    // the native map is freed. Every async callback bails on this.
    val destroyed = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var lineMgr by remember { mutableStateOf<LineManager?>(null) }
    var symbolMgr by remember { mutableStateOf<SymbolManager?>(null) }
    var styleReady by remember { mutableStateOf(false) }
    val clickHandler by rememberUpdatedState(onMapClick)

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose {
            destroyed.set(true)
            lifecycleOwner.lifecycle.removeObserver(obs)
            // Don't destroy the annotation managers first: that was the page-switch native crash.
            // MapView.onDestroy() frees them with the style.
            runCatching { mapView.onStop() }
            runCatching { mapView.onDestroy() }
        }
    }

    LaunchedEffect(mapView) {
        mapView.getMapAsync { m ->
            if (destroyed.get()) return@getMapAsync
            map = m
            zoomLimit?.let {
                m.setMinZoomPreference(it.first - ZOOM_OFFSET)
                m.setMaxZoomPreference(it.last - ZOOM_OFFSET)
            }
            m.uiSettings.apply {
                isRotateGesturesEnabled = false
                isTiltGesturesEnabled = false
                isCompassEnabled = false
                isAttributionEnabled = true   // OSM/OpenFreeMap credit (licence requirement)
                isLogoEnabled = false
            }
            m.addOnMapClickListener { p ->
                clickHandler?.invoke(com.google.android.gms.maps.model.LatLng(p.latitude, p.longitude))
                clickHandler != null
            }
            val builder = if (styleJson.isEmpty()) Style.Builder().fromUri(STYLE_URL) else Style.Builder().fromJson(styleJson)
            m.setStyle(builder) { style ->
                if (destroyed.get()) return@setStyle
                style.addImage(RIDER_ICON, freeChevronBitmap())
                style.addImage(DEST_ICON, freeDestPinBitmap())
                lineMgr = LineManager(mapView, m, style)
                symbolMgr = SymbolManager(mapView, m, style).apply {
                    iconAllowOverlap = true; iconIgnorePlacement = true
                }
                styleReady = true
            }
        }
    }

    val markerDestinations = destinations.ifEmpty { listOfNotNull(dest) }

    // Route (with traffic colours when Google routing supplied them) + markers.
    LaunchedEffect(styleReady, routePoints, trafficIntervals, markerDestinations, riderLat, riderLng, riderBearing, navMode) {
        if (destroyed.get()) return@LaunchedEffect
        val lm = lineMgr ?: return@LaunchedEffect
        val sm = symbolMgr ?: return@LaunchedEffect
        lm.deleteAll(); sm.deleteAll()
        if (routePoints.size >= 2) {
            lm.create(LineOptions().withLatLngs(routePoints.map { LatLng(it.lat, it.lng) })
                .withLineColor("#4285F4").withLineWidth(6f))
            trafficIntervals.forEach { iv ->
                val start = iv.startPointIndex.coerceIn(0, routePoints.lastIndex)
                val end = iv.endPointIndex.coerceIn(start, routePoints.lastIndex)
                val color = when (iv.speed) {
                    TrafficSpeed.SLOW -> "#F9AB00"
                    TrafficSpeed.TRAFFIC_JAM -> "#EA4335"
                    else -> null
                }
                if (end > start && color != null) lm.create(LineOptions()
                    .withLatLngs(routePoints.subList(start, end + 1).map { LatLng(it.lat, it.lng) })
                    .withLineColor(color).withLineWidth(6f))
            }
        }
        markerDestinations.forEach {
            sm.create(SymbolOptions().withLatLng(LatLng(it.first, it.second)).withIconImage(DEST_ICON).withIconSize(1.1f))
        }
        if (riderLat != null && riderLng != null) {
            sm.create(SymbolOptions().withLatLng(LatLng(riderLat, riderLng)).withIconImage(RIDER_ICON)
                .withIconRotate(if (navMode) 0f else riderBearing).withIconSize(1.0f))
        }
    }

    fun animate(update: org.maplibre.android.camera.CameraUpdate, ms: Int) {
        if (destroyed.get() || !styleReady) return
        runCatching { map?.animateCamera(update, ms) }
    }

    LaunchedEffect(styleReady, recenterKey) {
        if (recenterKey > 0L && riderLat != null && riderLng != null)
            animate(CameraUpdateFactory.newLatLngZoom(LatLng(riderLat, riderLng), FOLLOW_ZOOM), 500)
    }
    LaunchedEffect(styleReady, centerOnRiderInitially, riderLat != null && riderLng != null) {
        if (centerOnRiderInitially && riderLat != null && riderLng != null && recenterKey == 0L)
            animate(CameraUpdateFactory.newLatLngZoom(LatLng(riderLat, riderLng), FOLLOW_ZOOM), 500)
    }
    LaunchedEffect(styleReady, fitRoute, routePoints.size) {
        if (fitRoute && routePoints.size >= 2) {
            val b = LatLngBounds.Builder()
            routePoints.forEach { b.include(LatLng(it.lat, it.lng)) }
            animate(CameraUpdateFactory.newLatLngBounds(b.build(), 110), 800)
        }
    }
    LaunchedEffect(styleReady, riderLat, riderLng, riderBearing, followRider, navMode) {
        if (followRider && camLat == null && riderLat != null && riderLng != null) {
            animate(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder()
                .target(LatLng(riderLat, riderLng)).zoom(FOLLOW_ZOOM)
                .bearing(if (navMode) riderBearing.toDouble() else 0.0)
                .tilt(if (navMode) 45.0 else 0.0).build()), 600)
        }
    }
    // Mirror of the dash viewport (Dash screen) / destination framing (route preview).
    LaunchedEffect(styleReady, camLat, camLng, camZoom, camBearing, dest, navMode, followRider) {
        when {
            camLat != null && camLng != null -> animate(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder()
                .target(LatLng(camLat, camLng))
                .zoom((camZoom?.toDouble() ?: 15.5) - ZOOM_OFFSET)
                .bearing(camBearing?.toDouble() ?: 0.0)
                .tilt(if (navMode) 45.0 else 0.0).build()), 400)
            !followRider && !fitRoute && dest != null ->
                animate(CameraUpdateFactory.newLatLngZoom(LatLng(dest.first, dest.second), 13.0 - ZOOM_OFFSET), 800)
        }
    }

    AndroidView(factory = { mapView }, modifier = modifier)
}

/** Blue chevron-in-a-circle pointing "up" (rotated to heading by the symbol). */
private fun freeChevronBitmap(): Bitmap {
    val s = 84
    val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    p.color = android.graphics.Color.WHITE
    c.drawCircle(s / 2f, s / 2f, s * 0.34f, p)
    p.color = android.graphics.Color.rgb(66, 133, 244)
    c.drawCircle(s / 2f, s / 2f, s * 0.30f, p)
    p.color = android.graphics.Color.WHITE
    val cx = s / 2f
    c.drawPath(Path().apply {
        moveTo(cx, s * 0.24f); lineTo(s * 0.72f, s * 0.70f); lineTo(cx, s * 0.58f); lineTo(s * 0.28f, s * 0.70f); close()
    }, p)
    return bmp
}

/** Red destination pin (white ring + red fill). */
private fun freeDestPinBitmap(): Bitmap {
    val s = 72
    val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    p.color = android.graphics.Color.WHITE; c.drawCircle(s / 2f, s / 2f, s * 0.30f, p)
    p.color = android.graphics.Color.rgb(234, 67, 53); c.drawCircle(s / 2f, s / 2f, s * 0.24f, p)
    p.color = android.graphics.Color.WHITE; c.drawCircle(s / 2f, s / 2f, s * 0.09f, p)
    return bmp
}
