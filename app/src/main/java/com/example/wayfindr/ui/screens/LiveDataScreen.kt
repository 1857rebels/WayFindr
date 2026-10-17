package com.example.wayfindr.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.wayfindr.dash.DashSession.DashField
import com.example.wayfindr.ui.components.*
import com.example.wayfindr.ui.theme.*
import com.example.wayfindr.viewmodel.DashViewModel
import kotlinx.coroutines.delay

/**
 * Live view of every field the dash sends (types 0C/0F/03/09/0B…, not the 01 01 heartbeat), so
 * the rider can compare against the cluster on the go and work out which field is speed, trip, etc.
 * Labels marked "?" are guesses from the 2026-10-16 logs, not confirmed.
 */
@Composable
fun LiveDataScreen(vm: DashViewModel) {
    val fields by vm.dashFields.collectAsState()
    val usage by com.example.wayfindr.data.ApiUsage.thisMonth.collectAsState()
    // Tick so the "changed Ns ago" highlight fades even when no packet arrives.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(500) } }

    val rows = fields.values.filter { !(it.type == 0x01 && it.sub == 0x01) }
        .sortedWith(compareBy({ it.type }, { it.sub }))

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp).padding(bottom = 24.dp),
    ) {
        ScreenHeader(eyebrow = "Dash", title = "Live data",
            hint = "Everything the dash sends, updated live. Gold = changed in the last 3 s. " +
                "Note what the cluster shows and compare.")
        ApiUsageCard(usage)
        Spacer(Modifier.height(12.dp))
        if (rows.isEmpty()) {
            WayfindrCard(Modifier.fillMaxWidth(), padding = 18.dp) {
                Text("Nothing yet — connect to the dash.", color = TextMid, fontSize = 14.sp)
            }
            return@Column
        }
        rows.forEach { f ->
            FieldRow(f, now)
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun ApiUsageCard(usage: Map<String, Int>) {
    WayfindrCard(Modifier.fillMaxWidth(), padding = 14.dp) {
        Text("API USAGE · ${com.example.wayfindr.data.ApiUsage.monthLabel().uppercase()}",
            color = TextMid, fontSize = 12.sp)
        Spacer(Modifier.height(6.dp))
        if (usage.isEmpty()) Text("No requests yet this month.", color = TextLo, fontSize = 13.sp)
        usage.entries.sortedByDescending { it.value }.forEach { (name, n) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(name, color = TextHi, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Text("$n", color = Gold, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace)
            }
        }
        Text("Counted on this phone from 16 Oct 2026. Only Routes API calls are billed; the " +
            "40-per-destination cap applies to automatic ones.", color = TextLo, fontSize = 11.sp)
    }
}

@Composable
private fun FieldRow(f: DashField, now: Long) {
    val fresh = now - f.changedMs < 3_000
    val bytes = hexBytes(f.value)
    WayfindrCard(Modifier.fillMaxWidth(), padding = 12.dp) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text("%02X %02X".format(f.type, f.sub) + (label(f.type, f.sub)?.let { "  ·  $it" } ?: ""),
                    color = TextMid, fontSize = 12.sp)
                Text(decode(f.type, f.sub, bytes) ?: f.value.substringAfter('=').take(80),
                    color = if (fresh) Gold else TextHi, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace)
                if (bytes.size in 1..8)
                    Text("hex ${f.value.substringAfter('=')}  ·  dec ${numeric(bytes)}",
                        color = TextLo, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
            Text("${f.changes}×", color = TextLo, fontSize = 11.sp)
        }
    }
}

/** Guessed meanings (unconfirmed — see CLAUDE.md "Dash → app"). */
private fun label(type: Int, sub: Int): String? = when ((type shl 8) or sub) {
    0x0B02 -> "module firmware versions"
    0x0C07 -> "battery voltage?"
    0x0C25 -> "air temp °C?"
    0x0C26 -> "light sensor?"
    0x0F04, 0x0F0B -> "encrypted telemetry"
    else -> null
}

private fun decode(type: Int, sub: Int, b: List<Int>): String? = when ((type shl 8) or sub) {
    0x0B02 -> String(b.drop(2).takeWhile { it != 0 }.map { it.toByte() }.toByteArray(), Charsets.US_ASCII)
        .chunked(8).joinToString("  ")
    0x0C07 -> if (b.size == 2) "%.1f V".format(((b[0] shl 8) or b[1]) / 10f) else null
    0x0C25 -> b.singleOrNull()?.let { "$it °C" }
    else -> null
}

private fun numeric(b: List<Int>): String = when (b.size) {
    1 -> b[0].toString()
    else -> b.fold(0L) { acc, v -> (acc shl 8) or v.toLong() }.toString()
}

private fun hexBytes(value: String): List<Int> =
    value.substringAfter('=').substringBefore(" (").trim().split(' ')
        .mapNotNull { it.takeIf { s -> s.length == 2 }?.toIntOrNull(16) }
