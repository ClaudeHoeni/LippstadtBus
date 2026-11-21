package de.lippstadt.bus

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class LippstadtBusTest {

    @Test
    fun testBusSearchFromLippstadtMarktplatzToBahnhof() = runBlocking {
        val lippstadtLat = 51.6754
        val lippstadtLon = 8.3446

        val connections = fetchConnections(
            fromLat = lippstadtLat,
            fromLon = lippstadtLon,
            query = "Bahnhof"
        )

        println("=== TEST ERGEBNIS LIPPSTADT MARKTPLATZ -> BAHNHOF ===")
        connections.forEach { conn ->
            println("Linie ${conn.line} | Abfahrt ${conn.departureTime} (+${conn.delayMinutes} Min) | Ab: ${conn.originStop} -> ${conn.destinationStop}")
        }

        assertTrue(
            "Es sollte mindestens eine Busverbindung in Lippstadt gefunden werden.",
            connections.isNotEmpty()
        )
    }
}
