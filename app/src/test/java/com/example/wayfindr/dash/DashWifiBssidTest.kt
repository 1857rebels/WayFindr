package com.example.wayfindr.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DashWifiBssidTest {
    @Test fun `real MAC is kept, lower-cased`() {
        assertEquals("aa:bb:cc:dd:ee:01", DashWifiManager.usableBssid(" AA:BB:CC:DD:EE:01 "))
    }

    @Test fun `hidden or malformed BSSIDs are rejected`() {
        assertNull(DashWifiManager.usableBssid("02:00:00:00:00:00"))
        assertNull(DashWifiManager.usableBssid("00:00:00:00:00:00"))
        assertNull(DashWifiManager.usableBssid("a4cf123b9e01"))
        assertNull(DashWifiManager.usableBssid(""))
        assertNull(DashWifiManager.usableBssid(null))
    }
}
