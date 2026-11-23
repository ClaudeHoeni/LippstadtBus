package de.lippstadt.bus

import de.lippstadt.bus.data.parseNaturalTransitRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class TransitRequestParserTest {

    private val tz = TimeZone.getTimeZone("Europe/Berlin")

    /** Montag, 23.11.2026, 10:00 Uhr */
    private val now: Long = Calendar.getInstance(tz).apply {
        clear()
        set(2026, Calendar.NOVEMBER, 23, 10, 0, 0)
    }.timeInMillis

    private fun parse(text: String) = parseNaturalTransitRequest(text, now, tz)

    private fun calOf(millis: Long) = Calendar.getInstance(tz).apply { timeInMillis = millis }

    @Test
    fun parsesDestinationAndMode() {
        val p = parse("Wann fährt ein Bus zum Bahnhof")
        assertEquals("Bahnhof", p.destination)
        assertEquals("BUS", p.preferredMode)
        assertNull(p.poiCategory)
    }

    @Test
    fun fromToUsesDestination() {
        val p = parse("Von der Lange Straße nach Bad Waldliesborn")
        assertEquals("Bad Waldliesborn", p.destination)
    }

    @Test
    fun cityEssenIsNotRestaurant() {
        val p = parse("Wie komme ich nach Essen")
        assertNull(p.poiCategory)
        assertEquals("Essen", p.destination)
    }

    @Test
    fun nearestStopIsPoiSearch() {
        val p = parse("Wo ist die nächste Haltestelle")
        assertTrue(p.poiCategory!!.isTransitStopSearch)
        assertNull(p.poiAnchorPlace)
    }

    @Test
    fun poiWithAnchorKeepsCase() {
        val p = parse("Apotheke am Rathausplatz")
        assertEquals("Apotheke", p.poiCategory!!.label)
        assertEquals("Rathausplatz", p.poiAnchorPlace)
    }

    @Test
    fun poiNearbyHasNoAnchor() {
        val p = parse("Bäckerei in der Nähe")
        assertEquals("Bäckerei", p.poiCategory!!.label)
        assertNull(p.poiAnchorPlace)
    }

    @Test
    fun brandSearchFiltersByName() {
        val p = parse("Wie komme ich zum Rewe")
        assertEquals("Rewe", p.poiCategory!!.label)
        assertTrue(p.poiCategory!!.overpassQueries.all { it.contains("rewe") })
    }

    @Test
    fun stopAsOriginDoesNotHijackDestination() {
        val p = parse("Bus von der Haltestelle Lipperoder Straße zum Bahnhof")
        assertNull(p.poiCategory)
        assertEquals("Bahnhof", p.destination)
    }

    @Test
    fun relativeMinutes() {
        val p = parse("In 30 Minuten zum Bahnhof")
        assertEquals(now + 30 * 60_000L, p.targetTimeMillis)
        assertEquals("Bahnhof", p.destination)
    }

    @Test
    fun tomorrowAtTime() {
        val p = parse("Morgen um 7:30 zum Bahnhof")
        val c = calOf(p.targetTimeMillis!!)
        assertEquals(24, c.get(Calendar.DAY_OF_MONTH))
        assertEquals(7, c.get(Calendar.HOUR_OF_DAY))
        assertEquals(30, c.get(Calendar.MINUTE))
        assertEquals("Bahnhof", p.destination)
    }

    @Test
    fun dateAtEndOfSentence() {
        val p = parse("Zum Bahnhof am 24.12.")
        val c = calOf(p.targetTimeMillis!!)
        assertEquals(24, c.get(Calendar.DAY_OF_MONTH))
        assertEquals(Calendar.DECEMBER, c.get(Calendar.MONTH))
        assertEquals("Bahnhof", p.destination)
    }

    @Test
    fun todayAloneMeansNow() {
        val p = parse("Heute zum Bahnhof")
        assertNull(p.targetTimeMillis)
        assertEquals("Bahnhof", p.destination)
    }

    @Test
    fun pastTimeRollsToNextDay() {
        val p = parse("Um 8 Uhr zum Bahnhof")
        val c = calOf(p.targetTimeMillis!!)
        assertEquals(24, c.get(Calendar.DAY_OF_MONTH))
        assertEquals(8, c.get(Calendar.HOUR_OF_DAY))
    }

    @Test
    fun eveningShiftsHour() {
        val p = parse("Heute abend um 7 zum Bahnhof")
        val c = calOf(p.targetTimeMillis!!)
        assertEquals(23, c.get(Calendar.DAY_OF_MONTH))
        assertEquals(19, c.get(Calendar.HOUR_OF_DAY))
    }

    @Test
    fun weekdayDefaultsToMorning() {
        val p = parse("Freitag nach Paderborn")
        assertNotNull(p.targetTimeMillis)
        val c = calOf(p.targetTimeMillis!!)
        assertEquals(Calendar.FRIDAY, c.get(Calendar.DAY_OF_WEEK))
        assertEquals(8, c.get(Calendar.HOUR_OF_DAY))
        assertEquals("Paderborn", p.destination)
    }
}
