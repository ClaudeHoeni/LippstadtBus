package de.lippstadt.bus

import org.junit.Assert.assertEquals
import org.junit.Test

class LippstadtBusTest {
    @Test
    fun testEnrichedQueryForCities() {
        assertEquals("Bahnhof, 59555 Lippstadt", buildEnrichedQuery("Bahnhof", "59555 Lippstadt"))
        assertEquals("Alexanderplatz, Berlin", buildEnrichedQuery("Alexanderplatz", "Berlin"))
        assertEquals("Sanssouci, Potsdam", buildEnrichedQuery("Sanssouci", "Potsdam"))
        assertEquals("Bahnhof Lippstadt", buildEnrichedQuery("Bahnhof Lippstadt", "59555 Lippstadt"))
    }
}