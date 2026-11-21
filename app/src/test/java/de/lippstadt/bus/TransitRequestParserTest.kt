package de.lippstadt.bus

import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.After
import java.time.ZonedDateTime
import java.util.TimeZone

class TransitRequestParserTest {
    private lateinit var previousZone: TimeZone
    private val now = ZonedDateTime.parse("2026-11-21T18:00:00+01:00[Europe/Berlin]").toInstant().toEpochMilli()
    @Before fun setZone() { previousZone = TimeZone.getDefault(); TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin")) }
    @After fun resetZone() { TimeZone.setDefault(previousZone) }
    private fun parse(text: String) = parseNaturalTransitRequest(text, now)
    private fun expected(time: String) = ZonedDateTime.parse(time + "[Europe/Berlin]").toInstant().toEpochMilli()

    @Test fun bakeryNearbyIsCategory() {
        val q = parse("Bäckerei in der Nähe")
        assertEquals("Bäckerei", q.poiCategory?.label)
        assertNull(q.poiAnchorPlace)
    }
    @Test fun nearestBusStopIsNearbySearch() {
        val q = parse("wo ist die nächste Bushaltestelle")
        assertEquals(true, q.poiCategory?.isStop)
        assertNull(q.poiAnchorPlace)
    }
    @Test fun doctorDoesNotBecomeAnUnrelatedStation() {
        assertEquals("Arztpraxis", parse("wo ist hier der nächste Arzt").poiCategory?.label)
        assertEquals("Zahnarzt", parse("wo ist der nächste Zahnarzt").poiCategory?.label)
    }
    @Test fun namedStationRetainsDestination() {
        val q = parse("s-bahnhof Friedenau")
        assertNull(q.poiCategory)
        assertTrue(q.destination.contains("Friedenau"))
        assertTrue(placeNameMatchScore(q.destination, "S Friedenau (Berlin)") >
            placeNameMatchScore(q.destination, "Bahnhof Südkreuz, Berlin"))
        assertTrue(placeNameMatchScore(q.destination, "S Friedenau (Berlin)") >
            placeNameMatchScore(q.destination, "Berlin, Rubensstr./S Friedenau"))
    }
    @Test fun explicitOriginIsPreserved() {
        val q = parse("von Lippstadt nach Soest morgen um 9 Uhr")
        assertEquals("Lippstadt", q.origin)
        assertEquals("Soest", q.destination)
        assertEquals(expected("2026-11-22T09:00:00+01:00"), q.targetTimeMillis)
    }
    @Test fun dateWithoutYearAndTrailingDot() {
        val q = parse("nach Soest am 25.11.")
        assertEquals("Soest", q.destination)
        assertEquals(expected("2026-11-25T08:00:00+01:00"), q.targetTimeMillis)
    }
    @Test fun absoluteDateAndTime() {
        assertEquals(expected("2026-11-25T14:30:00+01:00"), parse("nach Soest am 25.11.2026 um 14:30 Uhr").targetTimeMillis)
    }
    @Test fun relativeTime() {
        assertEquals(now + 30 * 60000, parse("nach Soest in 30 Minuten").targetTimeMillis)
        assertEquals(now + 2 * 3600000, parse("nach Soest in 2 Stunden").targetTimeMillis)
    }
    @Test fun weekdayAndOvermorrow() {
        assertEquals(expected("2026-11-23T08:00:00+01:00"), parse("nach Soest am Montag").targetTimeMillis)
        assertEquals(expected("2026-11-23T08:00:00+01:00"), parse("nach Soest übermorgen").targetTimeMillis)
    }
    @Test fun pastTimeWithoutDateMeansTomorrow() {
        assertEquals(expected("2026-11-22T09:00:00+01:00"), parse("nach Soest um 9 Uhr").targetTimeMillis)
    }
    @Test fun todayWithoutTimeMeansNow() {
        assertEquals(now, parse("nach Soest heute").targetTimeMillis)
    }
    @Test(expected = IllegalArgumentException::class) fun impossibleDateIsRejected() { parse("nach Soest am 31.02.2027") }
    @Test(expected = IllegalArgumentException::class) fun invalidTimeIsRejected() { parse("nach Soest um 25:30") }
    @Test(expected = IllegalArgumentException::class) fun explicitPastIsRejected() { parse("nach Soest heute um 9 Uhr") }
    @Test fun offsetsRepresentTheSameInstant() {
        assertEquals(parseIsoMillis("2026-11-21T17:00:00Z"), parseIsoMillis("2026-11-21T18:00:00+01:00"))
        assertEquals(parseIsoMillis("2026-11-21T17:00:00Z"), parseIsoMillis("2026-11-21T18:00:00"))
        assertNull(parseIsoMillis("invalid"))
    }
}
