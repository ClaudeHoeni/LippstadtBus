package de.lippstadt.bus

import de.lippstadt.bus.data.StreetMatch
import de.lippstadt.bus.data.parseNaturalTransitRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreetMatchTest {

    @Test
    fun detectsStreets() {
        for (q in listOf("Erwitter Straße", "Lange Str.", "Gottes Garten", "Gottesgarten", "Im Ried",
            "Unter den Eichen", "Am Anhalter Bahnhof", "Rathausplatz", "Wiedenbrücker Weg")) {
            assertTrue(q, parseNaturalTransitRequest(q).isStreet)
        }
    }

    @Test
    fun citiesAreNoStreets() {
        for (q in listOf("Paderborn", "Tivoli", "Bad Waldliesborn", "Erwitte", "Hamburg Hbf", "Lippstadt Bahnhof")) {
            assertFalse(q, parseNaturalTransitRequest(q).isStreet)
        }
    }

    @Test
    fun articleBeforeStationIsNearestStation() {
        assertEquals("Nächster Bahnhof", parseNaturalTransitRequest("die Bahnhof").poiCategory?.label)
    }

    @Test
    fun joinsSplitWords() {
        assertTrue("Gottesgarten" in StreetMatch.variants("Gottes Garten"))
        assertTrue(StreetMatch.similar("Gottesgarten", "Gottes Garten"))
        assertTrue(StreetMatch.similar("Erwitter Straße", "Erwitter Str."))
        assertTrue(StreetMatch.similar("Unter den Eichen", "unter den eichen"))
        assertFalse(StreetMatch.similar("Gartenstraße", "Gottes Garten"))
    }
}
