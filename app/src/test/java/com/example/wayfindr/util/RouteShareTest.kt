package com.example.wayfindr.util

import com.example.wayfindr.data.SavedLocation
import com.example.wayfindr.data.SharedLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.google.zxing.LuminanceSource

class RouteShareTest {
    private val stops = listOf(
        SharedLocation(name = "Lalbagh, Mavalli,Bengaluru", lat = 12.9507, lng = 77.5848),
        SharedLocation(name = "Café Ümlaut & Sons | 100% 🏍", lat = -33.868820, lng = 151.209290),
        SharedLocation(name = "ಮೈಸೂರು ಅರಮನೆ", lat = 12.305163, lng = 76.655161),
    )

    private fun assertSameStops(expected: List<SharedLocation>, actual: List<SharedLocation>) {
        assertEquals(expected.size, actual.size)
        expected.zip(actual).forEach { (e, a) ->
            assertEquals(e.name, a.name)
            assertEquals(e.lat!!, a.lat!!, 1e-6)
            assertEquals(e.lng!!, a.lng!!, 1e-6)
        }
    }

    @Test fun roundTripKeepsOrderNamesAndCoordinates() {
        val r = RouteShare.decode(RouteShare.link("Weekend loop, south", stops))!!
        assertEquals("Weekend loop, south", r.name)
        assertSameStops(stops, r.stops)
    }

    @Test fun linkHasNoRawSpacesCommasInNamesOrNewlines() {
        val link = RouteShare.link("A b\nc", stops)
        assertTrue(link.startsWith("wayfindr://route?"))
        assertTrue(link.endsWith("&v=1"))
        assertFalse(link.any { it.isWhitespace() })
        assertTrue(link.contains("Lalbagh%2C%20Mavalli%2CBengaluru"))
        assertEquals("A b c", RouteShare.decode(link)!!.name)
    }

    @Test fun manyStopsRoundTrip() {
        val many = (0 until 26).map { SharedLocation(name = "Stop #$it, km ${it * 10}", lat = 12.0 + it * 0.01, lng = 77.0 - it * 0.02) }
        assertSameStops(many, RouteShare.decode(RouteShare.link("Long", many))!!.stops)
    }

    @Test fun moreThanTheStopLimitIsCapped() {
        val many = (0 until 40).map { SharedLocation(name = "S$it", lat = 10.0 + it * 0.001, lng = 76.0) }
        assertEquals(26, RouteShare.decode(RouteShare.link("x", many))!!.stops.size)
    }

    @Test fun decodesFromInsideForwardedMessageWithTrailingPunctuation() {
        val text = "Ride this: <" + RouteShare.link("Coast", stops) + ">. See you there!"
        assertSameStops(stops, RouteShare.decode(text)!!.stops)
    }

    @Test fun singlePlaceSavedDestinationShares() {
        val loc = SavedLocation(name = "Malpe Beach", lat = 13.3500, lng = 74.7034)
        val r = RouteShare.decode(RouteShare.link(loc.name, RouteShare.stopsOf(loc)))!!
        assertEquals("Malpe Beach", r.name)
        assertEquals(listOf("Malpe Beach"), r.stops.map { it.name })
    }

    @Test fun itineraryFinalStopIsLast() {
        val loc = SavedLocation(name = "Trip", lat = 12.305163, lng = 76.655161, waypoints = stops)
        assertEquals("ಮೈಸೂರು ಅರಮನೆ", RouteShare.decode(RouteShare.link(loc.name, RouteShare.stopsOf(loc)))!!.stops.last().name)
    }

    @Test fun invalidOrMissingLinkIsNull() {
        assertNull(RouteShare.decode("https://maps.app.goo.gl/abc"))
        assertNull(RouteShare.decode("wayfindr://route?n=x&s=999,1,bad&s=nope&v=1"))
        assertNull(RouteShare.decode("wayfindr://route?n=x&s=%E0%A4,1,2&v=1"))
    }

    @Test fun stopWithoutNameGetsPlaceholderAndRouteNameFallsBackToFinalStop() {
        val r = RouteShare.decode("wayfindr://route?s=12.5,77.25&s=13,78,End&v=2&future=1")!!
        assertEquals(listOf("Stop 1", "End"), r.stops.map { it.name })
        assertEquals("End", r.name)
    }

    private fun scan(text: String): String {
        val m = RouteQr.matrix(text)!!
        val k = 5                                           // px per module (RouteQr.image draws ≥5)
        val q = 4 * k                                       // quiet zone, as drawn by RouteQr.image
        val w = m.width * k + 2 * q
        val px = ByteArray(w * w) { 0xFF.toByte() }
        for (y in 0 until m.height * k) for (x in 0 until m.width * k)
            if (m[x / k, y / k]) px[(y + q) * w + x + q] = 0
        // Same reader the Route screen uses for a QR picked from the gallery.
        return RouteQr.decode(object : LuminanceSource(w, w) {
            override fun getRow(y: Int, row: ByteArray?) = px.copyOfRange(y * w, (y + 1) * w)
            override fun getMatrix() = px
        })!!
    }

    @Test fun qrRoundTripKeepsEveryStop() {
        val link = RouteShare.link("Weekend loop, south", stops)
        assertEquals(link, scan(link))
        assertSameStops(stops, RouteShare.decode(scan(link))!!.stops)
    }

    @Test fun qrFitsTheLargestItinerary() {
        val many = (0 until 26).map { SharedLocation(name = "Stop number $it, Some Long Road Name", lat = 12.123456 + it * 0.01, lng = 77.654321 - it * 0.02) }
        val link = RouteShare.link("Twenty-six stop grand tour", many)
        assertSameStops(many, RouteShare.decode(scan(link))!!.stops)
    }

    @Test fun tooLongForAnyQrIsNull() {
        assertNull(RouteQr.matrix("x".repeat(5000)))
    }
}
