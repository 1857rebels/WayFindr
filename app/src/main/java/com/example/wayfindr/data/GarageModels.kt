package com.example.wayfindr.data

/** A single fuel fill-up. Mileage (km/l) is derived from the odometer gap to the prior fill. */
data class FuelFillup(
    val id: Long = 0,
    val dateMs: Long,
    val litres: Double,
    val cost: Double,
    val odometerKm: Int,
    val location: String = "",
    val sid: String = "",       // stable record id (UUID)
)

/**
 * One recorded ride = one connect→disconnect session with the dash. Stats are computed
 * from the GPS track as it streams; [trackPolyline] is the encoded path (for the map
 * snapshot on RidesScreen).
 */
data class Ride(
    val id: Long = 0,
    val startMs: Long,
    val endMs: Long,
    val distanceMeters: Double,
    val durationSec: Long,
    val avgSpeedMps: Double,
    val maxSpeedMps: Double,
    val trackPolyline: String = "",   // Google/OSRM-encoded lat/lng path
    val startLat: Double = 0.0,
    val startLng: Double = 0.0,
    val endLat: Double = 0.0,
    val endLng: Double = 0.0,
    val sid: String = "",             // stable record id (UUID)
) {
    val avgSpeedKmh: Double get() = avgSpeedMps * 3.6
    val maxSpeedKmh: Double get() = maxSpeedMps * 3.6
    val distanceKm: Double get() = distanceMeters / 1000.0
}

/**
 * A recurring maintenance item with its interval and when it was last serviced.
 * Intervals follow the CR manual's "km OR months, whichever is earlier" rule:
 * [intervalKm] = 0 means time-only (e.g. brake fluid), [intervalMonths] = 0 means km-only.
 */
data class MaintenanceItem(
    val id: Long = 0,
    val name: String,
    val iconKey: String,        // "chain" | "drop" | "wrench" | "gauge" | "thermo" | "fuel"
    val intervalKm: Int,
    val lastDoneOdoKm: Int,
    val lastDoneDateMs: Long,
    val intervalMonths: Int = 0,   // 0 = no time interval; otherwise due after this many months too
    val sid: String = "",       // stable record id (UUID)
)

/**
 * One service VISIT = a row in the log. A single visit can cover MANY maintenance items at once
 * (a company service does oil + air filter + chain together), or be a one-off DIY job. Saving a
 * visit resets the countdown on every item in [itemSids]. [scheduledKey] links it to a
 * manufacturer milestone (see [ScheduledService]) when it fulfils one. Captures cost + an
 * optional uploaded invoice (image/PDF in app-private storage).
 */
data class ServiceRecord(
    val id: Long = 0,
    val sid: String = "",
    val title: String,                          // "1st free service" | "Chain clean" | "General service"
    val kind: String = "company",               // company | diy | other
    val scheduledKey: String = "",              // ScheduledService.sid this fulfils, or ""
    val itemSids: List<String> = emptyList(),   // maintenance items this visit reset
    val odometerKm: Int,
    val dateMs: Long,
    val cost: Double = 0.0,
    val invoicePath: String = "",               // local path to the uploaded bill/invoice, or ""
    val note: String = "",
)

/**
 * A manufacturer (or rider-added) scheduled service milestone — e.g. the 4 free services at
 * 500 / 5,000 / 10,000 / 15,000 km. "Availed" is derived: a [ServiceRecord] whose scheduledKey
 * equals this row's sid marks it done.
 */
data class ScheduledService(
    val id: Long = 0,
    val sid: String = "",
    val label: String,        // "1st service"
    val targetKm: Int,        // odometer milestone (0 = none)
    val targetMonths: Int,    // time milestone in months (0 = none)
    val free: Boolean = false,
    val orderIdx: Int = 0,
)

/** A stored vehicle document (insurance, PUC, RC, licence, invoice, warranty, RSA…). */
data class VehicleDocument(
    val id: Long = 0,
    val sid: String = "",
    val type: String,         // insurance | puc | rc | licence | invoice | warranty | rsa | other
    val title: String,
    val number: String = "",
    val issueMs: Long = 0L,
    val expiryMs: Long = 0L,  // 0 = no expiry
    val filePath: String = "",
    val note: String = "",
)

/** Quick-reference identity for the bike (asked for at service/insurance/RTO). Single row. */
data class BikeIdentity(
    val vin: String = "",
    val engineNo: String = "",
    val regNo: String = "",
    val purchaseMs: Long = 0L,
    val colour: String = "",
)
