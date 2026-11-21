package de.lippstadt.bus

import org.junit.Assert.assertEquals
import org.junit.Test

class LippstadtBusTest {
    @Test
    fun testQueryBuilder() {
        assertEquals("Bahnhof, 59555 Lippstadt", buildEnrichedQuery("Bahnhof", "59555 Lippstadt"))
        assertEquals("Hauptbahnhof, Berlin", buildEnrichedQuery("Hauptbahnhof", "Berlin"))
        assertEquals("Hauptbahnhof, Hamburg", buildEnrichedQuery("Hauptbahnhof", "Hamburg"))
        assertEquals("Bahnhof Lippstadt", buildEnrichedQuery("Bahnhof Lippstadt", "59555 Lippstadt"))
    }
}