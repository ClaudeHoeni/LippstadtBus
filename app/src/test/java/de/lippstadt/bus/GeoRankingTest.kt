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

    @Test
    fun localMatchBeatsFarExactMatch() {
        // Lippstadt: "Tivoli" darf nicht in Metz landen
        val metz = GeoCandidate("TIVOLI", "57000 Moselle, Metz", 49.11, 6.18, isStop = true)
        val lp = GeoCandidate("Tivoli", "Lippstadt", 51.6770, 8.3500, isStop = true)
        val result = GeoRanking.rank(listOf(metz, lp), "Tivoli", 51.6735, 8.3446)
        assertEquals(listOf(lp), result)
    }

    @Test
    fun dropsFarGenericButKeepsCities() {
        val lat = 51.6735; val lon = 8.3446 // Lippstadt
        val farStop = GeoCandidate("Friedhof", "Kreis Unna, Werne", 51.66, 7.63, isStop = true)
        assertEquals(emptyList<GeoCandidate>(), GeoRanking.rank(listOf(farStop), "Friedhof", lat, lon))
        val erwitte = GeoCandidate("Erwitte", "59597 Kreis Soest, Erwitte", 51.61, 8.34)
        assertEquals(listOf(erwitte), GeoRanking.rank(listOf(erwitte), "Erwitte", lat, lon))
    }

    @Test
    fun exactNameKeptBeyondFiveKm() {
        // Ostkreuz -> Berlinickeplatz ca. 9 km
        val stop = GeoCandidate("Berlinickeplatz (Berlin)", "12103 Tempelhof-Schöneberg, Tempelhof", 52.46582, 13.378505, isStop = true)
        assertEquals(listOf(stop), GeoRanking.rank(listOf(stop), "Berlinickeplatz", 52.5030, 13.4690))
    }
}
