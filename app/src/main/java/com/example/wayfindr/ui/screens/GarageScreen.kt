package com.example.wayfindr.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.wayfindr.data.BikeIdentity
import com.example.wayfindr.data.FuelFillup
import com.example.wayfindr.data.MaintenanceItem
import com.example.wayfindr.data.ScheduledService
import com.example.wayfindr.data.ServiceRecord
import com.example.wayfindr.data.VehicleDocument
import com.example.wayfindr.ui.WayfindrIcons
import com.example.wayfindr.ui.components.*
import com.example.wayfindr.ui.theme.*
import com.example.wayfindr.viewmodel.GarageUi
import com.example.wayfindr.viewmodel.GarageViewModel
import com.example.wayfindr.viewmodel.MaintRow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val dfRow = SimpleDateFormat("MMM d", Locale.getDefault())
private val dfBar = SimpleDateFormat("d/M", Locale.getDefault())
private val dfLog = SimpleDateFormat("d MMM yyyy", Locale.getDefault())

/** "10,000 km / 12 mo" / "5,000 km" / "24 mo" — the interval in the units that apply. */
private fun intervalLabel(item: MaintenanceItem): String = buildString {
    if (item.intervalKm > 0) append("${"%,d".format(item.intervalKm)} km")
    if (item.intervalMonths > 0) { if (isNotEmpty()) append(" / "); append("${item.intervalMonths} mo") }
    if (isEmpty()) append("—")
}

/** Open a stored invoice (image/PDF) in an external viewer via FileProvider. */
private fun openInvoice(context: android.content.Context, path: String) {
    val file = File(path)
    if (!file.exists()) { Toast.makeText(context, "Invoice file missing", Toast.LENGTH_SHORT).show(); return }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val ext = path.substringAfterLast('.', "").lowercase()
    val mime = when (ext) { "pdf" -> "application/pdf"; "png" -> "image/png"; "webp" -> "image/webp"; else -> "image/jpeg" }
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mime)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }
        .onFailure { Toast.makeText(context, "No app to open this file", Toast.LENGTH_SHORT).show() }
}

private fun iconFor(key: String): ImageVector = when (key) {
    "chain"  -> WayfindrIcons.Chain
    "drop"   -> WayfindrIcons.Drop
    "gauge"  -> WayfindrIcons.Gauge
    "thermo" -> WayfindrIcons.Thermo
    "fuel"   -> WayfindrIcons.Fuel
    else     -> WayfindrIcons.Wrench
}

@Composable
fun GarageScreen(
    tab: String,
    onTabChange: (String) -> Unit,
    vm: GarageViewModel = viewModel(),
) {
    val ui by vm.ui.collectAsState()
    var showFuel by remember { mutableStateOf(false) }
    var showAddService by remember { mutableStateOf(false) }
    var showAddSchedule by remember { mutableStateOf(false) }
    var showOdo by remember { mutableStateOf(false) }
    // Non-null while the "log a service" dialog is open; carries any pre-selection.
    var loggingVisit by remember { mutableStateOf<VisitCtx?>(null) }
    // Glovebox dialogs.
    var addingDoc by remember { mutableStateOf(false) }
    var editingDoc by remember { mutableStateOf<com.example.wayfindr.data.VehicleDocument?>(null) }
    var editIdentity by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(18.dp)
            .padding(bottom = 24.dp),
    ) {
        ScreenHeader(
            eyebrow = "Mt.Fuji 450 · ${"%,.1f".format(ui.odometerKm)} km",
            title = "Garage",
            hint = "Track maintenance, fuel fill-ups and mileage, and service-due reminders. Tap Odometer to keep the distance current.",
            trailing = {
                WayfindrBtn("Odometer", onClick = { showOdo = true }, variant = BtnVariant.Ghost, size = BtnSize.Sm)
            },
        )

        WayfindrSegmented(
            options = listOf("Maintenance", "Fuel diary", "Glovebox"),
            selected = tab,
            onSelect = onTabChange,
            modifier = Modifier.fillMaxWidth().padding(bottom = 18.dp),
        )

        when (tab) {
            "Fuel diary" -> FuelTab(ui, onAdd = { showFuel = true }, onDelete = { vm.deleteFuel(it) })
            "Glovebox" -> GloveboxTab(
                ui,
                onAddDoc = { addingDoc = true },
                onEditDoc = { editingDoc = it },
                onDeleteDoc = { vm.deleteDocument(it) },
                onEditIdentity = { editIdentity = true },
            )
            else -> MaintenanceTab(
                ui,
                onLogVisit = { ctx -> loggingVisit = ctx },
                onAdd = { showAddService = true },
                onAddSchedule = { showAddSchedule = true },
                onDeleteSchedule = { vm.deleteScheduledService(it) },
            )
        }
    }

    if (showFuel) AddFuelDialog(
        defaultOdo = ui.odometerKm.toInt(),
        onAdd = { l, c, o, loc -> vm.addFuel(l, c, o, loc); showFuel = false },
        onDismiss = { showFuel = false },
    )
    if (showAddService) AddServiceDialog(
        onAdd = { n, ic, km, mo -> vm.addService(n, ic, km, mo); showAddService = false },
        onDismiss = { showAddService = false },
    )
    if (showAddSchedule) AddScheduleDialog(
        onAdd = { label, km, mo -> vm.addScheduledService(label, km, mo); showAddSchedule = false },
        onDismiss = { showAddSchedule = false },
    )
    loggingVisit?.let { ctx ->
        LogVisitDialog(
            items = ui.maint.map { it.item },
            ctx = ctx,
            defaultOdo = ui.odometerKm.toInt(),
            onConfirm = { title, kind, odo, cost, picked, invoice, note ->
                vm.logVisit(title, kind, ctx.scheduledKey, picked, odo, cost, invoice, note); loggingVisit = null
            },
            onDismiss = { loggingVisit = null },
        )
    }
    if (addingDoc) DocumentDialog(
        existing = null,
        onSave = { type, title, num, issue, expiry, file, note -> vm.saveDocument(null, type, title, num, issue, expiry, file, "", note); addingDoc = false },
        onDismiss = { addingDoc = false },
    )
    editingDoc?.let { d ->
        DocumentDialog(
            existing = d,
            onSave = { type, title, num, issue, expiry, file, note -> vm.saveDocument(d.sid, type, title, num, issue, expiry, file, d.filePath, note); editingDoc = null },
            onDismiss = { editingDoc = null },
        )
    }
    if (editIdentity) IdentityDialog(
        identity = ui.identity,
        onSave = { vm.saveIdentity(it); editIdentity = false },
        onDismiss = { editIdentity = false },
    )
    if (showOdo) OdometerDialog(ui.odometerKm, onSet = { vm.setOdometer(it); showOdo = false }, onDismiss = { showOdo = false })
}

/** Context for opening the log-a-service dialog (pre-selected items / scheduled link / defaults). */
private data class VisitCtx(
    val preItems: Set<String> = emptySet(),
    val scheduledKey: String = "",
    val title: String = "",
    val kind: String = "company",
)

@Composable
private fun MaintenanceTab(
    ui: GarageUi,
    onLogVisit: (VisitCtx) -> Unit,
    onAdd: () -> Unit,
    onAddSchedule: () -> Unit,
    onDeleteSchedule: (ScheduledService) -> Unit,
) {
    val toneColor = mapOf("ok" to Gold, "warn" to Warn, "alert" to Alert)
    val hero = ui.maint.maxByOrNull { it.urgency }
    val ctx = LocalContext.current

    if (hero != null) {
        val frac = hero.fraction.coerceIn(0.04f, 1f)
        val tone = toneColor[hero.tone] ?: Gold
        WayfindrCard(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            Row(horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
                Column {
                    Eyebrow("Most urgent")
                    Text(hero.item.name, color = TextHi, fontSize = 18.sp, fontWeight = FontWeight.Bold, fontFamily = GeistFamily, letterSpacing = (-0.36).sp, modifier = Modifier.padding(top = 5.dp))
                }
                WayfindrChip(
                    hero.dueLabel,
                    if (hero.tone == "alert") ChipTone.Alert else if (hero.tone == "warn") ChipTone.Warn else ChipTone.Gold,
                    dot = true,
                )
            }
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(hero.dueLabel.replaceFirstChar { it.uppercase() }, color = TextHi, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, fontFamily = GeistMonoFamily)
                Spacer(Modifier.width(8.dp))
                Text("· every ${intervalLabel(hero.item)}", color = TextLo, fontSize = 12.sp, fontFamily = GeistMonoFamily, modifier = Modifier.padding(bottom = 3.dp))
            }
            Spacer(Modifier.height(8.dp))
            Box(modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(Surf3)) {
                Box(Modifier.fillMaxWidth(frac).fillMaxHeight().clip(CircleShape).background(Brush.horizontalGradient(listOf(GoldDeep, tone))))
            }
            Spacer(Modifier.height(14.dp))
            WayfindrBtn("Mark done + add invoice", onClick = { onLogVisit(VisitCtx(preItems = setOf(hero.item.sid), title = hero.item.name)) }, icon = WayfindrIcons.Check, variant = BtnVariant.Primary, size = BtnSize.Sm, modifier = Modifier.fillMaxWidth())
        }
    }

    // ── Free / scheduled services (manufacturer milestones) ──
    if (ui.scheduled.isNotEmpty()) {
        Eyebrow("Service schedule", Modifier.padding(bottom = 8.dp, start = 4.dp))
        WayfindrCard(modifier = Modifier.fillMaxWidth(), padding = 6.dp) {
            ui.scheduled.forEachIndexed { i, row ->
                if (i > 0) WayfindrDivider(Modifier.padding(horizontal = 4.dp))
                val target = buildString {
                    if (row.svc.targetKm > 0) append("${"%,d".format(row.svc.targetKm)} km")
                    if (row.svc.targetMonths > 0) { if (isNotEmpty()) append(" / "); append("${row.svc.targetMonths} mo") }
                }
                val overdue = !row.availed && row.svc.targetKm in 1..ui.odometerKm.toInt()
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 11.dp)) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(34.dp).clip(CircleShape).background(if (row.availed) GoldTint else Surf2).border(1.dp, if (row.availed) Gold else Line, CircleShape)) {
                        Icon(if (row.availed) WayfindrIcons.Check else WayfindrIcons.Wrench, null, tint = if (row.availed) Gold else if (overdue) Alert else TextMid, modifier = Modifier.size(16.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(row.svc.label, color = TextHi, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, fontFamily = GeistFamily)
                            if (row.svc.free) { Spacer(Modifier.width(6.dp)); Text("FREE", color = Gold, fontSize = 9.sp, fontWeight = FontWeight.Bold, fontFamily = GeistMonoFamily) }
                        }
                        Text(
                            if (row.availed) "Done · ${dfLog.format(Date(row.availedDateMs))} · ${"%,d".format(row.availedOdoKm)} km"
                            else "At $target${if (overdue) " · due now" else ""}",
                            color = if (overdue) Alert else TextLo, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp), maxLines = 1,
                        )
                    }
                    if (row.availed) {
                        if (row.invoicePath.isNotBlank())
                            WayfindrBtn("Invoice", onClick = { openInvoice(ctx, row.invoicePath) }, variant = BtnVariant.Ghost, size = BtnSize.Sm)
                    } else {
                        WayfindrBtn("Mark", onClick = { onLogVisit(VisitCtx(scheduledKey = row.svc.sid, title = row.svc.label)) }, variant = BtnVariant.Ghost, size = BtnSize.Sm)
                        if (!row.svc.free) Icon(WayfindrIcons.Cross, "delete", tint = TextLo, modifier = Modifier.size(16.dp).clickable { onDeleteSchedule(row.svc) })
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        WayfindrBtn("Add scheduled service", onClick = onAddSchedule, icon = WayfindrIcons.Plus, variant = BtnVariant.Ghost, size = BtnSize.Sm)
        Spacer(Modifier.height(18.dp))
    }

    Eyebrow("Service intervals", Modifier.padding(bottom = 8.dp, start = 4.dp))

    WayfindrCard(modifier = Modifier.fillMaxWidth(), padding = 6.dp) {
        if (ui.maint.isEmpty()) {
            Text("No intervals yet — add one below.", color = TextLo, fontSize = 13.sp, modifier = Modifier.padding(14.dp))
        }
        ui.maint.forEachIndexed { i, row ->
            if (i > 0) WayfindrDivider(Modifier.padding(horizontal = 4.dp))
            val color = toneColor[row.tone] ?: Gold
            val fill = row.fraction.coerceIn(0.06f, 1f)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 12.dp)) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(40.dp).clip(RoundedCornerShape(11.dp)).background(Surf2).border(1.dp, Line, RoundedCornerShape(11.dp))) {
                    Icon(iconFor(row.item.iconKey), null, tint = color, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(13.dp))
                Column(Modifier.weight(1f)) {
                    Text(row.item.name, color = TextHi, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, fontFamily = GeistFamily)
                    Text("Every ${intervalLabel(row.item)}", color = TextLo, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(row.dueLabel, color = color, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, fontFamily = GeistMonoFamily)
                    Spacer(Modifier.height(6.dp))
                    Box(Modifier.width(56.dp).height(4.dp).clip(CircleShape).background(Surf3)) {
                        Box(Modifier.fillMaxWidth(fill).fillMaxHeight().clip(CircleShape).background(color))
                    }
                }
            }
        }
    }

    Spacer(Modifier.height(14.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        WayfindrBtn("Log a service", onClick = { onLogVisit(VisitCtx()) }, icon = WayfindrIcons.Check, variant = BtnVariant.Ghost, size = BtnSize.Md, modifier = Modifier.weight(1f))
        WayfindrBtn("Add interval", onClick = onAdd, icon = WayfindrIcons.Plus, variant = BtnVariant.Ghost, size = BtnSize.Md, modifier = Modifier.weight(1f))
    }

    // Service log — every visit, with its kind, cost and uploaded invoice.
    if (ui.services.isNotEmpty()) {
        Spacer(Modifier.height(20.dp))
        Eyebrow("Service log", Modifier.padding(bottom = 8.dp, start = 4.dp))
        WayfindrCard(modifier = Modifier.fillMaxWidth(), padding = 6.dp) {
            ui.services.take(12).forEachIndexed { i, rec ->
                if (i > 0) WayfindrDivider(Modifier.padding(horizontal = 4.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 11.dp)) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(rec.title.ifBlank { "Service" }, color = TextHi, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, fontFamily = GeistFamily)
                            Spacer(Modifier.width(6.dp))
                            Text(if (rec.kind == "diy") "DIY" else if (rec.kind == "company") "Company" else "Other",
                                color = TextLo, fontSize = 9.sp, fontWeight = FontWeight.Bold, fontFamily = GeistMonoFamily)
                        }
                        Text(
                            buildString {
                                append(dfLog.format(Date(rec.dateMs)))
                                append(" · ${"%,d".format(rec.odometerKm)} km")
                                if (rec.itemSids.size > 1) append(" · ${rec.itemSids.size} items")
                                if (rec.cost > 0) append(" · ₹${"%,.0f".format(rec.cost)}")
                            },
                            color = TextLo, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp), maxLines = 1,
                        )
                    }
                    if (rec.invoicePath.isNotBlank()) {
                        WayfindrBtn("Invoice", onClick = { openInvoice(ctx, rec.invoicePath) }, variant = BtnVariant.Ghost, size = BtnSize.Sm)
                    }
                }
            }
        }
    }
}

@Composable
private fun FuelTab(ui: GarageUi, onAdd: () -> Unit, onDelete: (FuelFillup) -> Unit) {
    WayfindrCard(modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
        Row(horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
            Column {
                Eyebrow("Avg. efficiency · 30 days")
                Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 7.dp)) {
                    Text(ui.avgKmpl30?.let { "%.1f".format(it) } ?: "—", color = Gold, fontSize = 38.sp, fontWeight = FontWeight.SemiBold, fontFamily = GeistMonoFamily, letterSpacing = (-0.76).sp)
                    Spacer(Modifier.width(6.dp))
                    Text("km / l", color = TextMid, fontSize = 14.sp, fontFamily = GeistMonoFamily, modifier = Modifier.padding(bottom = 5.dp))
                }
            }
        }
        val chart = ui.fuel.filter { it.kmpl != null }.take(6).reversed()
        if (chart.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            WayfindrBarChart(
                data = chart.map { BarEntry(dfBar.format(Date(it.fill.dateMs)), it.kmpl!!.toFloat()) },
                height = 108.dp,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 18.dp)) {
        listOf(
            Pair("₹${"%,.0f".format(ui.spent30)}", "Spent · 30 days"),
            Pair("%.1f l".format(ui.litres30), "Fuel · ${ui.fills30} fills"),
        ).forEach { (v, k) ->
            Column(modifier = Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(Surf1).border(1.dp, Line, RoundedCornerShape(14.dp)).padding(horizontal = 16.dp, vertical = 14.dp)) {
                Text(v, color = TextHi, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, fontFamily = GeistMonoFamily)
                Eyebrow(k, Modifier.padding(top = 4.dp))
            }
        }
    }

    Eyebrow("Fill-ups", Modifier.padding(bottom = 8.dp, start = 4.dp))
    WayfindrCard(modifier = Modifier.fillMaxWidth(), padding = 6.dp) {
        if (ui.fuel.isEmpty()) {
            Text("No fill-ups yet — add your first below.", color = TextLo, fontSize = 13.sp, modifier = Modifier.padding(14.dp))
        }
        ui.fuel.forEachIndexed { i, row ->
            if (i > 0) WayfindrDivider(Modifier.padding(horizontal = 4.dp))
            val f = row.fill
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { onDelete(f) }.padding(horizontal = 6.dp, vertical = 12.dp)) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(40.dp).clip(RoundedCornerShape(11.dp)).background(Surf2).border(1.dp, Line, RoundedCornerShape(11.dp))) {
                    Icon(WayfindrIcons.Fuel, null, tint = TextMid, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(13.dp))
                Column(Modifier.weight(1f)) {
                    Text("%.1f l · ₹%,.0f".format(f.litres, f.cost), color = TextHi, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, fontFamily = GeistFamily)
                    Text("${dfRow.format(Date(f.dateMs))} · ${"%,d".format(f.odometerKm)} km${if (f.location.isNotBlank()) " · ${f.location}" else ""}", color = TextLo, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp), maxLines = 1)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(row.kmpl?.let { "%.1f".format(it) } ?: "—", color = Gold, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, fontFamily = GeistMonoFamily)
                    Eyebrow("km/l", Modifier.padding(top = 2.dp))
                }
            }
        }
    }

    Spacer(Modifier.height(14.dp))
    WayfindrBtn("Add fill-up", onClick = onAdd, icon = WayfindrIcons.Plus, variant = BtnVariant.Ghost, size = BtnSize.Md, modifier = Modifier.fillMaxWidth())
}

// ── Dialogs ──────────────────────────────────────────────────────────────

@Composable
private fun AddFuelDialog(defaultOdo: Int, onAdd: (Double, Double, Int, String) -> Unit, onDismiss: () -> Unit) {
    var litres by remember { mutableStateOf("") }
    var cost by remember { mutableStateOf("") }
    var odo by remember { mutableStateOf(defaultOdo.toString()) }
    var loc by remember { mutableStateOf("") }
    val valid = litres.toDoubleOrNull() != null && cost.toDoubleOrNull() != null && odo.toIntOrNull() != null
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(enabled = valid, onClick = { onAdd(litres.toDouble(), cost.toDouble(), odo.toInt(), loc.trim()) }) { Text("Add", color = if (valid) Gold else TextLo) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = TextMid) } },
        title = { Text("Add fill-up", color = TextHi) },
        text = {
            Column {
                NumField(litres, { litres = it }, "Litres", true)
                NumField(cost, { cost = it }, "Cost (₹)", true)
                NumField(odo, { odo = it }, "Odometer (km)", false)
                OutlinedTextField(loc, { loc = it }, label = { Text("Location (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            }
        },
        containerColor = Surf1,
    )
}

@Composable
private fun AddServiceDialog(onAdd: (String, String, Int, Int) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var interval by remember { mutableStateOf("") }
    var months by remember { mutableStateOf("") }
    val icons = listOf("wrench", "chain", "drop", "gauge", "thermo", "fuel")
    var icon by remember { mutableStateOf("wrench") }
    val km = interval.toIntOrNull() ?: 0
    val mo = months.toIntOrNull() ?: 0
    val valid = name.isNotBlank() && (km > 0 || mo > 0)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(enabled = valid, onClick = { onAdd(name.trim(), icon, km, mo) }) { Text("Add", color = if (valid) Gold else TextLo) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = TextMid) } },
        title = { Text("Add interval", color = TextHi) },
        text = {
            Column {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                NumField(interval, { interval = it }, "Every (km) — optional", false)
                NumField(months, { months = it }, "Every (months) — optional", false)
                Text("Set km, months, or both (whichever comes first).", color = TextLo, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                Spacer(Modifier.height(10.dp))
                Eyebrow("Icon")
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    icons.forEach { key ->
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.size(38.dp).clip(RoundedCornerShape(10.dp))
                                .background(if (key == icon) GoldTint else Surf2)
                                .border(1.dp, if (key == icon) Gold else Line, RoundedCornerShape(10.dp))
                                .clickable { icon = key },
                        ) { Icon(iconFor(key), null, tint = if (key == icon) Gold else TextMid, modifier = Modifier.size(18.dp)) }
                    }
                }
            }
        },
        containerColor = Surf1,
    )
}

/**
 * Log a service VISIT: company or DIY, covering one or more items at once, with odometer, cost,
 * note and an optional invoice. Resets the countdown on every ticked item.
 */
@Composable
private fun LogVisitDialog(
    items: List<MaintenanceItem>,
    ctx: VisitCtx,
    defaultOdo: Int,
    onConfirm: (String, String, Int, Double, List<MaintenanceItem>, Uri?, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by remember { mutableStateOf(ctx.title.ifBlank { "General service" }) }
    var kind by remember { mutableStateOf(if (ctx.kind == "diy") "DIY" else "Company") }
    var odo by remember { mutableStateOf(defaultOdo.toString()) }
    var cost by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var invoice by remember { mutableStateOf<Uri?>(null) }
    val selected = remember { mutableStateListOf<String>().apply { addAll(ctx.preItems) } }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> if (uri != null) invoice = uri }
    val valid = odo.toIntOrNull() != null && title.isNotBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                onConfirm(title.trim(), if (kind == "DIY") "diy" else "company", odo.toInt(),
                    cost.toDoubleOrNull() ?: 0.0, items.filter { it.sid in selected }, invoice, note.trim())
            }) { Text("Save", color = if (valid) Gold else TextLo) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = TextMid) } },
        title = { Text("Log a service", color = TextHi) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                WayfindrSegmented(options = listOf("Company", "DIY"), selected = kind, onSelect = { kind = it }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(title, { title = it }, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                NumField(odo, { odo = it }, "Odometer (km)", false)
                NumField(cost, { cost = it }, "Cost ₹ — optional", true)
                Spacer(Modifier.height(10.dp))
                Eyebrow("What was done")
                items.forEach { item ->
                    val on = item.sid in selected
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                            .clickable { if (on) selected.remove(item.sid) else selected.add(item.sid) },
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(22.dp).clip(RoundedCornerShape(6.dp)).background(if (on) GoldTint else Surf2).border(1.dp, if (on) Gold else Line, RoundedCornerShape(6.dp))) {
                            if (on) Icon(WayfindrIcons.Check, null, tint = Gold, modifier = Modifier.size(15.dp))
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(item.name, color = TextHi, fontSize = 13.5.sp)
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(note, { note = it }, label = { Text("Note — optional") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                WayfindrBtn(
                    if (invoice != null) "Invoice attached ✓" else "Attach invoice / bill",
                    onClick = { picker.launch("*/*") },
                    icon = WayfindrIcons.Plus,
                    variant = if (invoice != null) BtnVariant.Primary else BtnVariant.Ghost,
                    size = BtnSize.Sm,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Photo or PDF — stored on this phone.", color = TextLo, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
            }
        },
        containerColor = Surf1,
    )
}

@Composable
private fun AddScheduleDialog(onAdd: (String, Int, Int) -> Unit, onDismiss: () -> Unit) {
    var label by remember { mutableStateOf("") }
    var km by remember { mutableStateOf("") }
    var months by remember { mutableStateOf("") }
    val k = km.toIntOrNull() ?: 0
    val m = months.toIntOrNull() ?: 0
    val valid = label.isNotBlank() && (k > 0 || m > 0)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(enabled = valid, onClick = { onAdd(label.trim(), k, m) }) { Text("Add", color = if (valid) Gold else TextLo) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = TextMid) } },
        title = { Text("Add scheduled service", color = TextHi) },
        text = {
            Column {
                OutlinedTextField(label, { label = it }, label = { Text("Name (e.g. 5th service)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                NumField(km, { km = it }, "At odometer (km) — optional", false)
                NumField(months, { months = it }, "At age (months) — optional", false)
            }
        },
        containerColor = Surf1,
    )
}

@Composable
private fun OdometerDialog(current: Double, onSet: (Double) -> Unit, onDismiss: () -> Unit) {
    // Show whole readings without a trailing ".0"; keep one decimal otherwise.
    val initial = if (current % 1.0 == 0.0) current.toLong().toString() else "%.1f".format(current)
    var odo by remember { mutableStateOf(initial) }
    val valid = odo.toDoubleOrNull() != null
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(enabled = valid, onClick = { onSet(odo.toDouble()) }) { Text("Save", color = if (valid) Gold else TextLo) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = TextMid) } },
        title = { Text("Set odometer", color = TextHi) },
        text = { NumField(odo, { odo = it }, "Odometer (km)", true) },
        containerColor = Surf1,
    )
}

@Composable
private fun NumField(value: String, onChange: (String) -> Unit, label: String, decimal: Boolean) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    )
}

// ── Glovebox ────────────────────────────────────────────────────────────────

private val dfDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
private fun parseDate(s: String): Long = if (s.isBlank()) 0L else runCatching { dfDate.parse(s)?.time ?: 0L }.getOrDefault(0L)
private fun fmtDate(ms: Long): String = if (ms <= 0L) "" else dfDate.format(Date(ms))

private val DOC_TYPES = listOf(
    "insurance" to "Insurance", "puc" to "PUC", "rc" to "RC", "licence" to "Licence",
    "invoice" to "Invoice", "warranty" to "Warranty", "rsa" to "RSA", "other" to "Other",
)
private fun docLabel(type: String) = DOC_TYPES.firstOrNull { it.first == type }?.second ?: "Other"

@Composable
private fun GloveboxTab(
    ui: GarageUi,
    onAddDoc: () -> Unit,
    onEditDoc: (VehicleDocument) -> Unit,
    onDeleteDoc: (VehicleDocument) -> Unit,
    onEditIdentity: () -> Unit,
) {
    val ctx = LocalContext.current
    val now = System.currentTimeMillis()

    Eyebrow("Documents", Modifier.padding(bottom = 8.dp, start = 4.dp))
    WayfindrCard(modifier = Modifier.fillMaxWidth(), padding = 6.dp) {
        if (ui.documents.isEmpty()) {
            Text("No documents yet — add insurance, PUC, RC, licence…", color = TextLo, fontSize = 13.sp, modifier = Modifier.padding(14.dp))
        }
        ui.documents.forEachIndexed { i, d ->
            if (i > 0) WayfindrDivider(Modifier.padding(horizontal = 4.dp))
            val days = if (d.expiryMs > 0) (d.expiryMs - now) / 86_400_000L else null
            val expColor = when { days == null -> TextLo; days < 0 -> Alert; days < 14 -> Warn; else -> TextLo }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { onEditDoc(d) }.padding(horizontal = 6.dp, vertical = 11.dp)) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(40.dp).clip(RoundedCornerShape(11.dp)).background(Surf2).border(1.dp, Line, RoundedCornerShape(11.dp))) {
                    Icon(WayfindrIcons.Wrench, null, tint = TextMid, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(13.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(d.title.ifBlank { docLabel(d.type) }, color = TextHi, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, fontFamily = GeistFamily)
                        Spacer(Modifier.width(6.dp)); Text(docLabel(d.type).uppercase(), color = TextLo, fontSize = 9.sp, fontWeight = FontWeight.Bold, fontFamily = GeistMonoFamily)
                    }
                    Text(
                        buildString {
                            if (d.number.isNotBlank()) append(d.number)
                            if (days != null) {
                                if (isNotEmpty()) append(" · ")
                                append(if (days < 0) "expired" else "expires ${fmtDate(d.expiryMs)}")
                            }
                            if (isEmpty()) append("Tap to edit")
                        },
                        color = expColor, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp), maxLines = 1,
                    )
                }
                if (d.filePath.isNotBlank())
                    WayfindrBtn("View", onClick = { openInvoice(ctx, d.filePath) }, variant = BtnVariant.Ghost, size = BtnSize.Sm)
                Icon(WayfindrIcons.Cross, "delete", tint = TextLo, modifier = Modifier.padding(start = 4.dp).size(16.dp).clickable { onDeleteDoc(d) })
            }
        }
    }
    Spacer(Modifier.height(10.dp))
    WayfindrBtn("Add document", onClick = onAddDoc, icon = WayfindrIcons.Plus, variant = BtnVariant.Ghost, size = BtnSize.Md, modifier = Modifier.fillMaxWidth())

    Spacer(Modifier.height(20.dp))
    Eyebrow("Bike identity", Modifier.padding(bottom = 8.dp, start = 4.dp))
    WayfindrCard(modifier = Modifier.fillMaxWidth()) {
        val id = ui.identity
        IdRow("VIN / chassis", id.vin)
        IdRow("Engine no.", id.engineNo)
        IdRow("Registration", id.regNo)
        IdRow("Purchased", fmtDate(id.purchaseMs))
        IdRow("Colour", id.colour)
        Spacer(Modifier.height(10.dp))
        WayfindrBtn("Edit identity", onClick = onEditIdentity, icon = WayfindrIcons.Wrench, variant = BtnVariant.Ghost, size = BtnSize.Sm)
    }

    Spacer(Modifier.height(20.dp))
    Eyebrow("Reference · Mt.Fuji 450", Modifier.padding(bottom = 8.dp, start = 4.dp))
    WayfindrCard(modifier = Modifier.fillMaxWidth()) {
        SpecRow("Tyre pressure", "32 psi front & rear")
        SpecRow("Engine oil", "10W40 API SN / JASO MA2 · 2.1 L")
        SpecRow("Coolant", "TOTAL COOLELF AUTO SUPRA")
        SpecRow("Fork oil", "SS-47G · RH 589 / LH 507 ml")
        SpecRow("Brake fluid", "DOT 4 · F 90 / R 80 ml")
        SpecRow("Spark plug", "Champion · gap 0.8–0.9 mm")
        SpecRow("Fuel", "17 L (low 3 L) · E20 ok")
        SpecRow("Battery", "12 V 8 Ah VRLA")
    }
}

@Composable
private fun IdRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = TextLo, fontSize = 13.sp)
        Text(value.ifBlank { "—" }, color = TextHi, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFamily = GeistMonoFamily)
    }
}

@Composable
private fun SpecRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
        Text(label, color = TextLo, fontSize = 13.sp)
        Text(value, color = TextHi, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, fontFamily = GeistMonoFamily, modifier = Modifier.padding(start = 12.dp))
    }
}

@Composable
private fun DocumentDialog(
    existing: VehicleDocument?,
    onSave: (String, String, String, Long, Long, Uri?, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var type by remember { mutableStateOf(existing?.type ?: "insurance") }
    var title by remember { mutableStateOf(existing?.title ?: "") }
    var number by remember { mutableStateOf(existing?.number ?: "") }
    var issue by remember { mutableStateOf(fmtDate(existing?.issueMs ?: 0L)) }
    var expiry by remember { mutableStateOf(fmtDate(existing?.expiryMs ?: 0L)) }
    var note by remember { mutableStateOf(existing?.note ?: "") }
    var file by remember { mutableStateOf<Uri?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> if (uri != null) file = uri }
    val valid = title.isNotBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(enabled = valid, onClick = { onSave(type, title.trim(), number.trim(), parseDate(issue), parseDate(expiry), file, note.trim()) }) {
                Text("Save", color = if (valid) Gold else TextLo)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = TextMid) } },
        title = { Text(if (existing == null) "Add document" else "Edit document", color = TextHi) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Eyebrow("Type")
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DOC_TYPES.take(4).forEach { (key, lbl) -> DocTypeChip(lbl, key == type) { type = key } }
                }
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DOC_TYPES.drop(4).forEach { (key, lbl) -> DocTypeChip(lbl, key == type) { type = key } }
                }
                OutlinedTextField(title, { title = it }, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                OutlinedTextField(number, { number = it }, label = { Text("Number / policy no. — optional") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                OutlinedTextField(issue, { issue = it }, label = { Text("Issued (yyyy-mm-dd) — optional") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                OutlinedTextField(expiry, { expiry = it }, label = { Text("Expiry (yyyy-mm-dd) — optional") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                OutlinedTextField(note, { note = it }, label = { Text("Note — optional") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                Spacer(Modifier.height(10.dp))
                WayfindrBtn(
                    if (file != null) "File attached ✓" else if (existing?.filePath?.isNotBlank() == true) "Replace file" else "Attach photo / PDF",
                    onClick = { picker.launch("*/*") },
                    icon = WayfindrIcons.Plus,
                    variant = if (file != null) BtnVariant.Primary else BtnVariant.Ghost,
                    size = BtnSize.Sm,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Stored on this phone. Insurance / PUC / Licence get expiry reminders.", color = TextLo, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
            }
        },
        containerColor = Surf1,
    )
}

@Composable
private fun RowScope.DocTypeChip(label: String, on: Boolean, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.weight(1f).clip(RoundedCornerShape(9.dp))
            .background(if (on) GoldTint else Surf2).border(1.dp, if (on) Gold else Line, RoundedCornerShape(9.dp))
            .clickable { onClick() }.padding(vertical = 8.dp),
    ) { Text(label, color = if (on) Gold else TextMid, fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun IdentityDialog(identity: BikeIdentity, onSave: (BikeIdentity) -> Unit, onDismiss: () -> Unit) {
    var vin by remember { mutableStateOf(identity.vin) }
    var engine by remember { mutableStateOf(identity.engineNo) }
    var reg by remember { mutableStateOf(identity.regNo) }
    var purchase by remember { mutableStateOf(fmtDate(identity.purchaseMs)) }
    var colour by remember { mutableStateOf(identity.colour) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onSave(BikeIdentity(vin.trim(), engine.trim(), reg.trim(), parseDate(purchase), colour.trim())) }) { Text("Save", color = Gold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = TextMid) } },
        title = { Text("Bike identity", color = TextHi) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(vin, { vin = it }, label = { Text("VIN / chassis no.") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(engine, { engine = it }, label = { Text("Engine no.") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                OutlinedTextField(reg, { reg = it }, label = { Text("Registration no.") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                OutlinedTextField(purchase, { purchase = it }, label = { Text("Purchased (yyyy-mm-dd)") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                OutlinedTextField(colour, { colour = it }, label = { Text("Colour / variant") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            }
        },
        containerColor = Surf1,
    )
}
