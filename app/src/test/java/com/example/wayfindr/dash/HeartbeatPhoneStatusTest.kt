package com.example.wayfindr.dash

import com.example.wayfindr.dash.protocol.DashCommands
import org.junit.Assert.assertEquals
import org.junit.Test

class HeartbeatPhoneStatusTest {
    private fun field(pkt: ByteArray, type: Int, sub: Int): Int {
        for (i in 0 until pkt.size - 4)
            if (pkt[i] == type.toByte() && pkt[i + 1] == sub.toByte() && pkt[i + 2] == 0.toByte() && pkt[i + 3] == 1.toByte())
                return pkt[i + 4].toInt() and 0xFF
        error("field %02X %02X missing".format(type, sub))
    }

    @Test fun `live phone status lands in the heartbeat fields`() {
        val p = DashCommands.heartbeat(phone = PhoneStatus(batteryPct = 8, charging = true, signal = false,
            gpsOn = false, mediaVolume = 0, callVolume = 10))
        assertEquals(108, field(p, 0x06, 0x04))
        assertEquals(0x55, field(p, 0x06, 0x0F))
        assertEquals(0x00, field(p, 0x06, 0x01))
        assertEquals(0xAA, field(p, 0x06, 0x03))
        assertEquals(0x10, field(p, 0x05, 0x4C))
        assertEquals(0x1A, field(p, 0x05, 0x1B))
    }

    @Test fun `defaults reproduce the old captured heartbeat`() {
        val p = DashCommands.heartbeat()
        assertEquals(0xA2, field(p, 0x06, 0x04))
        assertEquals(0xAA, field(p, 0x06, 0x0F))
        assertEquals(0x01, field(p, 0x06, 0x01))
        assertEquals(0x55, field(p, 0x06, 0x03))
        assertEquals(0x13, field(p, 0x05, 0x4C))
        assertEquals(0x19, field(p, 0x05, 0x1B))
    }

    @Test fun `volume bucketing gives 0 to 10`() {
        assertEquals(0, PhoneStatus.volumeLevel(0, 0, 15))
        assertEquals(1, PhoneStatus.volumeLevel(1, 0, 15))
        assertEquals(10, PhoneStatus.volumeLevel(15, 0, 15))
        assertEquals(5, PhoneStatus.volumeLevel(7, 0, 15))
    }
}
