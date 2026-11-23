package de.lippstadt.bus

import de.lippstadt.bus.data.PoiParsers
import de.lippstadt.bus.data.parseNaturalTransitRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PoiParsersTest {

    private val supermarket = parseNaturalTransitRequest("Supermarkt in der Nähe").poiCategory!!

    @Test
    fun overpassResultsSortedByDistance() {
        val json = """{"elements":[
          {"type":"node","lat":52.4900,"lon":13.4353,"tags":{"name":"Weit"}},
          {"type":"way","center":{"lat":52.4815,"lon":13.4353},"tags":{"name":"Nah","addr:street":"Karl-Marx-Str.","addr:housenumber":"1"}},
          {"type":"node","lat":52.4830,"lon":13.4353}
        ]}"""
        val list = PoiParsers.parseOverpass(json, 52.4811, 13.4353, supermarket)
        assertEquals(2, list.size)
        assertEquals("Nah", list.first().name)
        assertTrue(list.first().areaLabel.startsWith("Karl-Marx-Str. 1"))
    }

    @Test
    fun photonParsesAndSorts() {
        val json = """{"features":[
          {"geometry":{"coordinates":[13.44,52.49]},"properties":{"name":"B"}},
          {"geometry":{"coordinates":[13.4355,52.4812]},"properties":{"name":"A","street":"Weserstr."}}
        ]}"""
        val list = PoiParsers.parsePhoton(json, 52.4811, 13.4353, supermarket)
        assertEquals(listOf("A", "B"), list.map { it.name })
    }

    @Test
    fun photonTagsFromCategory() {
        assertEquals(listOf("shop:supermarket", "shop:discount"), PoiParsers.photonTags(supermarket))
    }

    @Test
    fun queryHasNoResultLimit() {
        val q = PoiParsers.buildOverpassQuery(supermarket, 52.48, 13.43, 1000)
        assertTrue(q.contains("around:1000,52.48,13.43"))
        assertTrue(q.endsWith("out center tags;"))
    }
}
