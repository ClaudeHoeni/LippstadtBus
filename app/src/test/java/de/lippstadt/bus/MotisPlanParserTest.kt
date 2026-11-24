package de.lippstadt.bus

import de.lippstadt.bus.data.MotisPlanParser
import de.lippstadt.bus.data.buildSpokenSummary
import de.lippstadt.bus.data.distanceMeters
import de.lippstadt.bus.data.isoToLocalHhMm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.OffsetDateTime
import java.time.ZoneId

class MotisPlanParserTest {

    private val berlin = ZoneId.of("Europe/Berlin")
    private val ref = OffsetDateTime.parse("2026-11-23T08:00:00Z").toInstant().toEpochMilli()

    private val json = """
    {"itineraries":[
      {"duration":1800,"legs":[
        {"mode":"WALK","duration":240,"distance":300,
         "from":{"name":"START","lat":51.67,"lon":8.34},
         "to":{"name":"Lippstadt Rathaus","lat":51.671,"lon":8.345},
         "startTime":"2026-11-23T08:06:00Z","endTime":"2026-11-23T08:10:00Z"},
        {"mode":"BUS","duration":600,"distance":4000,"routeShortName":"C1","headsign":"Bahnhof",
         "from":{"name":"Lippstadt Rathaus","lat":51.671,"lon":8.345},
         "to":{"name":"Bahnhof","lat":51.67,"lon":8.35},
         "startTime":"2026-11-23T08:12:00Z","scheduledStartTime":"2026-11-23T08:10:00Z",
         "endTime":"2026-11-23T08:22:00Z"},
        {"mode":"REGIONAL_RAIL","duration":600,"distance":15000,"routeShortName":"RB89","headsign":"Paderborn",
         "from":{"name":"Bahnhof","lat":51.67,"lon":8.35},
         "to":{"name":"END","lat":51.7,"lon":8.7},
         "startTime":"2026-11-23T08:26:00Z","endTime":"2026-11-23T08:36:00Z"}
      ]},
      {"duration":600,"legs":[
        {"mode":"BUS","routeShortName":"C2","headsign":"Alt",
         "from":{"name":"Alt"},"to":{"name":"END"},
         "startTime":"2026-11-23T07:30:00Z","endTime":"2026-11-23T07:40:00Z"}
      ]}
    ]}
    """.trimIndent()

    @Test
    fun convertsUtcToLocalTime() {
        assertEquals("09:15", isoToLocalHhMm("2026-11-23T08:15:00Z", berlin))
        assertEquals("10:15", isoToLocalHhMm("2026-07-01T08:15:00Z", berlin))
    }

    @Test
    fun parsesItineraryWithTransferAndDelay() {
        val list = MotisPlanParser.parse(json, "Paderborn Hbf", 51.7, 8.7, ref, berlin)
        // Zweite Verbindung liegt in der Vergangenheit und wird verworfen
        assertEquals(1, list.size)
        val c = list.first()
        assertEquals("C1 → RB89", c.summaryLines)
        assertEquals("09:12", c.firstDepartureTime)
        assertEquals("09:36", c.finalArrivalTime)
        assertEquals(2, c.firstDelayMinutes)
        assertEquals(1, c.transfersCount)
        assertFalse(c.isWalkOnly)
        assertTrue(c.legs.any { it.isTransfer && it.durationMinutes == 4 })
        assertEquals("Aktueller Standort", c.legs.first().fromStop)
        assertEquals("Paderborn Hbf", c.legs.last().toStop)
        assertTrue(buildSpokenSummary(c).contains("9 Uhr 12"))
    }

    @Test
    fun directWalkFallback() {
        val c = MotisPlanParser.directWalk(51.67, 8.34, "Ziel", 51.68, 8.34, ref, berlin)
        assertTrue(c.isWalkOnly)
        assertEquals(1, c.legs.size)
        assertTrue(c.totalWalkMeters in 1100..1125)
    }

    @Test
    fun haversineDistance() {
        val d = distanceMeters(51.6717, 8.3447, 51.7189, 8.7575) // Lippstadt → Paderborn
        assertTrue(d in 28_000.0..30_000.0)
    }
}
