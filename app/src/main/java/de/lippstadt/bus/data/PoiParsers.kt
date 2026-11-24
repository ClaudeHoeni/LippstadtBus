package de.lippstadt.bus.data

import org.json.JSONObject

/** Wandelt Overpass- und Photon-Antworten in [GeoCandidate]s um (ohne Netzwerk, testbar). */
object PoiParsers {

    private val TAG_FILTER = Regex("""\["([a-z_:]+)"[=~]"([a-z_|]+)"""")

    /** Erste Tag-Bedingung jeder Overpass-Abfrage als Photon-Filter, z. B. "shop:supermarket". */
    fun photonTags(category: PoiCategory): List<String> =
        category.overpassQueries.flatMap { q ->
            val m = TAG_FILTER.find(q) ?: return@flatMap emptyList()
            m.groupValues[2].split("|").map { "${m.groupValues[1]}:$it" }
        }.distinct()

    fun buildOverpassQuery(category: PoiCategory, lat: Double, lon: Double, radius: Int): String {
        val union = category.overpassQueries.joinToString("") { f ->
            "node$f(around:$radius,$lat,$lon);way$f(around:$radius,$lat,$lon);"
        }
        // Kein Limit bei "out": sonst liefert Overpass beliebige statt der nächsten Treffer.
        return "[out:json][timeout:15];($union);out center tags;"
    }

    fun parseOverpass(json: String, centerLat: Double, centerLon: Double, category: PoiCategory): List<GeoCandidate> {
        val elements = JSONObject(json).optJSONArray("elements") ?: return emptyList()
        return (0 until elements.length()).mapNotNull { i ->
            val el = elements.optJSONObject(i) ?: return@mapNotNull null
            val tags = el.optJSONObject("tags") ?: return@mapNotNull null
            val center = el.optJSONObject("center")
            val lat = if (el.has("lat")) el.optDouble("lat") else center?.optDouble("lat") ?: Double.NaN
            val lon = if (el.has("lon")) el.optDouble("lon") else center?.optDouble("lon") ?: Double.NaN
            if (lat.isNaN() || lon.isNaN()) return@mapNotNull null
            candidate(
                name = tags.optString("name", ""),
                street = tags.optString("addr:street", ""),
                house = tags.optString("addr:housenumber", ""),
                lat = lat, lon = lon,
                centerLat = centerLat, centerLon = centerLon, category = category
            )
        }.sortedBy { distanceMeters(centerLat, centerLon, it.lat, it.lon) }
    }

    fun parsePhoton(json: String, centerLat: Double, centerLon: Double, category: PoiCategory): List<GeoCandidate> {
        val features = JSONObject(json).optJSONArray("features") ?: return emptyList()
        return (0 until features.length()).mapNotNull { i ->
            val f = features.optJSONObject(i) ?: return@mapNotNull null
            val coords = f.optJSONObject("geometry")?.optJSONArray("coordinates") ?: return@mapNotNull null
            val props = f.optJSONObject("properties") ?: return@mapNotNull null
            val lon = coords.optDouble(0)
            val lat = coords.optDouble(1)
            if (lat.isNaN() || lon.isNaN()) return@mapNotNull null
            candidate(
                name = props.optString("name", ""),
                street = props.optString("street", ""),
                house = props.optString("housenumber", ""),
                lat = lat, lon = lon,
                centerLat = centerLat, centerLon = centerLon, category = category
            )
        }.sortedBy { distanceMeters(centerLat, centerLon, it.lat, it.lon) }
    }

    fun applyNameFilter(list: List<GeoCandidate>, category: PoiCategory): List<GeoCandidate> {
        val f = category.nameFilter ?: return list
        return list.filter { it.name.contains(f, ignoreCase = true) }
    }

    private fun candidate(
        name: String, street: String, house: String,
        lat: Double, lon: Double, centerLat: Double, centerLon: Double, category: PoiCategory
    ): GeoCandidate {
        val dist = distanceMeters(centerLat, centerLon, lat, lon).toInt()
        val addr = listOf(street.trim(), house.trim()).filter { it.isNotBlank() }.joinToString(" ")
        return GeoCandidate(
            name = sanitizeTranscriptWords(name.trim()).ifBlank { category.label },
            areaLabel = if (addr.isNotBlank()) "$addr · ${formatDistance(dist)}" else "${formatDistance(dist)} entfernt",
            lat = lat,
            lon = lon,
            isStop = category.isTransitStopSearch
        )
    }
}
