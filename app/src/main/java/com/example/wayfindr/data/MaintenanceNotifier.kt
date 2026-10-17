package com.example.wayfindr.data

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * Posts a reminder when a maintenance item comes due. There's no continuous background
 * service (lean, single-user app), so this is called whenever the data changes —
 * on app open and after any odometer/service edit (see [GarageViewModel] + MainActivity).
 *
 * De-dupe: we remember which items we've already flagged as due, so we only buzz when a
 * NEW item crosses into due — not on every reload. Servicing an item clears it, so it can
 * remind again next interval.
 */
object MaintenanceNotifier {

    private const val CHANNEL_ID = "maintenance"
    private const val NOTIF_ID = 4201
    private const val PREFS = "maint_notify"
    private const val KEY_NOTIFIED = "notified_sids"

    private const val MS_PER_MONTH = 2_629_800_000.0   // average month in ms

    /**
     * "Due" once within the last 25% of EITHER the km or the months interval (whichever is
     * earlier — matching the manual and the UI warn/alert tone).
     */
    private fun isDue(m: MaintenanceItem, odo: Int): Boolean {
        val kmDue = m.intervalKm > 0 && (m.lastDoneOdoKm + m.intervalKm - odo) < m.intervalKm * 0.25
        val moElapsed = (System.currentTimeMillis() - m.lastDoneDateMs) / MS_PER_MONTH
        val moDue = m.intervalMonths > 0 && moElapsed > m.intervalMonths * 0.75
        return kmDue || moDue
    }

    // notify() is guarded by areNotificationsEnabled() (reflects the POST_NOTIFICATIONS grant
    // on 33+) and wrapped in runCatching — lint can't see those, so suppress the false positive.
    @SuppressLint("MissingPermission")
    fun check(context: Context, items: List<MaintenanceItem>, odometer: Int) {
        val ctx = context.applicationContext
        val due = items.filter { isDue(it, odometer) }
        val dueSids = due.map { it.sid }.toSet()

        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val notified = prefs.getStringSet(KEY_NOTIFIED, emptySet()) ?: emptySet()
        // Drop serviced items from the remembered set so they can remind again next interval,
        // but keep still-due items we've ALREADY flagged. Crucially, don't mark newly-due items
        // as notified until we actually post — otherwise a reminder is lost when notifications
        // are off, and never fires once they're turned on.
        val stillKnownDue = notified intersect dueSids

        val newlyDue = dueSids - notified
        if (newlyDue.isEmpty()) { prefs.edit().putStringSet(KEY_NOTIFIED, stillKnownDue).apply(); return }

        ensureChannel(ctx)
        if (NotificationManagerCompat.from(ctx).areNotificationsEnabled().not()) {
            prefs.edit().putStringSet(KEY_NOTIFIED, stillKnownDue).apply()   // not flagged → can fire later
            return
        }
        prefs.edit().putStringSet(KEY_NOTIFIED, dueSids).apply()   // posting now → remember all due

        val (title, text) = buildText(due, odometer)
        val tap = PendingIntent.getActivity(
            ctx, 0,
            ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notif = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_my_calendar)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(NOTIF_ID, notif) }
    }

    private fun buildText(due: List<MaintenanceItem>, odo: Int): Pair<String, String> {
        fun line(m: MaintenanceItem): String {
            val kmFrac = if (m.intervalKm > 0) (odo - m.lastDoneOdoKm).toDouble() / m.intervalKm else null
            val moElapsed = (System.currentTimeMillis() - m.lastDoneDateMs) / MS_PER_MONTH
            val moFrac = if (m.intervalMonths > 0) moElapsed / m.intervalMonths else null
            // Report whichever dimension is nearer to (or further past) due.
            val kmDom = (kmFrac ?: -1.0) >= (moFrac ?: -1.0)
            return when {
                kmFrac != null && kmDom -> {
                    val rem = m.lastDoneOdoKm + m.intervalKm - odo
                    if (rem < 0) "${m.name} — overdue ${-rem} km" else "${m.name} — due in $rem km"
                }
                moFrac != null -> {
                    val rem = m.intervalMonths - moElapsed
                    if (rem < 0) "${m.name} — overdue ${ceilInt(-rem)} mo" else "${m.name} — due in ${ceilInt(rem)} mo"
                }
                else -> m.name
            }
        }
        return if (due.size == 1) "Maintenance due" to line(due.first())
        else "${due.size} services due" to due.joinToString("\n") { line(it) }
    }

    private fun ceilInt(x: Double): Int = kotlin.math.ceil(x).toInt()

    // ── Document expiry (Glovebox) ───────────────────────────────────────────
    private const val DOC_NOTIF_ID = 4202
    private const val DOC_KEY_NOTIFIED = "doc_notified_sids"
    private const val DOC_WARN_DAYS = 14L
    private const val DAY_MS = 86_400_000L

    /** Notify when an expiry-bearing document (insurance/PUC/licence/RSA) is within 14 days of — or past — expiry. */
    @SuppressLint("MissingPermission")  // see check(): notify() is guarded + runCatching-wrapped
    fun checkDocuments(context: Context, docs: List<com.example.wayfindr.data.VehicleDocument>) {
        val ctx = context.applicationContext
        val now = System.currentTimeMillis()
        val soon = docs.filter { it.expiryMs > 0 && it.expiryMs - now < DOC_WARN_DAYS * DAY_MS }
        val ids = soon.map { it.sid }.toSet()

        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val notified = prefs.getStringSet(DOC_KEY_NOTIFIED, emptySet()) ?: emptySet()
        val stillKnown = notified intersect ids
        val newly = ids - notified
        if (newly.isEmpty()) { prefs.edit().putStringSet(DOC_KEY_NOTIFIED, stillKnown).apply(); return }

        ensureChannel(ctx)
        if (NotificationManagerCompat.from(ctx).areNotificationsEnabled().not()) {
            prefs.edit().putStringSet(DOC_KEY_NOTIFIED, stillKnown).apply(); return
        }
        prefs.edit().putStringSet(DOC_KEY_NOTIFIED, ids).apply()

        fun line(d: com.example.wayfindr.data.VehicleDocument): String {
            val days = ((d.expiryMs - now) / DAY_MS)
            return when {
                days < 0  -> "${d.title} — expired ${-days} day${if (-days == 1L) "" else "s"} ago"
                days == 0L -> "${d.title} — expires today"
                else      -> "${d.title} — expires in $days day${if (days == 1L) "" else "s"}"
            }
        }
        val (title, text) = if (soon.size == 1) "Document expiring" to line(soon.first())
            else "${soon.size} documents expiring" to soon.joinToString("\n") { line(it) }
        val tap = PendingIntent.getActivity(
            ctx, 0,
            ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notif = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_my_calendar)
            .setContentTitle(title).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(tap).setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT).build()
        runCatching { NotificationManagerCompat.from(ctx).notify(DOC_NOTIF_ID, notif) }
    }

    private fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Maintenance reminders", NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = "Alerts when a service interval is due" }
            )
        }
    }
}
