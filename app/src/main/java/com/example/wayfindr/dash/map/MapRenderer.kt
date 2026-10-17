package com.example.wayfindr.dash.map

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import com.example.wayfindr.dash.nav.GeoPoint
import com.example.wayfindr.dash.nav.TrafficInterval
import com.example.wayfindr.dash.nav.TrafficSpeed

/**
 * Draws the navigation frame for the Console Dash (526 × 300).
 *
 * Layers: cached raster tiles (optional night ColorMatrix) → road route polyline
 * → destination pin → rider marker → top banner (name + remaining) → maneuver chip.
 * Optional heading-up rotation. Paint/Path/Rect objects are reused across frames
 * to avoid per-frame allocation churn.
 */
class MapRenderer(private val tiles: TileProvider) {

    data class Frame(
        val centerLat: Double,
        val centerLng: Double,
        val zoom: Int,
        val panX: Float = 0f,
        val panY: Float = 0f,
        val headingUp: Boolean = false,
        val cameraHeading: Float = 0f,     // world/camera bearing, degrees
        val riderHeading: Float = 0f,      // actual travel bearing, degrees
        val riderLat: Double? = null,
        val riderLng: Double? = null,
        val destLat: Double? = null,
        val destLng: Double? = null,
        val destinations: List<GeoPoint> = emptyList(),
        val destName: String? = null,
        val route: List<GeoPoint> = emptyList(),
        val traffic: List<TrafficInterval> = emptyList(),
        val maneuverText: String? = null,  // e.g. "Turn left · 400 m"
        val remainingText: String? = null, // e.g. "186 km"
        val tilt3d: Boolean = false,       // perspective 3D view (nav heading-up only)
        val etaPrimary: String? = null,    // big glance value, e.g. "24 min" (nav only)
        val etaSecondary: String? = null,  // smaller line, e.g. "18 km · 13:32"
        val pickerRows: List<MenuRow>? = null,  // on-dash menu page (null = closed)
        val pickerTitle: String = "SETTINGS",
        val pickerIndex: Int = 0,
        val pickerSelectHint: String = "Hold right: go",
        val zoomLabel: String? = null,     // e.g. "Zoom 16", shown briefly after a zoom
        val joystickTrail: String? = null, // e.g. "DDRR": recent presses, bottom-centre (setting)
        val attribution: String? = null,   // map-data credit the tile source requires (OSM)
        val notice: List<String>? = null,
        val routingBadge: com.example.wayfindr.dash.RoutingSource? = null, // G / O / BR box, top of the right stack  // short confirmation, e.g. ["Skipped Block 6", "→ Home"], above centre
        val downHint: String? = null,      // "▼ again: destinations" after a lone DOWN, bottom-centre
        val phoneBattery: Pair<Int, Boolean>? = null, // (percent, charging), above the ETA box (setting)
        val gpsWeak: Boolean = false,      // imprecise/slow fix → amber marker + "GPS weak" pill
        val gpsLost: Boolean = false,      // no fresh fix → grey marker + "GPS lost" pill
        val dark: Boolean = false,         // night ColorMatrix on cached light tiles
    )

    /**
     * One row of the on-dash menu. [header] rows are section titles the cursor skips; [active]
     * marks the setting in use (drawn green with a tick); [submenu] rows open another page (›).
     */
    data class MenuRow(
        val label: String,
        val header: Boolean = false,
        val active: Boolean = false,
        val submenu: Boolean = false,
    )

    private val lightBg   = Color.rgb(229, 227, 223) // Google Maps land colour, behind missing tiles
    private val darkBg    = Color.rgb(22, 28, 36)
    private val routeBlue = Color.rgb(66, 133, 244)  // Google Maps directions blue (#4285F4)
    private val trafficAmber = Color.rgb(251, 188, 5)
    private val trafficRed = Color.rgb(234, 67, 53)
    private val googleRed = Color.rgb(234, 67, 53)   // Google destination pin red (#EA4335)
    private val batteryYellow = Color.rgb(251, 188, 5)   // battery % while on battery (not charging)

    private val tilePaint  = Paint(Paint.FILTER_BITMAP_FLAG)
    private val lightTileFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(1.2f) })
    private val darkTileFilter = ColorMatrixColorFilter(nightTileMatrix())
    private val routeCasing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE
        strokeWidth = 11f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val routePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = routeBlue; style = Paint.Style.STROKE
        strokeWidth = 6f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val dotPaint     = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint    = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 22f; isFakeBoldText = true }
    private val subTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = routeBlue; textSize = 19f; isFakeBoldText = true }
    private val bannerPaint  = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(215, 13, 15, 17) }
    private val standbyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(60, 64, 67); textSize = 22f; isFakeBoldText = true }

    // ETA box (drawn in screen space, lower-right, inside the round safe zone)
    private val etaBgPaint     = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(232, 20, 22, 26) }
    private val etaBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(46, 255, 255, 255); style = Paint.Style.STROKE; strokeWidth = 1.5f }
    private val etaBigPaint    = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(126, 217, 87); textSize = 20.4f; isFakeBoldText = true; textAlign = Paint.Align.CENTER }   // Google-nav green
    // Optional overlays are drawn inside overlay(): an error there only skips that box (logged once)
    // instead of failing the whole frame — 3 failed frames make the loop rebuild the encoder.
    private val failedOverlays = java.util.Collections.synchronizedSet(HashSet<String>())
    private inline fun overlay(name: String, draw: () -> Unit) {
        try { draw() } catch (e: Exception) {
            if (failedOverlays.add(name)) com.example.wayfindr.data.RideDiagnostics.log("render", "overlay '$name' failed: $e")
        }
    }
    private val attributionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(230, 232, 234); textSize = 11f; textAlign = Paint.Align.CENTER
    }
    private val boltPath = android.graphics.Path()
    private val boltPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(126, 217, 87) }   // same green as ETA
    private val etaSmallPaint  = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(196, 201, 208); textSize = 16f; textAlign = Paint.Align.CENTER }
    // GPS-health pill (top-centre, shown only when the signal is weak/lost)
    private val gpsPillText    = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 13f; isFakeBoldText = true; textAlign = Paint.Align.CENTER }

    // Reused across frames
    private val routePath = Path()
    private val riderPath = Path()
    private val tmpRect = RectF()
    private val pillRect = RectF()
    // Saved-destination picker (centred panel inside the round safe zone)
    private val pickerBgPaint   = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(238, 20, 22, 26) }
    private val pickerHiPaint   = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(232, 183, 72) }   // app gold
    private val pickerTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(150, 156, 164); textSize = 16f; textAlign = Paint.Align.CENTER }
    private val pickerItemPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(236, 238, 241); textSize = 26f }
    private val pickerHintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(150, 156, 164); textSize = 16f; textAlign = Paint.Align.CENTER }
    private val pickerRow = RectF()
    private val routingBadgePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pickerSectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(150, 156, 164); textSize = 17f; isFakeBoldText = true }
    private val pickerMarkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 26f; textAlign = Paint.Align.RIGHT; isFakeBoldText = true }
    private val tileSrc = Rect()      // sub-region of an ancestor tile when scaling up
    private val childDst = RectF()    // quadrant target when scaling children down
    private val textBounds = Rect()
    private val tiltMatrix = Matrix()
    private val tiltSrc = FloatArray(8)
    private val tiltDst = FloatArray(8)

    /** [w]×[h] is the LOGICAL frame size; the canvas may be pre-scaled (2× "sharper map"). */
    fun draw(canvas: Canvas, f: Frame, w: Int = canvas.width, h: Int = canvas.height) {
        canvas.drawColor(if (f.dark) darkBg else lightBg)
        tilePaint.colorFilter = if (f.dark) darkTileFilter else lightTileFilter
        routeCasing.color = if (f.dark) Color.rgb(18, 22, 28) else Color.WHITE
        standbyPaint.color = if (f.dark) Color.rgb(186, 192, 200) else Color.rgb(60, 64, 67)

        val rotate = f.headingUp
        val tilt = rotate && f.tilt3d
        // Nav view: bias the rider toward the lower third so the road AHEAD fills the
        // screen (like Google Maps navigation). 3D pushes it lower still. North-up
        // view keeps the rider centred.
        val pivotY = if (rotate) (if (tilt) h * 0.74f else h * 0.66f) else h / 2f

        val ts = Mercator.TILE_SIZE
        val cx = Mercator.lngToTileX(f.centerLng, f.zoom) * ts + f.panX
        val cy = Mercator.latToTileY(f.centerLat, f.zoom) * ts + f.panY
        val left = cx - w / 2.0
        val top  = cy - pivotY
        // Tiles are cached as north-up raster bitmaps. Rotate that bitmap layer on every draw so
        // labels baked into the tile follow the current camera heading. This does not affect the
        // screen-space ETA/GPS overlays drawn after the world layer is restored.
        val displayHeading = normalizeHeading(f.cameraHeading)

        fun sx(lng: Double) = (Mercator.lngToTileX(lng, f.zoom) * ts - left).toFloat()
        fun sy(lat: Double) = (Mercator.latToTileY(lat, f.zoom) * ts - top).toFloat()

        if (rotate) canvas.save()
        if (rotate) {
            if (tilt) {
                // Perspective tilt: warp the flat frame into a trapezoid that converges
                // toward the top, so the road ahead recedes into the distance (the
                // Google-Maps 3D look). Near things (rider, bottom) stay ~undistorted;
                // far things (dest, route ahead) shrink, which is exactly right.
                val inset = w * 0.18f
                tiltSrc[0] = 0f;          tiltSrc[1] = 0f
                tiltSrc[2] = w.toFloat(); tiltSrc[3] = 0f
                tiltSrc[4] = w.toFloat(); tiltSrc[5] = h.toFloat()
                tiltSrc[6] = 0f;          tiltSrc[7] = h.toFloat()
                tiltDst[0] = inset;          tiltDst[1] = 0f
                tiltDst[2] = w - inset;      tiltDst[3] = 0f
                tiltDst[4] = w.toFloat();    tiltDst[5] = h.toFloat()
                tiltDst[6] = 0f;             tiltDst[7] = h.toFloat()
                tiltMatrix.setPolyToPoly(tiltSrc, 0, tiltDst, 0, 4)
                canvas.concat(tiltMatrix)
            }
            canvas.rotate(-displayHeading, w / 2f, pivotY)
        }

        // ── Tiles (padded when rotating so corners are covered) ──
        val pad = if (rotate) (maxOf(w, h) * 0.45).toInt() else 0
        val txMin = Math.floorDiv((left - pad).toInt(), ts)
        val tyMin = Math.floorDiv((top - pad).toInt(), ts)
        val txMax = Math.floorDiv((left + w + pad).toInt(), ts)
        val tyMax = Math.floorDiv((top + h + pad).toInt(), ts)
        for (tx in txMin..txMax) for (ty in tyMin..tyMax) {
            val dstL = (tx * ts - left).toFloat()
            val dstT = (ty * ts - top).toFloat()
            tmpRect.set(dstL, dstT, dstL + ts, dstT + ts)
            drawTileBestEffort(canvas, f.zoom, tx, ty, tmpRect)
        }

        // ── Road route polyline ──
        if (f.route.size >= 2) {
            routePath.reset()
            routePath.moveTo(sx(f.route[0].lng), sy(f.route[0].lat))
            for (i in 1 until f.route.size) routePath.lineTo(sx(f.route[i].lng), sy(f.route[i].lat))
            canvas.drawPath(routePath, routeCasing)
            canvas.drawPath(routePath, routePaint)
            // Google Routes returns live speed intervals against encoded-polyline point indexes.
            // Keep the white casing continuous, then colour only the road segments it classifies.
            f.traffic.forEach { interval ->
                val start = interval.startPointIndex.coerceIn(0, f.route.lastIndex)
                val end = interval.endPointIndex.coerceIn(start, f.route.lastIndex)
                if (end <= start) return@forEach
                routePath.reset()
                routePath.moveTo(sx(f.route[start].lng), sy(f.route[start].lat))
                for (i in start + 1..end) routePath.lineTo(sx(f.route[i].lng), sy(f.route[i].lat))
                routePaint.color = when (interval.speed) {
                    TrafficSpeed.NORMAL -> routeBlue
                    TrafficSpeed.SLOW -> trafficAmber
                    TrafficSpeed.TRAFFIC_JAM -> trafficRed
                    TrafficSpeed.UNKNOWN -> routeBlue
                }
                canvas.drawPath(routePath, routePaint)
            }
            routePaint.color = routeBlue // paint is reused by later frames
        }

        // ── Destination pins ──
        val destinationPoints = f.destinations.ifEmpty {
            listOfNotNull(f.destLat?.let { lat -> f.destLng?.let { lng -> GeoPoint(lat, lng) } })
        }
        destinationPoints.forEachIndexed { index, point ->
            val dx = sx(point.lng); val dy = sy(point.lat)
            // Intermediate stops are amber; the final destination remains red.
            dotPaint.color = Color.WHITE; canvas.drawCircle(dx, dy, 12f, dotPaint)
            dotPaint.color = if (index == destinationPoints.lastIndex) googleRed else Color.rgb(242, 169, 59)
            canvas.drawCircle(dx, dy, 9f, dotPaint)
            dotPaint.color = Color.WHITE; canvas.drawCircle(dx, dy, 3.5f, dotPaint)
        }

        // Restore the world transform before drawing the rider. The chevron is a
        // screen-space element: its angle is computed relative to the camera so the
        // map can keep rotating independently of the actual direction of travel.
        if (rotate) canvas.restore()

        // ── Rider marker — colour tracks GPS health (blue good, amber weak, grey lost) ──
        if (f.riderLat != null && f.riderLng != null) {
            // Follow mode deliberately locks the rider to the camera pivot. Drawing in
            // screen space prevents the marker itself from inheriting the world rotation.
            val rx = w / 2f
            val ry = pivotY
            val markerColor = when {
                f.gpsLost -> Color.rgb(150, 154, 160)
                f.gpsWeak -> Color.rgb(251, 188, 5)
                else -> routeBlue
            }
            val haloColor = Color.argb(60, Color.red(markerColor), Color.green(markerColor), Color.blue(markerColor))
            dotPaint.color = haloColor
            canvas.drawCircle(rx, ry, 22f, dotPaint)

            val relativeHeading = if (f.headingUp) {
                normalizeHeading(f.riderHeading - displayHeading)
            } else {
                normalizeHeading(f.riderHeading)
            }

            canvas.save()
            canvas.translate(rx, ry)
            canvas.rotate(relativeHeading)

            dotPaint.color = Color.WHITE
            canvas.drawCircle(0f, 0f, 10f, dotPaint)

            riderPath.reset()
            riderPath.moveTo(0f, -8f)
            riderPath.lineTo(6.5f, 7f)
            riderPath.lineTo(0f, 3.5f)
            riderPath.lineTo(-6.5f, 7f)
            riderPath.close()

            dotPaint.color = markerColor
            canvas.drawPath(riderPath, dotPaint)
            canvas.restore()
        }

        // ── ETA box (screen-space so it stays upright; bottom-right of the round display) ──
        // Shows ETA only (time + arrival clock) — distance lives on the dash's own widget.
        // Was bottom-centre, which sat in the rider's line of sight; now tucked into the lower-right
        // of the visible circle, with every corner checked against it so the bezel never crops it.
        var etaTop = h - ETA_MARGIN_BOTTOM   // zoom box sits above the ETA box (or in its place)
        f.etaPrimary?.let { primary ->
            val secondary = f.etaSecondary
            val padH = 9f; val padV = 4f; val gap = 1f
            val bigFm = etaBigPaint.fontMetrics
            val smallFm = etaSmallPaint.fontMetrics
            val bigH = bigFm.descent - bigFm.ascent
            val smallH = if (secondary != null) smallFm.descent - smallFm.ascent else 0f
            val contentW = maxOf(etaBigPaint.measureText(primary), secondary?.let { etaSmallPaint.measureText(it) } ?: 0f)
            val pillW = (contentW + padH * 2).coerceAtMost(h * 0.5f)
            val pillH = padV * 2 + bigH + (if (secondary != null) gap + smallH else 0f)
            // Bottom-right corner: on the bike the frame's bottom edge is flat (the dash's own band
            // sits below it) and only the TOP corners are cut by the round bezel (photo 2026-10-16).
            pillRect.set(w - ETA_MARGIN_RIGHT - pillW, h - ETA_MARGIN_BOTTOM - pillH,
                w - ETA_MARGIN_RIGHT, h - ETA_MARGIN_BOTTOM)
            val cxp = pillRect.centerX()
            val top = pillRect.top
            etaTop = top
            val r = 8f
            canvas.drawRoundRect(pillRect, r, r, etaBgPaint)
            canvas.drawRoundRect(pillRect, r, r, etaBorderPaint)
            var baseline = top + padV - bigFm.ascent
            canvas.drawText(primary, cxp, baseline, etaBigPaint)
            if (secondary != null) {
                baseline += bigFm.descent + gap - smallFm.ascent
                canvas.drawText(secondary, cxp, baseline, etaSmallPaint)
            }
        }

        // ── Map credit (required by OpenStreetMap), tiny, bottom centre ──
        var bottomEdge = h - ETA_MARGIN_BOTTOM
        overlay("attribution") {
            f.attribution?.let { text ->
                val fm = attributionPaint.fontMetrics
                val tw = attributionPaint.measureText(text)
                val bh = fm.descent - fm.ascent + 4f
                pillRect.set(w / 2f - tw / 2f - 5f, h - 3f - bh, w / 2f + tw / 2f + 5f, h - 3f)
                canvas.drawRoundRect(pillRect, 4f, 4f, etaBgPaint)
                canvas.drawText(text, w / 2f, pillRect.top + 2f - fm.ascent, attributionPaint)
                bottomEdge = pillRect.top - 3f
            }
        }

        // ── Recent joystick presses ("DDRR"), small box at the bottom centre (the DOWN hint
        //    stacks above it) ──
        overlay("joystick presses") {
            f.joystickTrail?.let { trail ->
                val fm = etaSmallPaint.fontMetrics
                val padH = 7f; val padV = 3f
                // Fixed width (4 chars) so the box doesn't jump as presses come and go.
                val bw = etaSmallPaint.measureText("WWWW") + padH * 2
                val bh = padV * 2 + fm.descent - fm.ascent
                val cxp = w / 2f
                pillRect.set(cxp - bw / 2f, bottomEdge - bh, cxp + bw / 2f, bottomEdge)
                canvas.drawRoundRect(pillRect, 6f, 6f, etaBgPaint)
                canvas.drawRoundRect(pillRect, 6f, 6f, etaBorderPaint)
                canvas.drawText(trail, cxp, pillRect.top + padV - fm.ascent, etaSmallPaint)
                bottomEdge = pillRect.top - 4f
            }

        }

        // ── "▼ again: destinations": a lone DOWN is waiting for its second press (3 s) ──
        overlay("down hint") {
            f.downHint?.let { hint ->
                val fm = etaSmallPaint.fontMetrics
                val padH = 9f; val padV = 3f
                val bw = etaSmallPaint.measureText(hint) + padH * 2
                val bh = padV * 2 + fm.descent - fm.ascent
                val cxp = w / 2f
                pillRect.set(cxp - bw / 2f, bottomEdge - bh, cxp + bw / 2f, bottomEdge)
                canvas.drawRoundRect(pillRect, 8f, 8f, etaBgPaint)
                canvas.drawRoundRect(pillRect, 8f, 8f, etaBorderPaint)
                canvas.drawText(hint, cxp, pillRect.top + padV - fm.ascent, etaSmallPaint)
            }
        }

        // ── Phone battery, always (setting): right-aligned just above the ETA box, with a bolt
        //    while charging. Low battery (≤15 %) in red. ──
        var stackTop = etaTop   // next box up the right-hand stack goes above this
        overlay("battery") {
            f.phoneBattery?.let { (pct, charging) ->
                val label = "$pct%"
                val fm = etaSmallPaint.fontMetrics
                val padH = 9f; val padV = 4f
                val textH = fm.descent - fm.ascent
                val boltW = if (charging) textH * 0.55f + 4f else 0f
                val bw = etaSmallPaint.measureText(label) + boltW + padH * 2
                val bh = padV * 2 + textH
                val bottom = stackTop - 6f
                pillRect.set(w - ETA_MARGIN_RIGHT - bw, bottom - bh, w - ETA_MARGIN_RIGHT, bottom)
                canvas.drawRoundRect(pillRect, 8f, 8f, etaBgPaint)
                canvas.drawRoundRect(pillRect, 8f, 8f, etaBorderPaint)
                val textLeft = pillRect.left + padH + boltW
                if (charging) {
                    // Lightning bolt drawn as a path (an emoji glyph may render in colour or not at all).
                    val bx = pillRect.left + padH; val top = pillRect.top + padV + 1f; val hgt = textH - 2f
                    val bwid = textH * 0.55f
                    boltPath.reset()
                    boltPath.moveTo(bx + bwid * 0.65f, top)
                    boltPath.lineTo(bx, top + hgt * 0.56f)
                    boltPath.lineTo(bx + bwid * 0.45f, top + hgt * 0.56f)
                    boltPath.lineTo(bx + bwid * 0.3f, top + hgt)
                    boltPath.lineTo(bx + bwid, top + hgt * 0.4f)
                    boltPath.lineTo(bx + bwid * 0.55f, top + hgt * 0.4f)
                    boltPath.close()
                    canvas.drawPath(boltPath, boltPaint)
                }
                val oldColor = etaSmallPaint.color
                val oldAlign = etaSmallPaint.textAlign
                // Yellow while on battery (red once low); normal colour while charging.
                if (!charging) etaSmallPaint.color = if (pct <= 15) googleRed else batteryYellow
                etaSmallPaint.textAlign = Paint.Align.LEFT
                canvas.drawText(label, textLeft, pillRect.top + padV - fm.ascent, etaSmallPaint)
                etaSmallPaint.color = oldColor; etaSmallPaint.textAlign = oldAlign
                stackTop = pillRect.top
            }

        }

        // ── Zoom level, for 3 s after each zoom: right-aligned above the battery / ETA box ──
        overlay("zoom") {
            f.zoomLabel?.let { label ->
                val fm = etaSmallPaint.fontMetrics
                val padH = 9f; val padV = 4f
                val bw = etaSmallPaint.measureText(label) + padH * 2
                val bh = padV * 2 + fm.descent - fm.ascent
                val bottom = stackTop - 6f
                pillRect.set(w - ETA_MARGIN_RIGHT - bw, bottom - bh, w - ETA_MARGIN_RIGHT, bottom)
                canvas.drawRoundRect(pillRect, 8f, 8f, etaBgPaint)
                canvas.drawRoundRect(pillRect, 8f, 8f, etaBorderPaint)
                canvas.drawText(label, pillRect.centerX(), pillRect.top + padV - fm.ascent, etaSmallPaint)
                stackTop = pillRect.top
            }

        }

        // ── Routing source in use: "G" green (Google), "O" orange (OSM), "BR" brown (BRouter),
        //    filled box at the top of the right-hand stack ──
        overlay("routing source") {
            f.routingBadge?.let { src ->
                val (label, fill) = when (src) {
                    com.example.wayfindr.dash.RoutingSource.GOOGLE -> "G" to ROUTING_GREEN
                    com.example.wayfindr.dash.RoutingSource.OSM -> "O" to ROUTING_ORANGE
                    com.example.wayfindr.dash.RoutingSource.BROUTER -> "BR" to ROUTING_BROWN
                }
                val fm = etaSmallPaint.fontMetrics
                val padH = 9f; val padV = 4f
                // At least as wide as "BR" so the box doesn't change size between sources.
                val bw = maxOf(etaSmallPaint.measureText("BR"), etaSmallPaint.measureText(label)) + padH * 2
                val bh = padV * 2 + fm.descent - fm.ascent
                val bottom = stackTop - 6f
                pillRect.set(w - ETA_MARGIN_RIGHT - bw, bottom - bh, w - ETA_MARGIN_RIGHT, bottom)
                routingBadgePaint.color = fill
                canvas.drawRoundRect(pillRect, 8f, 8f, routingBadgePaint)
                val oldColor = etaSmallPaint.color; val oldBold = etaSmallPaint.isFakeBoldText
                etaSmallPaint.color = Color.WHITE; etaSmallPaint.isFakeBoldText = true
                canvas.drawText(label, pillRect.centerX(), pillRect.top + padV - fm.ascent, etaSmallPaint)
                etaSmallPaint.color = oldColor; etaSmallPaint.isFakeBoldText = oldBold
                stackTop = pillRect.top
            }
        }

        // ── GPS-health pill (top-centre safe zone) — only when degraded, so the rider knows the
        // marker is imprecise/held rather than wondering why the map stopped tracking ──
        if (f.gpsLost || f.gpsWeak) {
            val label = if (f.gpsLost) "GPS lost" else "GPS weak"
            gpsPillText.color = if (f.gpsLost) googleRed else Color.rgb(251, 188, 5)
            val padH = 14f; val padV = 6f
            val fm = gpsPillText.fontMetrics
            val textH = fm.descent - fm.ascent
            val pillW = gpsPillText.measureText(label) + padH * 2
            val cxp = w / 2f
            val top = 14f
            val bottom = top + padV * 2 + textH
            pillRect.set(cxp - pillW / 2f, top, cxp + pillW / 2f, bottom)
            val r = (bottom - top) / 2f
            canvas.drawRoundRect(pillRect, r, r, etaBgPaint)
            canvas.drawRoundRect(pillRect, r, r, etaBorderPaint)
            canvas.drawText(label, cxp, top + padV - fm.ascent, gpsPillText)
        }

        f.notice?.let { lines -> drawNotice(canvas, w, h, lines) }
        f.pickerRows?.let { rows -> drawPicker(canvas, w, h, f.pickerTitle, rows, f.pickerIndex, f.pickerSelectHint) }

        // No other on-map text overlays — the dash's own widgets show name/turn, and the
        // round bezel clips anything near the top edge.

        // ── Standby when nothing to show ──
        if (f.riderLat == null && f.destLat == null) {
            val msg = "WAYFINDR · waiting for GPS"
            standbyPaint.getTextBounds(msg, 0, msg.length, textBounds)
            canvas.drawText(msg, (w - textBounds.width()) / 2f, h / 2f, standbyPaint)
        }

    }

    /**
     * Saved-destination picker: a centred panel with a header, a window of [PICKER_ROWS] rows
     * scrolled to keep the highlighted one visible, and a control hint. Sized so all four corners
     * sit inside the visible circle (same safe-zone assumption as the ETA box).
     */
    private fun drawPicker(canvas: Canvas, w: Int, h: Int, title: String, items: List<MenuRow>, index: Int, selectHint: String) {
        val rowH = 40f; val headH = 28f; val hintH = 26f; val pad = 6f
        val rows = minOf(PICKER_ROWS, items.size)
        // Right edge where it always was; left edge pushed clear of the dash's own turn-glyph circle,
        // which it draws over the lower-left of our frame (photo 2026-10-16 cropped the list there).
        val right = w / 2f + PICKER_W / 2f
        val left = PICKER_LEFT
        val panelW = right - left
        val panelH = pad + headH + rows * rowH + hintH + pad
        val cx = (left + right) / 2f; val cy = h / 2f
        pillRect.set(left, cy - panelH / 2f, right, cy + panelH / 2f)
        canvas.drawRoundRect(pillRect, 12f, 12f, pickerBgPaint)
        canvas.drawRoundRect(pillRect, 12f, 12f, etaBorderPaint)

        var y = pillRect.top + pad
        canvas.drawText(title, cx, y + headH / 2f - (pickerTitlePaint.descent() + pickerTitlePaint.ascent()) / 2f, pickerTitlePaint)
        y += headH

        val sel = index.coerceIn(0, items.lastIndex)
        val first = (sel - rows / 2).coerceIn(0, maxOf(0, items.size - rows))
        val textX = pillRect.left + 16f
        val maxTextW = panelW - 32f
        for (i in first until first + rows) {
            val row = items[i]
            if (row.header) {
                // Section title: small grey caps with a rule under it; never highlighted.
                val ty = y + rowH / 2f - (pickerSectionPaint.descent() + pickerSectionPaint.ascent()) / 2f
                canvas.drawText(ellipsize(row.label.uppercase(), pickerSectionPaint, maxTextW), textX - 6f, ty, pickerSectionPaint)
                y += rowH
                continue
            }
            val selected = i == sel
            pickerRow.set(pillRect.left + 5f, y + 1f, pillRect.right - 5f, y + rowH - 1f)
            if (selected) canvas.drawRoundRect(pickerRow, 7f, 7f, pickerHiPaint)
            pickerItemPaint.color = when {
                selected -> Color.rgb(20, 22, 26)
                row.active -> ACTIVE_GREEN
                else -> Color.rgb(236, 238, 241)
            }
            pickerItemPaint.isFakeBoldText = selected || row.active
            val mark = when { row.active -> "✓"; row.submenu -> "›"; else -> null }
            val markW = if (mark != null) 28f else 0f
            val textY = y + rowH / 2f - (pickerItemPaint.descent() + pickerItemPaint.ascent()) / 2f
            val label = ellipsize(row.label, pickerItemPaint, maxTextW - markW)
            canvas.drawText(label, textX, textY, pickerItemPaint)
            if (mark != null) {
                pickerMarkPaint.color = if (selected) Color.rgb(20, 22, 26) else if (row.active) ACTIVE_GREEN else Color.rgb(150, 156, 164)
                canvas.drawText(mark, pillRect.right - 16f, textY, pickerMarkPaint)
            }
            y += rowH
        }
        canvas.drawText(ellipsize(selectHint, pickerHintPaint, panelW - 10f), cx, y + hintH / 2f - (pickerHintPaint.descent() + pickerHintPaint.ascent()) / 2f, pickerHintPaint)
    }

    /** Confirmation panel above the rider marker: first line bold, the rest normal; fits the safe circle. */
    private fun drawNotice(canvas: Canvas, w: Int, h: Int, lines: List<String>) {
        if (lines.isEmpty()) return
        val lineH = 30f; val padV = 8f
        val maxTextW = NOTICE_W - 24f
        val texts = lines.mapIndexed { i, s ->
            pickerItemPaint.isFakeBoldText = i == 0
            ellipsize(s, pickerItemPaint, maxTextW)
        }
        var textW = 0f
        texts.forEachIndexed { i, s -> pickerItemPaint.isFakeBoldText = i == 0; textW = maxOf(textW, pickerItemPaint.measureText(s)) }
        val panelW = textW + 24f
        val panelH = padV * 2 + lineH * texts.size
        val cx = w / 2f
        val bottom = h / 2f - NOTICE_GAP_ABOVE_CENTRE
        pillRect.set(cx - panelW / 2f, bottom - panelH, cx + panelW / 2f, bottom)
        canvas.drawRoundRect(pillRect, 10f, 10f, pickerBgPaint)
        canvas.drawRoundRect(pillRect, 10f, 10f, etaBorderPaint)
        val align = pickerItemPaint.textAlign
        pickerItemPaint.textAlign = Paint.Align.CENTER
        var y = pillRect.top + padV
        texts.forEachIndexed { i, s ->
            pickerItemPaint.isFakeBoldText = i == 0
            pickerItemPaint.color = if (i == 0) Color.rgb(236, 238, 241) else ACTIVE_GREEN
            canvas.drawText(s, cx, y + lineH / 2f - (pickerItemPaint.descent() + pickerItemPaint.ascent()) / 2f, pickerItemPaint)
            y += lineH
        }
        pickerItemPaint.textAlign = align
        pickerItemPaint.isFakeBoldText = false
    }

    private fun ellipsize(s: String, p: Paint, maxW: Float): String {
        if (p.measureText(s) <= maxW) return s
        var end = s.length
        while (end > 1 && p.measureText(s, 0, end) + p.measureText("…") > maxW) end--
        return s.substring(0, end) + "…"
    }

    /**
     * Place a [bw]×[bh] box as far out as possible along [angleDeg] (0 = right, 90 = down) while
     * keeping ALL four corners inside the dash's visible circle, writing the result into [out].
     *
     * Visible circle: the round TFT shows a circle centred on the frame, about the frame's height
     * across — the 526-wide frame's sides are cropped (the call banner and GPS pill are sized to the
     * same assumption). [SAFE_RADIUS_FRAC] trims a margin for the bezel; lower it if the box clips.
     */
    private fun placeInSafeCircle(w: Int, h: Int, bw: Float, bh: Float, angleDeg: Float, out: RectF) {
        val cx = w / 2f; val cy = h / 2f
        val radius = h / 2f * SAFE_RADIUS_FRAC
        val ux = Math.cos(Math.toRadians(angleDeg.toDouble())).toFloat()
        val uy = Math.sin(Math.toRadians(angleDeg.toDouble())).toFloat()
        fun inside(x: Float, y: Float) = (x - cx) * (x - cx) + (y - cy) * (y - cy) <= radius * radius
        // Walk the box's centre outward from the middle; keep the last spot where it still fits.
        var best = 0f
        var d = 0f
        while (d <= radius) {
            val bx = cx + ux * d; val by = cy + uy * d
            val l = bx - bw / 2f; val t = by - bh / 2f; val rr = bx + bw / 2f; val b = by + bh / 2f
            if (inside(l, t) && inside(rr, t) && inside(l, b) && inside(rr, b)) best = d else break
            d += 1f
        }
        val bx = cx + ux * best; val by = cy + uy * best
        out.set(bx - bw / 2f, by - bh / 2f, bx + bw / 2f, by + bh / 2f)
    }

    private fun normalizeHeading(degrees: Float): Float {
        var value = degrees % 360f
        if (value < 0f) value += 360f
        return value
    }

    /**
     * Draws one map tile, never leaving a blank cell on a cache miss. Slippy-map trick:
     * 1. exact tile if cached (also kicks off the async load);
     * 2. else scale UP the matching sub-region of a cached lower-zoom ancestor — the level
     *    we just left is still in memory, so a zoom-IN renders instantly and only sharpens
     *    once the real tile arrives (no blank flash, which is what made zoom feel slow);
     * 3. else scale DOWN the four cached higher-zoom children into quadrants (zoom-OUT case).
     */
    private fun drawTileBestEffort(canvas: Canvas, z: Int, tx: Int, ty: Int, dst: RectF) {
        tiles.get(z, tx, ty)?.let { canvas.drawBitmap(it, null, dst, tilePaint); return }

        var up = 1
        while (up <= 4 && z - up >= 0) {
            val anc = tiles.getCached(z - up, tx shr up, ty shr up)
            if (anc != null) {
                val cells = 1 shl up
                // Sub-region in the bitmap's OWN pixel space (512 px for hi-DPI tiles, 256 for any
                // legacy tile) — not the logical TILE_SIZE, or it would sample the wrong quadrant.
                val sub = anc.width / cells
                val sxOff = (tx and (cells - 1)) * sub
                val syOff = (ty and (cells - 1)) * sub
                tileSrc.set(sxOff, syOff, sxOff + sub, syOff + sub)
                canvas.drawBitmap(anc, tileSrc, dst, tilePaint)
                return
            }
            up++
        }

        val halfW = (dst.right - dst.left) / 2f
        val halfH = (dst.bottom - dst.top) / 2f
        for (qx in 0..1) for (qy in 0..1) {
            val child = tiles.getCached(z + 1, tx * 2 + qx, ty * 2 + qy) ?: continue
            childDst.set(
                dst.left + qx * halfW, dst.top + qy * halfH,
                dst.left + qx * halfW + halfW, dst.top + qy * halfH + halfH,
            )
            canvas.drawBitmap(child, null, childDst, tilePaint)
        }
        // Deep miss across all levels → the land-colour bg shows through (rare).
    }

    companion object {
        /** Visible-circle radius as a fraction of half the frame height (rest = bezel margin). */
        private const val SAFE_RADIUS_FRAC = 0.92f
        /** Direction from the centre the ETA box is pushed: 0° = right, 90° = down. 55° puts it
         *  low-right, clear of the rider marker (on the centre line, at 0.5h or 0.66h) and of the
         *  route behind it. Steeper covers the route; shallower crowds the marker. */
        private const val ETA_ANGLE_DEG = 55f
        /** ETA box inset from the frame's bottom-right corner. Raise if the bezel clips it. */
        private const val ETA_MARGIN_RIGHT = 24f
        private const val ETA_MARGIN_BOTTOM = 8f
        /** Picker panel: width and visible rows chosen so its corners stay inside the safe circle. */
        private const val PICKER_W = 380f
        /** Routing-source box fills: Google green, OSM orange, BRouter brown. */
        private val ROUTING_GREEN = Color.rgb(30, 142, 62)
        private val ROUTING_ORANGE = Color.rgb(239, 108, 0)
        private val ROUTING_BROWN = Color.rgb(121, 85, 72)
        /** Notice panel: max width, and how far its bottom sits above the frame centre (rider). */
        private const val NOTICE_W = 340f
        private const val NOTICE_GAP_ABOVE_CENTRE = 22f
        /** Menu: the setting currently in use. */
        private val ACTIVE_GREEN = Color.rgb(96, 205, 128)
        private const val PICKER_ROWS = 4
        /** Picker's left edge: clear of the dash's native turn circle (~x 0–145 of the frame). */
        private const val PICKER_LEFT = 160f
        /**
         * CSS-style invert() + hue-rotate(180deg) so land goes dark while water/parks
         * keep a plausible hue. Applied at blit time; the disk cache stays the light rasters.
         */
        private fun nightTileMatrix(): ColorMatrix {
            val invert = ColorMatrix(
                floatArrayOf(
                    -1f, 0f, 0f, 0f, 255f,
                    0f, -1f, 0f, 0f, 255f,
                    0f, 0f, -1f, 0f, 255f,
                    0f, 0f, 0f, 1f, 0f,
                )
            )
            // W3C filter-effects hue-rotate(180°): cos=-1, sin=0
            val hue = ColorMatrix(
                floatArrayOf(
                    -0.574f, 1.430f, 0.144f, 0f, 0f,
                    0.426f, 0.430f, 0.144f, 0f, 0f,
                    0.426f, 1.430f, -0.856f, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f,
                )
            )
            val sat = ColorMatrix().apply { setSaturation(1.15f) }
            val dim = ColorMatrix(
                floatArrayOf(
                    0.86f, 0f, 0f, 0f, 6f,
                    0f, 0.86f, 0f, 0f, 6f,
                    0f, 0f, 0.86f, 0f, 6f,
                    0f, 0f, 0f, 1f, 0f,
                )
            )
            val out = ColorMatrix(invert)
            out.postConcat(hue)
            out.postConcat(sat)
            out.postConcat(dim)
            return out
        }
    }
}
