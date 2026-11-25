package de.lippstadt.bus

import de.lippstadt.bus.data.PolylineCodec
import org.junit.Assert.assertEquals
import org.junit.Test

class PolylineTest {
    @Test
    fun decodesGoogleExample() {
        // Beispiel aus der Google-Dokumentation (Genauigkeit 5)
        val pts = PolylineCodec.decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@", 5)
        assertEquals(3, pts.size)
        assertEquals(38.5, pts[0].first, 1e-6)
        assertEquals(-120.2, pts[0].second, 1e-6)
        assertEquals(43.252, pts[2].first, 1e-6)
        assertEquals(-126.453, pts[2].second, 1e-6)
    }
}
