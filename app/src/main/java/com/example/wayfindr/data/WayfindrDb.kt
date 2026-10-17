package com.example.wayfindr.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * On-device SQLite — the source of truth for the Garage (maintenance + fuel), saved
 * destinations, and the odometer. Plain SQLiteOpenHelper (no Room/KSP).
 *
 * Every row carries a stable [sid] (a UUID) used by the upsert / delete-by-sid methods.
 * All calls are synchronous; callers run them off the main thread.
 */
class WayfindrDb private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    companion object {
        private const val DB_NAME = "wayfindr.db"
        private const val DB_VERSION = 9
        @Volatile private var instance: WayfindrDb? = null
        fun get(context: Context): WayfindrDb =
            instance ?: synchronized(this) {
                instance ?: WayfindrDb(context).also { instance = it }
            }
        const val DEFAULT_ODOMETER = 325.0   // seeded from the bike's current ODO; user-editable. Fractional so recorded rides add their exact distance.
        fun newSid(): String = UUID.randomUUID().toString()

        private const val CREATE_RIDE =
            """CREATE TABLE IF NOT EXISTS ride(
                 id INTEGER PRIMARY KEY AUTOINCREMENT,
                 sid TEXT NOT NULL DEFAULT '',
                 start_ms INTEGER NOT NULL,
                 end_ms INTEGER NOT NULL,
                 distance_m REAL NOT NULL,
                 duration_s INTEGER NOT NULL,
                 avg_speed REAL NOT NULL,
                 max_speed REAL NOT NULL,
                 track TEXT NOT NULL DEFAULT '',
                 start_lat REAL NOT NULL DEFAULT 0,
                 start_lng REAL NOT NULL DEFAULT 0,
                 end_lat REAL NOT NULL DEFAULT 0,
                 end_lng REAL NOT NULL DEFAULT 0)"""

        private const val CREATE_SERVICE_RECORD =
            """CREATE TABLE IF NOT EXISTS service_record(
                 id INTEGER PRIMARY KEY AUTOINCREMENT,
                 sid TEXT NOT NULL DEFAULT '',
                 item_sid TEXT NOT NULL DEFAULT '',
                 name TEXT NOT NULL DEFAULT '',
                 odometer_km INTEGER NOT NULL DEFAULT 0,
                 date_ms INTEGER NOT NULL,
                 cost REAL NOT NULL DEFAULT 0,
                 invoice_path TEXT NOT NULL DEFAULT '',
                 note TEXT NOT NULL DEFAULT '',
                 title TEXT NOT NULL DEFAULT '',
                 kind TEXT NOT NULL DEFAULT 'company',
                 scheduled_key TEXT NOT NULL DEFAULT '',
                 item_sids TEXT NOT NULL DEFAULT '')"""

        private const val CREATE_SCHEDULED_SERVICE =
            """CREATE TABLE IF NOT EXISTS scheduled_service(
                 id INTEGER PRIMARY KEY AUTOINCREMENT,
                 sid TEXT NOT NULL DEFAULT '',
                 label TEXT NOT NULL,
                 target_km INTEGER NOT NULL DEFAULT 0,
                 target_months INTEGER NOT NULL DEFAULT 0,
                 free INTEGER NOT NULL DEFAULT 0,
                 order_idx INTEGER NOT NULL DEFAULT 0)"""

        private const val CREATE_DOCUMENT =
            """CREATE TABLE IF NOT EXISTS document(
                 id INTEGER PRIMARY KEY AUTOINCREMENT,
                 sid TEXT NOT NULL DEFAULT '',
                 type TEXT NOT NULL DEFAULT 'other',
                 title TEXT NOT NULL DEFAULT '',
                 number TEXT NOT NULL DEFAULT '',
                 issue_ms INTEGER NOT NULL DEFAULT 0,
                 expiry_ms INTEGER NOT NULL DEFAULT 0,
                 file_path TEXT NOT NULL DEFAULT '',
                 note TEXT NOT NULL DEFAULT '')"""

        private const val CREATE_BIKE_IDENTITY =
            """CREATE TABLE IF NOT EXISTS bike_identity(
                 id INTEGER PRIMARY KEY,
                 vin TEXT NOT NULL DEFAULT '',
                 engine_no TEXT NOT NULL DEFAULT '',
                 reg_no TEXT NOT NULL DEFAULT '',
                 purchase_ms INTEGER NOT NULL DEFAULT 0,
                 colour TEXT NOT NULL DEFAULT '')"""

        /** The 4 manufacturer free services (deterministic sids so they dedupe across devices). */
        data class SchedSeed(val sid: String, val label: String, val km: Int, val months: Int)
        val FREE_SERVICES = listOf(
            SchedSeed("free-1", "1st service", 500, 2),
            SchedSeed("free-2", "2nd service", 5000, 6),
            SchedSeed("free-3", "3rd service", 10000, 12),
            SchedSeed("free-4", "4th service", 15000, 18),
        )

        /**
         * Canonical maintenance schedule, derived from the RE New Mt.Fuji 450 owner's manual
         * periodic-maintenance chart (km OR months, whichever earlier). Deterministic sids so two
         * devices dedupe on sync, and so upgrades can realign existing rows in place.
         */
        // [aliases] = historical names this seed has had, so a rename can't strand an old row as a
        // permanent duplicate (the dedupe collapses by sid OR any alias). "Engine oil" was renamed
        // to "Engine oil & filter".
        data class Seed(
            val sid: String, val name: String, val icon: String, val km: Int, val months: Int,
            val aliases: List<String> = emptyList(),
        )
        val SEED_ITEMS = listOf(
            Seed("seed-chain",        "Chain clean & lube",   "chain",  500,   0),
            Seed("seed-oil",          "Engine oil & filter",  "drop",   10000, 12, aliases = listOf("Engine oil")),
            Seed("seed-airfilter",    "Air filter",           "wrench", 10000, 0),
            Seed("seed-brakes",       "Brake pads (front)",   "gauge",  5000,  0),
            Seed("seed-valve",        "Valve clearance",      "wrench", 20000, 0),
            Seed("seed-sparkplug",    "Spark plug",           "wrench", 20000, 0),
            Seed("seed-brakefluid",   "Brake fluid",          "drop",   0,     24),
            Seed("seed-coolant",      "Coolant",              "thermo", 40000, 48),
            Seed("seed-throttlebody", "Throttle body clean",  "wrench", 40000, 48),
        )
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE fuel_fillup(
                 id INTEGER PRIMARY KEY AUTOINCREMENT,
                 sid TEXT NOT NULL DEFAULT '',
                 date_ms INTEGER NOT NULL,
                 litres REAL NOT NULL,
                 cost REAL NOT NULL,
                 odometer_km INTEGER NOT NULL,
                 location TEXT NOT NULL DEFAULT '')"""
        )
        db.execSQL(
            """CREATE TABLE maintenance_item(
                 id INTEGER PRIMARY KEY AUTOINCREMENT,
                 sid TEXT NOT NULL DEFAULT '',
                 name TEXT NOT NULL,
                 icon_key TEXT NOT NULL,
                 interval_km INTEGER NOT NULL,
                 last_done_odo_km INTEGER NOT NULL,
                 last_done_date_ms INTEGER NOT NULL,
                 interval_months INTEGER NOT NULL DEFAULT 0)"""
        )
        db.execSQL("CREATE TABLE bike_state(id INTEGER PRIMARY KEY, odometer_km REAL NOT NULL)")
        db.execSQL("INSERT INTO bike_state(id, odometer_km) VALUES (0, $DEFAULT_ODOMETER)")
        db.execSQL(
            """CREATE TABLE saved_location(
                 id INTEGER PRIMARY KEY AUTOINCREMENT,
                 sid TEXT NOT NULL DEFAULT '',
                 name TEXT NOT NULL,
                 lat REAL NOT NULL,
                 lng REAL NOT NULL,
                 note TEXT NOT NULL DEFAULT '',
                 created_ms INTEGER NOT NULL,
                 waypoints TEXT NOT NULL DEFAULT '')"""
        )
        db.execSQL(CREATE_RIDE)
        db.execSQL(CREATE_SERVICE_RECORD)
        db.execSQL(CREATE_SCHEDULED_SERVICE)
        db.execSQL(CREATE_DOCUMENT)
        db.execSQL(CREATE_BIKE_IDENTITY)
        db.execSQL("INSERT INTO bike_identity(id) VALUES (0)")
        seedMaintenance(db)
        seedScheduledServices(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS saved_location(
                     id INTEGER PRIMARY KEY AUTOINCREMENT,
                     name TEXT NOT NULL, lat REAL NOT NULL, lng REAL NOT NULL,
                     note TEXT NOT NULL DEFAULT '', created_ms INTEGER NOT NULL)"""
            )
        }
        if (oldVersion < 3) {
            // Add sid columns + backfill existing rows with random UUID-like ids.
            for (t in listOf("fuel_fillup", "maintenance_item", "saved_location")) {
                runCatching { db.execSQL("ALTER TABLE $t ADD COLUMN sid TEXT NOT NULL DEFAULT ''") }
                db.execSQL(
                    "UPDATE $t SET sid = lower(hex(randomblob(16))) WHERE sid IS NULL OR sid = ''"
                )
            }
        }
        if (oldVersion < 4) db.execSQL(CREATE_RIDE)
        if (oldVersion < 5) {
            // Odometer becomes fractional (recorded rides add their exact distance). Rebuild the
            // single-row bike_state table with REAL affinity, preserving the current reading.
            db.execSQL("ALTER TABLE bike_state RENAME TO bike_state_old")
            db.execSQL("CREATE TABLE bike_state(id INTEGER PRIMARY KEY, odometer_km REAL NOT NULL)")
            db.execSQL("INSERT INTO bike_state(id, odometer_km) SELECT id, odometer_km FROM bike_state_old")
            db.execSQL("DROP TABLE bike_state_old")
        }
        if (oldVersion < 6) {
            // Maintenance gains a months interval (manual is "km OR months"); add the service log.
            runCatching { db.execSQL("ALTER TABLE maintenance_item ADD COLUMN interval_months INTEGER NOT NULL DEFAULT 0") }
            db.execSQL(CREATE_SERVICE_RECORD)
            // Realign the originally-seeded items to the manual values, and add the items that
            // were missing. Only the intervals are touched — the rider's last-done odo/date and
            // any custom items are left untouched.
            for (s in SEED_ITEMS) {
                val updated = db.update("maintenance_item",
                    ContentValues().apply { put("interval_km", s.km); put("interval_months", s.months) },
                    "sid=?", arrayOf(s.sid))
                if (updated == 0) {
                    db.insert("maintenance_item", null, ContentValues().apply {
                        put("sid", s.sid); put("name", s.name); put("icon_key", s.icon)
                        put("interval_km", s.km); put("interval_months", s.months)
                        put("last_done_odo_km", 0); put("last_done_date_ms", System.currentTimeMillis())
                    })
                }
            }
        }
        if (oldVersion < 7) {
            // Service log becomes visit-based (multi-item, company/DIY); add scheduled services,
            // documents (Glovebox), and bike identity. (service_record exists by now — created in
            // v6 above for older DBs, or already present for v6 DBs.)
            for (col in listOf(
                "title TEXT NOT NULL DEFAULT ''",
                "kind TEXT NOT NULL DEFAULT 'company'",
                "scheduled_key TEXT NOT NULL DEFAULT ''",
                "item_sids TEXT NOT NULL DEFAULT ''",
            )) runCatching { db.execSQL("ALTER TABLE service_record ADD COLUMN $col") }
            // Old single-item rows → carry their name/item into the new visit fields.
            runCatching { db.execSQL("UPDATE service_record SET title = name WHERE title = '' AND name <> ''") }
            runCatching { db.execSQL("UPDATE service_record SET item_sids = item_sid WHERE item_sids = '' AND item_sid <> ''") }
            db.execSQL(CREATE_SCHEDULED_SERVICE)
            db.execSQL(CREATE_DOCUMENT)
            db.execSQL(CREATE_BIKE_IDENTITY)
            runCatching { db.execSQL("INSERT INTO bike_identity(id) VALUES (0)") }
            seedScheduledServices(db)
        }
        if (oldVersion < 8) {
            runCatching { db.execSQL("ALTER TABLE saved_location ADD COLUMN waypoints TEXT NOT NULL DEFAULT ''") }
        }
    }

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Safe no-op on downgrade to prevent SQLiteException crashes if DB version on device is higher
    }

    private fun seedScheduledServices(db: SQLiteDatabase) {
        for ((i, s) in FREE_SERVICES.withIndex()) {
            db.insert("scheduled_service", null, ContentValues().apply {
                put("sid", s.sid); put("label", s.label); put("target_km", s.km)
                put("target_months", s.months); put("free", 1); put("order_idx", i)
            })
        }
    }

    private fun seedMaintenance(db: SQLiteDatabase) {
        val now = System.currentTimeMillis()
        // DETERMINISTIC sids: every fresh install seeds the same ids, so when two devices
        // sync these dedupe (upsert by sid) instead of producing duplicates.
        for (s in SEED_ITEMS) {
            db.insert("maintenance_item", null, ContentValues().apply {
                put("sid", s.sid)
                put("name", s.name)
                put("icon_key", s.icon)
                put("interval_km", s.km)
                put("interval_months", s.months)
                put("last_done_odo_km", 0)
                put("last_done_date_ms", now)
            })
        }
    }

    // ── Odometer (synced as a single doc) ──────────────────────────────────
    fun odometer(): Double =
        readableDatabase.rawQuery("SELECT odometer_km FROM bike_state WHERE id=0", null).use {
            if (it.moveToFirst()) it.getDouble(0) else DEFAULT_ODOMETER
        }

    fun setOdometer(km: Double) {
        writableDatabase.update("bike_state", ContentValues().apply { put("odometer_km", km) }, "id=0", null)
    }

    /** Atomically add [deltaKm] to the stored odometer (used when a recorded ride completes). */
    fun addToOdometer(deltaKm: Double) {
        writableDatabase.execSQL("UPDATE bike_state SET odometer_km = odometer_km + ? WHERE id=0", arrayOf(deltaKm))
    }

    // ── Fuel ──────────────────────────────────────────────────────────────
    fun fuelFills(): List<FuelFillup> {
        val out = ArrayList<FuelFillup>()
        readableDatabase.rawQuery(
            "SELECT id,sid,date_ms,litres,cost,odometer_km,location FROM fuel_fillup " +
                "ORDER BY odometer_km DESC, date_ms DESC", null,
        ).use { c ->
            while (c.moveToNext()) out.add(
                FuelFillup(
                    id = c.getLong(0), sid = c.getString(1), dateMs = c.getLong(2), litres = c.getDouble(3),
                    cost = c.getDouble(4), odometerKm = c.getInt(5), location = c.getString(6) ?: "",
                )
            )
        }
        return out
    }

    /** Insert or update by sid. */
    fun upsertFuel(f: FuelFillup) {
        val cv = ContentValues().apply {
            put("sid", f.sid); put("date_ms", f.dateMs); put("litres", f.litres)
            put("cost", f.cost); put("odometer_km", f.odometerKm); put("location", f.location)
        }
        if (writableDatabase.update("fuel_fillup", cv, "sid=?", arrayOf(f.sid)) == 0)
            writableDatabase.insert("fuel_fillup", null, cv)
    }

    fun deleteFuelBySid(sid: String) =
        writableDatabase.delete("fuel_fillup", "sid=?", arrayOf(sid))

    // ── Maintenance ───────────────────────────────────────────────────────
    fun maintenanceItems(): List<MaintenanceItem> {
        val out = ArrayList<MaintenanceItem>()
        readableDatabase.rawQuery(
            "SELECT id,sid,name,icon_key,interval_km,last_done_odo_km,last_done_date_ms,interval_months " +
                "FROM maintenance_item ORDER BY id ASC", null,
        ).use { c ->
            while (c.moveToNext()) out.add(
                MaintenanceItem(
                    id = c.getLong(0), sid = c.getString(1), name = c.getString(2), iconKey = c.getString(3),
                    intervalKm = c.getInt(4), lastDoneOdoKm = c.getInt(5), lastDoneDateMs = c.getLong(6),
                    intervalMonths = c.getInt(7),
                )
            )
        }
        return out
    }

    fun upsertMaintenance(m: MaintenanceItem) {
        val cv = ContentValues().apply {
            put("sid", m.sid); put("name", m.name); put("icon_key", m.iconKey)
            put("interval_km", m.intervalKm); put("last_done_odo_km", m.lastDoneOdoKm)
            put("last_done_date_ms", m.lastDoneDateMs); put("interval_months", m.intervalMonths)
        }
        if (writableDatabase.update("maintenance_item", cv, "sid=?", arrayOf(m.sid)) == 0)
            writableDatabase.insert("maintenance_item", null, cv)
    }

    fun deleteMaintenanceBySid(sid: String) =
        writableDatabase.delete("maintenance_item", "sid=?", arrayOf(sid))

    /**
     * Collapse duplicate SEEDED maintenance items (e.g. two "Chain clean & lube" rows). Older
     * upgrades inserted the canonical seed rows by deterministic sid, but pre-v3 rows had random
     * sids backfilled, so the seed insert couldn't match them and produced duplicates. For each
     * seed NAME we keep the most-progressed row (latest done odo, then date) and realign it to the
     * canonical seed sid + icon, deleting the rest. Custom (non-seed-name) items are left alone.
     *
     * Returns the sids that no longer exist (deleted duplicates + replaced sids of realigned
     * keepers).
     * Idempotent: a no-op once the data is canonical.
     */
    data class DedupeResult(val removedSids: List<String>, val changed: Boolean)

    fun dedupeMaintenance(): DedupeResult {
        val removed = ArrayList<String>()
        var changed = false
        val bySid = SEED_ITEMS.associateBy { it.sid }
        // current name + every historical alias → seed
        val byName = HashMap<String, Seed>().apply {
            for (s in SEED_ITEMS) { put(s.name, s); s.aliases.forEach { put(it, s) } }
        }
        val wdb = writableDatabase
        wdb.beginTransaction()
        try {
            // Group rows by the seed they belong to — resolved by deterministic sid OR by name/alias,
            // so a renamed row (e.g. "Engine oil") groups with its seed ("Engine oil & filter").
            val groups = HashMap<String, MutableList<MaintenanceItem>>()
            for (item in maintenanceItems()) {
                val seed = bySid[item.sid] ?: byName[item.name] ?: continue
                groups.getOrPut(seed.sid) { ArrayList() }.add(item)
            }
            for ((seedSid, group) in groups) {
                val seed = bySid.getValue(seedSid)
                // Keep the row with the most service history; tie-break newest, then lowest id.
                val keeper = group.maxWith(compareBy({ it.lastDoneOdoKm }, { it.lastDoneDateMs }, { -it.id }))
                group.filter { it.id != keeper.id }.forEach {
                    wdb.delete("maintenance_item", "id=?", arrayOf(it.id.toString()))
                    removed.add(it.sid); changed = true
                }
                // Realign the keeper to the canonical sid + name + icon (fixes an old random sid AND
                // a stale alias name like "Engine oil").
                if (keeper.sid != seed.sid || keeper.name != seed.name || keeper.iconKey != seed.icon) {
                    if (keeper.sid != seed.sid) removed.add(keeper.sid)   // old doc must go from the cloud too
                    wdb.update(
                        "maintenance_item",
                        ContentValues().apply { put("sid", seed.sid); put("name", seed.name); put("icon_key", seed.icon) },
                        "id=?", arrayOf(keeper.id.toString()),
                    )
                    changed = true
                }
            }
            wdb.setTransactionSuccessful()
        } finally {
            wdb.endTransaction()
        }
        return DedupeResult(removed, changed)
    }

    // ── Saved destinations ─────────────────────────────────────────────────
    fun savedLocations(): List<SavedLocation> {
        val out = ArrayList<SavedLocation>()
        readableDatabase.rawQuery(
            "SELECT id,sid,name,lat,lng,note,created_ms,waypoints FROM saved_location ORDER BY created_ms DESC", null,
        ).use { c ->
            while (c.moveToNext()) out.add(
                SavedLocation(
                    id = c.getLong(0), sid = c.getString(1), name = c.getString(2), lat = c.getDouble(3),
                    lng = c.getDouble(4), note = c.getString(5) ?: "", createdMs = c.getLong(6),
                    waypoints = decodeWaypoints(c.getString(7)),
                )
            )
        }
        return out
    }

    fun upsertSaved(s: SavedLocation) {
        val cv = ContentValues().apply {
            put("sid", s.sid); put("name", s.name); put("lat", s.lat); put("lng", s.lng)
            put("note", s.note); put("created_ms", s.createdMs)
            put("waypoints", encodeWaypoints(s.waypoints))
        }
        if (writableDatabase.update("saved_location", cv, "sid=?", arrayOf(s.sid)) == 0)
            writableDatabase.insert("saved_location", null, cv)
    }

    fun deleteSavedBySid(sid: String) =
        writableDatabase.delete("saved_location", "sid=?", arrayOf(sid))

    private fun encodeWaypoints(points: List<SharedLocation>): String {
        if (points.isEmpty()) return ""
        val json = JSONArray()
        points.forEach { point ->
            json.put(JSONObject().apply {
                put("name", point.name)
                point.lat?.let { put("lat", it) }
                point.lng?.let { put("lng", it) }
            })
        }
        return json.toString()
    }

    private fun decodeWaypoints(value: String?): List<SharedLocation> = runCatching {
        if (value.isNullOrBlank()) return@runCatching emptyList()
        val json = JSONArray(value)
        buildList {
            for (i in 0 until json.length()) {
                val point = json.getJSONObject(i)
                if (point.has("lat") && point.has("lng")) add(SharedLocation(
                    name = point.optString("name", "Saved stop"),
                    lat = point.getDouble("lat"), lng = point.getDouble("lng"),
                ))
            }
        }
    }.getOrDefault(emptyList())

    // ── Rides ──────────────────────────────────────────────────────────────
    fun rides(): List<Ride> {
        val out = ArrayList<Ride>()
        readableDatabase.rawQuery(
            "SELECT id,sid,start_ms,end_ms,distance_m,duration_s,avg_speed,max_speed," +
                "track,start_lat,start_lng,end_lat,end_lng FROM ride ORDER BY start_ms DESC", null,
        ).use { c ->
            while (c.moveToNext()) out.add(
                Ride(
                    id = c.getLong(0), sid = c.getString(1), startMs = c.getLong(2), endMs = c.getLong(3),
                    distanceMeters = c.getDouble(4), durationSec = c.getLong(5), avgSpeedMps = c.getDouble(6),
                    maxSpeedMps = c.getDouble(7), trackPolyline = c.getString(8) ?: "",
                    startLat = c.getDouble(9), startLng = c.getDouble(10), endLat = c.getDouble(11), endLng = c.getDouble(12),
                )
            )
        }
        return out
    }

    fun upsertRide(r: Ride) {
        val cv = ContentValues().apply {
            put("sid", r.sid); put("start_ms", r.startMs); put("end_ms", r.endMs)
            put("distance_m", r.distanceMeters); put("duration_s", r.durationSec)
            put("avg_speed", r.avgSpeedMps); put("max_speed", r.maxSpeedMps); put("track", r.trackPolyline)
            put("start_lat", r.startLat); put("start_lng", r.startLng); put("end_lat", r.endLat); put("end_lng", r.endLng)
        }
        if (writableDatabase.update("ride", cv, "sid=?", arrayOf(r.sid)) == 0)
            writableDatabase.insert("ride", null, cv)
    }

    fun deleteRideBySid(sid: String) =
        writableDatabase.delete("ride", "sid=?", arrayOf(sid))

    // ── Service records / visits (the maintenance log + invoices) ────────────
    fun serviceRecords(): List<ServiceRecord> {
        val out = ArrayList<ServiceRecord>()
        readableDatabase.rawQuery(
            "SELECT id,sid,title,name,kind,scheduled_key,item_sids,item_sid,odometer_km,date_ms,cost,invoice_path,note " +
                "FROM service_record ORDER BY date_ms DESC", null,
        ).use { c ->
            while (c.moveToNext()) {
                val title = c.getString(2).ifBlank { c.getString(3) ?: "" }
                val itemSidsCsv = (c.getString(6) ?: "").ifBlank { c.getString(7) ?: "" }
                out.add(
                    ServiceRecord(
                        id = c.getLong(0), sid = c.getString(1), title = title,
                        kind = c.getString(4)?.ifBlank { "company" } ?: "company",
                        scheduledKey = c.getString(5) ?: "",
                        itemSids = itemSidsCsv.split(',').map { it.trim() }.filter { it.isNotEmpty() },
                        odometerKm = c.getInt(8), dateMs = c.getLong(9), cost = c.getDouble(10),
                        invoicePath = c.getString(11) ?: "", note = c.getString(12) ?: "",
                    )
                )
            }
        }
        return out
    }

    fun upsertServiceRecord(r: ServiceRecord) {
        val cv = ContentValues().apply {
            put("sid", r.sid); put("title", r.title); put("name", r.title)
            put("kind", r.kind); put("scheduled_key", r.scheduledKey)
            put("item_sids", r.itemSids.joinToString(",")); put("item_sid", r.itemSids.firstOrNull() ?: "")
            put("odometer_km", r.odometerKm); put("date_ms", r.dateMs); put("cost", r.cost)
            put("invoice_path", r.invoicePath); put("note", r.note)
        }
        if (writableDatabase.update("service_record", cv, "sid=?", arrayOf(r.sid)) == 0)
            writableDatabase.insert("service_record", null, cv)
    }

    fun deleteServiceRecordBySid(sid: String) =
        writableDatabase.delete("service_record", "sid=?", arrayOf(sid))

    // ── Scheduled services (manufacturer milestones + rider-added) ───────────
    fun scheduledServices(): List<ScheduledService> {
        val out = ArrayList<ScheduledService>()
        readableDatabase.rawQuery(
            "SELECT id,sid,label,target_km,target_months,free,order_idx FROM scheduled_service ORDER BY order_idx ASC, target_km ASC", null,
        ).use { c ->
            while (c.moveToNext()) out.add(
                ScheduledService(
                    id = c.getLong(0), sid = c.getString(1), label = c.getString(2),
                    targetKm = c.getInt(3), targetMonths = c.getInt(4), free = c.getInt(5) != 0, orderIdx = c.getInt(6),
                )
            )
        }
        return out
    }

    fun upsertScheduledService(s: ScheduledService) {
        val cv = ContentValues().apply {
            put("sid", s.sid); put("label", s.label); put("target_km", s.targetKm)
            put("target_months", s.targetMonths); put("free", if (s.free) 1 else 0); put("order_idx", s.orderIdx)
        }
        if (writableDatabase.update("scheduled_service", cv, "sid=?", arrayOf(s.sid)) == 0)
            writableDatabase.insert("scheduled_service", null, cv)
    }

    fun deleteScheduledServiceBySid(sid: String) =
        writableDatabase.delete("scheduled_service", "sid=?", arrayOf(sid))

    // ── Documents (Glovebox) ─────────────────────────────────────────────────
    fun documents(): List<VehicleDocument> {
        val out = ArrayList<VehicleDocument>()
        readableDatabase.rawQuery(
            "SELECT id,sid,type,title,number,issue_ms,expiry_ms,file_path,note FROM document ORDER BY expiry_ms ASC, title ASC", null,
        ).use { c ->
            while (c.moveToNext()) out.add(
                VehicleDocument(
                    id = c.getLong(0), sid = c.getString(1), type = c.getString(2), title = c.getString(3),
                    number = c.getString(4) ?: "", issueMs = c.getLong(5), expiryMs = c.getLong(6),
                    filePath = c.getString(7) ?: "", note = c.getString(8) ?: "",
                )
            )
        }
        return out
    }

    fun upsertDocument(d: VehicleDocument) {
        val cv = ContentValues().apply {
            put("sid", d.sid); put("type", d.type); put("title", d.title); put("number", d.number)
            put("issue_ms", d.issueMs); put("expiry_ms", d.expiryMs); put("file_path", d.filePath); put("note", d.note)
        }
        if (writableDatabase.update("document", cv, "sid=?", arrayOf(d.sid)) == 0)
            writableDatabase.insert("document", null, cv)
    }

    fun deleteDocumentBySid(sid: String) =
        writableDatabase.delete("document", "sid=?", arrayOf(sid))

    // ── Bike identity (single row) ───────────────────────────────────────────
    fun bikeIdentity(): BikeIdentity =
        readableDatabase.rawQuery("SELECT vin,engine_no,reg_no,purchase_ms,colour FROM bike_identity WHERE id=0", null).use {
            if (it.moveToFirst()) BikeIdentity(
                vin = it.getString(0) ?: "", engineNo = it.getString(1) ?: "", regNo = it.getString(2) ?: "",
                purchaseMs = it.getLong(3), colour = it.getString(4) ?: "",
            ) else BikeIdentity()
        }

    fun setBikeIdentity(b: BikeIdentity) {
        val cv = ContentValues().apply {
            put("vin", b.vin); put("engine_no", b.engineNo); put("reg_no", b.regNo)
            put("purchase_ms", b.purchaseMs); put("colour", b.colour)
        }
        if (writableDatabase.update("bike_identity", cv, "id=0", null) == 0) {
            cv.put("id", 0); writableDatabase.insert("bike_identity", null, cv)
        }
    }
}
