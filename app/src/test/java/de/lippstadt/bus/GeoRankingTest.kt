package de.lippstadt.bus

import de.lippstadt.bus.data.GeoCandidate
import de.lippstadt.bus.data.GeoRanking
import org.junit.Assert.assertEquals
import org.junit.Test

class GeoRankingTest {

    // Standort: Berlin-Tempelhof
    private val lat = 52.4700
    private val lon = 13.3850

    @Test
    fun dropsFarAwayAlternatives() {
        val local = GeoCandidate("S+U Tempelhof (Berlin)", "12099 Tempelhof", 52.4703, 13.3857, isStop = true)
        val far = GeoCandidate("Marke, S-Bahnhof (Bus)", "06779 Raguhn-Jeßnitz", 51.70, 12.30, isStop = true)
        val result = GeoRanking.rank(listOf(far, local), "S-Bahnhof Tempelhof", lat, lon)
        assertEquals(listOf(local), result)
    }

    @Test
    fun nearbyWinsOverSameNameFarAway() {
        val farBhf = GeoCandidate("Bahnhof", "Hamburg", 53.55, 10.00, isStop = true)
        val nearBhf = GeoCandidate("Bahnhof", "Berlin", 52.48, 13.39, isStop = true)
        assertEquals(nearBhf, GeoRanking.rank(listOf(farBhf, nearBhf), "Bahnhof", lat, lon).first())
    }

    @Test
    fun explicitFarDestinationStillWorks() {
        val hamburg = GeoCandidate("Hamburg Hbf", "Hamburg", 53.553, 10.006, isStop = true)
        val result = GeoRanking.rank(listOf(hamburg), "Hamburg Hbf", lat, lon)
        assertEquals(listOf(hamburg), result)
    }
}
