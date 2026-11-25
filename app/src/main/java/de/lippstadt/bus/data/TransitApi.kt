package de.lippstadt.bus.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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

    internal fun httpPost(urlString: String, formBody: String, timeoutMs: Int = 10_000): String {
        val conn = URL(urlString).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.outputStream.use { it.write(formBody.toByteArray(Charsets.UTF_8)) }
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

    suspend fun searchNearbyPois(
        centerLat: Double,
        centerLon: Double,
        category: PoiCategory,
        cityContext: String,
        radiusMeters: Int = 2000,
        maxResults: Int = 10
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

        // Photon-Umkreissuche (OSM-Daten, nach Entfernung sortiert, < 1 s). Bei Haltestellen IMMER
        // zusätzlich – Transitous allein lieferte oft nur Haltestellen in einer Richtung.
        if (category.nameRegex != null) {
            // Küchen-Suche: Overpass (kennt "cuisine") und Photon (Namensmuster) gleichzeitig, Ergebnisse vereinen
            val viaOverpass = ioScope.async { overpassSearch(centerLat, centerLon, category, radiusMeters) }
            val viaPhoton = PoiParsers.applyNameRegex(
                photonNearby(centerLat, centerLon, category.copy(nameRegex = null), radiusMeters, 25), category
            )
            found += viaPhoton
            found += withTimeoutOrNull(if (viaPhoton.isEmpty()) 10_000 else 5_000) { viaOverpass.await() }.orEmpty()
        } else if (found.isEmpty() || category.isTransitStopSearch) {
            found += photonNearby(centerLat, centerLon, category, radiusMeters, maxResults)
        }

        // Reserve: Overpass (alle Server parallel, max. 10 s).
        if (found.isEmpty()) found += overpassSearch(centerLat, centerLon, category, radiusMeters)

        if (found.isEmpty()) {
            // Letzter Ausweg: Volltextsuche, nur Treffer im Umkreis.
            val keyword = sanitizeTranscriptWords(category.fallbackKeyword)
            val q = if (cityContext.isNotBlank()) "$keyword $cityContext" else keyword
            found += geocode(q, centerLat, centerLon, cityContext)
                .filter { distanceMeters(centerLat, centerLon, it.lat, it.lon) <= radiusMeters * 1.5 }
        }

        // Gleichnamige Bahnsteige/Masten einer Haltestelle nur einmal zählen, damit Platz für
        // Haltestellen in allen Richtungen bleibt.
        dedupe(
            found.sortedBy { distanceMeters(centerLat, centerLon, it.lat, it.lon) },
            minMeters = if (category.isTransitStopSearch) 300.0 else 60.0,
            max = maxResults
        )
    }

    /** Eigener Scope, damit blockierende HTTP-Aufrufe nicht abgewartet werden müssen, wenn ein anderer schneller war. */
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Startet alle Aufgaben parallel und liefert das erste Ergebnis != null (oder null nach [timeoutMs]). */
    private suspend fun <T : Any> firstResult(timeoutMs: Long, tasks: List<() -> T?>): T? {
        val channel = Channel<T?>(tasks.size)
        val jobs = tasks.map { task -> ioScope.launch { channel.send(runCatching(task).getOrNull()) } }
        return try {
            withTimeoutOrNull(timeoutMs) {
                var result: T? = null
                for (i in tasks.indices) {
                    result = channel.receive()
                    if (result != null) break
                }
                result
            }
        } finally {
            jobs.forEach { it.cancel() }
        }
    }

    private val OVERPASS_ENDPOINTS = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.private.coffee/api/interpreter",
        "https://maps.mail.ru/osm/tools/overpass/api/interpreter"
    )

    private suspend fun overpassSearch(lat: Double, lon: Double, category: PoiCategory, radius: Int): List<GeoCandidate> {
        val query = PoiParsers.buildOverpassQuery(category, lat, lon, radius)
        val body = "data=${enc(query)}"
        val tasks = OVERPASS_ENDPOINTS.map { ep ->
            {
                PoiParsers.applyNameFilter(
                    PoiParsers.parseOverpass(httpPost(ep, body, timeoutMs = 9_000), lat, lon, category), category
                ).takeIf { it.isNotEmpty() }
            }
        }
        return firstResult(10_000, tasks) ?: emptyList()
    }

    /**
     * Umkreissuche über Photon /reverse mit OSM-Tag-Filter. Die frühere Textsuche (/api?q=Supermarkt)
     * gewichtete den Standort kaum und lieferte z. B. Treffer auf Sardinien – daher kam nichts Brauchbares an.
     */
    private fun photonNearby(
        lat: Double,
        lon: Double,
        category: PoiCategory,
        radiusMeters: Int,
        maxResults: Int = 10
    ): List<GeoCandidate> {
        val tags = PoiParsers.photonTags(category)
        if (tags.isEmpty()) return emptyList()
        val tagParams = tags.joinToString("") { "&osm_tag=${enc(it)}" }
        val limit = if (category.nameFilter != null) 50 else maxOf(20, maxResults * 2).coerceAtMost(50)
        val radiusKm = String.format(Locale.US, "%.1f", radiusMeters / 1000.0)
        val url = "https://photon.komoot.io/reverse?lat=$lat&lon=$lon&radius=$radiusKm&limit=$limit&lang=de$tagParams"
        val list = runCatching { PoiParsers.parsePhoton(httpGet(url, timeoutMs = 8_000), lat, lon, category) }
            .getOrDefault(emptyList())
        return PoiParsers.applyNameFilter(list, category)
            .filter { distanceMeters(lat, lon, it.lat, it.lon) <= radiusMeters }
    }

    suspend fun geocode(
        rawQuery: String,
        biasLat: Double,
        biasLon: Double,
        cityContext: String,
        wide: Boolean = false
    ): List<GeoCandidate> = withContext(Dispatchers.IO) {
        val cleanQuery = sanitizeTranscriptWords(rawQuery.trim())
        if (cleanQuery.isBlank()) return@withContext emptyList()
        val queryWords = cleanQuery.lowercase(Locale.GERMANY).split(Regex("\\s+")).filter { it.length >= 3 }

        // Transitous gewichtet den Standort kaum ("Tivoli" -> Metz). Daher zusätzlich mit Stadtnamen
        // und parallel Photon mit starker Standort-Gewichtung abfragen.
        val queries = mutableListOf(cleanQuery)
        if (queryWords.size <= 2 && cityContext.isNotBlank() && !cleanQuery.contains(cityContext, true)) {
            queries += "$cleanQuery, $cityContext"
        }
        val tasks: List<() -> List<GeoCandidate>> = queries.map { q -> { transitousGeocode(q, biasLat, biasLon) } } +
            listOf({ photonGeocode(cleanQuery, biasLat, biasLon) })
        val raw = coroutineScope {
            tasks.map { t -> async { runCatching { t() }.getOrDefault(emptyList()) } }.awaitAll().flatten()
        }

        val sorted = GeoRanking.rank(raw, cleanQuery, biasLat, biasLon, cityContext, wide)
        dedupe(sorted, minMeters = 300.0)
    }

    /**
     * Straßensuche im Umkreis (Standard 10 km), fehlertolerant: probiert auch zusammengeschriebene
     * Varianten ("Gottes Garten" -> "Gottesgarten"). Gleiche Straße (mehrere Abschnitte) nur einmal.
     */
    suspend fun searchStreets(
        query: String,
        lat: Double,
        lon: Double,
        city: String,
        radiusMeters: Int = 10_000
    ): List<GeoCandidate> = withContext(Dispatchers.IO) {
        val variants = StreetMatch.variants(sanitizeTranscriptWords(query))
        val tasks: List<() -> List<GeoCandidate>> = variants.map { v ->
            {
                val url = "https://photon.komoot.io/api/?q=${enc(v)}&lat=$lat&lon=$lon" +
                    "&location_bias_scale=0.0&zoom=14&limit=15&lang=de&osm_tag=highway"
                PoiParsers.parsePhotonPlaces(httpGet(url, timeoutMs = 8_000))
            }
        } + listOfNotNull(
            city.takeIf { it.isNotBlank() }?.let { c -> { transitousGeocode("${variants.first()}, $c", lat, lon) } }
        )
        val raw = coroutineScope {
            tasks.map { t -> async { runCatching { t() }.getOrDefault(emptyList()) } }.awaitAll().flatten()
        }
        val fuzzy = raw
            .filter { c -> !c.isStop && variants.any { StreetMatch.similar(c.name, it) } }
            .filter { distanceMeters(lat, lon, it.lat, it.lon) <= radiusMeters }
            .sortedBy { distanceMeters(lat, lon, it.lat, it.lon) }
        // Exakte Namen ("Berlinickeplatz") vor Teiltreffern ("Schule am Berlinickeplatz") – gibt es
        // exakte, werden nur diese angeboten (kein unnötiges Auswahl-Popup).
        val variantKeys = variants.map { StreetMatch.normalize(it) }.toSet()
        val exact = fuzzy.filter { StreetMatch.normalize(it.name) in variantKeys }
        val matches = exact.ifEmpty { fuzzy }
        val out = mutableListOf<GeoCandidate>()
        for (c in matches) {
            val key = StreetMatch.normalize(c.name)
            val sameStreet = out.any {
                StreetMatch.normalize(it.name) == key && distanceMeters(it.lat, it.lon, c.lat, c.lon) < 3_000
            }
            if (!sameStreet) out += c.copy(isStreet = true)
            if (out.size >= 8) break
        }
        out
    }

    private fun transitousGeocode(q: String, biasLat: Double, biasLon: Double): List<GeoCandidate> {
        val arr = JSONArray(httpGet("$BASE/geocode?text=${enc(q)}&place=$biasLat,$biasLon"))
        return (0 until arr.length()).mapNotNull { i ->
            val obj = arr.optJSONObject(i) ?: return@mapNotNull null
            val lat = obj.optDouble("lat", Double.NaN)
            val lon = obj.optDouble("lon", Double.NaN)
            if (lat.isNaN() || lon.isNaN()) return@mapNotNull null
            val type = obj.optString("type", "").uppercase(Locale.US)
            GeoCandidate(
                name = sanitizeTranscriptWords(obj.optString("name", q).trim()),
                areaLabel = areaLabel(obj),
                lat = lat,
                lon = lon,
                isStop = type == "STOP",
                isStreet = type == "ADDRESS"
            )
        }
    }

    private fun photonGeocode(q: String, biasLat: Double, biasLon: Double): List<GeoCandidate> {
        val url = "https://photon.komoot.io/api/?q=${enc(q)}&lat=$biasLat&lon=$biasLon" +
            "&location_bias_scale=0.0&zoom=14&limit=10&lang=de"
        return PoiParsers.parsePhotonPlaces(httpGet(url, timeoutMs = 8_000))
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

    /** Namen vergleichbar machen: "S+U Tempelhof (Berlin)", "Lippstadt, Tivoli" -> "tempelhof", "tivoli". */
    private fun nameKey(name: String): String = StreetMatch.normalize(
        name.replace(Regex("\\(.*?\\)"), "")
            .substringAfter(", ")
            .replace(Regex("^(S\\+U|S|U)\\s+"), "")
            .trim()
    )

    private fun sameName(a: String, b: String): Boolean = nameKey(a) == nameKey(b)

    private fun dedupe(list: List<GeoCandidate>, minMeters: Double, max: Int = 6): List<GeoCandidate> {
        val out = mutableListOf<GeoCandidate>()
        for (c in list) {
            val isDuplicate = out.any {
                val d = distanceMeters(it.lat, it.lon, c.lat, c.lon)
                d < 15.0 || (d < minMeters && sameName(it.name, c.name))
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

    /** Beste Fußroute (Geometrie, Länge, Dauer) über den Routenplaner. */
    suspend fun fetchWalkRoute(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): WalkRoute? =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = "$BASE/plan?fromPlace=$fromLat,$fromLon&toPlace=$toLat,$toLon" +
                    "&directModes=WALK&transitModes=WALK&maxDirectTime=7200"
                val direct = JSONObject(httpGet(url, timeoutMs = 12_000)).optJSONArray("direct")
                    ?: return@runCatching null
                val it = direct.optJSONObject(0) ?: return@runCatching null
                val legs = it.optJSONArray("legs") ?: return@runCatching null
                val points = mutableListOf<Pair<Double, Double>>()
                var dist = 0.0
                for (i in 0 until legs.length()) {
                    val leg = legs.optJSONObject(i) ?: continue
                    dist += leg.optDouble("distance", 0.0).let { d -> if (d.isNaN()) 0.0 else d }
                    val g = leg.optJSONObject("legGeometry") ?: continue
                    points += PolylineCodec.decode(g.optString("points", ""), g.optInt("precision", 7))
                }
                if (points.size < 2) null
                else WalkRoute(points, dist.toInt(), maxOf(1, it.optInt("duration", 0) / 60))
            }.getOrNull()
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
            val urls = (0 until assets.length())
                .mapNotNull { assets.optJSONObject(it)?.optString("browser_download_url", "") }
                .filter { it.endsWith(".apk") }
            // Gezielt ABFAHRTEN.apk nehmen – im Release lag noch eine alte app-debug.apk
            (urls.firstOrNull { it.endsWith("/ABFAHRTEN.apk") } ?: urls.firstOrNull())
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
