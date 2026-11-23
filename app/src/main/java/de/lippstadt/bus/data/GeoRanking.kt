package de.lippstadt.bus.data

import java.util.Locale

/** Sortiert Geocoding-Treffer nach Textübereinstimmung UND Nähe (ohne Netzwerk, testbar). */
object GeoRanking {

    /** Weitere Treffer nur bis zu dieser Entfernung (oder 1,5 × Entfernung des besten Treffers). */
    private const val LOCAL_RADIUS_M = 25_000.0

    fun rank(raw: List<GeoCandidate>, query: String, biasLat: Double, biasLon: Double): List<GeoCandidate> {
        if (raw.isEmpty()) return raw
        val lowerQuery = query.lowercase(Locale.GERMANY).trim()
        val words = lowerQuery.split(Regex("\\s+")).filter { it.length >= 3 }
        fun dist(c: GeoCandidate) = distanceMeters(biasLat, biasLon, c.lat, c.lon)

        fun score(c: GeoCandidate): Int {
            val name = c.name.lowercase(Locale.GERMANY)
            val full = "$name ${c.areaLabel.lowercase(Locale.GERMANY)}"
            val exact = when {
                name == lowerQuery -> 40
                name.contains(lowerQuery) -> 25
                else -> 0
            }
            // Entfernung kostet Punkte: 1 Punkt je 3 km, max. 30 – so gewinnt der Treffer vor Ort.
            val distancePenalty = minOf(30, (dist(c) / 3_000).toInt())
            return exact + words.count { name.contains(it) } * 15 + words.count { full.contains(it) } * 8 +
                (if (c.isStop) 5 else if (c.isStreet) 2 else 0) - distancePenalty
        }

        val sorted = raw.sortedWith(compareByDescending<GeoCandidate> { score(it) }.thenBy { dist(it) })
        val limit = maxOf(LOCAL_RADIUS_M, dist(sorted.first()) * 1.5)
        return sorted.filter { dist(it) <= limit }
    }
}
