package com.example.wayfindr.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * In-app updater. On app open we ask GitHub for the latest release; if its version is newer
 * than what's installed, the UI shows a dialog with the changelog and a one-tap "Update" that
 * downloads the APK and hands it to the system package installer.
 *
 * No backend, no Play Store — just the public GitHub Releases API. Because every release is
 * signed with the same key (see signing.properties / build.gradle.kts), the downloaded APK
 * installs straight over the existing app, keeping the user's data.
 */
object UpdateChecker {

    private const val TAG = "UpdateChecker"
    /** GitHub release check on app start. Off for now. */
    private const val CHECK_ENABLED = false
    // The repo whose Releases we track. Keep in sync with the README download link.
    private const val LATEST_RELEASE_API =
        "https://api.github.com/repos/1857rebels/WayFindr/releases/latest"
    private const val RELEASES_PAGE =
        "https://github.com/1857rebels/WayFindr/releases/latest"

    data class ReleaseInfo(
        val versionName: String,   // tag without a leading "v", e.g. "1.2"
        val title: String,         // release name, falls back to the tag
        val notes: String,         // release body (markdown changelog)
        val apkUrl: String,        // browser_download_url of the .apk asset
        val apkSize: Long,         // bytes, 0 if unknown
        val sha256: String = "",   // 12-hex APK checksum, if the release notes publish one (see [shouldOffer])
    )

    /** True in the full edition (with the notification listener); its APK name ends in "-full.apk". */
    private val FULL = com.example.wayfindr.BuildConfig.MEDIA_LISTENER

    /**
     * Pull this edition's published APK checksum out of release notes: a line "sha256: 1caad8bed911"
     * for the standard APK, "sha256-full: …" for the full one.
     */
    private fun parseSha(body: String): String =
        Regex(if (FULL) "sha-?256-full[:\\s]+([0-9a-fA-F]{12,64})" else "sha-?256(?!-full)[:\\s]+([0-9a-fA-F]{12,64})",
            RegexOption.IGNORE_CASE)
            .find(body)?.groupValues?.get(1)?.lowercase()?.take(12).orEmpty()

    /**
     * Decide whether to offer [remote] to the user — by CHECKSUM when the release publishes one
     * (offer iff the running APK's hash differs), else by version. Checksum is exact and
     * self-correcting: the prompt vanishes the moment the matching APK is installed, regardless of
     * how it got there. Mirrors the test channel ([com.example.wayfindr.data.TestBuildChecker]).
     */
    fun shouldOffer(context: Context, remote: ReleaseInfo): Boolean {
        val mine = com.example.wayfindr.util.BuildId.sha12(context)
        return if (remote.sha256.isNotBlank() && mine != "unknown") mine != remote.sha256
        else isNewer(remote.versionName, currentVersionName(context))
    }

    /** The version string the app was built with (from the installed package). */
    fun currentVersionName(context: Context): String =
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"
        }.getOrDefault("0")

    /** Fetches the latest published (non-draft, non-prerelease) release. Null on any failure. */
    fun fetchLatest(context: Context): ReleaseInfo? = runCatching {
        // Paused at the rider's request (2026-10-16): no GitHub call, so no update prompt.
        // Set CHECK_ENABLED = true to turn it back on.
        if (!CHECK_ENABLED) return null
        ApiUsage.record(ApiUsage.UPDATE_CHECK)
        val conn = (URL(LATEST_RELEASE_API).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8000
            readTimeout = 8000
            // GitHub's API rejects requests without a User-Agent.
            setRequestProperty("User-Agent", "WayfindR-App")
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        try {
            if (conn.responseCode != 200) {
                Log.w(TAG, "release check HTTP ${conn.responseCode}")
                return null
            }
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            if (json.optBoolean("draft") || json.optBoolean("prerelease")) return null

            val tag = json.optString("tag_name").ifBlank { return null }
            val versionName = tag.removePrefix("v").removePrefix("V").trim()

            // Find this edition's .apk asset (full: "...-full.apk"; standard: any other .apk).
            val assets = json.optJSONArray("assets")
            var apkUrl = ""
            var apkSize = 0L
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    val name = a.optString("name")
                    if (name.endsWith(".apk", ignoreCase = true) &&
                        name.endsWith("-full.apk", ignoreCase = true) == FULL) {
                        apkUrl = a.optString("browser_download_url")
                        apkSize = a.optLong("size", 0L)
                        break
                    }
                }
            }
            if (apkUrl.isBlank()) return null

            val body = json.optString("body").trim()
            ReleaseInfo(
                versionName = versionName,
                title = json.optString("name").ifBlank { tag },
                notes = body,
                apkUrl = apkUrl,
                apkSize = apkSize,
                sha256 = parseSha(body),
            )
        } finally {
            conn.disconnect()
        }
    }.onFailure { Log.w(TAG, "release check failed: ${it.message}") }.getOrNull()

    /** True if [remote] is a strictly higher version than [installed] (dotted-number compare). */
    fun isNewer(remote: String, installed: String): Boolean {
        fun parts(v: String): List<Int> =
            v.trim().split('.', '-', '+')
                .mapNotNull { seg -> seg.takeWhile { it.isDigit() }.toIntOrNull() }
        val r = parts(remote)
        val i = parts(installed)
        if (r.isEmpty()) return false
        for (idx in 0 until maxOf(r.size, i.size)) {
            val rv = r.getOrElse(idx) { 0 }
            val iv = i.getOrElse(idx) { 0 }
            if (rv != iv) return rv > iv
        }
        return false
    }

    /**
     * Downloads the APK into cacheDir/updates and returns the file. [onProgress] reports
     * 0f..1f when the total size is known (otherwise stays at 0). Null on failure.
     */
    fun download(context: Context, url: String, onProgress: (Float) -> Unit): File? = runCatching {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        // Reuse a single filename so we never pile up old downloads.
        val out = File(dir, "wayfindr-update.apk")
        if (out.exists()) out.delete()

        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000
            readTimeout = 30000
            setRequestProperty("User-Agent", "WayfindR-App")
            instanceFollowRedirects = true
        }
        try {
            if (conn.responseCode !in 200..299) {
                Log.w(TAG, "download HTTP ${conn.responseCode}")
                return null
            }
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var read: Int
                    var done = 0L
                    while (input.read(buf).also { read = it } != -1) {
                        output.write(buf, 0, read)
                        done += read
                        if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
            out
        } finally {
            conn.disconnect()
        }
    }.onFailure { Log.w(TAG, "download failed: ${it.message}") }.getOrNull()

    /**
     * Hands the downloaded APK to the system package installer. On Android O+ the app first
     * needs the per-app "install unknown apps" grant — if it's missing we send the user to
     * that settings screen instead, and they tap Update again afterwards.
     *
     * @return false if we redirected to the unknown-sources settings (install not yet started).
     */
    fun install(context: Context, file: File): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            runCatching {
                context.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${context.packageName}"),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            return false
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }.onFailure { Log.w(TAG, "install intent failed: ${it.message}") }
        return true
    }

    /** Fallback: open the Releases page in a browser if the in-app download/install fails. */
    fun openReleasesPage(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES_PAGE))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
