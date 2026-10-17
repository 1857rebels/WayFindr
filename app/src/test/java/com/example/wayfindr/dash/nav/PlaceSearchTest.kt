package com.example.wayfindr.dash.nav

import com.example.wayfindr.data.SavedLocation
import com.example.wayfindr.data.SharedLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaceSearchTest {
    private val saved = listOf(
        SavedLocation(name = "Tesing 2", lat = 13.0842, lng = 77.5647, waypoints = listOf(
            SharedLocation(name = "Block 6", lat = 13.0842, lng = 77.5647),
            SharedLocation(name = "73/5, Kodigehalli post,Magadi Road", lat = 13.0672, lng = 77.5699),
        )),
        SavedLocation(name = "Udupi Stay", lat = 13.3833, lng = 74.7001),
    )

    @Test fun itineraryNameMeansItsFinalStop() {
        val hit = PlaceSearch.savedMatch("tesing", saved)!!
        assertEquals("Tesing 2", hit.name)
        assertEquals(13.0672, hit.lat, 1e-9)
    }

    @Test fun matchesAStopInsideAnItinerary() {
        assertEquals("Block 6", PlaceSearch.savedMatch("block 6", saved)!!.name)
    }

    @Test fun everyWordMustMatch() {
        assertEquals("Udupi Stay", PlaceSearch.savedMatch("stay udupi", saved)!!.name)
        assertNull(PlaceSearch.savedMatch("udupi beach", saved))
    }

    @Test fun wordScoreCountsTypedWords() {
        assertEquals(3, PlaceSearch.wordScore("Lalbagh Botanical Garden", "Lal Bagh Botanical Garden, Bengaluru"))
        assertEquals(2, PlaceSearch.wordScore("Lalbagh Botanical Garden", "Atal Bihari Botanical Garden, Bengaluru"))
    }
}
