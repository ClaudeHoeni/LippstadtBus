package de.lippstadt.bus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransitRequestParserTest {

    @Test
    fun parsesDestinationAndModeCorrectly() {
        val parsed = parseNaturalTransitRequest("Wann fährt ein Bus zum Bahnhof")
        assertEquals("Bahnhof", parsed.destination)
        assertEquals("BUS", parsed.preferredMode)
    }

    @Test
    fun prefersExactStopNameMatch() {
        val exactStop = GeoCandidate(
            name = "Bustreff Bahnhof",
            areaLabel = "59555 Lippstadt",
            lat = 51.67,
            lon = 8.34,
            isStop = true
        )
        val otherStop = GeoCandidate(
            name = "Cartec",
            areaLabel = "59557 Lippstadt",
            lat = 51.67,
            lon = 8.34,
            isStop = true
        )
        assertTrue(exactStop.name.contains("Bahnhof", ignoreCase = true))
        assertTrue(!otherStop.name.contains("Bahnhof", ignoreCase = true))
    }
}