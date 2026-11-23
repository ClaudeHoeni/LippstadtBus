package de.lippstadt.bus.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Netzwerkzugriffe (transitous.org / MOTIS, Overpass, GitHub). Alle Funktionen laufen auf Dispatchers.IO. */
object TransitApi {

    private const val BASE = "https://api.transitous.org/api/v1"
    private const val USER_AGENT = "AbfahrtenNextAndHere/6.0 (https://github.com/ClaudeHoeni/LippstadtBus)"
    private const val RELEASE_URL = "https://api.github.com/repos/ClaudeHoeni/LippstadtBus/releases/tags/v-latest"

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    internal fun httpGet(urlString: String, timeoutMs: Int = 10_000): String {
        val conn = URL(urlString).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept", "application/json")
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            val code = conn.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    // ------------------------------------------------------------ Reverse-Geocoding

    suspend fun reverseCityName(lat: Double, lon: Double): String? = withContext(Dispatchers.IO) {
        runCatching {
            val arr = JSONArray(httpGet("$BASE/reverse-geocode?place=$lat,$lon"))
            val areas = arr.optJSONObject(0)?.optJSONArray("areas") ?: return@runCatching null
            // Gemeinde-Ebene bevorzugen (adminLevel 8), sonst erstes Gebiet
            var best: String? = null
            for (a in 0 until areas.length()) {
                val area = areas.optJSONObject(a) ?: continue
                val name = area.optString("name", "").trim()
                if (name.isBlank()) continue
                if (area.optInt("adminLevel", 0) == 8) return@runCatching name
                if (best == null) best = name
            }
            best
        }.getOrNull()
    }

    // ------------------------------------------------------------ Haltestellen / Orte

    suspend fun findNearestStop(lat: Double, lon: Double, maxMeters: Int = 1500): GeoCandidate? =
        searchNearbyPois(lat, lon, TRANSIT_STOP_CATEGORY, cityContext = "", radiusMeters = maxMeters)
            .filter { it.isStop && distanceMeters(lat, lon, it.lat, it.lon) <= maxMeters }
            .minByOrNull { distanceMeters(lat, lon, it.lat, it.lon) }

    suspend fun searchNearbyPois(
        centerLat: Double,
        centerLon: Double,
        category: PoiCategory,
        cityContext: String,
        radiusMeters: Int = 2000
    ): List<GeoCandidate> = withContext(Dispatchers.IO) {
        val found = mutableListOf<GeoCandidate>()

        if (category.isTransitStopSearch) {
            runCatching {
                val arr = JSONArray(httpGet("$BASE/reverse-geocode?place=$centerLat,$centerLon"))
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val type = obj.optString("type", "").uppercase(Locale.US)
                    val name = sanitizeTranscriptWords(obj.optString("name", "").trim())
                    val lat = obj.optDouble("lat", Double.NaN)
                    val lon = obj.optDouble("lon", Double.NaN)
                    if (lat.isNaN() || lon.isNaN() || name.isBlank()) continue
                    if (type == "STOP") {
                        val dist = distanceMeters(centerLat, centerLon, lat, lon).toInt()
                        if (dist <= radiusMeters) {
                            found += GeoCandidate(name, "ca. $dist m entfernt", lat, lon, isStop = true)
                        }
                    }
                }
            }
        }

        if (found.isEmpty()) found += overpassSearch(centerLat, centerLon, category, radiusMeters)

        if (found.isEmpty()) {
            val keyword = sanitizeTranscriptWords(category.fallbackKeyword)
            val q = if (cityContext.isNotBlank()) "$keyword $cityContext" else keyword
            found += geocode(q, centerLat, centerLon, cityContext)
        }

        dedupe(found.sortedBy { distanceMeters(centerLat, centerLon, it.lat, it.lon) }, minMeters = 40.0)
    }

    private fun overpassSearch(lat: Double, lon: Double, category: PoiCategory, radius: Int): List<GeoCandidate> {
        val union = category.overpassQueries.joinToString("\n") { f ->
            "node$f(around:$radius,$lat,$lon);\nway$f(around:$radius,$lat,$lon);"
        }
        val query = "[out:json][timeout:12];\n(\n$union\n);\nout center 25;"
        val endpoints = listOf(
            "https://overpass-api.de/api/interpreter",
            "https://overpass.kumi.systems/api/interpreter",
            "https://maps.mail.ru/osm/tools/overpass/api/interpreter"
        )
        for (ep in endpoints) {
            val list = runCatching {
                val root = JSONObject(httpGet("$ep?data=${enc(query)}", timeoutMs = 14_000))
                val elements = root.optJSONArray("elements") ?: return@runCatching emptyList<GeoCandidate>()
                (0 until elements.length()).mapNotNull { i ->
                    val el = elements.optJSONObject(i) ?: return@mapNotNull null
                    val tags = el.optJSONObject("tags") ?: return@mapNotNull null
                    val center = el.optJSONObject("center")
                    val pLat = if (el.has("lat")) el.optDouble("lat") else center?.optDouble("lat") ?: Double.NaN
                    val pLon = if (el.has("lon")) el.optDouble("lon") else center?.optDouble("lon") ?: Double.NaN
                    if (pLat.isNaN() || pLon.isNaN()) return@mapNotNull null
                    val rawName = sanitizeTranscriptWords(tags.optString("name", "").trim())
                    val street = tags.optString("addr:street", "").trim()
                    val house = tags.optString("addr:housenumber", "").trim()
                    val dist = distanceMeters(lat, lon, pLat, pLon).toInt()
                    val addr = listOf(street, house).filter { it.isNotBlank() }.joinToString(" ")
                    GeoCandidate(
                        name = rawName.ifBlank { category.label },
                        areaLabel = if (addr.isNotBlank()) "$addr · ca. $dist m" else "ca. $dist m entfernt",
                        lat = pLat,
                        lon = pLon,
                        isStop = category.isTransitStopSearch
                    )
                }
            }.getOrDefault(emptyList())
            if (list.isNotEmpty()) return list
        }
        return emptyList()
    }

    suspend fun geocode(
        rawQuery: String,
        biasLat: Double,
        biasLon: Double,
        cityContext: String
    ): List<GeoCandidate> = withContext(Dispatchers.IO) {
        val cleanQuery = sanitizeTranscriptWords(rawQuery.trim())
        if (cleanQuery.isBlank()) return@withContext emptyList()
        val queryWords = cleanQuery.lowercase(Locale.GERMANY).split(Regex("\\s+")).filter { it.length >= 3 }

        val queries = mutableListOf(cleanQuery)
        if (queryWords.size == 1 && cityContext.isNotBlank() && !cleanQuery.contains(cityContext, true)) {
            queries += "$cleanQuery, $cityContext"
        }

        val raw = mutableListOf<GeoCandidate>()
        for (q in queries) {
            val arr = runCatching { JSONArray(httpGet("$BASE/geocode?text=${enc(q)}&place=$biasLat,$biasLon")) }
                .getOrNull() ?: continue
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val lat = obj.optDouble("lat", Double.NaN)
                val lon = obj.optDouble("lon", Double.NaN)
                if (lat.isNaN() || lon.isNaN()) continue
                if (distanceMeters(biasLat, biasLon, lat, lon) > 120_000) continue
                val type = obj.optString("type", "").uppercase(Locale.US)
                raw += GeoCandidate(
                    name = sanitizeTranscriptWords(obj.optString("name", cleanQuery).trim()),
                    areaLabel = areaLabel(obj),
                    lat = lat,
                    lon = lon,
                    isStop = type == "STOP",
                    isStreet = type == "ADDRESS"
                )
            }
        }

        val lowerQuery = cleanQuery.lowercase(Locale.GERMANY)
        fun score(c: GeoCandidate): Int {
            val name = c.name.lowercase(Locale.GERMANY)
            val full = "$name ${c.areaLabel.lowercase(Locale.GERMANY)}"
            val exact = when {
                name == lowerQuery -> 40
                name.contains(lowerQuery) -> 25
                else -> 0
            }
            return exact + queryWords.count { name.contains(it) } * 15 +
                queryWords.count { full.contains(it) } * 8 +
                if (c.isStop) 5 else if (c.isStreet) 2 else 0
        }

        val sorted = raw.sortedWith(
            compareByDescending<GeoCandidate> { score(it) }
                .thenBy { distanceMeters(biasLat, biasLon, it.lat, it.lon) }
        )
        dedupe(sorted, minMeters = 300.0)
    }

    private fun areaLabel(obj: JSONObject): String {
        val parts = mutableListOf<String>()
        val areas = obj.optJSONArray("areas")
        if (areas != null) {
            for (a in 0 until areas.length()) {
                val area = areas.optJSONObject(a) ?: continue
                val name = sanitizeTranscriptWords(area.optString("name", "").trim())
                val level = area.optInt("adminLevel", 0)
                if (name.isBlank() || name == "Deutschland" || name.startsWith("Regierungsbezirk", true)) continue
                if (level in 1..5) continue
                if (name !in parts) parts += name
            }
        }
        val zip = obj.optString("zip", "").trim()
        return "${if (zip.isNotBlank()) "$zip " else ""}${parts.takeLast(2).joinToString(", ")}".trim()
    }

    private fun dedupe(list: List<GeoCandidate>, minMeters: Double, max: Int = 6): List<GeoCandidate> {
        val out = mutableListOf<GeoCandidate>()
        for (c in list) {
            val isDuplicate = out.any {
                val d = distanceMeters(it.lat, it.lon, c.lat, c.lon)
                d < 15.0 || (d < minMeters && it.name.equals(c.name, ignoreCase = true))
            }
            if (!isDuplicate) out += c
            if (out.size >= max) break
        }
        return out
    }

    // ------------------------------------------------------------ Verbindungen

    suspend fun fetchConnections(
        startLat: Double,
        startLon: Double,
        destLat: Double,
        destLon: Double,
        destName: String,
        preferredMode: String? = null,
        departureMillis: Long? = null
    ): List<TransitConnection> = withContext(Dispatchers.IO) {
        val modes = when (preferredMode) {
            "BUS" -> "&transitModes=BUS"
            "SUBURBAN" -> "&transitModes=SUBURBAN,RAIL"
            "TRAM" -> "&transitModes=TRAM"
            "REGIONAL_RAIL" -> "&transitModes=REGIONAL_RAIL,REGIONAL_FAST_RAIL,RAIL"
            else -> ""
        }
        val ref = departureMillis ?: System.currentTimeMillis()
        val iso = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(ref).truncatedTo(ChronoUnit.SECONDS))
        val url = "$BASE/plan?fromPlace=$startLat,$startLon&toPlace=$destLat,$destLon" +
            "&time=${enc(iso)}&arriveBy=false&maxPreTransitTime=1800&maxPostTransitTime=1800" +
            "&numItineraries=10$modes"

        val list = MotisPlanParser.parse(httpGet(url, timeoutMs = 15_000), destName, destLat, destLon, ref)
        list.ifEmpty { listOf(MotisPlanParser.directWalk(startLat, startLon, destName, destLat, destLon, ref)) }
    }

    // ------------------------------------------------------------ App-Update

    suspend fun checkForUpdate(currentVersionCode: Int): AppUpdateInfo? = withContext(Dispatchers.IO) {
        runCatching {
            val obj = JSONObject(httpGet(RELEASE_URL))
            val title = obj.optString("name", "")
            val run = Regex("\\((\\d+)\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
                ?: return@runCatching null
            if (100 + run <= currentVersionCode) return@runCatching null
            val assets = obj.optJSONArray("assets") ?: return@runCatching null
            (0 until assets.length())
                .mapNotNull { assets.optJSONObject(it)?.optString("browser_download_url", "") }
                .firstOrNull { it.endsWith(".apk") }
                ?.let { AppUpdateInfo(title, it) }
        }.getOrNull()
    }

    suspend fun downloadApk(update: AppUpdateInfo, target: File): File = withContext(Dispatchers.IO) {
        val conn = URL(update.downloadUrl).openConnection() as HttpURLConnection
        try {
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            if (conn.responseCode !in 200..299) throw IllegalStateException("Download fehlgeschlagen (HTTP ${conn.responseCode})")
            val tmp = File(target.parentFile, target.name + ".part")
            conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) throw IllegalStateException("APK konnte nicht gespeichert werden")
            target
        } finally {
            conn.disconnect()
        }
    }
}
