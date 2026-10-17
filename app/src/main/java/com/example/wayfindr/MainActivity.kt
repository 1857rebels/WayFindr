package com.example.wayfindr

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.example.wayfindr.data.SyncRepository
import com.example.wayfindr.ui.navigation.AppNavigation
import com.example.wayfindr.ui.theme.Bg1
import com.example.wayfindr.ui.theme.WayfindrTheme
import com.example.wayfindr.viewmodel.RouteViewModel

class MainActivity : ComponentActivity() {
    private val routeViewModel: RouteViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val sync = SyncRepository.get(applicationContext)

        // Maintenance + document-expiry reminders on app open (fire even if Garage is never
        // opened). Only on a fresh launch — savedInstanceState != null means this is a config
        // change (e.g. rotation), where re-running it is wasted work.
        if (savedInstanceState == null) {
            Thread {
                com.example.wayfindr.data.MaintenanceNotifier.check(
                    applicationContext, sync.maintenanceItems(), sync.odometer().toInt()
                )
                com.example.wayfindr.data.MaintenanceNotifier.checkDocuments(applicationContext, sync.documents())
            }.start()
        }

        handleIntent(intent)
        setContent {
            WayfindrTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Bg1) {
                    AppNavigation(routeViewModel = routeViewModel)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_SEND -> {
                if (intent.type == "text/plain") {
                    val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
                    routeViewModel.handleSharedText(text)
                }
            }
            Intent.ACTION_VIEW -> {
                val uri = intent.data?.toString() ?: return
                routeViewModel.handleSharedText(uri)
            }
        }
    }
}
