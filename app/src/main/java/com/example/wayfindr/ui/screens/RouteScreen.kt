package com.example.wayfindr.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.wayfindr.ui.WayfindrIcons
import com.example.wayfindr.ui.components.*
import com.example.wayfindr.ui.theme.*
import com.example.wayfindr.viewmodel.RouteViewModel
import com.example.wayfindr.data.SharedLocation

@Composable
fun RouteScreen(
    onBack: () -> Unit,
    onSentToDash: (List<SharedLocation>) -> Unit,
    routeViewModel: RouteViewModel = viewModel(),
) {
    val routeState by routeViewModel.state.collectAsState()
    val dest       = routeState.destination
    val destName   = dest?.name?.ifBlank { "Shared location" } ?: "Shared location"
    val destSub    = when {
        dest?.lat != null && dest.lng != null ->
            "%.5f, %.5f".format(dest.lat, dest.lng)
        dest?.url != null -> "Maps link"
        else              -> ""
    }

    val savedList by routeViewModel.saved.collectAsState()
    val ctx = LocalContext.current
    val voiceManager = remember { com.example.wayfindr.dash.nav.VoiceManager.get(ctx) }
    val voiceMode by voiceManager.mode.collectAsState()
    val voice = when (voiceMode) {
        com.example.wayfindr.dash.nav.VoiceMode.OFF   -> "Off"
        com.example.wayfindr.dash.nav.VoiceMode.CHIME -> "Chime only"
        com.example.wayfindr.dash.nav.VoiceMode.FULL  -> "Full TTS"
    }
    var sent by remember { mutableStateOf(false) }
    var showSave by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<com.example.wayfindr.data.SavedLocation?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    val searchSource by remember { com.example.wayfindr.dash.DashConfig.get(ctx) }.routingSource.collectAsState()

    // Sync search query with destination name when it changes (except when resolving)
    LaunchedEffect(destName) {
        if (!routeState.isResolving && destName != "Shared location" && destName != "Dropped pin") {
            searchQuery = destName
        }
    }

    // Nav needs GPS (+ NEARBY_WIFI_DEVICES on 13+ to reach the dash). The Dash screen
    // requests these, but a rider can go straight Share → Route → Start navigation
    // without ever opening Dash — so request them here too. Without this the ride
    // starts with no GPS (and previously crashed the foreground service on 14+).
    val navPermissions = remember {
        buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }.toTypedArray()
    }
    fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    // Whether or not the grant succeeds, proceed (the service start is now defensive and
    // the rider can still preview); denying just means no live GPS until they grant it.
    val navPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { sent = true }

    val locationPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { routeViewModel.startLocationUpdates() }

    LaunchedEffect(Unit) {
        if (hasLocationPermission()) {
            routeViewModel.startLocationUpdates()
        } else {
            locationPermLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    LaunchedEffect(sent) {
        if (sent) {
            kotlinx.coroutines.delay(650)
            onSentToDash(routeState.waypoints)
        }
    }

    Column(Modifier.fillMaxSize()) {
        // Map preview (top 46%)
        Box(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.46f)
                .background(MapBase),
        ) {
            // Real Google Maps preview — destination pin + route line.
            WayfindrMap(
                riderLat = routeState.currentLocation?.first,
                riderLng = routeState.currentLocation?.second,
                riderBearing = routeState.currentBearing,
                recenterKey = routeState.locationRefreshKey,
                followRider = false,
                centerOnRiderInitially = true,
                dest = dest?.let { d -> if (d.lat != null && d.lng != null) d.lat to d.lng else null },
                destinations = routeState.waypoints.mapNotNull { stop ->
                    if (stop.lat != null && stop.lng != null) stop.lat to stop.lng else null
                },
                routePoints = routeState.route?.geometry.orEmpty(),
                trafficIntervals = routeState.route?.traffic.orEmpty(),
                hasLocationPermission = true,
                fitRoute = routeState.route != null && !routeState.routing,
                onMapClick = { routeViewModel.selectLocation(it.latitude, it.longitude) },
                modifier = Modifier.fillMaxSize(),
            )

            // Top bar overlay
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(listOf(Color(0xD9080C0C), Color.Transparent))
                    )
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            ) {
                WayfindrIconBtn(
                    WayfindrIcons.ChevronLeft,
                    onClick = onBack,
                    size = 40.dp,
                    modifier = Modifier.background(Color(0xB30D0F11), IconBtnShape),
                )
                Spacer(Modifier.width(12.dp))
                WayfindrChip(
                    if (routeState.isResolving) "Resolving link…" else "Shared from Google Maps",
                    tone = if (routeState.isResolving) ChipTone.Gold else ChipTone.Neutral,
                    icon = WayfindrIcons.Share,
                    modifier = Modifier.background(Color(0xB30D0F11), CircleShape),
                )
                WayfindrIconBtn(
                    WayfindrIcons.Recenter,
                    onClick = routeViewModel::refreshCurrentLocation,
                    size = 40.dp,
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .background(Color(0xB30D0F11), IconBtnShape),
                )
            }
        }

        // Detail sheet (overlapping bottom portion)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                .background(Bg1)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 8.dp)
                .padding(bottom = 20.dp),
        ) {
            // Drag handle
            Box(
                Modifier
                    .width(40.dp).height(4.dp)
                    .clip(CircleShape).background(Line3)
                    .align(Alignment.CenterHorizontally)
                    .padding(bottom = 16.dp)
            )

            Spacer(Modifier.height(12.dp))

            // Search Bar
            WayfindrCard(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp), padding = 4.dp) {
                androidx.compose.material3.TextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = {
                        Text((if (routeState.waypoints.isEmpty()) "Search" else "Add stop") +
                            " · " + com.example.wayfindr.dash.nav.PlaceSearch.label(searchSource), color = TextLo,
                            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = { Icon(WayfindrIcons.Search, null, tint = Gold, modifier = Modifier.size(20.dp)) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            Icon(
                                WayfindrIcons.Cross, "clear",
                                tint = TextLo,
                                modifier = Modifier.size(18.dp).clickable {
                                    searchQuery = ""
                                }
                            )
                        }
                    },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        imeAction = androidx.compose.ui.text.input.ImeAction.Search
                    ),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                        onSearch = { routeViewModel.search(searchQuery) }
                    ),
                    colors = androidx.compose.material3.TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent
                    )
                )
            }
            routeState.searchNote?.let { note ->
                Text(note, color = TextLo, fontSize = 12.sp, modifier = Modifier.padding(start = 6.dp, bottom = 12.dp))
            }

            // No destination yet → tell the rider exactly how to start one.
            if (routeState.waypoints.isNotEmpty()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                ) {
                    Eyebrow("Route stops · ${routeState.waypoints.size}")
                    WayfindrBtn(
                        label = "Clear",
                        onClick = routeViewModel::clearDestinations,
                        icon = WayfindrIcons.X,
                        variant = BtnVariant.Ghost,
                        size = BtnSize.Sm,
                    )
                }
                WayfindrCard(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp), padding = 6.dp) {
                    routeState.waypoints.forEachIndexed { index, stop ->
                        if (index > 0) WayfindrDivider(Modifier.padding(horizontal = 4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 9.dp)) {
                            Text(if (index == routeState.waypoints.lastIndex) "Final" else "${index + 1}", color = Gold, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(38.dp))
                            Text(stop.name, color = TextHi, fontSize = 14.sp, fontFamily = GeistFamily, modifier = Modifier.weight(1f), maxLines = 1)
                            if (routeState.waypoints.size > 1) {
                                Icon(WayfindrIcons.MoveUp, "Move stop earlier", tint = if (index > 0) TextMid else TextLo,
                                    modifier = Modifier.size(19.dp).clickable(enabled = index > 0) { routeViewModel.moveWaypoint(index, index - 1) })
                                Icon(WayfindrIcons.MoveDown, "Move stop later", tint = if (index < routeState.waypoints.lastIndex) TextMid else TextLo,
                                    modifier = Modifier.size(19.dp).clickable(enabled = index < routeState.waypoints.lastIndex) { routeViewModel.moveWaypoint(index, index + 1) })
                                Icon(WayfindrIcons.Cross, "Remove stop", tint = TextLo,
                                    modifier = Modifier.padding(start = 4.dp).size(18.dp).clickable { routeViewModel.removeWaypoint(index) })
                            }
                        }
                    }
                }
            }

            if ((dest?.lat == null || dest.lng == null) && !routeState.isResolving) {
                WayfindrCard(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp), padding = 16.dp) {
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(WayfindrIcons.Share, contentDescription = null, tint = Gold, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("Start a route from Google Maps", color = TextHi, fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold, fontFamily = GeistFamily)
                            Text(
                                "Open Google Maps → pick a place → tap Share → choose Wayfindr. " +
                                    "The destination lands here, then tap Start navigation to send it to the dash.",
                                color = TextMid, fontSize = 13.sp, fontFamily = GeistFamily,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
            }

            Eyebrow(if (routeState.waypoints.size > 1) "Final destination" else "Destination", Modifier.padding(bottom = 6.dp))

            Row(verticalAlignment = Alignment.Top) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(GoldTint),
                ) {
                    Icon(WayfindrIcons.LocationPin, contentDescription = null, tint = Gold, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    if (routeState.isResolving) {
                        Text("Resolving…", color = TextLo, fontSize = 19.sp, fontWeight = FontWeight.Bold, fontFamily = GeistFamily, letterSpacing = (-0.38).sp)
                    } else {
                        Text(destName, color = TextHi, fontSize = 19.sp, fontWeight = FontWeight.Bold, fontFamily = GeistFamily, letterSpacing = (-0.38).sp)
                    }
                    if (destSub.isNotBlank()) {
                        Text(destSub, color = TextMid, fontSize = 13.sp, fontFamily = GeistMonoFamily, modifier = Modifier.padding(top = 2.dp))
                    }
                }
            }

            Spacer(Modifier.height(18.dp))

            // Route stats (real values once routing completes)
            val routing = routeState.routing
            val statsList = listOf(
                Triple(if (routing) "…" else (routeState.distanceText ?: "—"), "", "Distance"),
                Triple(if (routing) "…" else (routeState.durationText ?: "—"), "", "Duration"),
                Triple(if (routing) "…" else (routeState.etaText ?: "—"), "", "Arrive"),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                statsList.forEach { (v, u, k) ->
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Surf1)
                            .border(1.dp, Line, RoundedCornerShape(14.dp))
                            .padding(13.dp),
                    ) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(v, color = TextHi, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, fontFamily = GeistMonoFamily)
                            Spacer(Modifier.width(4.dp))
                            Text(u, color = TextLo, fontSize = 11.sp, fontFamily = GeistMonoFamily, modifier = Modifier.padding(bottom = 2.dp))
                        }
                        Eyebrow(k, Modifier.padding(top = 4.dp))
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            // ── Routing + map source (moved here from Settings) ──
            val dashConfig = remember { com.example.wayfindr.dash.DashConfig.get(ctx) }
            val routingSource by dashConfig.routingSource.collectAsState()
            val mapSource by dashConfig.mapSource.collectAsState()
            val offlineFile by dashConfig.offlineMapFile.collectAsState()
            val brouterInstalled = remember { com.example.wayfindr.dash.nav.OfflineRouter.isInstalled(ctx) }
            val offlineName = remember(offlineFile, mapSource) {
                com.example.wayfindr.dash.map.OfflineMapFiles.resolve(ctx, offlineFile)?.name
            }
            ChoiceHeader("Routing", when (routingSource) {
                com.example.wayfindr.dash.RoutingSource.GOOGLE -> "Two-wheeler, live traffic"
                com.example.wayfindr.dash.RoutingSource.OSM -> "Free server · needs internet"
                com.example.wayfindr.dash.RoutingSource.BROUTER ->
                    if (brouterInstalled) "On the phone · no internet" else "BRouter app not installed"
            })
            WayfindrSegmented(
                options = com.example.wayfindr.dash.RoutingSource.available.map { it.label },
                selected = routingSource.label,
                onSelect = { label ->
                    val src = com.example.wayfindr.dash.RoutingSource.values().first { it.label == label }
                    if (src != routingSource) { dashConfig.setRoutingSource(src); routeViewModel.recomputeRoute() }
                },
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            )
            ChoiceHeader("Map source", when (mapSource) {
                com.example.wayfindr.dash.MapSource.GOOGLE -> "Google tiles, route cached"
                com.example.wayfindr.dash.MapSource.OSM -> "OpenStreetMap, loaded as you ride"
                com.example.wayfindr.dash.MapSource.OFFLINE -> offlineName ?: "No .mbtiles file (import in Settings)"
            })
            WayfindrSegmented(
                options = com.example.wayfindr.dash.MapSource.available.map { it.label },
                selected = mapSource.label,
                onSelect = { label ->
                    com.example.wayfindr.dash.MapSource.values().first { it.label == label }.let(dashConfig::setMapSource)
                },
                modifier = Modifier.fillMaxWidth().padding(bottom = 22.dp),
            )

            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(bottom = 9.dp),
            ) {
                Eyebrow("Voice guidance")
                Icon(
                    if (voice == "Off") WayfindrIcons.SpeakerOff else WayfindrIcons.Speaker,
                    contentDescription = null,
                    tint = if (voice == "Off") TextLo else Gold,
                    modifier = Modifier.size(18.dp),
                )
            }

            WayfindrSegmented(
                options = listOf("Off", "Chime only", "Full TTS"),
                selected = voice,
                onSelect = {
                    voiceManager.setMode(when (it) {
                        "Off"  -> com.example.wayfindr.dash.nav.VoiceMode.OFF
                        "Full TTS" -> com.example.wayfindr.dash.nav.VoiceMode.FULL
                        else   -> com.example.wayfindr.dash.nav.VoiceMode.CHIME
                    })
                },
                modifier = Modifier.fillMaxWidth().padding(bottom = 22.dp),
            )

            val canStart = dest?.lat != null && dest.lng != null && !routeState.isResolving
            WayfindrBtn(
                label = when {
                    sent                   -> "Starting navigation…"
                    routeState.isResolving -> "Resolving destination…"
                    routeState.routing     -> "Finding route…"
                    else                   -> "Start navigation"
                },
                onClick = { if (hasLocationPermission()) sent = true else navPermLauncher.launch(navPermissions) },
                icon = if (sent) WayfindrIcons.Check else WayfindrIcons.Navi,
                variant = if (sent) BtnVariant.Secondary else BtnVariant.Primary,
                size = BtnSize.Lg,
                enabled = !sent && canStart,
                modifier = Modifier.fillMaxWidth(),
            )

            if (canStart) {
                Spacer(Modifier.height(10.dp))
                WayfindrBtn(
                    label = "Save this destination",
                    onClick = { showSave = true },
                    icon = WayfindrIcons.Pin,
                    variant = BtnVariant.Ghost,
                    size = BtnSize.Md,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // ── Saved destinations ──
            if (savedList.isNotEmpty()) {
                Spacer(Modifier.height(22.dp))
                Eyebrow("Saved destinations", Modifier.padding(bottom = 6.dp, start = 4.dp))
                WayfindrCard(modifier = Modifier.fillMaxWidth(), padding = 6.dp) {
                    savedList.forEachIndexed { i, loc ->
                        if (i > 0) WayfindrDivider(Modifier.padding(horizontal = 4.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { routeViewModel.selectSaved(loc) }
                                .padding(horizontal = 6.dp, vertical = 12.dp),
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.size(40.dp).clip(RoundedCornerShape(11.dp)).background(GoldTint),
                            ) { Icon(WayfindrIcons.LocationPin, null, tint = Gold, modifier = Modifier.size(20.dp)) }
                            Spacer(Modifier.width(13.dp))
                            Column(Modifier.weight(1f)) {
                                Text(loc.name, color = TextHi, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, fontFamily = GeistFamily, maxLines = 1)
                                Text(
                                    if (loc.note.isNotBlank()) loc.note else "%.4f, %.4f".format(loc.lat, loc.lng),
                                    color = TextLo, fontSize = 12.sp, fontFamily = GeistMonoFamily,
                                    modifier = Modifier.padding(top = 2.dp), maxLines = 1,
                                )
                            }
                            Icon(
                                WayfindrIcons.Edit, "edit", tint = TextLo,
                                modifier = Modifier.size(18.dp).clickable { editing = loc },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showSave) SaveLocationDialog(
        defaultName = destName,
        onSave = { name, note -> routeViewModel.saveCurrentDestination(name, note); showSave = false },
        onDismiss = { showSave = false },
    )
    editing?.let { loc ->
        EditLocationDialog(
            loc = loc,
            onSave = { name, note -> routeViewModel.renameSaved(loc, name, note); editing = null },
            onDelete = { routeViewModel.deleteSaved(loc); editing = null },
            onDismiss = { editing = null },
        )
    }
}

@Composable
private fun SaveLocationDialog(defaultName: String, onSave: (String, String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(defaultName) }
    var note by remember { mutableStateOf("") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { androidx.compose.material3.TextButton(enabled = name.isNotBlank(), onClick = { onSave(name.trim(), note.trim()) }) { Text("Save", color = if (name.isNotBlank()) Gold else TextLo) } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel", color = TextMid) } },
        title = { Text("Save destination", color = TextHi) },
        text = {
            Column {
                androidx.compose.material3.OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                androidx.compose.material3.OutlinedTextField(note, { note = it }, label = { Text("Note (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            }
        },
        containerColor = Surf1,
    )
}

@Composable
private fun EditLocationDialog(loc: com.example.wayfindr.data.SavedLocation, onSave: (String, String) -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(loc.name) }
    var note by remember { mutableStateOf(loc.note) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { androidx.compose.material3.TextButton(enabled = name.isNotBlank(), onClick = { onSave(name.trim(), note.trim()) }) { Text("Save", color = if (name.isNotBlank()) Gold else TextLo) } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDelete) { Text("Delete", color = Alert) } },
        title = { Text("Edit destination", color = TextHi) },
        text = {
            Column {
                androidx.compose.material3.OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                androidx.compose.material3.OutlinedTextField(note, { note = it }, label = { Text("Note") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            }
        },
        containerColor = Surf1,
    )
}


/** Small header above a segmented picker: label on the left, what the choice means on the right. */
@Composable
private fun ChoiceHeader(label: String, detail: String) {
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(bottom = 9.dp),
    ) {
        Eyebrow(label)
        Text(detail, color = TextLo, fontSize = 11.5.sp, fontFamily = GeistFamily, maxLines = 1,
            modifier = Modifier.padding(start = 12.dp))
    }
}
