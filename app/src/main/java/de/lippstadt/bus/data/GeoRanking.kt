package de.lippstadt.bus.data

import java.util.Locale

/** Sortiert Geocoding-Treffer nach Textübereinstimmung UND Nähe (ohne Netzwerk, testbar). */
object GeoRanking {

    private const val REGION_RADIUS_M = 30_000.0
    private const val MAX_LOCAL_M = 5_000.0

    fun rank(
        raw: List<GeoCandidate>,
        query: String,
        biasLat: Double,
        biasLon: Double,
        city: String = ""
    ): List<GeoCandidate> {
        if (raw.isEmpty()) return raw
        val lowerQuery = query.lowercase(Locale.GERMANY).trim()
        fun dist(c: GeoCandidate) = distanceMeters(biasLat, biasLon, c.lat, c.lon)

        val queryTokens = tokens(lowerQuery)

        fun score(c: GeoCandidate): Int {
            // Nur ganze Wörter zählen: "Erwitte" darf nicht "Erwitter Straße" treffen.
            val nameTokens = tokens(c.name)
            val fullTokens = nameTokens + tokens(c.areaLabel)
            val exact = when {
                nameTokens == queryTokens -> 40
                nameTokens.containsAll(queryTokens) -> 25
                else -> 0
            }
            // Entfernung kostet Punkte: 1 Punkt je 3 km, max. 30 – so gewinnt der Treffer vor Ort.
            val distancePenalty = minOf(30, (dist(c) / 3_000).toInt())
            return exact + queryTokens.count { it in nameTokens } * 15 + queryTokens.count { it in fullTokens } * 8 +
                (if (c.isStop) 5 else if (c.isStreet) 2 else 0) - distancePenalty
        }

        val sorted = raw.sortedWith(compareByDescending<GeoCandidate> { score(it) }.thenBy { dist(it) })
        // Gibt es einen passenden Treffer in der Region, fliegen alle weit entfernten raus
        // (vorher: "Tivoli" in Lippstadt -> Metz 323 km). Nur ohne lokalen Treffer gelten Fernziele.
        // Nur Treffer bis 5 km – außer Städte/Orte bzw. Ziele in einer genannten Stadt
        // ("Erwitte", "Paderborn Hbf", "Hamburg"): deren Ortsname steht in der Eingabe.
        fun cityMatch(c: GeoCandidate): Boolean {
            val area = tokens(c.areaLabel)
            return queryTokens.any { it in area }
        }
        val candidates = sorted.filter { dist(it) <= MAX_LOCAL_M || cityMatch(it) }

        val local = candidates.filter { c ->
            dist(c) <= REGION_RADIUS_M && (tokens(c.name) + tokens(c.areaLabel)).containsAll(queryTokens)
        }
        if (local.isNotEmpty()) {
            // In der Region zählt Nähe stark: 4 Punkte je km (max. 30), Bonus für die eigene Stadt.
            val cityLower = city.lowercase(Locale.GERMANY)
            fun localScore(c: GeoCandidate): Double {
                val inCity = cityLower.isNotBlank() && cityLower in (tokens(c.name) + tokens(c.areaLabel))
                val exactName = tokens(c.name) == queryTokens
                // Exakter Name (z. B. Ort "Erwitte") schlägt Teiltreffer ("Erwitte - Verl"); Nähe max. 30 Punkte.
                return score(c) + (if (exactName) 30 else 0) + (if (inCity) 10 else 0) -
                    minOf(30.0, dist(c) / 250.0)
            }
            return local.sortedByDescending { localScore(it) }
        }
        return candidates
    }

    private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")

    private fun tokens(s: String): List<String> =
        s.lowercase(Locale.GERMANY).split(NON_WORD).filter { it.isNotBlank() }
}
