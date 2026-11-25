package de.lippstadt.bus

import de.lippstadt.bus.data.GeoCandidate
import de.lippstadt.bus.data.PoiParsers
import de.lippstadt.bus.data.parseNaturalTransitRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CuisineTest {

    private fun label(q: String) = parseNaturalTransitRequest(q).poiCategory?.label

    @Test
    fun cuisineWordsMapToRestaurants() {
        assertEquals("Indisches Restaurant", label("Inder"))
        assertEquals("Indisches Restaurant", label("Wo ist der nächste Inder"))
        assertEquals("Chinesisches Restaurant", label("zum Chinesen"))
        assertEquals("Asiatisches Restaurant", label("Asiate in der Nähe"))
        assertEquals("Vietnamesisches Restaurant", label("Vietnamese"))
        assertEquals("Italienisches Restaurant", label("beim Italiener"))
        assertEquals("Italienisches Restaurant", label("Pizzeria"))
        assertEquals("Veganes Restaurant", label("vegan essen gehen"))
        assertEquals("Vegetarisches Restaurant", label("vegetarisch"))
        assertEquals("Griechisches Restaurant", label("Grieche"))
    }

    @Test
    fun overpassUsesCuisineTag() {
        val cat = parseNaturalTransitRequest("Inder").poiCategory!!
        assertTrue(cat.overpassQueries.all { it.contains("cuisine") && it.contains("indian") })
        assertEquals(listOf("amenity:restaurant", "amenity:fast_food"), PoiParsers.photonTags(cat))
    }

    @Test
    fun nameRegexFiltersTypicalNames() {
        val cat = parseNaturalTransitRequest("Italiener").poiCategory!!
        val list = listOf(
            GeoCandidate("Pizzeria Napoli", "", 0.0, 0.0),
            GeoCandidate("Zum Goldenen Hirsch", "", 0.0, 0.0),
            GeoCandidate("Trattoria da Mario", "", 0.0, 0.0)
        )
        assertEquals(listOf("Pizzeria Napoli", "Trattoria da Mario"), PoiParsers.applyNameRegex(list, cat).map { it.name })
    }

    @Test
    fun kinderIsNotInder() {
        assertTrue(label("Kinderarzt") != "Indisches Restaurant")
    }
}
