package de.lippstadt.bus

import org.junit.Assert.assertEquals
import org.junit.Test

class LippstadtBusTest {

    @Test
    fun testParseLippstadtBusConnection() {
        val sampleJson = """
        {
          "journeys": [
            {
              "legs": [
                {
                  "walking": false,
                  "line": { "name": "Bus C1" },
                  "direction": "Lippstadt, Bahnhof",
                  "origin": { "name": "Lippstadt, Marktplatz" },
                  "destination": { "name": "Lippstadt, Bahnhof" },
                  "departure": "2026-11-21T15:42:00+01:00",
                  "departureDelay": 180
                }
              ]
            }
          ]
        }
        """.trimIndent()

        val connections = parseJourneysJson(sampleJson, "Bahnhof")
        assertEquals(1, connections.size)
        assertEquals("Bus C1", connections[0].line)
        assertEquals("15:42", connections[0].departureTime)
        assertEquals(3, connections[0].delayMinutes)
        assertEquals("Lippstadt, Marktplatz", connections[0].originStop)
    }
}
