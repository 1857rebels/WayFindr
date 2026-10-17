package com.example.wayfindr.dash.protocol

import java.io.ByteArrayOutputStream

/**
 * K1G commands ported from better-dash (Console_app_like_nav.py),
 * which was reconstructed from the dash + packet captures.
 */
object DashCommands {

    // ── Auth ──────────────────────────────────────────────────────────────
    /** q3c.e — "request auth / send me your RSA public key". */
    fun authRequest() = "0016000200000000020100054b314720000804000101".hexToBytes()

    /** q3c.d — RSA-encrypted (SSID ‖ AES-256 key). Ciphertext must be 128 B. */
    fun authSendKey(ciphertext: ByteArray): ByteArray {
        require(ciphertext.size == 128) { "q3c.d expects 128B RSA ciphertext, got ${ciphertext.size}" }
        return "0095000200000000020100054B3147200008000080".hexToBytes() + ciphertext
    }

    // ── Initial burst (sent right after the socket opens) ─────────────────
    // The dash only answers with its RSA pubkey on UDP/2002 after seeing
    // this burst on UDP/2000. Order and content from INITIAL_BURST_HEX.
    fun initialBurst(hostname: String): List<ByteArray> = listOf(
        authRequest(),
        hostnameAnnounce(hostname),
        timeSync(),
        "0016000200000000020100054b314720030557000155".hexToBytes(),
        "0016000200000000020100054b3147200405560001aa".hexToBytes(),
        "0016000200000000020100054b3147200506050001aa".hexToBytes(),
        "0016000200000000020100054b3147200605170001aa".hexToBytes(),
        "001d000200000000020100054b314720080a020008aa55000000000000".hexToBytes(),
        ("0044000a00000000020100054b3147200906080001ff060300015506040001a2060f0001aa" +
         "0601000101054c000113052d00020000051b0001190521000132054d000132").hexToBytes(),
    )

    /**
     * 06 06 — time-of-day sync (hour, minute, second). The dash has no clock source
     * of its own: it shows whatever the phone last fed it, so the dash keeps
     * feeding it. better-dash replays a hardcoded capture time (0x0e 0x33 0x34 =
     * 14:51:52) once, which left the dash clock wrong AND frozen. Build it from the
     * real clock; DashSession re-sends it every 30 s.
     */
    fun timeSync(): ByteArray {
        val cal = java.util.Calendar.getInstance()
        return K1GPacket.build(
            K1GPacket.tlv(
                0x06, 0x06,
                cal.get(java.util.Calendar.HOUR_OF_DAY),
                cal.get(java.util.Calendar.MINUTE),
                cal.get(java.util.Calendar.SECOND),
            )
        )
    }

    /** Bluconnect announce — device name shown on the dash's "Connected to X" screen. */
    fun hostnameAnnounce(hostname: String): ByteArray {
        val raw = hostname.toByteArray(Charsets.UTF_8).let {
            if (it.size > 200) it.copyOf(200) else it
        }
        val out = ByteArrayOutputStream()
        out.write("0021000200000000020100054b314720".hexToBytes())
        out.write(byteArrayOf(0x01, 0x06, 0x0B, 0x00, (raw.size + 1).toByte()))
        out.write(raw)
        out.write(0x00)
        val bytes = out.toByteArray()
        bytes[0] = ((bytes.size shr 8) and 0xFF).toByte()
        bytes[1] = (bytes.size and 0xFF).toByte()
        return bytes
    }

    // ── Navigation mode ────────────────────────────────────────────────────
    /** q3c.q — nav context. */
    fun navContext() = "0016000200000000020100054B31472000052E00011E".hexToBytes()

    /** q3c.r — empty favourite lists. */
    fun emptyLists() =
        "002A000600000000020100054B31472000052F0001000530000100053100010005320001000533000100"
            .hexToBytes()

    /** q3c.z2 — start navigation. Send ONCE, after the route card. */
    fun navStart() = "0016000200000000020100054B31472000068000010B".hexToBytes()

    /** q3c.t8 placeholder sent between projection-frame and z2 in the phone's capture. */
    fun navPlaceholder() = K1GPacket.build(K1GPacket.tlv(0x06, 0x0A, 0x00, 0x00))

    // ── Projection control ────────────────────────────────────────────────
    /** q3c.g — projection keep-alive; MUST repeat at the encoder frame rate (4 Hz). */
    fun projectionFrame() = "0016000200000000020100054B314720000556000155".hexToBytes()
    fun projectionOn()    = "0016000200000000020100054B314720000605000155".hexToBytes()
    fun projectionStop()  = "0016000200000000020100054B3147200005560001AA".hexToBytes()
    fun projectionOff()   = "0016000200000000020100054B3147200006050001AA".hexToBytes()

    // ── Frame-decoded acknowledgements ────────────────────────────────────
    /** q3c.L2 — mandatory reply to the dash's 09 06 55 per-IDR "frame decoded" notify. */
    fun frameDecodedIdr() = "0016000200000000020100054B314720000611000155".hexToBytes()
    /** q3c.K2 — reply to 09 04 55. */
    fun frameDecodedP()   = "0016000200000000020100054B314720000612000155".hexToBytes()

    // ── Button / event acknowledgement: echo the code back in 06 80 ───────
    fun buttonAck(code: Byte) =
        K1GPacket.build(K1GPacket.tlv(0x06, 0x80, code.toInt() and 0xFF))

    // Joystick codes seen in the 09 00 event family (clk.D/E/F/c0/d0/u)
    const val BTN_05: Byte = 0x05
    const val BTN_06: Byte = 0x06
    const val BTN_07: Byte = 0x07
    const val BTN_09: Byte = 0x09
    const val BTN_0A: Byte = 0x0A
    const val BTN_22: Byte = 0x22

    // ── 1 Hz status heartbeat (0049, fixed temp) ──────────────────────────
    private val HB_0049 =
        ("0049000b00000000020100054b3147200006080001050610000139060300015506040001a2060f0001aa" +
         "0601000101054c000113052d00020000051b0001190521000132054d000132").hexToBytes()

    /**
     * d.run() heartbeat — on-wire temp byte = °C + 40.
     * [headingDeg] fills 05 2D (u16 degrees, 0 = north): the heading the dash's native COMPASS
     * view shows. The original app puts its GPS bearing here (REForeGroundService, hbg.q2);
     * the captured template had it fixed at 0, so the compass always pointed north.
     */
    fun heartbeat(tempC: Int = 25, headingDeg: Int = 0,
                  phone: com.example.wayfindr.dash.PhoneStatus = com.example.wayfindr.dash.PhoneStatus()): ByteArray {
        val pkt = HB_0049.copyOf()
        // Phone status-bar fields (original app's REForeGroundService.d.run()).
        fun put(type: Int, sub: Int, v: Int) {
            val at = indexOf(pkt, byteArrayOf(type.toByte(), sub.toByte(), 0x00, 0x01))
            if (at >= 0 && at + 4 < pkt.size) pkt[at + 4] = (v and 0xFF).toByte()
        }
        put(0x06, 0x04, phone.batteryPct.coerceIn(0, 100) + 100)
        put(0x06, 0x0F, if (phone.charging) 0x55 else 0xAA)
        put(0x06, 0x01, if (phone.signal) 0x01 else 0x00)
        put(0x06, 0x03, if (phone.gpsOn) 0x55 else 0xAA)
        put(0x05, 0x4C, 0x10 + phone.mediaVolume.coerceIn(0, 10))
        put(0x05, 0x1B, 0x10 + phone.callVolume.coerceIn(0, 10))
        val marker = byteArrayOf(0x06, 0x10, 0x00, 0x01)
        val i = indexOf(pkt, marker)
        if (i >= 0 && i + 4 < pkt.size) pkt[i + 4] = ((tempC + 40) and 0xFF).toByte()
        val h = indexOf(pkt, byteArrayOf(0x05, 0x2D, 0x00, 0x02))
        val deg = ((headingDeg % 360) + 360) % 360
        if (h >= 0 && h + 5 < pkt.size) {
            pkt[h + 4] = ((deg shr 8) and 0xFF).toByte()
            pkt[h + 5] = (deg and 0xFF).toByte()
        }
        return pkt
    }

    /** Dash event code for "open compass" (09 00 00 01 22, original app's hbg.p2). */
    const val EVT_COMPASS: Byte = 0x22

    /** 05 56 = 55: GPS available — the original app sends this (hbg.g) when the dash opens
     *  its compass view and location is on. Our 06 80 22 echo is its other reply (hbg.J2). */
    fun gpsAvailable() = "0016000200000000020100054B314720000556000155".hexToBytes()

    // ── Route card (0x007E) ───────────────────────────────────────────────
    // Template captured from the dash: K1G header + TLV(05,01,title+NUL)
    // + a fixed suffix of sub-TLVs. The byte after marker 06 05 00 01 is the
    // projection flag (0x55 on / 0xAA off).
    private val NAV_TEMPLATE =
        ("007e001100000000020100054b31472025050100145461696c6c65206465204d617320647520477200" +
         "050200013c050300013405050002000a05060001300507000130050800043033303305540001300509" +
         "0002004f0546000110050a000155050c000104050b0006303031303030055500012006050001aa060d0001aa")
            .hexToBytes()

    private val navPrefix: ByteArray  // header up to (not incl.) the seq byte
    private val navSuffix: ByteArray  // everything after the title TLV

    init {
        val magic = indexOf(NAV_TEMPLATE, "4b314720".hexToBytes())
        require(magic >= 0) { "K1G marker missing in nav template" }
        val seqOff = magic + 4
        val titleLen = ((NAV_TEMPLATE[seqOff + 3].toInt() and 0xFF) shl 8) or
                        (NAV_TEMPLATE[seqOff + 4].toInt() and 0xFF)
        navPrefix = NAV_TEMPLATE.copyOfRange(0, seqOff)
        navSuffix = NAV_TEMPLATE.copyOfRange(seqOff + 5 + titleLen, NAV_TEMPLATE.size)
    }

    /**
     * Full 0x007E route card. Must be sent BEFORE z2 (sets the destination the
     * dash needs to open its decoder), then re-sent at ~1 Hz while streaming
     * or the dash's destination watchdog tears the decoder down after ~15 s.
     *
     * The template is a capture of a REAL French route, so its nav fields carry
     * that ride's values — total distance 0x004F (79 km-tenths = "7.9 km"),
     * glyph 0x3C, ETA "03:03". Re-sending them unpatched at 1 Hz stomps the live
     * figures from [activeNavPacket]. Pass the live values to overwrite them
     * (field meanings per better-dash: 0502 glyph t3c.g, 0506 unit t3c.j,
     * 0508 ETA HH:MM ASCII, 0509 total t3c.q, 0546 total unit t3c.r).
     */
    fun routeCard(
        title: String,
        projectionOn: Boolean = false,
        maneuver: Int? = null,
        primaryUnit: Int? = null,
        totalDist: Int? = null,
        totalUnit: Int? = null,
        etaHHMM: String? = null,   // 4 ASCII digits, e.g. "1845"
        secondary: Int? = null,
        secondaryDist: Int? = null,
        secondaryUnit: Int? = null,
    ): ByteArray {
        val rt = title.toByteArray(Charsets.UTF_8).let {
            if (it.size > 60) it.copyOf(60) else it
        } + 0x00.toByte()

        val out = ByteArrayOutputStream()
        out.write(navPrefix)
        out.write(0x00)                                  // seq, patched at send
        out.write(byteArrayOf(0x05, 0x01))
        out.write((rt.size shr 8) and 0xFF)
        out.write(rt.size and 0xFF)
        out.write(rt)
        out.write(navSuffix)

        val bytes = out.toByteArray()
        // All patched fields live in the suffix (after the title), so search from
        // the end — the title text can never collide with a marker that way.
        fun patch1(t: Int, s: Int, v: Int) {
            val m = indexOf(bytes, byteArrayOf(t.toByte(), s.toByte(), 0x00, 0x01), fromEnd = true)
            if (m >= 0 && m + 4 < bytes.size) bytes[m + 4] = (v and 0xFF).toByte()
        }
        fun patch2(t: Int, s: Int, v: Int) {
            val m = indexOf(bytes, byteArrayOf(t.toByte(), s.toByte(), 0x00, 0x02), fromEnd = true)
            if (m >= 0 && m + 5 < bytes.size) {
                bytes[m + 4] = ((v shr 8) and 0xFF).toByte()
                bytes[m + 5] = (v and 0xFF).toByte()
            }
        }
        patch1(0x06, 0x05, if (projectionOn) 0x55 else 0xAA)
        maneuver?.let { patch1(0x05, 0x02, it) }
        primaryUnit?.let { patch1(0x05, 0x06, it) }
        // The template carries the captured French ride's figures (total 0x004F =
        // "7.9 km", secondary 0x000A). Zero them by DEFAULT so a card sent with no live
        // route shows 0, not a bogus "7.9 km". Live callers pass real values.
        patch2(0x05, 0x09, totalDist ?: 0)
        // Secondary ("then ...") turn. The template carries the French ride's values (glyph 0x34,
        // 10 m), so always overwrite: live values when given, else "none".
        patch1(0x05, 0x03, secondary ?: NAV_SECONDARY_NONE)
        patch2(0x05, 0x05, secondaryDist ?: 0)
        secondaryUnit?.let { patch1(0x05, 0x07, it) }
        totalUnit?.let { patch1(0x05, 0x46, it) }
        if (etaHHMM != null && etaHHMM.length == 4) {
            val m = indexOf(bytes, byteArrayOf(0x05, 0x08, 0x00, 0x04), fromEnd = true)
            if (m >= 0 && m + 8 < bytes.size) {
                for (i in 0 until 4) bytes[m + 4 + i] = etaHHMM[i].code.toByte()
            }
        }
        bytes[0] = ((bytes.size shr 8) and 0xFF).toByte()
        bytes[1] = (bytes.size and 0xFF).toByte()
        return bytes
    }

    // ── Active navigation info (0x007E-family, ~1 Hz while guiding) ───────
    // Ported from better-dash build_active_nav_packet. Drives the dash's
    // instruction bubble: primary maneuver glyph + distance-to-turn + total.
    const val NAV_MANEUVER_CONTINUE = 0x09   // STRAIGHT in the original app's glyph table (see DashGlyph)
    /** "No secondary turn": the original app passes -1 (-> 0xFF) when there's no next turn to show. */
    const val NAV_SECONDARY_NONE = 0xFF
    const val NAV_UNIT_KM_TENTHS = 0x10   // distance field = km × 10
    const val NAV_UNIT_METERS    = 0x30
    private const val NAV_HDR = "00000000020100054B31472000"

    /**
     * @param maneuver  dash glyph code (see nav.DashGlyph)
     * @param primaryDistM  distance to next turn (metres if [primaryUnit]=METERS,
     *                      or km×10 if KM_TENTHS)
     * @param totalDistM    remaining distance, same unit convention via [totalUnit]
     */
    fun activeNavPacket(
        maneuver: Int = NAV_MANEUVER_CONTINUE,
        primaryDist: Int = 500,
        primaryUnit: Int = NAV_UNIT_METERS,
        totalDist: Int = 500,
        totalUnit: Int = NAV_UNIT_METERS,
        projectionOn: Boolean = true,
        // "Then ..." turn after the primary one. Fields per better-dash's protocol notes
        // (03 secondary maneuver / 05 secondary distance / 07 secondary unit, interleaved with
        // the primary 02/04/06); sizes per the captured route-card template (1 / 2 / 1 bytes).
        secondary: Int = NAV_SECONDARY_NONE,
        secondaryDist: Int = 0,
        secondaryUnit: Int = NAV_UNIT_METERS,
    ): ByteArray {
        fun u16(v: Int) = "%04X".format(v and 0xFFFF)
        fun u8(v: Int) = "%02X".format(v and 0xFF)
        val tlvs = StringBuilder()
        tlvs.append("05020001").append(u8(maneuver))      // primary maneuver
        tlvs.append("05040002").append(u16(primaryDist))  // primary distance
        tlvs.append("05060001").append(u8(primaryUnit))   // primary unit
        tlvs.append("05030001").append(u8(secondary))     // secondary maneuver (FF = none)
        tlvs.append("05050002").append(u16(secondaryDist)) // secondary distance
        tlvs.append("05070001").append(u8(secondaryUnit)) // secondary unit
        tlvs.append("05090002").append(u16(totalDist))    // total distance
        tlvs.append("05460001").append(u8(totalUnit))     // total unit
        tlvs.append("050A000155")                          // decimal separator = '.'
        tlvs.append("06050001").append(if (projectionOn) "55" else "AA") // projection flag
        tlvs.append("060D0001AA")                          // decimal format off

        val segCount = 11 + 1
        val innerHex = "%04X".format(segCount) + NAV_HDR + tlvs.toString()
        val innerBytes = innerHex.length / 2
        val outerLen = innerBytes + 2
        return ("%04X".format(outerLen) + innerHex).hexToBytes()
    }

    // ── Media now-playing (05 0d) + telephony (05 22) ─────────────────────
    // Now-playing matches the original app (decompiled bluconnect.x9k.A1 + hbg constants):
    //   05 17 55/AA = music session active / inactive. The dash only shows now-playing text
    //                 once this is ON — without it the title never appears.
    //   05 19 55/AA = playing / paused.
    //   05 0d value = three NUL-separated fields [album]\0[title]\0[artist], each cut to
    //                 19 chars, one byte per char (chars above 0xFF sent as 0xFF).
    //   05 22 value = caller display name, NUL-terminated, re-sent ~1 Hz while ringing.
    //   05 40       = album-art JPEG, fragmented across ~1 KB packets (see albumArtFrames).
    // K1GPacket.build emits the exact captured frame: [len][segcount][..]"K1G "[seq] TT SS LL value.

    private const val MEDIA_FIELD_MAX = 19   // the original app cuts each field to 19 chars
    private const val CALLER_MAX = 20

    /** One byte per char like the original app — the dash font is single-byte, so UTF-8
     *  multi-byte sequences (non-ASCII titles) would garble the card. */
    private fun mediaField(s: String): ByteArray {
        val t = if (s.length > MEDIA_FIELD_MAX) s.substring(0, MEDIA_FIELD_MAX) else s
        return ByteArray(t.length) { i -> t[i].code.coerceAtMost(0xFF).toByte() }
    }

    /** 05 17: switch the dash's music widget on (session active) or off. */
    fun musicActive(on: Boolean): ByteArray =
        K1GPacket.build(K1GPacket.tlv(0x05, 0x17, if (on) 0x55 else 0xAA))

    /** 05 19: playing (true) or paused (false). */
    fun musicPlaying(playing: Boolean): ByteArray =
        K1GPacket.build(K1GPacket.tlv(0x05, 0x19, if (playing) 0x55 else 0xAA))

    /**
     * 05 0d now-playing card, NUL-separated. The original app sends album / title / artist, but the
     * dash's navigation screen shows only the FIRST field, so the rider saw the album there. We put
     * the song title first and the album second (the music screen may show them swapped).
     */
    fun nowPlaying(title: String, album: String, artist: String): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(mediaField(title)); out.write(0x00)
        out.write(mediaField(album)); out.write(0x00)
        out.write(mediaField(artist))
        return K1GPacket.build(K1GPacket.tlv(0x05, 0x0D, out.toByteArray()))
    }

    /** 05 22 incoming-call notification: caller name, NUL-terminated. Re-send ~1 Hz while ringing. */
    fun callNotify(callerName: String): ByteArray {
        // Unchanged encoding (UTF-8, 20 chars): the call card already works on the dash.
        val name = callerName.take(CALLER_MAX).toByteArray(Charsets.UTF_8)
        return K1GPacket.build(K1GPacket.tlv(0x05, 0x22, name + 0x00.toByte()))
    }

    /** Best-effort "call ended": empty 05 22. the dash'sexact clear wasn't captured; the dash also
     *  times the notification out once callNotify stops repeating, so this is belt-and-suspenders. */
    fun callClear(): ByteArray = K1GPacket.build(K1GPacket.tlv(0x05, 0x22, byteArrayOf(0x00)))

    /**
     * 05 40 album-art frames (EXPERIMENTAL — fragment sub-framing unconfirmed). The capture showed
     * the art split across ~1 KB 05 40 TLVs with an incrementing seq byte; the 2-byte intra-value
     * prefix we saw isn't decoded yet, so this is NOT auto-sent until validated against the dash.
     * Returns one packet per ~1000-byte JPEG chunk.
     */
    fun albumArtFrames(jpeg: ByteArray, chunk: Int = 1000): List<ByteArray> =
        jpeg.toList().chunked(chunk).map { c ->
            K1GPacket.build(K1GPacket.tlv(0x05, 0x40, c.toByteArray()))
        }

    private fun indexOf(haystack: ByteArray, needle: ByteArray, fromEnd: Boolean = false): Int {
        val range = if (fromEnd) (haystack.size - needle.size downTo 0) else (0..haystack.size - needle.size)
        outer@ for (i in range) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
