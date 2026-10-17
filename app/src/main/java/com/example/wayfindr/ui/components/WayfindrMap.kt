package com.example.wayfindr.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.example.wayfindr.dash.nav.GeoPoint
import com.example.wayfindr.dash.nav.TrafficInterval
import com.example.wayfindr.dash.nav.TrafficSpeed
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberMarkerState

private const val FOLLOW_ZOOM = 15.5f

/**
 * In-app phone map using Google Maps.
 *
 * Modes: [fitRoute] frames the whole route; [navMode] tilts/zooms/rotates to heading with a
 * rider chevron; default follows the rider north-up.
 */
@Composable
internal fun GoogleWayfindrMap(
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
    // Dashboard camera state (for mirroring the dash viewport)
    camLat: Double? = null,
    camLng: Double? = null,
    camZoom: Float? = null,
    camBearing: Float? = null,
    /** Google's live congestion overlay. It refreshes while the device has internet access. */
    showTraffic: Boolean = true,
    onMapClick: ((LatLng) -> Unit)? = null,
) {
    val cameraPositionState = rememberCameraPositionState()
    
    val riderMarkerState = rememberMarkerState(
        position = LatLng(riderLat ?: 0.0, riderLng ?: 0.0)
    )
    val destMarkerState = rememberMarkerState(
        position = LatLng(dest?.first ?: 0.0, dest?.second ?: 0.0)
    )

    // Sync marker states when inputs change
    LaunchedEffect(riderLat, riderLng) {
        if (riderLat != null && riderLng != null) {
            riderMarkerState.position = LatLng(riderLat, riderLng)
        }
    }
    LaunchedEffect(dest) {
        if (dest != null) {
            destMarkerState.position = LatLng(dest.first, dest.second)
        }
    }
    val markerDestinations = destinations.ifEmpty { listOfNotNull(dest) }

    // Explicit recentering is separate from normal map interaction. The key changes only when
    // the rider presses the current-location button, so GPS updates cannot steal the camera back.
    LaunchedEffect(recenterKey) {
        if (recenterKey > 0L && riderLat != null && riderLng != null) {
            cameraPositionState.animate(
                CameraUpdateFactory.newLatLngZoom(LatLng(riderLat, riderLng), FOLLOW_ZOOM),
                500,
            )
        }
    }

    // Center once when the first location fix becomes available. This is deliberately separate
    // from the follow camera so subsequent GPS updates do not take control away from the rider.
    LaunchedEffect(centerOnRiderInitially, riderLat != null && riderLng != null) {
        if (centerOnRiderInitially && riderLat != null && riderLng != null && recenterKey == 0L) {
            cameraPositionState.animate(
                CameraUpdateFactory.newLatLngZoom(LatLng(riderLat, riderLng), FOLLOW_ZOOM),
                500,
            )
        }
    }

    // Fit a newly selected route once. GPS updates must not repeat this animation after the
    // rider starts panning or zooming the preview.
    LaunchedEffect(fitRoute, routePoints.size) {
        if (fitRoute && routePoints.size >= 2) {
            val bounds = LatLngBounds.Builder().apply {
                routePoints.forEach { include(LatLng(it.lat, it.lng)) }
            }.build()
            cameraPositionState.animate(CameraUpdateFactory.newLatLngBounds(bounds, 110), 800)
        }
    }

    // Camera control
    LaunchedEffect(riderLat, riderLng, riderBearing, followRider, navMode) {
        when {
            followRider && riderLat != null && riderLng != null -> {
                val target = LatLng(riderLat, riderLng)
                val position = CameraPosition.Builder()
                    .target(target)
                    .zoom(FOLLOW_ZOOM)
                    .bearing(if (navMode) riderBearing else 0f)
                    .tilt(if (navMode) 45f else 0f)
                    .build()
                cameraPositionState.animate(CameraUpdateFactory.newCameraPosition(position), 600)
            }
        }
    }

    // Dashboard preview camera and destination framing are independent of live rider updates
    // when route view has followRider disabled.
    LaunchedEffect(camLat, camLng, camZoom, camBearing, dest, navMode, followRider) {
        when {
            camLat != null && camLng != null -> {
                val position = CameraPosition.Builder()
                    .target(LatLng(camLat, camLng))
                    .zoom(camZoom ?: FOLLOW_ZOOM)
                    .bearing(camBearing ?: 0f)
                    .tilt(if (navMode) 45f else 0f)
                    .build()
                cameraPositionState.animate(CameraUpdateFactory.newCameraPosition(position), 400)
            }
            !followRider && !fitRoute && dest != null -> {
                cameraPositionState.animate(
                    CameraUpdateFactory.newLatLngZoom(LatLng(dest.first, dest.second), 13.0f),
                    800,
                )
            }
        }
    }

    GoogleMap(
        modifier = modifier,
        cameraPositionState = cameraPositionState,
        onMapClick = onMapClick,
        properties = MapProperties(isTrafficEnabled = showTraffic),
        uiSettings = MapUiSettings(
            zoomControlsEnabled = false,
            tiltGesturesEnabled = false,
            myLocationButtonEnabled = false,
            compassEnabled = false,
            mapToolbarEnabled = false
        )
    ) {
        // Draw route line
        if (routePoints.size >= 2) {
            Polyline(
                points = routePoints.map { LatLng(it.lat, it.lng) },
                color = Color(0xFF4285F4),
                width = 16f,
                startCap = com.google.android.gms.maps.model.RoundCap(),
                endCap = com.google.android.gms.maps.model.RoundCap(),
                jointType = com.google.android.gms.maps.model.JointType.ROUND
            )
            trafficIntervals.forEach { interval ->
                val start = interval.startPointIndex.coerceIn(0, routePoints.lastIndex)
                val end = interval.endPointIndex.coerceIn(start, routePoints.lastIndex)
                if (end <= start) return@forEach
                Polyline(
                    points = routePoints.subList(start, end + 1).map { LatLng(it.lat, it.lng) },
                    color = when (interval.speed) {
                        TrafficSpeed.NORMAL -> Color(0xFF4285F4)
                        TrafficSpeed.SLOW -> Color(0xFFF9AB00)
                        TrafficSpeed.TRAFFIC_JAM -> Color(0xFFEA4335)
                        TrafficSpeed.UNKNOWN -> Color(0xFF4285F4)
                    },
                    width = 16f,
                    startCap = com.google.android.gms.maps.model.RoundCap(),
                    endCap = com.google.android.gms.maps.model.RoundCap(),
                    jointType = com.google.android.gms.maps.model.JointType.ROUND,
                )
            }
        }

        // Destination markers, one for each ordered route stop.
        markerDestinations.forEachIndexed { index, point ->
            val markerState = if (index == 0 && destinations.isEmpty()) destMarkerState else
                rememberMarkerState(position = LatLng(point.first, point.second))
            LaunchedEffect(point) { markerState.position = LatLng(point.first, point.second) }
            Marker(
                state = markerState,
                icon = BitmapDescriptorFactory.fromBitmap(destPinBitmap()),
                anchor = androidx.compose.ui.geometry.Offset(0.5f, 0.5f)
            )
        }

        // Rider Marker (Chevron)
        if (riderLat != null && riderLng != null) {
            Marker(
                state = riderMarkerState,
                icon = BitmapDescriptorFactory.fromBitmap(chevronBitmap()),
                // In navMode, the map rotates to heading, so the marker should point "up" (0 rotation).
                // In North-up mode, the marker rotates to show the heading.
                rotation = if (navMode) 0f else riderBearing,
                flat = true,
                anchor = androidx.compose.ui.geometry.Offset(0.5f, 0.5f)
            )
        }
    }
}

/** 
 * Google-style blue chevron with a heading cone.
 * The chevron itself points "up" (0 degrees).
 */
private fun chevronBitmap(): Bitmap {
    val s = 120 // Larger canvas for the cone
    val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    val cx = s / 2f
    val cy = s / 2f

    // Heading cone (transparent gradient-like triangle)
    p.color = android.graphics.Color.argb(40, 66, 133, 244)
    val conePath = Path().apply {
        moveTo(cx, cy)
        lineTo(cx - 30f, cy - 50f)
        lineTo(cx + 30f, cy - 50f)
        close()
    }
    c.drawPath(conePath, p)

    // Outer white ring
    p.color = android.graphics.Color.WHITE
    c.drawCircle(cx, cy, 22f, p)
    
    // Blue inner circle
    p.color = android.graphics.Color.rgb(66, 133, 244)
    c.drawCircle(cx, cy, 18f, p)
    
    // White chevron arrow
    p.color = android.graphics.Color.WHITE
    val arrowPath = Path().apply {
        moveTo(cx, cy - 12f)
        lineTo(cx + 10f, cy + 10f)
        lineTo(cx, cy + 5f)
        lineTo(cx - 10f, cy + 10f)
        close()
    }
    c.drawPath(arrowPath, p)
    
    return bmp
}

/** Simple red destination pin. */
private fun destPinBitmap(): Bitmap {
    val s = 72
    val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    val cx = s / 2f
    val cy = s / 2f
    p.color = android.graphics.Color.WHITE; c.drawCircle(cx, cy, 22f, p)
    p.color = android.graphics.Color.rgb(234, 67, 53); c.drawCircle(cx, cy, 18f, p)
    p.color = android.graphics.Color.WHITE; c.drawCircle(cx, cy, 6f, p)
    return bmp
}
