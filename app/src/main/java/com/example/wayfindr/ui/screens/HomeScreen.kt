package com.example.wayfindr.ui.screens

import androidx.compose.animation.core.*
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.wayfindr.BuildConfig
import com.example.wayfindr.util.BuildId
import com.example.wayfindr.ui.WayfindrIcons
import com.example.wayfindr.ui.components.*
import com.example.wayfindr.ui.theme.*
import com.example.wayfindr.viewmodel.ConnectionState
import com.example.wayfindr.viewmodel.RouteViewModel

@Composable
fun HomeScreen(
    conn: ConnectionState,
    onNavigate: (String) -> Unit,
    routeViewModel: RouteViewModel = viewModel(),
) {
    val saved by routeViewModel.saved.collectAsState()
    var sharing by remember { mutableStateOf<com.example.wayfindr.data.SavedLocation?>(null) }
    sharing?.let { loc -> RouteQrDialog(loc, onDismiss = { sharing = null }) }
    val status = when (conn) {
        ConnectionState.Connected -> Triple("Connected", "Streaming to Console Dash", Gold)
        ConnectionState.Searching -> Triple("Searching…", "Looking for Console Dash", Warn)
        ConnectionState.Offline   -> Triple("Offline", "Dash not detected", Offline)
    }
    val (statusLabel, statusSub, statusDot) = status

    // Pulse animation for connected dot
    val infiniteTransition = rememberInfiniteTransition(label = "dot-pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        1f, 0.35f,
        animationSpec = infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse",
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(18.dp)
            .padding(bottom = 24.dp),
    ) {
        ScreenHeader(
            wordmark = true,
            trailing = { WayfindrIconBtn(WayfindrIcons.Gear, onClick = { onNavigate("settings") }) },
        )

        // Prominent build identity (debug/test only) — the same sha12 checksum that pull-diag and
        // the "New test build" prompt use, so there's never any doubt which build is installed.
        if (BuildConfig.DEBUG) {
            val ctx = LocalContext.current
            val buildSha = remember { BuildId.sha12(ctx) }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 14.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Gold.copy(alpha = 0.10f))
                    .border(1.dp, Gold.copy(alpha = 0.40f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(
                    "TEST BUILD",
                    color = Gold, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    fontFamily = GeistMonoFamily, letterSpacing = 0.1.sp,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    buildSha,
                    color = TextHi, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    fontFamily = GeistMonoFamily,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    color = TextLo, fontSize = 11.sp, fontFamily = GeistMonoFamily,
                )
            }
        }

        // Connection hero card
        WayfindrCard(
            glow = conn == ConnectionState.Connected,
            padding = 20.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularDash(
                    size = 118.dp,
                    pan = Offset.Zero,
                    zoom = 1f,
                    compact = true,
                    live = conn == ConnectionState.Connected,
                )

                Spacer(Modifier.width(18.dp))

                Column(Modifier.weight(1f)) {
                    Eyebrow("Common Remington · Console")

                    Spacer(Modifier.height(7.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(9.dp).clip(CircleShape).background(
                                statusDot.copy(alpha = if (conn == ConnectionState.Connected) pulseAlpha else 1f)
                            )
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            statusLabel, color = TextHi, fontSize = 19.sp,
                            fontWeight = FontWeight.Bold, fontFamily = GeistFamily,
                            letterSpacing = (-0.38).sp,
                        )
                    }

                    Spacer(Modifier.height(4.dp))

                    Text(statusSub, color = TextMid, fontSize = 13.sp)

                    Spacer(Modifier.height(12.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        WayfindrChip("Mt.Fuji 450", ChipTone.Gold, icon = WayfindrIcons.Motor)
                    }
                }
            }
        }

        Spacer(Modifier.height(18.dp))

        WayfindrBtn(
            "Start navigation", onClick = { onNavigate("route") },
            icon = WayfindrIcons.Navi, variant = BtnVariant.Primary, size = BtnSize.Lg,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(10.dp))

        WayfindrBtn(
            "Open dash view", onClick = { onNavigate("dash") },
            icon = WayfindrIcons.Dash, variant = BtnVariant.Secondary, size = BtnSize.Md,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(18.dp))

        Eyebrow("Saved destinations", Modifier.padding(bottom = 6.dp, start = 4.dp))

        if (saved.isEmpty()) {
            WayfindrCard(modifier = Modifier.fillMaxWidth(), padding = 16.dp) {
                Text(
                    "No saved destinations yet. Share a place from Google Maps, then tap “Save this destination”.",
                    color = TextLo, fontSize = 13.sp,
                )
            }
        } else {
            WayfindrCard(modifier = Modifier.fillMaxWidth(), padding = 6.dp) {
                saved.forEachIndexed { i, loc ->
                    if (i > 0) WayfindrDivider(Modifier.padding(horizontal = 4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        WayfindrRow(
                            loc.name, icon = WayfindrIcons.LocationPin,
                            sub = loc.note.ifBlank { "%.4f, %.4f".format(loc.lat, loc.lng) },
                            trailingIcon = true,
                            onClick = { routeViewModel.selectSaved(loc); onNavigate("route") },
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            WayfindrIcons.Share, "Share ${loc.name}", tint = TextLo,
                            modifier = Modifier.clip(CircleShape)
                                .clickable { sharing = loc }
                                .padding(12.dp).size(18.dp),
                        )
                    }
                }
            }
        }
    }
}
