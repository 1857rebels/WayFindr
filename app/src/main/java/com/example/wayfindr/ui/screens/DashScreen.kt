package com.example.wayfindr.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.wayfindr.ui.WayfindrIcons
import com.example.wayfindr.ui.components.*
import com.example.wayfindr.ui.theme.*
import com.example.wayfindr.viewmodel.ConnStage
import com.example.wayfindr.viewmodel.DashViewModel
import kotlinx.coroutines.delay

@Composable
fun DashScreen(vm: DashViewModel = viewModel()) {
    val ui by vm.ui.collectAsState()
    val previewFrame by vm.previewFrame.collectAsState()
    val context = LocalContext.current

    // WiFi network request (13+: NEARBY_WIFI_DEVICES) + GPS for the map
    // Essential perms gate the connection; notifications are requested but optional.
    val essentialPermissions = remember {
        buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }.toTypedArray()
    }
    val requestedPermissions = remember {
        buildList {
            addAll(essentialPermissions)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray()
    }

    fun hasEssentialPermissions() = essentialPermissions.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    val hasBackgroundLocation = remember(ui.stage) {
        com.example.wayfindr.util.DeviceReadiness.hasBackgroundLocation(context)
    }

    // Cross-OEM: is the app exempt from battery optimization? Without it, OxygenOS/One UI/MIUI/etc.
    // kill the stream when the screen turns off. Re-checked on resume (the rider grants it in a
    // system dialog and returns).
    var batteryOk by remember {
        mutableStateOf(com.example.wayfindr.util.DeviceReadiness.isIgnoringBatteryOptimizations(context))
    }

    // The battery-exemption system dialog, launched as the last step before connecting. Whatever
    // the rider chooses, we re-check and continue the connect they started — no second button.
    val batteryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        batteryOk = com.example.wayfindr.util.DeviceReadiness.isIgnoringBatteryOptimizations(context)
        vm.connect()
    }

    // Connect, but first ask for background activity (battery-optimization exemption) once if it's
    // not already granted — folded into Connect so there's a single button. Remembered so we don't
    // re-prompt on later connects.
    fun connectWithBackgroundCheck() {
        if (!batteryOk && !com.example.wayfindr.util.DeviceReadiness.batteryExemptionAsked(context)) {
            com.example.wayfindr.util.DeviceReadiness.markBatteryExemptionAsked(context)
            val launched = runCatching {
                batteryLauncher.launch(com.example.wayfindr.util.DeviceReadiness.batteryExemptionIntent(context))
            }.isSuccess
            if (!launched) vm.connect()  // OEM without the standard dialog — connect anyway
        } else {
            vm.connect()
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val essentialOk = essentialPermissions.all { results[it] == true }
        if (essentialOk) connectWithBackgroundCheck()
    }

    // Local preview state (mirrors what the dash shows)
    var pan by remember { mutableStateOf(Offset.Zero) }
    var adjustMode by remember { mutableStateOf(true) }
    var joystickVelocity by remember { mutableStateOf(Offset.Zero) }

    // Physical joystick button → preview-only nudge (real map pan happens in the ViewModel)
    LaunchedEffect(ui.lastButton) {
        val b = ui.lastButton ?: return@LaunchedEffect
        when {
            b.startsWith("→") -> pan = Offset((pan.x - 12f).coerceIn(-46f, 46f), pan.y)
            b.startsWith("←") -> pan = Offset((pan.x + 12f).coerceIn(-46f, 46f), pan.y)
            b.startsWith("↓") -> pan = Offset(pan.x, (pan.y - 12f).coerceIn(-46f, 46f))
            b.startsWith("↑") -> pan = Offset(pan.x, (pan.y + 12f).coerceIn(-46f, 46f))
            b.startsWith("●") -> pan = Offset.Zero
        }
    }

    LaunchedEffect(joystickVelocity, adjustMode) {
        while (adjustMode && (joystickVelocity.x != 0f || joystickVelocity.y != 0f)) {
            pan = Offset(
                (pan.x - joystickVelocity.x * 2.4f).coerceIn(-46f, 46f),
                (pan.y - joystickVelocity.y * 2.4f).coerceIn(-46f, 46f),
            )
            vm.panBy(joystickVelocity.x * 4f, joystickVelocity.y * 4f)
            delay(16)
        }
    }

    val streaming = ui.stage == ConnStage.STREAMING

    // Auto-connect on opening the Dash screen, so the rider doesn't tap "Connect" every
    // ride — just open the app. Fires once; if the dash is off it errors out quietly and
    // the rider can retry. (Needs permissions already granted from a prior run.)
    // rememberSaveable so a config change (rotation/theme) doesn't reset this and silently
    // reconnect after the rider deliberately disconnected.
    var autoConnectTried by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!autoConnectTried && ui.stage == ConnStage.OFFLINE && hasEssentialPermissions()) {
            autoConnectTried = true
            vm.connect()
        }
    }

    // If the rider was prompted to turn Wi‑Fi on and comes back to the app with it now enabled
    // (e.g. from the Wi‑Fi panel), retry the connection automatically — no second tap needed.
    val uiState = rememberUpdatedState(ui)
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                batteryOk = com.example.wayfindr.util.DeviceReadiness.isIgnoringBatteryOptimizations(context)
                if (uiState.value.needsWifiOn &&
                    (context.applicationContext.getSystemService(android.content.Context.WIFI_SERVICE)
                        as android.net.wifi.WifiManager).isWifiEnabled &&
                    hasEssentialPermissions()
                ) {
                    vm.connect()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(18.dp)
            .padding(bottom = 24.dp),
    ) {
        ScreenHeader(
            eyebrow = "What the rider sees",
            title = "Dash view",
            hint = "A live preview of the map sent to the dash. Tap Connect to join the bike's WiFi and stream — your phone screen can stay off.",
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (streaming && ui.gpsStatus != com.example.wayfindr.viewmodel.GpsStatus.GOOD) {
                        WayfindrChip(
                            if (ui.gpsStatus == com.example.wayfindr.viewmodel.GpsStatus.LOST) "GPS lost" else "GPS weak",
                            if (ui.gpsStatus == com.example.wayfindr.viewmodel.GpsStatus.LOST) ChipTone.Alert else ChipTone.Warn,
                        )
                    }
                    if (streaming && ui.thermal != "OK") {
                        WayfindrChip(
                            ui.thermal,
                            if (ui.thermal == "Warm") ChipTone.Warn else ChipTone.Alert,
                        )
                    }
                    when (ui.stage) {
                        ConnStage.STREAMING -> WayfindrChip("LIVE", ChipTone.Gold, dot = true)
                        ConnStage.WIFI,
                        ConnStage.AUTH      -> WayfindrChip("Connecting…", ChipTone.Neutral)
                        ConnStage.ERROR     -> WayfindrChip("Error", ChipTone.Alert)
                        ConnStage.OFFLINE   -> WayfindrChip("Offline", ChipTone.Off)
                    }
                }
            },
        )

        // Single connection card (hidden once streaming)
        if (!streaming) {
            if (!hasBackgroundLocation) {
                WayfindrCard(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp),
                    glow = true
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.weight(1f).padding(end = 12.dp)) {
                            Text(
                                "Set Location to \"Allow all the time\"",
                                color = TextHi, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                "Essential to keep GPS fresh when the screen is off. Select \"Allow all the time\" in system settings.",
                                color = TextMid, fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp),
                            )
                        }
                        WayfindrBtn(
                            "Fix",
                            onClick = {
                                context.startActivity(com.example.wayfindr.util.DeviceReadiness.appSettingsIntent(context))
                            },
                            variant = BtnVariant.Ghost,
                            size = BtnSize.Sm,
                        )
                    }
                }
            }
            WayfindrCard(modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(
                            when (ui.stage) {
                                ConnStage.OFFLINE -> "Connect to Console Dash"
                                ConnStage.WIFI    -> if (ui.ssid.isNotBlank()) "Joining ${ui.ssid}…" else "Finding your dash…"
                                ConnStage.AUTH    -> if (ui.retryAttempt > 0) "Retrying handshake…" else "Pairing with dash…"
                                ConnStage.ERROR   -> "Couldn't connect"
                                ConnStage.STREAMING -> "Streaming"
                            },
                            color = TextHi, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            when (ui.stage) {
                                ConnStage.OFFLINE -> "Turn the dash on, stand near the bike, then tap Connect"
                                ConnStage.WIFI    -> "Accept the system dialog if it appears"
                                ConnStage.AUTH    -> if (ui.retryAttempt > 0)
                                    "Handshake attempt ${ui.retryAttempt} of 4 — this is normal"
                                    else "Securing the link to firmware 11.63…"
                                ConnStage.ERROR   -> ui.errorMessage
                                    ?: "Make sure the dash is on and you're beside the bike, then try again."
                                ConnStage.STREAMING -> ""
                            },
                            color = if (ui.stage == ConnStage.ERROR) Warn else TextMid,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 3.dp),
                        )
                    }
                    if (ui.stage == ConnStage.OFFLINE || ui.stage == ConnStage.ERROR) {
                        WayfindrBtn(
                            when {
                                ui.needsWifiOn -> "Turn on Wi‑Fi"
                                ui.stage == ConnStage.ERROR -> "Try again"
                                else -> "Connect"
                            },
                            onClick = {
                                when {
                                    // Android 10+ won't let us toggle Wi‑Fi — open the system Wi‑Fi
                                    // panel (slide-up, stays in-app). connect() auto-retries on resume.
                                    ui.needsWifiOn -> runCatching {
                                        context.startActivity(
                                            android.content.Intent(android.provider.Settings.Panel.ACTION_WIFI)
                                        )
                                    }
                                    // Connect handles the background-activity ask itself (single button).
                                    hasEssentialPermissions() -> connectWithBackgroundCheck()
                                    else -> permissionLauncher.launch(requestedPermissions)
                                }
                            },
                            icon = WayfindrIcons.Wifi,
                            variant = BtnVariant.Primary,
                            size = BtnSize.Sm,
                        )
                    } else {
                        WayfindrBtn(
                            "Cancel",
                            onClick = { vm.disconnect() },
                            variant = BtnVariant.Ghost,
                            size = BtnSize.Sm,
                        )
                    }
                }
                if (ui.frameCount > 0) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Frames encoded: ${ui.frameCount}",
                        color = TextLo, fontSize = 11.sp, fontFamily = GeistMonoFamily,
                    )
                }
                ui.lastButton?.let { btn ->
                    Text(
                        "Joystick: $btn",
                        color = Gold, fontSize = 11.sp, fontFamily = GeistMonoFamily,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }


        // Circular viewport with streaming aura
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        ) {
            if (streaming) {
                Box(
                    Modifier
                        .size(300.dp)
                        .clip(RoundedCornerShape(percent = 50))
                        .background(Brush.radialGradient(listOf(GoldGlow, Color.Transparent), radius = 200f))
                )
            }
            // Round Console viewport. While streaming we show the EXACT frame being encoded to
            // the dash (same renderer → pixel-identical to the bike). Before connecting there's no
            // dash frame yet, so we fall back to the live MapLibre map for route preview.
            Box(
                modifier = Modifier
                    .size(272.dp)
                    .clip(CircleShape)
                    .border(6.dp, Color(0xFF0D0F10), CircleShape)
                    .border(2.dp, Line2, CircleShape),
            ) {
                val dashFrame = previewFrame
                // Show the live dash frame if available; otherwise show the interactive Google Maps
                // preview. This removes the "Waiting..." screen and ensures the rider always has
                // a map view during the connection handshake.
                if (dashFrame != null) {
                    androidx.compose.foundation.Image(
                        bitmap = dashFrame.asImageBitmap(),
                        contentDescription = "Live dash frame",
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    WayfindrMap(
                        // Keep the preview marker aligned with the exact smoothed/predicted
                        // camera centre used by the encoded dash frame.
                        riderLat = ui.camLat ?: ui.riderLat,
                        riderLng = ui.camLng ?: ui.riderLng,
                        dest = ui.destLatLng,
                        routePoints = ui.routePoints,
                        trafficIntervals = ui.routeTraffic,
                        hasLocationPermission = hasEssentialPermissions(),
                        navMode = ui.headingUp,
                        riderBearing = ui.riderBearing,
                        camLat = ui.camLat,
                        camLng = ui.camLng,
                        camZoom = ui.mapZoom.toFloat(),
                        camBearing = ui.camBearing,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // Next-turn banner (real turn-by-turn from the routing engine)
        ui.maneuver?.let { mv ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(13.dp))
                    .background(if (ui.offRoute) Color(0x33D8853E) else GoldTint)
                    .border(1.dp, if (ui.offRoute) Warn else GoldTint2, RoundedCornerShape(13.dp))
                    .padding(horizontal = 14.dp, vertical = 11.dp),
            ) {
                Icon(WayfindrIcons.Navi, null, tint = if (ui.offRoute) Warn else Gold, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(11.dp))
                Text(
                    if (ui.offRoute) "Off route — rerouting…" else mv,
                    color = TextHi, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(10.dp))
        }

        // Live info strip — real remaining distance, ETA, zoom
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(
                Triple(
                    ui.remainingKm?.let { if (it >= 10) "%.0f".format(it) else "%.1f".format(it) } ?: "—",
                    if (ui.remainingKm != null) "km" else "", "Remaining",
                ),
                Triple(
                    ui.etaMinutes?.let { if (it >= 60) "${it / 60}h ${it % 60}" else "$it" } ?: "—",
                    ui.etaMinutes?.let { if (it >= 60) "m" else "min" } ?: "", "ETA",
                ),
                Triple("z${ui.mapZoom}", "", "Zoom"),
            ).forEach { (v, u, k) ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(13.dp))
                        .background(Surf1)
                        .border(1.dp, Line, RoundedCornerShape(13.dp))
                        .padding(11.dp),
                ) {
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.Center) {
                        Text(v, color = TextHi, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, fontFamily = GeistMonoFamily)
                        if (u.isNotEmpty()) {
                            Spacer(Modifier.width(3.dp))
                            Text(u, color = TextLo, fontSize = 10.5.sp, fontFamily = GeistMonoFamily, modifier = Modifier.padding(bottom = 2.dp))
                        }
                    }
                    Eyebrow(k, Modifier.padding(top = 3.dp))
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Map adjust mode toggle
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(if (adjustMode) GoldTint else Surf1)
                .border(1.dp, if (adjustMode) GoldTint2 else Line, RoundedCornerShape(14.dp))
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(WayfindrIcons.Cross, null, tint = if (adjustMode) Gold else TextMid, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(11.dp))
                Column {
                    Text("Map adjust mode", color = TextHi, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, fontFamily = GeistFamily)
                    Text("Mirrors the bike joystick", color = TextLo, fontSize = 11.5.sp, modifier = Modifier.padding(top = 1.dp))
                }
            }
            WayfindrToggle(on = adjustMode, onChange = { adjustMode = it })
        }

        Spacer(Modifier.height(10.dp))

        // Heading-up toggle (rotate map to travel direction)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Surf1)
                .border(1.dp, Line, RoundedCornerShape(14.dp))
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(WayfindrIcons.Navi, null, tint = if (ui.headingUp) Gold else TextMid, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(11.dp))
                Column {
                    Text("Heading-up map", color = TextHi, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, fontFamily = GeistFamily)
                    Text(if (ui.headingUp) "Rotates to travel direction" else "North-up", color = TextLo, fontSize = 11.5.sp, modifier = Modifier.padding(top = 1.dp))
                }
            }
            WayfindrToggle(on = ui.headingUp, onChange = { vm.toggleHeadingUp() })
        }

        Spacer(Modifier.height(10.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Surf1)
                .border(1.dp, Line, RoundedCornerShape(14.dp))
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(WayfindrIcons.Moon, null, tint = if (ui.mapDark) Gold else TextMid, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(11.dp))
                Column {
                    Text("Dark map", color = TextHi, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, fontFamily = GeistFamily)
                    Text(
                        (if (ui.mapDarkAuto) "Auto (7 pm–5 am) · " else "Manual · ") +
                            (if (ui.mapDark) "night look" else "daylight tiles"),
                        color = TextLo, fontSize = 11.5.sp, modifier = Modifier.padding(top = 1.dp),
                    )
                }
            }
            WayfindrToggle(on = ui.mapDark, onChange = { vm.setMapDark(it) })
        }

        Spacer(Modifier.height(16.dp))

        // Controls: joystick + zoom (drive the actual dash map)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Surf1)
                    .border(1.dp, Line, RoundedCornerShape(18.dp))
                    .padding(vertical = 16.dp, horizontal = 12.dp),
            ) {
                Joystick(
                    size = 128.dp,
                    onMove = { v -> joystickVelocity = if (adjustMode) v else Offset.Zero },
                )
                Spacer(Modifier.height(9.dp))
                Eyebrow("Pan")
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                WayfindrIconBtn(WayfindrIcons.Plus,  onClick = { vm.zoomIn() },  size = 52.dp)
                Text("z${ui.mapZoom}", color = Gold, fontSize = 12.sp, fontFamily = GeistMonoFamily, fontWeight = FontWeight.SemiBold)
                WayfindrIconBtn(WayfindrIcons.Minus, onClick = { vm.zoomOut() }, size = 52.dp)
                WayfindrIconBtn(WayfindrIcons.Recenter, onClick = { vm.recenter(); pan = Offset.Zero }, size = 52.dp, active = true)
            }
        }

        // Exit navigation → free roam (keeps streaming, just drops the route)
        if (streaming && ui.destinationName != null) {
            Spacer(Modifier.height(16.dp))
            WayfindrBtn(
                "Exit navigation",
                onClick = { vm.exitNavigation() },
                icon = WayfindrIcons.Navi,
                variant = BtnVariant.Ghost,
                size = BtnSize.Md,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // Disconnect button when streaming
        if (streaming) {
            Spacer(Modifier.height(20.dp))
            WayfindrBtn(
                "Disconnect",
                onClick = { vm.disconnect() },
                icon = WayfindrIcons.Power,
                variant = BtnVariant.Danger,
                size = BtnSize.Md,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
