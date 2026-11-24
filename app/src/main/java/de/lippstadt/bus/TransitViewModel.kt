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

    fun toggleGps() {
        if (gpsActive) stopGps() else refreshLocation()
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

    fun search(rawOverride: String? = null) {
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

                val category = parsed.poiCategory
                if (category != null) {
                    // Bei "Apotheke am Markt" um den Ankerort herum suchen statt um den Nutzer.
                    var centerLat = loc.lat
                    var centerLon = loc.lon
                    val anchor = parsed.poiAnchorPlace
                    if (anchor != null) {
                        status = "Suche $anchor …"
                        TransitApi.geocode(anchor, loc.lat, loc.lon, loc.city).firstOrNull()?.let {
                            centerLat = it.lat
                            centerLon = it.lon
                        }
                    }
                    status = "Suche ${category.label} …"
                    val pois = TransitApi.searchNearbyPois(centerLat, centerLon, category, loc.city, radiusMeters = 2500)
                    val pool = if (category.isTransitStopSearch) pois else pois.filter { !it.isStop }.ifEmpty { pois }
                    val sorted = pool.sortedBy { distanceMeters(centerLat, centerLon, it.lat, it.lon) }
                    if (sorted.isEmpty()) {
                        status = "Keine ${category.label} gefunden."
                        return@launch
                    }
                    places = sorted
                    isPoiResult = true
                    showWalkTo(loc, sorted.first())
                } else {
                    status = "Suche „$destText“ …"
                    val candidates = TransitApi.geocode(destText, loc.lat, loc.lon, loc.city)
                    if (candidates.isEmpty()) {
                        status = "Ziel „$destText“ nicht gefunden."
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
                if (isPoiResult) showWalkTo(loc, dest) else loadRoute(loc, dest)
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
