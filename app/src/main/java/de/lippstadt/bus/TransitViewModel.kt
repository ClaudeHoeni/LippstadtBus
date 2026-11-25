package de.lippstadt.bus

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.os.Bundle
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import de.lippstadt.bus.data.AppUpdateInfo
import de.lippstadt.bus.data.GeoCandidate
import de.lippstadt.bus.data.MotisPlanParser
import de.lippstadt.bus.data.TransitApi
import de.lippstadt.bus.data.TransitConnection
import de.lippstadt.bus.data.buildSpokenSummary
import de.lippstadt.bus.data.distanceMeters
import de.lippstadt.bus.data.parseNaturalTransitRequest
import de.lippstadt.bus.data.sanitizeTranscriptWords
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale

data class UserLocation(val lat: Double, val lon: Double, val city: String)

/**
 * Hält den kompletten Zustand der App. Überlebt Bildschirmdrehungen –
 * vorher gingen Suchergebnisse bei jeder Drehung verloren und TTS/Spracherkennung
 * wurden jedes Mal neu erzeugt.
 */
class TransitViewModel(app: Application) : AndroidViewModel(app) {

    private companion object {
        const val GPS_IDLE_MS = 2 * 60_000L
        const val FAVORITE_SLOTS = 4
    }

    private val ctx get() = getApplication<Application>()

    var query by mutableStateOf("")
    var status by mutableStateOf("Bereit. Ziel, Haltestelle, Arzt oder Bäckerei sagen.")
        private set
    var isLoading by mutableStateOf(false)
        private set
    var isListening by mutableStateOf(false)
        private set
    var isSpeaking by mutableStateOf(false)
        private set

    var gpsCity by mutableStateOf<String?>(null)
        private set
    var userLocation by mutableStateOf<UserLocation?>(null)
        private set
    var isLocating by mutableStateOf(false)
        private set

    var connections by mutableStateOf<List<TransitConnection>>(emptyList())
        private set
    var selectedConnection by mutableIntStateOf(0)
        private set
    var places by mutableStateOf<List<GeoCandidate>>(emptyList())
        private set
    var selectedPlace by mutableIntStateOf(0)
        private set
    var timeLabel by mutableStateOf<String?>(null)
        private set

    /** Suchbegriff der letzten Kategoriesuche (z. B. "Supermarkt") – für Google Maps. */
    var poiKeyword by mutableStateOf<String?>(null)
        private set

    /** Mehrere passende Straßen -> Auswahl-Popup. */
    var streetChoices by mutableStateOf<List<GeoCandidate>>(emptyList())
        private set

    var availableUpdate by mutableStateOf<AppUpdateInfo?>(null)
        private set
    var isUpdating by mutableStateOf(false)
        private set

    val activeConnection: TransitConnection? get() = connections.getOrNull(selectedConnection)
    val activePlace: GeoCandidate? get() = places.getOrNull(selectedPlace)

    private var searchJob: Job? = null
    private var activeMode: String? = null
    private var activeTimeMillis: Long? = null
    private var isPoiResult = false

    private val fusedLocation = LocationServices.getFusedLocationProviderClient(app)

    // ------------------------------------------------------------ Favoriten (4 Plätze, lokal gespeichert)

    private val prefs = app.getSharedPreferences("favoriten", android.content.Context.MODE_PRIVATE)

    var favorites by mutableStateOf(loadFavorites())
        private set

    private fun loadFavorites(): List<GeoCandidate?> = (0 until FAVORITE_SLOTS).map { i ->
        prefs.getString("fav_$i", null)?.let { raw ->
            runCatching {
                val o = org.json.JSONObject(raw)
                GeoCandidate(
                    name = o.getString("name"),
                    areaLabel = o.optString("area", ""),
                    lat = o.getDouble("lat"),
                    lon = o.getDouble("lon"),
                    isStop = o.optBoolean("stop", false)
                )
            }.getOrNull()
        }
    }

    /** Speichert das aktuell gewählte Ziel auf Platz [slot]. */
    fun saveFavorite(slot: Int) {
        val p = activePlace ?: run {
            status = "Erst ein Ziel suchen, dann als Favorit speichern."
            return
        }
        val json = org.json.JSONObject()
            .put("name", p.name).put("area", p.areaLabel)
            .put("lat", p.lat).put("lon", p.lon).put("stop", p.isStop)
        prefs.edit().putString("fav_$slot", json.toString()).apply()
        favorites = loadFavorites()
        status = "„${p.name}“ als Favorit ${slot + 1} gespeichert."
    }

    fun deleteFavorite(slot: Int) {
        prefs.edit().remove("fav_$slot").apply()
        favorites = loadFavorites()
        status = "Favorit ${slot + 1} gelöscht."
    }

    /** Verbindung zum Favoriten suchen. */
    fun goToFavorite(slot: Int) {
        val fav = favorites.getOrNull(slot) ?: return
        touch()
        stopSpeaking()
        query = fav.name
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            isLoading = true
            connections = emptyList()
            places = listOf(fav)
            streetChoices = emptyList()
            activeCategory = null
            poiKeyword = null
            selectedPlace = 0
            selectedConnection = 0
            activeMode = null
            activeTimeMillis = null
            timeLabel = null
            isPoiResult = false
            try {
                status = "Ermittle Standort …"
                val loc = locate()
                if (loc == null) {
                    status = "GPS-Standort nicht verfügbar."
                    return@launch
                }
                if (distanceMeters(loc.lat, loc.lon, fav.lat, fav.lon) <= 1_000) showWalkTo(loc, fav) else loadRoute(loc, fav)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status = "Fehler: ${e.localizedMessage ?: "Netzwerkproblem"}"
            } finally {
                isLoading = false
            }
        }
    }

    // ------------------------------------------------------------ Sprachausgabe

    private var ttsReady = false
    private val tts: TextToSpeech = TextToSpeech(app) { st ->
        if (st == TextToSpeech.SUCCESS) {
            ttsReady = true
            setupTts()
        }
    }

    private fun setupTts() {
        tts.language = Locale.GERMANY
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = updateSpeaking(true)
            override fun onDone(utteranceId: String?) = updateSpeaking(false)
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = updateSpeaking(false)
        })
    }

    private fun updateSpeaking(value: Boolean) {
        viewModelScope.launch { isSpeaking = value }
    }

    fun speak(text: String) {
        if (!ttsReady) {
            status = "Sprachausgabe auf diesem Gerät nicht verfügbar."
            return
        }
        tts.speak(sanitizeTranscriptWords(text), TextToSpeech.QUEUE_FLUSH, null, "summary")
    }

    fun stopSpeaking() {
        if (ttsReady) tts.stop()
        isSpeaking = false
    }

    fun toggleSpeakActive() {
        val conn = activeConnection ?: run {
            status = "Noch keine Verbindung zum Vorlesen."
            return
        }
        if (isSpeaking) stopSpeaking() else speak(buildSpokenSummary(conn))
    }

    // ------------------------------------------------------------ Spracherkennung

    private var recognizer: SpeechRecognizer? = null

    /** Muss auf dem Main-Thread aufgerufen werden und setzt RECORD_AUDIO voraus. */
    fun startVoiceInput() {
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
            status = "Spracherkennung auf diesem Gerät nicht verfügbar."
            return
        }
        if (isListening) {
            recognizer?.stopListening()
            isListening = false
            return
        }
        stopSpeaking()
        val rec = recognizer ?: SpeechRecognizer.createSpeechRecognizer(ctx).also { recognizer = it }
        rec.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                isListening = true
                status = "Höre zu … Ziel oder Wunsch nennen."
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { isListening = false }
            override fun onError(error: Int) {
                isListening = false
                status = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Nichts verstanden – bitte nochmal."
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Spracherkennung: kein Netz."
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Mikrofon-Berechtigung fehlt."
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Spracherkennung ist beschäftigt."
                    else -> "Spracheingabe fehlgeschlagen (Code $error)."
                }
            }
            override fun onResults(results: Bundle?) {
                isListening = false
                val spoken = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!spoken.isNullOrBlank()) search(spoken)
            }
            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { query = it }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "de-DE")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        rec.startListening(intent)
    }

    // ------------------------------------------------------------ Standort

    fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    // ------------------------------------------------------------ GPS an/aus

    /** GPS liefert laufend Positionen (Kompass/Entfernung aktuell). Geht nach 2 Min. ohne Bedienung aus. */
    var gpsActive by mutableStateOf(false)
        private set

    private var lastInteraction = System.currentTimeMillis()
    private var autoOffJob: Job? = null

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val l = result.lastLocation ?: return
            locatedAtMillis = System.currentTimeMillis()
            userLocation = UserLocation(l.latitude, l.longitude, userLocation?.city.orEmpty())
        }
    }

    /** Bei jeder Bedienung aufrufen – setzt den 2-Minuten-Timer zurück. */
    fun touch() {
        lastInteraction = System.currentTimeMillis()
    }

    @SuppressLint("MissingPermission")
    fun startGps() {
        touch()
        if (gpsActive || !hasLocationPermission()) return
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5_000)
            .setMinUpdateIntervalMillis(2_000)
            .build()
        runCatching { fusedLocation.requestLocationUpdates(request, locationCallback, Looper.getMainLooper()) }
            .onSuccess { gpsActive = true }
        autoOffJob?.cancel()
        autoOffJob = viewModelScope.launch {
            while (true) {
                delay(10_000)
                if (System.currentTimeMillis() - lastInteraction >= GPS_IDLE_MS) {
                    stopGps()
                    break
                }
            }
        }
    }

    fun stopGps() {
        autoOffJob?.cancel()
        autoOffJob = null
        runCatching { fusedLocation.removeLocationUpdates(locationCallback) }
        gpsActive = false
    }

    /** true, wenn der Nutzer GPS bewusst ausgeschaltet hat – dann beim App-Start nicht wieder einschalten. */
    private var gpsDisabledByUser = false

    fun toggleGps() {
        if (gpsActive) {
            gpsDisabledByUser = true
            stopGps()
        } else {
            gpsDisabledByUser = false
            refreshLocation()
        }
    }

    /** Beim Öffnen der App: GPS direkt einschalten (außer der Nutzer hat es ausgeschaltet). */
    fun onAppVisible() {
        checkForUpdate()
        if (!gpsDisabledByUser && hasLocationPermission()) refreshLocation()
    }

    /** Stadtname für die Suche; wartet notfalls kurz darauf (wurde sonst leer -> "Tivoli" in Metz). */
    private suspend fun cityFor(loc: UserLocation): String {
        loc.city.ifBlank { gpsCity.orEmpty() }.takeIf { it.isNotBlank() }?.let { return it }
        val city = withTimeoutOrNull(4_000) { resolveCity(loc.lat, loc.lon) }.orEmpty()
        if (city.isNotBlank()) {
            gpsCity = city
            if (userLocation?.lat == loc.lat && userLocation?.lon == loc.lon) userLocation = loc.copy(city = city)
        }
        return city
    }

    fun refreshLocation() {
        viewModelScope.launch { locate(maxAgeMs = 0) }
    }

    private var locatedAtMillis = 0L

    /**
     * Liefert den Standort schnell: ein frischer Standort (< [maxAgeMs]) wird wiederverwendet,
     * ein neuer GPS-Fix hat höchstens 6 s Zeit, danach gilt der letzte bekannte Standort.
     * Der Stadtname wird im Hintergrund ermittelt und blockiert die Suche nicht mehr.
     */
    @SuppressLint("MissingPermission")
    private suspend fun locate(maxAgeMs: Long = 90_000): UserLocation? {
        if (!hasLocationPermission()) return null
        startGps()
        val cached = userLocation
        if (cached != null && System.currentTimeMillis() - locatedAtMillis <= maxAgeMs) return cached

        isLocating = true
        return try {
            val loc = withTimeoutOrNull(6_000) {
                runCatching { fusedLocation.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null).await() }.getOrNull()
            } ?: runCatching { fusedLocation.lastLocation.await() }.getOrNull()
            if (loc == null) {
                cached
            } else {
                val prevCity = cached?.takeIf {
                    distanceMeters(it.lat, it.lon, loc.latitude, loc.longitude) < 3_000
                }?.city.orEmpty()
                locatedAtMillis = System.currentTimeMillis()
                val fresh = UserLocation(loc.latitude, loc.longitude, prevCity)
                userLocation = fresh
                if (prevCity.isBlank()) {
                    viewModelScope.launch {
                        val city = withTimeoutOrNull(5_000) { resolveCity(loc.latitude, loc.longitude) }
                        if (!city.isNullOrBlank()) {
                            gpsCity = city
                            if (userLocation === fresh) userLocation = fresh.copy(city = city)
                        }
                    }
                }
                fresh
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Vorher führte eine Exception hier (z. B. SecurityException) zum Absturz der App.
            cached
        } finally {
            isLocating = false
        }
    }

    private suspend fun resolveCity(lat: Double, lon: Double): String? {
        val local = withContext(Dispatchers.IO) {
            runCatching {
                @Suppress("DEPRECATION")
                Geocoder(ctx, Locale.GERMANY).getFromLocation(lat, lon, 1)?.firstOrNull()
                    ?.let { it.locality ?: it.subAdminArea }
            }.getOrNull()
        }
        return (local ?: TransitApi.reverseCityName(lat, lon))?.let { sanitizeTranscriptWords(it) }
    }

    // ------------------------------------------------------------ Suche

    /** Letzte Suche ohne Treffer – kann mit größerem Radius wiederholt werden. */
    var widenQuery by mutableStateOf<String?>(null)
        private set

    /** Suche mit größerem Radius wiederholen (Orte bis 100 km, Kategorien bis 10 km). */
    fun searchWider() {
        val q = widenQuery ?: return
        search(q, wide = true)
    }

    fun search(rawOverride: String? = null, wide: Boolean = false) {
        widenQuery = null
        touch()
        val raw = sanitizeTranscriptWords(rawOverride ?: query)
        if (raw.isBlank()) {
            status = "Bitte ein Ziel oder eine Frage eingeben."
            return
        }
        stopSpeaking()
        val parsed = parseNaturalTransitRequest(raw)
        val destText = sanitizeTranscriptWords(parsed.destination)
        query = destText

        // Laufende Suche abbrechen – sonst konnten sich alte und neue Ergebnisse überschreiben.
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            isLoading = true
            connections = emptyList()
            places = emptyList()
            streetChoices = emptyList()
            activeCategory = null
            selectedPlace = 0
            selectedConnection = 0
            activeMode = parsed.preferredMode
            activeTimeMillis = parsed.targetTimeMillis
            timeLabel = parsed.timeDisplayLabel
            try {
                status = "Ermittle Standort …"
                val loc = locate()
                if (loc == null) {
                    status = if (hasLocationPermission()) "GPS-Standort nicht verfügbar. Standortdienst aktivieren."
                    else "Standort-Berechtigung fehlt."
                    return@launch
                }

                val city = cityFor(loc)
                val category = parsed.poiCategory
                poiKeyword = category?.fallbackKeyword
                activeCategory = category
                if (category != null) {
                    // Bei "Apotheke am Markt" um den Ankerort herum suchen statt um den Nutzer.
                    var centerLat = loc.lat
                    var centerLon = loc.lon
                    val anchor = parsed.poiAnchorPlace
                    if (anchor != null) {
                        status = "Suche $anchor …"
                        TransitApi.geocode(anchor, loc.lat, loc.lon, city).firstOrNull()?.let {
                            centerLat = it.lat
                            centerLon = it.lon
                        }
                    }
                    status = "Suche ${category.label} …"
                    val pois = TransitApi.searchNearbyPois(
                        centerLat, centerLon, category, city,
                        radiusMeters = if (wide) 10_000 else 2500,
                        maxResults = if (wide) 15 else 10
                    )
                    val pool = if (category.isTransitStopSearch) pois else pois.filter { !it.isStop }.ifEmpty { pois }
                    val sorted = pool.sortedBy { distanceMeters(centerLat, centerLon, it.lat, it.lon) }
                    if (sorted.isEmpty()) {
                        status = if (wide) "Keine ${category.label} im Umkreis von 10 km gefunden."
                        else "Keine ${category.label} im Umkreis von 2,5 km gefunden. " +
                            "Evtl. liegt der Ort außerhalb – Radius vergrößern oder mit Ort suchen, z. B. „${category.label} am Markt“."
                        if (!wide) widenQuery = raw
                        return@launch
                    }
                    places = sorted
                    isPoiResult = true
                    showPlace(loc, sorted.first())
                } else {
                    if (parsed.isStreet) {
                        status = "Suche Straße „$destText“ …"
                        val streets = TransitApi.searchStreets(destText, loc.lat, loc.lon, city)
                        if (streets.isNotEmpty()) {
                            places = streets
                            isPoiResult = false
                            if (streets.size == 1) {
                                loadRoute(loc, streets.first())
                            } else {
                                streetChoices = streets
                                status = "${streets.size} passende Straßen – bitte wählen."
                            }
                            return@launch
                        }
                    }
                    status = "Suche „$destText“ …"
                    val candidates = TransitApi.geocode(destText, loc.lat, loc.lon, city, wide)
                    if (candidates.isEmpty()) {
                        val cityHint = city.ifBlank { "Ortsname" }
                        status = if (wide) "Ziel „$destText“ auch im Umkreis von 100 km nicht gefunden. " +
                            "Mit Ort versuchen, z. B. „$destText, $cityHint“."
                        else "Ziel „$destText“ nicht gefunden. Es liegt womöglich außerhalb des Suchradius " +
                            "(5 km, eindeutige Namen 50 km) – Radius vergrößern oder mit Ort suchen, z. B. „$destText, $cityHint“."
                        if (!wide) widenQuery = raw
                        return@launch
                    }
                    places = candidates
                    isPoiResult = false
                    loadRoute(loc, candidates.first())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status = "Fehler: ${e.localizedMessage ?: "Netzwerkproblem"}"
            } finally {
                isLoading = false
            }
        }
    }

    /** Nahe Orte (≤ 1 km) als Fußweg, weiter entfernte mit ÖPNV-Verbindung. */
    private suspend fun showPlace(loc: UserLocation, dest: GeoCandidate) {
        if (distanceMeters(loc.lat, loc.lon, dest.lat, dest.lon) <= 1_000) showWalkTo(loc, dest) else loadRoute(loc, dest)
    }

    private fun showWalkTo(loc: UserLocation, dest: GeoCandidate) {
        val conn = MotisPlanParser.directWalk(loc.lat, loc.lon, dest.fullDisplay, dest.lat, dest.lon, System.currentTimeMillis())
        connections = listOf(conn)
        selectedConnection = 0
        status = "${dest.name} · ${conn.totalWalkMeters} m zu Fuß"
    }

    private suspend fun loadRoute(loc: UserLocation, dest: GeoCandidate) {
        val timeHint = timeLabel?.let { " · $it" }.orEmpty()
        status = "Suche Verbindungen nach ${dest.name}$timeHint …"
        val results = TransitApi.fetchConnections(
            // Direkt ab Standort: der Routenplaner wählt die beste Haltestelle inkl. Fußweg selbst.
            // Vorher wurde erst separat die nächste Haltestelle gesucht (langsam, oft schlechtere Route).
            startLat = loc.lat,
            startLon = loc.lon,
            destLat = dest.lat,
            destLon = dest.lon,
            destName = dest.name,
            preferredMode = activeMode,
            departureMillis = activeTimeMillis
        )
        connections = results
        selectedConnection = 0
        status = when {
            results.isEmpty() -> "Keine Verbindung nach ${dest.name}$timeHint gefunden."
            results.first().firstTransitLeg != null ->
                "Ab ${results.first().firstTransitLeg!!.fromStop} → ${dest.fullDisplay}$timeHint"
            else -> "Ziel: ${dest.fullDisplay}$timeHint"
        }
    }

    private var activeCategory: de.lippstadt.bus.data.PoiCategory? = null
    private var moreJob: Job? = null

    /**
     * Karte verschoben/herausgezoomt: weitere Treffer der aktuellen Kategorie im sichtbaren Bereich
     * nachladen und an die Liste anhängen (max. 40).
     */
    fun loadMoreAround(lat: Double, lon: Double, radiusMeters: Int) {
        val category = activeCategory ?: return
        if (places.size >= 40) return
        moreJob?.cancel()
        moreJob = viewModelScope.launch {
            val more = runCatching {
                TransitApi.searchNearbyPois(
                    lat, lon, category, userLocation?.city.orEmpty(),
                    radiusMeters = radiusMeters.coerceIn(500, 8_000),
                    maxResults = 25
                )
            }.getOrDefault(emptyList())
            val merged = places.toMutableList()
            for (c in more) {
                val dup = merged.any { distanceMeters(it.lat, it.lon, c.lat, c.lon) < 30 }
                if (!dup && merged.size < 40) merged += c
            }
            if (merged.size != places.size) places = merged
        }
    }

    /** Fußweg zum auf der Karte gewählten Ziel. */
    var walkRoute by mutableStateOf<de.lippstadt.bus.data.WalkRoute?>(null)
        private set
    private var walkJob: Job? = null

    fun loadWalkRoute(index: Int) {
        val dest = places.getOrNull(index) ?: return
        val loc = userLocation ?: return
        walkJob?.cancel()
        walkRoute = null
        walkJob = viewModelScope.launch {
            walkRoute = TransitApi.fetchWalkRoute(loc.lat, loc.lon, dest.lat, dest.lon)
        }
    }

    fun chooseStreet(index: Int) {
        streetChoices = emptyList()
        selectPlace(index)
    }

    fun dismissStreetChoices() {
        streetChoices = emptyList()
    }

    fun selectConnection(index: Int) {
        touch()
        if (index !in connections.indices) return
        stopSpeaking()
        selectedConnection = index
    }

    fun selectPlace(index: Int) {
        touch()
        val dest = places.getOrNull(index) ?: return
        selectedPlace = index
        val loc = userLocation ?: return
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            isLoading = true
            try {
                if (isPoiResult) showPlace(loc, dest) else loadRoute(loc, dest)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status = "Fehler: ${e.localizedMessage ?: "Netzwerkproblem"}"
            } finally {
                isLoading = false
            }
        }
    }

    fun postStatus(message: String) {
        status = message
    }

    // ------------------------------------------------------------ Update

    private var lastUpdateCheck = 0L

    /** Prüft höchstens alle 10 Min. auf ein neues Release (beim Start und beim Zurückkehren in die App). */
    fun checkForUpdate() {
        val now = System.currentTimeMillis()
        if (now - lastUpdateCheck < 10 * 60_000L) return
        lastUpdateCheck = now
        viewModelScope.launch {
            TransitApi.checkForUpdate(BuildConfig.VERSION_CODE)?.let { availableUpdate = it }
        }
    }

    /** Build-Nummer wie im Release-Namen „Aktuelle Version (N)“. */
    val buildNumber: Int get() = BuildConfig.VERSION_CODE - 100

    fun installUpdate() {
        val upd = availableUpdate ?: return
        if (isUpdating) return
        if (!SystemActions.ensureInstallPermission(ctx)) {
            status = "Bitte „Unbekannte Apps installieren“ erlauben und erneut tippen."
            return
        }
        viewModelScope.launch {
            isUpdating = true
            try {
                val apk = TransitApi.downloadApk(upd, File(ctx.cacheDir, "update.apk"))
                // Nur installieren, wenn die Datei wirklich neuer ist. Schlug der Upload eines Builds fehl,
                // lag im Release noch die alte APK -> Endlosschleife "Update 114" mit Version 112.
                @Suppress("DEPRECATION")
                val apkCode = ctx.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)?.versionCode ?: 0
                if (apkCode <= BuildConfig.VERSION_CODE) {
                    apk.delete()
                    availableUpdate = null
                    lastUpdateCheck = System.currentTimeMillis()
                    status = "Update ist noch nicht fertig hochgeladen – bitte später erneut versuchen."
                    return@launch
                }
                SystemActions.installApk(ctx, apk)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status = "Update-Fehler: ${e.localizedMessage ?: ""}"
            } finally {
                isUpdating = false
            }
        }
    }

    override fun onCleared() {
        stopGps()
        recognizer?.destroy()
        tts.stop()
        tts.shutdown()
        super.onCleared()
    }
}
