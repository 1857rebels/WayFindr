package com.example.wayfindr.viewmodel

import android.app.Application
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.wayfindr.data.BikeIdentity
import com.example.wayfindr.data.FuelFillup
import com.example.wayfindr.data.MaintenanceItem
import com.example.wayfindr.data.WayfindrDb
import com.example.wayfindr.data.ScheduledService
import com.example.wayfindr.data.ServiceRecord
import com.example.wayfindr.data.SyncRepository
import com.example.wayfindr.data.VehicleDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

data class FuelRow(val fill: FuelFillup, val kmpl: Double?)

/**
 * A maintenance item with its computed status. [dueLabel] / [tone] / [fraction] fold the manual's
 * "km OR months, whichever earlier" rule into ready-to-render values; [urgency] (consumed fraction,
 * can exceed 1 when overdue) is the sort key for the most-urgent hero.
 */
data class MaintRow(
    val item: MaintenanceItem,
    val dueLabel: String,
    val tone: String,        // ok | warn | alert
    val fraction: Float,     // 0..1 consumed, for the progress bar
    val urgency: Float,
)

/** A manufacturer/rider scheduled service with its availed status (derived from the log). */
data class ScheduledRow(
    val svc: ScheduledService,
    val availed: Boolean,
    val availedDateMs: Long = 0L,
    val availedOdoKm: Int = 0,
    val invoicePath: String = "",
)

data class GarageUi(
    val odometerKm: Double = 0.0,
    val fuel: List<FuelRow> = emptyList(),     // newest first; kmpl vs the prior fill
    val maint: List<MaintRow> = emptyList(),
    val services: List<ServiceRecord> = emptyList(),   // service log, newest first
    val scheduled: List<ScheduledRow> = emptyList(),   // manufacturer + custom milestones
    val documents: List<VehicleDocument> = emptyList(),
    val identity: BikeIdentity = BikeIdentity(),
    val avgKmpl30: Double? = null,
    val spent30: Double = 0.0,
    val litres30: Double = 0.0,
    val fills30: Int = 0,
)

private const val MS_PER_MONTH = 2_629_800_000.0   // average month in ms

class GarageViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = SyncRepository.get(app)
    private val _ui = MutableStateFlow(GarageUi())
    val ui = _ui.asStateFlow()

    init {
        reload()
        // Reload whenever local OR synced-from-cloud data changes.
        viewModelScope.launch { repo.revision.collect { reload() } }
    }

    private fun reload() = viewModelScope.launch {
        val ui = withContext(Dispatchers.IO) { compute() }
        _ui.value = ui
        // Buzz if a service crossed into "due" or a document is expiring (de-duped in the notifier).
        withContext(Dispatchers.IO) {
            com.example.wayfindr.data.MaintenanceNotifier.check(getApplication(), ui.maint.map { it.item }, ui.odometerKm.toInt())
            com.example.wayfindr.data.MaintenanceNotifier.checkDocuments(getApplication(), ui.documents)
        }
    }

    private fun compute(): GarageUi {
        val odo = repo.odometer()
        val fills = repo.fuelFills()   // highest odometer (newest) first
        val fuelRows = fills.mapIndexed { i, f ->
            val prev = fills.getOrNull(i + 1)   // next-lower odometer fill
            val kmpl = if (prev != null && f.litres > 0 && f.odometerKm > prev.odometerKm)
                (f.odometerKm - prev.odometerKm) / f.litres else null
            FuelRow(f, kmpl)
        }
        val cutoff = System.currentTimeMillis() - 30L * 24 * 3600 * 1000
        val recent = fuelRows.filter { it.fill.dateMs >= cutoff }
        val kmpls = recent.mapNotNull { it.kmpl }
        val now = System.currentTimeMillis()
        val maint = repo.maintenanceItems().map { m -> statusOf(m, odo, now) }
        val visits = repo.serviceRecords()
        // A scheduled service is "availed" once a visit references its sid.
        val byKey = visits.filter { it.scheduledKey.isNotBlank() }.associateBy { it.scheduledKey }
        val scheduled = repo.scheduledServices().map { s ->
            val v = byKey[s.sid]
            if (v != null) ScheduledRow(s, true, v.dateMs, v.odometerKm, v.invoicePath)
            else ScheduledRow(s, false)
        }
        return GarageUi(
            odometerKm = odo,
            fuel = fuelRows,
            maint = maint,
            services = visits,
            scheduled = scheduled,
            documents = repo.documents(),
            identity = repo.bikeIdentity(),
            avgKmpl30 = kmpls.takeIf { it.isNotEmpty() }?.average(),
            spent30 = recent.sumOf { it.fill.cost },
            litres30 = recent.sumOf { it.fill.litres },
            fills30 = recent.size,
        )
    }

    /** Fold km + months intervals into a single status (label, tone, progress, urgency). */
    private fun statusOf(m: MaintenanceItem, odo: Double, now: Long): MaintRow {
        val kmFrac = if (m.intervalKm > 0) ((odo - m.lastDoneOdoKm) / m.intervalKm).toFloat() else null
        val moElapsed = (now - m.lastDoneDateMs) / MS_PER_MONTH
        val moFrac = if (m.intervalMonths > 0) (moElapsed / m.intervalMonths).toFloat() else null
        val urgency = max(kmFrac ?: -1f, moFrac ?: -1f)
        val tone = when {
            urgency >= 1f   -> "alert"
            urgency >= 0.75f -> "warn"
            else            -> "ok"
        }
        // Label off whichever dimension is closer to due.
        val kmDom = (kmFrac ?: -1f) >= (moFrac ?: -1f)
        val dueLabel = when {
            kmFrac != null && kmDom -> {
                val rem = (m.lastDoneOdoKm + m.intervalKm - odo).roundToInt()
                if (rem < 0) "overdue ${"%,d".format(-rem)} km" else "in ${"%,d".format(rem)} km"
            }
            moFrac != null -> {
                val rem = m.intervalMonths - moElapsed
                if (rem < 0) "overdue ${ceil(-rem).toInt()} mo" else "in ${ceil(rem).toInt().coerceAtLeast(0)} mo"
            }
            else -> "—"
        }
        return MaintRow(m, dueLabel, tone, urgency.coerceIn(0f, 1f), urgency)
    }

    fun addFuel(litres: Double, cost: Double, odometerKm: Int, location: String) =
        viewModelScope.launch { withContext(Dispatchers.IO) { repo.addFuel(litres, cost, odometerKm, location) } }

    fun deleteFuel(fill: FuelFillup) =
        viewModelScope.launch { withContext(Dispatchers.IO) { repo.deleteFuel(fill) } }

    /**
     * Log a service visit: resets every covered item, links a scheduled milestone if set, and
     * stores the cost + uploaded invoice. The invoice [Uri] (from the picker) is copied into
     * app-private storage so it survives the source being deleted.
     */
    fun logVisit(
        title: String, kind: String, scheduledKey: String, items: List<MaintenanceItem>,
        odoKm: Int, cost: Double, invoice: Uri?, note: String,
    ) = viewModelScope.launch {
        withContext(Dispatchers.IO) {
            val path = invoice?.let { copyFile(it, "invoices") } ?: ""
            repo.logVisit(title, kind, scheduledKey, items, odoKm, cost, path, note)
        }
    }

    fun deleteServiceRecord(r: ServiceRecord) =
        viewModelScope.launch { withContext(Dispatchers.IO) { repo.deleteServiceRecord(r) } }

    fun addScheduledService(label: String, km: Int, months: Int) =
        viewModelScope.launch { withContext(Dispatchers.IO) { repo.addScheduledService(label, km, months) } }

    fun deleteScheduledService(s: ScheduledService) =
        viewModelScope.launch { withContext(Dispatchers.IO) { repo.deleteScheduledService(s) } }

    fun addService(name: String, iconKey: String, intervalKm: Int, intervalMonths: Int) =
        viewModelScope.launch { withContext(Dispatchers.IO) { repo.addMaintenance(name, iconKey, intervalKm, repo.odometer().toInt(), intervalMonths) } }

    fun deleteService(item: MaintenanceItem) =
        viewModelScope.launch { withContext(Dispatchers.IO) { repo.deleteMaintenance(item) } }

    fun setOdometer(km: Double) =
        viewModelScope.launch { withContext(Dispatchers.IO) { repo.setOdometer(km) } }

    // ── Glovebox: documents + identity ───────────────────────────────────────
    /** Add or update a document; [file] (if picked) is copied into app storage. */
    fun saveDocument(
        sid: String?, type: String, title: String, number: String,
        issueMs: Long, expiryMs: Long, file: Uri?, existingPath: String, note: String,
    ) = viewModelScope.launch {
        withContext(Dispatchers.IO) {
            val path = if (file != null) copyFile(file, "documents") else existingPath
            repo.upsertDocument(VehicleDocument(
                sid = sid ?: WayfindrDb.newSid(), type = type, title = title, number = number,
                issueMs = issueMs, expiryMs = expiryMs, filePath = path, note = note))
        }
    }

    fun deleteDocument(d: VehicleDocument) =
        viewModelScope.launch { withContext(Dispatchers.IO) { repo.deleteDocument(d) } }

    fun saveIdentity(b: BikeIdentity) =
        viewModelScope.launch { withContext(Dispatchers.IO) { repo.setBikeIdentity(b) } }

    /** Copy a picked content Uri into filesDir/[subdir]/<uuid>.<ext>; returns the stable path. */
    private fun copyFile(uri: Uri, subdir: String): String = try {
        val cr = getApplication<Application>().contentResolver
        val mime = cr.getType(uri) ?: ""
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
            ?: if (mime.contains("pdf")) "pdf" else "jpg"
        val dir = File(getApplication<Application>().filesDir, subdir).apply { mkdirs() }
        val out = File(dir, "${UUID.randomUUID()}.$ext")
        cr.openInputStream(uri)?.use { input -> out.outputStream().use { input.copyTo(it) } }
        out.absolutePath
    } catch (e: Exception) { "" }
}
