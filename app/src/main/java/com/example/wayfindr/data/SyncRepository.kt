package com.example.wayfindr.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Single store the Garage, rides and saved-location ViewModels talk to. Everything lives in
 * on-device SQLite ([WayfindrDb]); nothing leaves the phone.
 */
class SyncRepository private constructor(context: Context) {
    companion object {
        private const val TAG = "SyncRepository"
        @Volatile private var instance: SyncRepository? = null
        fun get(context: Context): SyncRepository =
            instance ?: synchronized(this) { instance ?: SyncRepository(context.applicationContext).also { instance = it } }
    }

    private val db = WayfindrDb.get(context)
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Bumped on every local OR remote data change so ViewModels reload. */
    private val _revision = MutableStateFlow(0L)
    val revision = _revision.asStateFlow()
    private fun bump() { _revision.value++ }

    init {
        // Heal any duplicate seeded maintenance items left by older upgrade paths, as soon as the
        // store is created — even before sign-in, so the Garage is clean offline too.
        io.launch { reconcileMaintenanceDuplicates() }
    }

    /** Collapse duplicate seeded maintenance items. No-op once everything is canonical. */
    private fun reconcileMaintenanceDuplicates() {
        val (removed, changed) = db.dedupeMaintenance()
        if (!changed) return
        android.util.Log.i(TAG, "maintenance dedupe: removed ${removed.size} stale row(s), realigned names/sids")
        bump()
    }

    // ── Reads (local) ────────────────────────────────────────────────────
    fun odometer() = db.odometer()
    fun fuelFills() = db.fuelFills()
    fun maintenanceItems() = db.maintenanceItems()
    fun savedLocations() = db.savedLocations()
    fun rides() = db.rides()

    // ── Mutations ───────────────────────────────────────────────────────
    fun setOdometer(km: Double) { db.setOdometer(km); bump() }

    fun addFuel(litres: Double, cost: Double, odoKm: Int, location: String) {
        val prevOdo = db.odometer()
        val f = FuelFillup(sid = WayfindrDb.newSid(), dateMs = System.currentTimeMillis(),
            litres = litres, cost = cost, odometerKm = odoKm, location = location)
        db.upsertFuel(f)
        if (odoKm > prevOdo) { db.setOdometer(odoKm.toDouble()) }
        bump()
    }
    fun deleteFuel(f: FuelFillup) { db.deleteFuelBySid(f.sid); bump() }

    fun addMaintenance(name: String, icon: String, intervalKm: Int, lastDoneOdoKm: Int, intervalMonths: Int = 0) {
        val m = MaintenanceItem(sid = WayfindrDb.newSid(), name = name, iconKey = icon,
            intervalKm = intervalKm, lastDoneOdoKm = lastDoneOdoKm, lastDoneDateMs = System.currentTimeMillis(),
            intervalMonths = intervalMonths)
        db.upsertMaintenance(m); bump()
    }
    /**
     * Log a service VISIT: a single event that resets the countdown on every covered
     * maintenance item, links to a scheduled milestone if [scheduledKey] is set, and records the
     * cost + uploaded invoice. The record's METADATA syncs (so the service history survives a
     * reinstall / restores on a second device); the invoice FILE stays on-device, so a restored
     * record just has no invoice attached. Scheduled services / documents remain local-only.
     */
    fun logVisit(
        title: String, kind: String, scheduledKey: String, items: List<MaintenanceItem>,
        odoKm: Int, cost: Double, invoicePath: String, note: String,
    ) {
        val now = System.currentTimeMillis()
        items.forEach { m ->
            val u = m.copy(lastDoneOdoKm = odoKm, lastDoneDateMs = now)
            db.upsertMaintenance(u)
        }
        val rec = ServiceRecord(
            sid = WayfindrDb.newSid(), title = title, kind = kind, scheduledKey = scheduledKey,
            itemSids = items.map { it.sid }, odometerKm = odoKm, dateMs = now, cost = cost,
            invoicePath = invoicePath, note = note)
        db.upsertServiceRecord(rec)
        bump()
    }

    fun serviceRecords() = db.serviceRecords()
    fun deleteServiceRecord(r: ServiceRecord) {
        db.deleteServiceRecordBySid(r.sid)
        if (r.invoicePath.isNotBlank()) runCatching { java.io.File(r.invoicePath).delete() }
        bump()
    }

    // ── Scheduled services ───────────────────────────────────────────────────
    fun scheduledServices() = db.scheduledServices()
    fun addScheduledService(label: String, targetKm: Int, targetMonths: Int) {
        db.upsertScheduledService(ScheduledService(
            sid = WayfindrDb.newSid(), label = label, targetKm = targetKm,
            targetMonths = targetMonths, free = false, orderIdx = 100))
        bump()
    }
    fun deleteScheduledService(s: ScheduledService) { db.deleteScheduledServiceBySid(s.sid); bump() }

    // ── Documents (Glovebox) ─────────────────────────────────────────────────
    fun documents() = db.documents()
    fun upsertDocument(d: VehicleDocument) { db.upsertDocument(d); bump() }
    fun deleteDocument(d: VehicleDocument) {
        db.deleteDocumentBySid(d.sid)
        if (d.filePath.isNotBlank()) runCatching { java.io.File(d.filePath).delete() }
        bump()
    }

    // ── Bike identity ─────────────────────────────────────────────────────────
    fun bikeIdentity() = db.bikeIdentity()
    fun setBikeIdentity(b: BikeIdentity) { db.setBikeIdentity(b); bump() }
    fun deleteMaintenance(m: MaintenanceItem) { db.deleteMaintenanceBySid(m.sid); bump() }

    fun addSaved(name: String, lat: Double, lng: Double, note: String) {
        val s = SavedLocation(sid = WayfindrDb.newSid(), name = name, lat = lat, lng = lng, note = note)
        db.upsertSaved(s); bump()
    }
    fun addSavedItinerary(name: String, waypoints: List<SharedLocation>, note: String) {
        val finalStop = waypoints.lastOrNull { it.lat != null && it.lng != null } ?: return
        val points = waypoints.filter { it.lat != null && it.lng != null }
        val s = SavedLocation(
            sid = WayfindrDb.newSid(), name = name, lat = finalStop.lat!!, lng = finalStop.lng!!,
            waypoints = points, note = note,
        )
        db.upsertSaved(s); bump()
    }
    fun renameSaved(s: SavedLocation, name: String, note: String) {
        val u = s.copy(name = name, note = note); db.upsertSaved(u); bump()
    }
    fun deleteSaved(s: SavedLocation) { db.deleteSavedBySid(s.sid); bump() }

    /**
     * Persist a finished ride (local + cloud). Self-scopes on the repo's own IO scope so the
     * write survives even when called from ViewModel teardown (onCleared cancels viewModelScope
     * before a launch there could run — that was silently dropping rides).
     */
    fun addRide(r: Ride) { io.launch {
        db.upsertRide(r)
        // A completed ride advances the bike's odometer by exactly what was ridden.
        db.addToOdometer(r.distanceKm)
        bump()
    } }
    fun deleteRide(r: Ride) { db.deleteRideBySid(r.sid); bump() }

}
