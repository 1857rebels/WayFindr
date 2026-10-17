package com.example.wayfindr

import android.app.Application
import com.example.wayfindr.util.BuildId
import com.example.wayfindr.util.CrashGuard
import com.example.wayfindr.util.ExitInfoCollector

class WayfindrApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        com.example.wayfindr.data.ApiUsage.init(this)
        // Load dash settings now so the routing choice (Google / free) is applied before any route.
        com.example.wayfindr.dash.DashConfig.get(this)
        com.example.wayfindr.dash.nav.OfflineRouter.init(this)
        com.example.wayfindr.dash.nav.OfflineRouter.registerDebugProbe(this)
        // Hash the installed APK off-thread so the build identity is ready to stamp into ride logs.
        BuildId.warm(this)
        // Uncaught exceptions are written to a local diag/crash-*.log before the process dies.
        CrashGuard.install(this)
        // Capture WHY we died last time — native crashes, ANRs, OEM kills — which CrashGuard's JVM
        // handler can't see. Writes a crash-exit-*.log for any new abnormal exit, picked up below.
        // arm() tags this process with its build sha so the NEXT crash is attributed to the build
        // that actually ran, not whatever is installed when we read it back; collect() reads prior exits.
        ExitInfoCollector.arm(this)
        ExitInfoCollector.collect(this)
        // Sleep after 10 min with no screen and no dash link. It also re-binds the notification
        // listener whenever the app is opened (a reinstall leaves it unbound).
        com.example.wayfindr.util.IdleSleep.init(this)
    }
}
