package com.example.wayfindr.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.wayfindr.dash.DashConfig
import com.example.wayfindr.ui.WayfindrIcons
import com.example.wayfindr.ui.components.*
import com.example.wayfindr.ui.theme.*
import com.example.wayfindr.viewmodel.ConnectionState
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    conn: ConnectionState,
    onConnChange: (ConnectionState) -> Unit,
    onBack: () -> Unit,
) {
    var autoConnect by remember { mutableStateOf(true) }
    var screenOff   by remember { mutableStateOf(true) }
    var keepAwake   by remember { mutableStateOf(true) }
    var units       by remember { mutableStateOf("Kilometres") }

    // Real voice setting, shared with RouteScreen via the VoiceManager singleton.
    val ctx = LocalContext.current
    val voiceManager = remember { com.example.wayfindr.dash.nav.VoiceManager.get(ctx) }
    val voiceMode by voiceManager.mode.collectAsState()
    val voice = when (voiceMode) {
        com.example.wayfindr.dash.nav.VoiceMode.OFF   -> "Off"
        com.example.wayfindr.dash.nav.VoiceMode.CHIME -> "Chime"
        com.example.wayfindr.dash.nav.VoiceMode.FULL  -> "Full TTS"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(18.dp)
            .padding(bottom = 24.dp),
    ) {
        ScreenHeader(title = "Settings", onBack = onBack,
            hint = "Connection, screen-off streaming, voice guidance, units, and media/call permissions.")

        SectionLabel("Connection")
        WayfindrCard(modifier = Modifier.fillMaxWidth(), padding = 6.dp) {
            SettingRow(WayfindrIcons.Bt, "Console Dash",
                sub = when (conn) { ConnectionState.Connected -> "Connected"; ConnectionState.Searching -> "Connecting…"; ConnectionState.Offline -> "Not connected" },
                control = { WayfindrChip(if (conn == ConnectionState.Connected) "Linked" else "Off", if (conn == ConnectionState.Connected) ChipTone.Gold else ChipTone.Off, dot = true) })
            WayfindrDivider(Modifier.padding(horizontal = 6.dp))
            SettingRow(WayfindrIcons.Sync, "Auto-connect on start", "Link when the bike is near",
                control = { WayfindrToggle(autoConnect) { autoConnect = it } })
            WayfindrDivider(Modifier.padding(horizontal = 6.dp))
            val streamCfg = remember { DashConfig.get(ctx) }
            val hqVideo by streamCfg.highQualityVideo.collectAsState()
            SettingRow(WayfindrIcons.Zap, "Higher video quality",
                if (hqVideo) "400 kbps · less blocky, more dash load" else "Balanced · 200 kbps",
                control = { WayfindrToggle(hqVideo) { streamCfg.setHighQualityVideo(it) } }, last = true)
        }

        SectionLabel("During a ride")
        val dashConfig = remember { DashConfig.get(ctx) }
        val mapDark by dashConfig.mapDarkMode.collectAsState()
        val mapDarkAuto by dashConfig.mapDarkAuto.collectAsState()
        val offlineMapFile by dashConfig.offlineMapFile.collectAsState()
        val settingsScope = rememberCoroutineScope()
        var importing by remember { mutableStateOf(false) }
        var mapStatus by remember { mutableStateOf("") }
        var mapStatusKey by remember { mutableStateOf(0) }
        LaunchedEffect(offlineMapFile, mapStatusKey) {
            mapStatus = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val f = com.example.wayfindr.dash.map.OfflineMapFiles.resolve(ctx, offlineMapFile)
                when (val r = f?.let { com.example.wayfindr.dash.map.MbTiles.open(it) }) {
                    null -> "No .mbtiles yet: import one below"
                    is com.example.wayfindr.dash.map.MbTiles.Companion.Result.Error -> r.message
                    is com.example.wayfindr.dash.map.MbTiles.Companion.Result.Ok -> {
                        val mb = r.tiles; mb.close()
                        "${mb.file.name} · zoom ${mb.minZoom}–${mb.maxZoom} · ${mb.file.length() / (1 shl 20)} MB"
                    }
                }
            }
        }
        val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) settingsScope.launch {
                importing = true
                val f = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    com.example.wayfindr.dash.map.OfflineMapFiles.import(ctx, uri)
                }
                importing = false
                if (f != null) { dashConfig.setOfflineMapFile(f.name); dashConfig.setMapSource(com.example.wayfindr.dash.MapSource.OFFLINE) }
                else mapStatus = "Import failed"
                mapStatusKey++
            }
        }
        val invertZoom by dashConfig.invertZoom.collectAsState()
        val sharperMap by dashConfig.sharperMap.collectAsState()
        val pickerDoubleTap by dashConfig.pickerDoubleTap.collectAsState()
        val showJoystickTrail by dashConfig.showJoystickTrail.collectAsState()
        val showPhoneBattery by dashConfig.showPhoneBattery.collectAsState()
        WayfindrCard(modifier = Modifier.fillMaxWidth(), padding = 6.dp) {
            SettingRow(WayfindrIcons.Power, "Turn phone screen off", "Map keeps streaming to the dash",
                control = { WayfindrToggle(screenOff) { screenOff = it } })
            WayfindrDivider(Modifier.padding(horizontal = 6.dp))
            SettingRow(WayfindrIcons.Dash, "Keep dash awake", "Prevent Console sleep",
                control = { WayfindrToggle(keepAwake) { keepAwake = it } })
            WayfindrDivider(Modifier.padding(horizontal = 6.dp))
            SettingRow(WayfindrIcons.Moon, "Auto dark map",
                if (mapDarkAuto) "Dark from 7 pm to 5 am (phone clock)" else "Off: use the manual switch below",
                control = { WayfindrToggle(mapDarkAuto) { dashConfig.setMapDarkAuto(it) } })
            WayfindrDivider(Modifier.padding(horizontal = 6.dp))
            // Shows the current state while auto; flipping it switches to manual.
            val darkNow = com.example.wayfindr.dash.DarkSchedule.effective(mapDarkAuto, mapDark)
            SettingRow(WayfindrIcons.Moon, "Dark dash map",
                if (mapDarkAuto) "Following the clock · tap to set it by hand" else "Manual · night look on the same cached tiles",
                control = { WayfindrToggle(darkNow) { dashConfig.setMapDarkMode(it); dashConfig.setMapDarkAuto(false) } })
            WayfindrDivider(Modifier.padding(horizontal = 6.dp))
            // Routing and Map source are picked on the Route screen, above Voice guidance.
            SettingRow(WayfindrIcons.Plus, "Import offline map",
                if (importing) "Importing…" else "$mapStatus · or copy a raster .mbtiles to Android/data/com.wayfindr.app/files/maps",
                control = {}, onClick = { if (!importing) importLauncher.launch(arrayOf("*/*")) })
            WayfindrDivider(Modifier.padding(horizontal = 6.dp))
            SettingRow(WayfindrIcons.Sync, "Invert joystick zoom",
                if (invertZoom) "Right zooms in · left zooms out" else "Left zooms in · right zooms out",
                control = { WayfindrToggle(invertZoom) { dashConfig.setInvertZoom(it) } })
            WayfindrDivider(Modifier.padding(horizontal = 6.dp))
            SettingRow(WayfindrIcons.Pin, "Double-tap left/right opens destination list",
                if (pickerDoubleTap) "On: double-tap left/right, or double-press down, opens the saved list"
                else "Off: double-press down (within 3 s) opens the saved list",
                control = { WayfindrToggle(pickerDoubleTap) { dashConfig.setPickerDoubleTap(it) } })
            WayfindrDivider(Modifier.padding(horizontal = 6.dp))
            SettingRow(WayfindrIcons.Target, "Show joystick presses",
                "Bottom-centre on the dash: last 4 presses as D / R / L, each shown for 2 s",
                control = { WayfindrToggle(showJoystickTrail) { dashConfig.setShowJoystickTrail(it) } })
            WayfindrDivider(Modifier.padding(horizontal = 6.dp))
            SettingRow(WayfindrIcons.Target, "Show phone battery on dash",
                "Battery % above the ETA box, with a bolt while charging",
                control = { WayfindrToggle(showPhoneBattery) { dashConfig.setShowPhoneBattery(it) } })
            WayfindrDivider(Modifier.padding(horizontal = 6.dp))
            SettingRow(WayfindrIcons.Target, "Sharper map",
                if (sharperMap) "2× render, high-res tiles · more memory" else "Standard tiles",
                control = { WayfindrToggle(sharperMap) { dashConfig.setSharperMap(it) } }, last = true)
        }

        SectionLabel("Media & calls on dash")
        // Re-check the grant on ON_RESUME so the chip flips to "On" the moment the user comes back
        // from the system notification-access screen (the Settings value isn't observable on its own).
        val lifecycleOwner = LocalLifecycleOwner.current
        fun answerGranted() = androidx.core.content.ContextCompat.checkSelfPermission(
            ctx, android.Manifest.permission.ANSWER_PHONE_CALLS,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        var mediaGranted by remember { mutableStateOf(com.example.wayfindr.media.MediaInfoProvider.isAccessGranted(ctx)) }
        var callAnswerGranted by remember { mutableStateOf(answerGranted()) }
        DisposableEffect(lifecycleOwner) {
            val obs = LifecycleEventObserver { _, e ->
                if (e == Lifecycle.Event.ON_RESUME) {
                    mediaGranted = com.example.wayfindr.media.MediaInfoProvider.isAccessGranted(ctx)
                    callAnswerGranted = answerGranted()
                }
            }
            lifecycleOwner.lifecycle.addObserver(obs)
            onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
        }
        val callPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            callAnswerGranted = it
        }
        WayfindrCard(modifier = Modifier.fillMaxWidth(), padding = 6.dp) {
            SettingRow(
                WayfindrIcons.Zap,
                "Now playing & calls on dash",
                if (mediaGranted) "Enabled · song info + caller shown while riding"
                else "Tap to allow notification access",
                control = {
                    WayfindrChip(
                        if (mediaGranted) "On" else "Enable",
                        if (mediaGranted) ChipTone.Gold else ChipTone.Off, dot = true,
                    )
                },
                onClick = if (mediaGranted) null else {
                    { runCatching { ctx.startActivity(com.example.wayfindr.media.MediaInfoProvider.accessSettingsIntent()) } }
                },
            )
            WayfindrDivider(Modifier.padding(horizontal = 6.dp))
            SettingRow(
                WayfindrIcons.Bt,
                "Answer calls from joystick",
                if (callAnswerGranted) "Enabled · UP answers, DOWN rejects"
                else "Tap to allow answering/rejecting calls",
                control = {
                    WayfindrChip(
                        if (callAnswerGranted) "On" else "Enable",
                        if (callAnswerGranted) ChipTone.Gold else ChipTone.Off, dot = true,
                    )
                },
                last = true,
                onClick = if (callAnswerGranted) null else {
                    { callPermLauncher.launch(android.Manifest.permission.ANSWER_PHONE_CALLS) }
                },
            )
        }

        SectionLabel("Voice & guidance")
        WayfindrCard(modifier = Modifier.fillMaxWidth(), padding = 14.dp) {
            WayfindrSegmented(listOf("Off", "Chime", "Full TTS"), voice, {
                voiceManager.setMode(when (it) {
                    "Off"      -> com.example.wayfindr.dash.nav.VoiceMode.OFF
                    "Full TTS" -> com.example.wayfindr.dash.nav.VoiceMode.FULL
                    else       -> com.example.wayfindr.dash.nav.VoiceMode.CHIME
                })
            }, Modifier.fillMaxWidth())
        }

        SectionLabel("Units")
        WayfindrCard(modifier = Modifier.fillMaxWidth(), padding = 14.dp) {
            WayfindrSegmented(listOf("Kilometres", "Miles"), units, { units = it }, Modifier.fillMaxWidth())
        }

        Spacer(Modifier.height(22.dp))

        val appVersion = remember { com.example.wayfindr.data.UpdateChecker.currentVersionName(ctx) }
        Text(
            "Wayfindr v$appVersion",
            color = TextDis, fontSize = 11.sp, fontFamily = GeistMonoFamily,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 10.dp),
        )
    }
}

@Composable
private fun SectionLabel(label: String) {
    Eyebrow(label, Modifier.padding(top = 22.dp, bottom = 9.dp, start = 4.dp))
}

@Composable
private fun SettingRow(
    icon: ImageVector,
    title: String,
    sub: String? = null,
    control: @Composable () -> Unit,
    last: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 6.dp, vertical = 13.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(38.dp).clip(RoundedCornerShape(11.dp)).background(Surf2).border(1.dp, Line, RoundedCornerShape(11.dp)),
        ) {
            Icon(icon, contentDescription = null, tint = TextMid, modifier = Modifier.size(19.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = TextHi, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, fontFamily = GeistFamily)
            if (sub != null) Text(sub, color = TextLo, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
        }
        control()
    }
}
