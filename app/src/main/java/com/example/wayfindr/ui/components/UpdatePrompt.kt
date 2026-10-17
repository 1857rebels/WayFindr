package com.example.wayfindr.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.wayfindr.data.UpdateChecker
import com.example.wayfindr.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Drop-in overlay: checks GitHub Releases once when the app opens and, if a newer version is
 * out, shows a dialog with the changelog + a one-tap Update (download → system installer).
 * Renders nothing until/unless an update is found. Safe to place at the app root.
 */
@Composable
fun UpdatePrompt() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var release by remember { mutableStateOf<UpdateChecker.ReleaseInfo?>(null) }
    var dismissed by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var failed by remember { mutableStateOf(false) }

    // One background check per app open. Offer by APK checksum when the release publishes one,
    // else by version (see UpdateChecker.shouldOffer) — so the prompt reflects the actual installed
    // build, not just the version string.
    LaunchedEffect(Unit) {
        val latest = withContext(Dispatchers.IO) { UpdateChecker.fetchLatest(context) }
        if (latest != null && UpdateChecker.shouldOffer(context, latest)) {
            release = latest
        }
    }

    val r = release ?: return
    if (dismissed) return

    AlertDialog(
        onDismissRequest = { if (!downloading) dismissed = true },
        containerColor = Surf1,
        titleContentColor = TextHi,
        textContentColor = TextMid,
        title = {
            Text(
                "Update available",
                color = TextHi,
                fontWeight = FontWeight.Bold,
                fontFamily = GeistFamily,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "WayfindR ${r.versionName}",
                    color = Gold,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = GeistFamily,
                )
                if (r.notes.isNotBlank()) {
                    Column(
                        Modifier
                            .heightIn(max = 280.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            prettifyNotes(r.notes),
                            color = TextMid,
                            fontSize = 13.sp,
                            lineHeight = 19.sp,
                            fontFamily = GeistFamily,
                        )
                    }
                }
                if (downloading) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.padding(top = 2.dp),
                        color = Gold,
                        trackColor = Line,
                    )
                    Text(
                        if (progress > 0f) "Downloading… ${(progress * 100).toInt()}%" else "Downloading…",
                        color = TextLo,
                        fontSize = 12.sp,
                        fontFamily = GeistFamily,
                    )
                }
                if (failed) {
                    Text(
                        "Download failed — opening the Releases page instead.",
                        color = Warn,
                        fontSize = 12.sp,
                        fontFamily = GeistFamily,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !downloading,
                onClick = {
                    scope.launch {
                        downloading = true
                        failed = false
                        progress = 0f
                        val file = withContext(Dispatchers.IO) {
                            UpdateChecker.download(context, r.apkUrl) { p -> progress = p }
                        }
                        downloading = false
                        if (file != null) {
                            // Returns false if it had to send the user to the unknown-sources
                            // setting first; keep the dialog up so they can tap Update again.
                            if (UpdateChecker.install(context, file)) dismissed = true
                        } else {
                            failed = true
                            UpdateChecker.openReleasesPage(context)
                        }
                    }
                },
            ) {
                Text(
                    if (downloading) "Downloading…" else "Update",
                    color = if (downloading) TextLo else Gold,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = GeistFamily,
                )
            }
        },
        dismissButton = {
            TextButton(
                enabled = !downloading,
                onClick = { dismissed = true },
            ) {
                Text("Later", color = TextLo, fontFamily = GeistFamily)
            }
        },
    )
}

/** Light touch-up of the GitHub markdown body so it reads cleanly as plain text. */
private fun prettifyNotes(md: String): String =
    md.lineSequence()
        .joinToString("\n") { line ->
            line.trimEnd()
                .removePrefix("### ").removePrefix("## ").removePrefix("# ")
                .replace(Regex("^\\s*[-*] "), "• ")
                .replace("**", "")
        }
        .trim()
