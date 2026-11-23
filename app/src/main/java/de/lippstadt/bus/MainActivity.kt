package de.lippstadt.bus

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.os.Bundle
import android.net.Uri
import android.provider.AlarmClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class GeoCandidate(
    val name: String,
    val areaLabel: String,
    val lat: Double,
    val lon: Double,
    val isStop: Boolean = false,
    val isStreet: Boolean = false
) {
    val fullDisplay: String
        get() = if (areaLabel.isNotBlank() && !name.contains(areaLabel, ignoreCase = true)) {
            "$name ($areaLabel)"
        } else {
            name
        }
}

data class RouteLegInfo(
    val isWalk: Boolean,
    val isTransfer: Boolean,
    val lineName: String,
    val direction: String,
    val fromStop: String,
    val toStop: String,
    val startTime: String,
    val endTime: String,
    val delayMinutes: Int,
    val durationMinutes: Int,
    val distanceMeters: Int
)

data class TransitConnection(
    val summaryLines: String,
    val firstLine: String,
    val firstDirection: String,
    val firstDepartureTime: String,
    val finalArrivalTime: String,
    val departureMillis: Long,
    val arrivalMillis: Long,
    val firstDelayMinutes: Int,
    val totalDurationMinutes: Int,
    val transfersCount: Int,
    val legs: List<RouteLegInfo>
)

data class AppUpdateInfo(
    val title: String,
    val downloadUrl: String
)

class MainActivity : ComponentActivity() {
    companion object {
        const val ACTION_VOICE_SEARCH = "de.lippstadt.bus.ACTION_VOICE_SEARCH"
    }

    private var voiceTriggerCounter by mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.action == ACTION_VOICE_SEARCH) {
            voiceTriggerCounter++
        }
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    TransitFinderScreen(externalVoiceTrigger = voiceTriggerCounter)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == ACTION_VOICE_SEARCH) {
            voiceTriggerCounter++
        }
    }
}

fun formatGermanLineName(rawMode: String, routeShortName: String, routeLongName: String): String {
    val shortClean = routeShortName.trim()
    val germanType = when (rawMode.uppercase(Locale.US)) {
        "BUS" -> "Bus"
        "SUBWAY" -> "U-Bahn"
        "SUBURBAN" -> "S-Bahn"
        "TRAM" -> "Tram"
        "REGIONAL_RAIL", "RAIL" -> "Zug"
        "HIGHSPEED_RAIL", "LONG_DISTANCE" -> "Fernzug"
        "FERRY" -> "Fähre"
        else -> "ÖPNV"
    }

    if (shortClean.isBlank()) {
        return routeLongName.ifBlank { germanType }
    }

    val upperShort = shortClean.uppercase(Locale.US)
    return when {
        germanType == "U-Bahn" && upperShort.startsWith("U") -> "U-Bahn $shortClean"
        germanType == "S-Bahn" && upperShort.startsWith("S") -> "S-Bahn $shortClean"
        upperShort.startsWith("BUS ") -> shortClean
        upperShort.startsWith("RE") || upperShort.startsWith("RB") || upperShort.startsWith("IC") -> shortClean
        else -> "$germanType $shortClean"
    }
}

private fun formatTimeForSpeech(hhMm: String): String {
    val parts = hhMm.split(":")
    if (parts.size != 2) return hhMm
    val hour = parts[0].toIntOrNull() ?: return hhMm
    val min = parts[1].toIntOrNull() ?: return hhMm
    return if (min == 0) "$hour Uhr" else "$hour Uhr $min"
}

fun buildSpokenSummary(conn: TransitConnection): String {
    val sb = StringBuilder()
    val delayNote = if (conn.firstDelayMinutes > 0) {
        ", aktuell ${conn.firstDelayMinutes} Minuten Verspätung"
    } else {
        ""
    }

    val transitLegs = conn.legs.filter { !it.isWalk && !it.isTransfer }
    val firstTransit = transitLegs.firstOrNull()
    if (firstTransit != null) {
        sb.append("Abfahrt um ${formatTimeForSpeech(conn.firstDepartureTime)}$delayNote ")
        sb.append("ab ${firstTransit.fromStop} mit ${firstTransit.lineName} ")
        sb.append("Richtung ${firstTransit.direction}. ")
    }

    for (leg in conn.legs) {
        if (leg.isTransfer) {
            sb.append("Umstieg an ${leg.fromStop}, ${leg.durationMinutes} Minuten Übergang. ")
        } else if (!leg.isWalk && leg != firstTransit) {
            sb.append("Weiter um ${formatTimeForSpeech(leg.startTime)} mit ${leg.lineName} ")
            sb.append("Richtung ${leg.direction}. ")
        }
    }

    sb.append("Ankunft am Ziel um ${formatTimeForSpeech(conn.finalArrivalTime)}. ")
    sb.append("Gesamtdauer ${conn.totalDurationMinutes} Minuten.")
    return sb.toString()
}

@Composable
fun TransitFinderScreen(externalVoiceTrigger: Int) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val fusedLocationClient = remember { LocationServices.getFusedLocationProviderClient(context) }

    var detectedGpsCity by remember { mutableStateOf<String?>(null) }

    var destinationInput by remember { mutableStateOf("") }
    var statusMessage by remember { mutableStateOf("Bereit. Haltestelle, Ziel oder Bäckerei/Imbiss eingeben.") }
    var isLoading by remember { mutableStateOf(false) }
    var isListening by remember { mutableStateOf(false) }
    var isSpeaking by remember { mutableStateOf(false) }

    var searchJob by remember { mutableStateOf<Job?>(null) }
    var isNearbySearch by remember { mutableStateOf(false) }
    var connections by remember { mutableStateOf<List<TransitConnection>>(emptyList()) }
    var selectedConnectionIndex by remember { mutableStateOf(0) }
    var expandedLegIndex by remember { mutableStateOf<Int?>(null) }
    var alternativesDropdownExpanded by remember { mutableStateOf(false) }

    // Alternative Orte/Haltestellen (als sauberes Dropdown statt Popup-Dialog!)
    var placeCandidates by remember { mutableStateOf<List<GeoCandidate>>(emptyList()) }
    var selectedPlaceIndex by remember { mutableStateOf(0) }
    var placesDropdownExpanded by remember { mutableStateOf(false) }
    var activeStartCoords by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var activePreferredMode by remember { mutableStateOf<String?>(null) }
    var activeModeDisplay by remember { mutableStateOf<String?>(null) }
    var activeTargetTimeMillis by remember { mutableStateOf<Long?>(null) }
    var activeTimeLabel by remember { mutableStateOf<String?>(null) }

    var availableUpdate by remember { mutableStateOf<AppUpdateInfo?>(null) }
    var isUpdating by remember { mutableStateOf(false) }

    var ttsReady by remember { mutableStateOf(false) }
    val tts = remember {
        var engine: TextToSpeech? = null
        engine = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                engine?.language = Locale.GERMANY
                ttsReady = true
            }
        }
        engine
    }

    DisposableEffect(Unit) {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { isSpeaking = true }
            override fun onDone(utteranceId: String?) { isSpeaking = false }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { isSpeaking = false }
        })
        onDispose {
            tts?.stop()
            tts?.shutdown()
        }
    }

    fun toggleSpeakConnection(conn: TransitConnection) {
        if (tts == null || !ttsReady) {
            statusMessage = "Sprachausgabe auf diesem Gerät nicht verfügbar."
            return
        }
        if (isSpeaking) {
            tts.stop()
            isSpeaking = false
        } else {
            val summaryText = buildSpokenSummary(conn)
            tts.speak(summaryText, TextToSpeech.QUEUE_FLUSH, null, "conn_summary")
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun detectCurrentLocation(): Triple<Double, Double, String>? {
        val hasLoc = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasLoc) return null

        val location = fusedLocationClient.getCurrentLocation(
            Priority.PRIORITY_HIGH_ACCURACY, null
        ).await() ?: fusedLocationClient.lastLocation.await() ?: return null

        val city = resolveCityNameFromCoords(context, location.latitude, location.longitude) ?: ""
        if (city.isNotBlank()) {
            detectedGpsCity = city
        }
        return Triple(location.latitude, location.longitude, city)
    }

    LaunchedEffect(Unit) {
        availableUpdate = checkForGitHubUpdate(context)
        try { detectCurrentLocation() } catch (_: Exception) { /* Search reports location failures. */ }
    }

    suspend fun loadRouteForPlace(
        startLat: Double,
        startLon: Double,
        dest: GeoCandidate,
        preferredMode: String?,
        modeDisplay: String?,
        targetTimeMillis: Long? = activeTargetTimeMillis,
        timeLabel: String? = activeTimeLabel
    ) {
        val modeHint = if (modeDisplay != null) " ($modeDisplay)" else ""
        val timeHint = if (timeLabel != null) " · $timeLabel" else ""
        statusMessage = "Suche$modeHint → ${dest.name}$timeHint..."
        val results = fetchConnectionsForCoords(
            startLat = startLat,
            startLon = startLon,
            destLat = dest.lat,
            destLon = dest.lon,
            destName = dest.name,
            preferredMode = preferredMode,
            customDepartureMillis = targetTimeMillis
        )
        connections = results
        selectedConnectionIndex = 0
        expandedLegIndex = null
        alternativesDropdownExpanded = false
        statusMessage = if (results.isEmpty() && isNearbySearch) {
            "Gefunden: ${dest.fullDisplay}. Keine ÖPNV-Verbindung erforderlich oder verfügbar; Fußweg in Karte öffnen."
        } else if (results.isEmpty()) {
            "Keine Verbindung$modeHint nach ${dest.name}$timeHint gefunden."
        } else {
            "Ziel: ${dest.fullDisplay}$timeHint"
        }
    }

    @SuppressLint("MissingPermission")
    fun searchTransit(rawQueryOverride: String? = null) {
        val rawText = (rawQueryOverride ?: destinationInput).trim()
        if (rawText.isBlank()) {
            statusMessage = "Bitte ein Ziel oder eine Frage eingeben."
            return
        }

        if (isSpeaking) {
            tts?.stop()
            isSpeaking = false
        }

        val parsed = try {
            parseNaturalTransitRequest(rawText)
        } catch (e: IllegalArgumentException) {
            statusMessage = e.message ?: "Datum oder Uhrzeit ungültig."
            return
        }
        searchJob?.cancel()
        isNearbySearch = parsed.poiCategory != null
        searchJob = scope.launch {
            isLoading = true
            connections = emptyList()
            placeCandidates = emptyList()
            selectedPlaceIndex = 0
            selectedConnectionIndex = 0
            alternativesDropdownExpanded = false
            placesDropdownExpanded = false
            activePreferredMode = parsed.preferredMode
            activeModeDisplay = parsed.modeDisplayName
            activeTargetTimeMillis = parsed.targetTimeMillis
            activeTimeLabel = parsed.timeDisplayLabel

            try {
                statusMessage = "Ermittle aktuellen GPS-Standort..."
                val locData = detectCurrentLocation()
                if (locData == null) {
                    statusMessage = "GPS-Standort nicht verfügbar. Bitte Standortdienst aktivieren."
                    isLoading = false
                    return@launch
                }

                val (baseLat, baseLon, cityContextName) = locData

                // 1. Startpunkt bestimmen (entweder explizit "von X nach Y" oder aktuelles GPS)
                var startLat = baseLat
                var startLon = baseLon
                if (!parsed.origin.isNullOrBlank()) {
                    val originCands = searchTransitStopsAndPlaces(parsed.origin, baseLat, baseLon, cityContextName)
                    val bestOrigin = originCands.firstOrNull()
                    if (bestOrigin == null) {
                        statusMessage = "Startort \"${parsed.origin}\" nicht gefunden."
                        isLoading = false
                        return@launch
                    }
                    startLat = bestOrigin.lat
                    startLon = bestOrigin.lon
                }
                activeStartCoords = startLat to startLon

                // 2. Zielkandidaten ermitteln (POIs oder ÖPNV-Haltestellen/Orte)
                val candidates: List<GeoCandidate> = if (parsed.poiCategory != null) {
                    var centerLat = baseLat
                    var centerLon = baseLon
                    var anchorLabel = "deiner Nähe"

                    if (!parsed.poiAnchorPlace.isNullOrBlank()) {
                        val anchorCands = searchTransitStopsAndPlaces(parsed.poiAnchorPlace, baseLat, baseLon, cityContextName)
                        val anchor = anchorCands.firstOrNull()
                        if (anchor == null) {
                            statusMessage = "Ort \"${parsed.poiAnchorPlace}\" nicht gefunden."
                            isLoading = false
                            return@launch
                        }
                        centerLat = anchor.lat
                        centerLon = anchor.lon
                        anchorLabel = anchor.name
                    }

                    statusMessage = "Suche ${parsed.poiCategory.label} bei $anchorLabel..."
                    searchNearbyPoisOverpass(
                        centerLat = centerLat,
                        centerLon = centerLon,
                        category = parsed.poiCategory,
                        radiusMeters = if (parsed.poiAnchorPlace != null) 1000 else 2500
                    )
                } else {
                    searchTransitStopsAndPlaces(parsed.destination, startLat, startLon, cityContextName)
                }

                if (candidates.isEmpty()) {
                    statusMessage = "Ziel \"${parsed.destination}\" nicht gefunden."
                    isLoading = false
                    return@launch
                }

                // Immer direkt den besten Treffer (ÖPNV-Haltestelle zuerst!) nehmen, Rest ins Dropdown!
                placeCandidates = candidates
                selectedPlaceIndex = 0
                loadRouteForPlace(
                    startLat = startLat,
                    startLon = startLon,
                    dest = candidates.first(),
                    preferredMode = parsed.preferredMode,
                    modeDisplay = parsed.modeDisplayName
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                statusMessage = "Fehler: ${e.localizedMessage ?: "Netzwerkproblem"}"
            } finally {
                if (kotlinx.coroutines.currentCoroutineContext().isActive) isLoading = false
            }
        }
    }

    val speechRecognizer = remember {
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            SpeechRecognizer.createSpeechRecognizer(context)
        } else null
    }

    DisposableEffect(Unit) {
        onDispose { speechRecognizer?.destroy() }
    }

    fun startVoiceInput() {
        if (speechRecognizer == null) {
            statusMessage = "Spracherkennung auf diesem Gerät nicht verfügbar."
            return
        }
        if (isSpeaking) {
            tts?.stop()
            isSpeaking = false
        }
        val recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "de-DE")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "z. B. Rathaus Steglitz oder nächste Bäckerei")
        }
        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                isListening = true
                statusMessage = "Höre zu... Bitte Ziel nennen."
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { isListening = false }
            override fun onError(error: Int) {
                isListening = false
                statusMessage = "Spracheingabe nicht verstanden (Code $error)."
            }
            override fun onResults(results: Bundle?) {
                isListening = false
                val spoken = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                if (!spoken.isNullOrBlank()) {
                    searchTransit(rawQueryOverride = spoken)
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        speechRecognizer.startListening(recognizerIntent)
    }

    var pendingVoiceAfterPermission by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        val audioOk = perms[Manifest.permission.RECORD_AUDIO] == true
        val locOk = perms[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                perms[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (locOk) {
            scope.launch { detectCurrentLocation() }
        }
        if (pendingVoiceAfterPermission) {
            pendingVoiceAfterPermission = false
            if (audioOk) {
                startVoiceInput()
            } else {
                statusMessage = "Mikrofon-Berechtigung wurde abgelehnt."
            }
        }
    }

    fun triggerVoiceWithPermissionCheck() {
        val hasAudio = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        val hasLoc = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (hasAudio && hasLoc) {
            startVoiceInput()
        } else {
            pendingVoiceAfterPermission = true
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.RECORD_AUDIO
                )
            )
        }
    }

    LaunchedEffect(Unit) {
        val hasLoc = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasLoc) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.RECORD_AUDIO
                )
            )
        }
    }

    LaunchedEffect(externalVoiceTrigger) {
        if (externalVoiceTrigger > 0) {
            triggerVoiceWithPermissionCheck()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(14.dp)
    ) {
        // KOPFZEILE
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Abfahrten Next&Here",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.secondaryContainer
            ) {
                Text(
                    text = if (!detectedGpsCity.isNullOrBlank()) "📍 $detectedGpsCity" else "📍 GPS aktiv",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
        }

        // UPDATE-BUTTON NUR WENN WIRKLICH NEUERES RELEASE ONLINE
        availableUpdate?.let { upd ->
            Spacer(modifier = Modifier.height(6.dp))
            Button(
                onClick = {
                    scope.launch {
                        isUpdating = true
                        try {
                            downloadAndInstallApk(context, upd)
                        } catch (e: Exception) {
                            statusMessage = "Update-Fehler: ${e.localizedMessage}"
                        } finally {
                            isUpdating = false
                        }
                    }
                },
                enabled = !isUpdating,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (isUpdating) "Lade Update..." else "Update installieren: ${upd.title}")
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // SUCHZEILE + MIKROFON
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = destinationInput,
                onValueChange = { destinationInput = it },
                label = { Text("Haltestelle, Ziel oder Bäckerei...") },
                placeholder = { Text("z. B. Rathaus Steglitz") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = { triggerVoiceWithPermissionCheck() },
                modifier = Modifier.height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isListening) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
            ) {
                Text(text = if (isListening) "..." else "MIC", fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(modifier = Modifier.height(6.dp))

        Button(
            onClick = { searchTransit() },
            enabled = !isLoading,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (isLoading) "Suche läuft..." else "Verbindung finden")
        }

        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = statusMessage,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(8.dp))

        if (isNearbySearch) {
            placeCandidates.getOrNull(selectedPlaceIndex)?.let { place ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(place.name, fontWeight = FontWeight.Bold)
                        Text(place.areaLabel)
                        OutlinedButton(onClick = {
                            val uri = Uri.parse("geo:${place.lat},${place.lon}?q=${place.lat},${place.lon}(${Uri.encode(place.name)})")
                            try {
                                context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                            } catch (_: ActivityNotFoundException) {
                                statusMessage = "Keine Karten-App installiert. Ziel: ${place.lat}, ${place.lon}"
                            }
                        }) { Text("In Karte öffnen / Fußweg planen") }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
        }

        // HAUPTERGEBNIS: BESTE VERBINDUNG DIE JETZT ABFÄHRT
        val activeConn = connections.getOrNull(selectedConnectionIndex)
        if (activeConn != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = activeConn.summaryLines,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )
                        val delayText = if (activeConn.firstDelayMinutes > 0) " (+${activeConn.firstDelayMinutes} Min)" else ""
                        Text(
                            text = "${activeConn.firstDepartureTime}$delayText",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = if (activeConn.firstDelayMinutes > 2) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    val transferLabel = when (activeConn.transfersCount) {
                        0 -> if (activeConn.legs.all { it.isWalk }) "Fußweg" else "Direktverbindung"
                        1 -> "1 Umstieg"
                        else -> "${activeConn.transfersCount} Umstiege"
                    }
                    Text(
                        text = "Ankunft ${activeConn.finalArrivalTime} Uhr · Dauer ${activeConn.totalDurationMinutes} Min. · $transferLabel",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    FilledTonalButton(
                        onClick = { toggleSpeakConnection(activeConn) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = if (isSpeaking) "⏹ Vorlesen stoppen" else "🔊 Vorlesen",
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // WECKER-NACHFRAGE (sobald die Abfahrt mindestens 12 Minuten in der Zukunft liegt)
                    val minsUntilDep = ((activeConn.departureMillis - System.currentTimeMillis()) / 60_000L).toInt()
                    if (minsUntilDep >= 12 && activeConn.legs.any { !it.isWalk && !it.isTransfer }) {
                        val defaultLeadMins = when {
                            minsUntilDep >= 45 -> 20
                            minsUntilDep >= 25 -> 15
                            else -> 10
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f),
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .padding(10.dp)
                        ) {
                            Text(
                                text = "⏰ Soll ich dir den Wecker auf $defaultLeadMins Minuten vorher zur Erinnerung stellen?",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                listOf(10, 15, 20, 30).filter { it <= minsUntilDep - 2 }.take(3).forEach { mins ->
                                    OutlinedButton(
                                        onClick = {
                                            statusMessage = setDepartureAlarm(
                                                context = context,
                                                departureMillis = activeConn.departureMillis,
                                                minutesBefore = mins,
                                                label = "Abfahrt ${activeConn.firstLine} (${activeConn.firstDepartureTime} Uhr)"
                                            )
                                        },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text(text = "$mins Min. vorher", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    activeConn.legs.forEachIndexed { idx, leg ->
                        if (idx > 0) {
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                        if (leg.isTransfer) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.85f),
                                        shape = RoundedCornerShape(6.dp)
                                    )
                                    .padding(horizontal = 10.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val distNote = if (leg.distanceMeters > 0) ", ${leg.distanceMeters} m Fußweg" else ""
                                Text(
                                    text = "↻ Umstieg an ${leg.fromStop} (${leg.durationMinutes} Min. Übergang$distNote)",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                            }
                        } else if (leg.isWalk) {
                            Text(
                                text = "↳ Fußweg (${leg.durationMinutes} Min., ${leg.distanceMeters} m) → ${leg.toStop}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        } else {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                    .padding(10.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "${leg.lineName} → ${leg.direction}",
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.bodyLarge,
                                        modifier = Modifier.weight(1f)
                                    )
                                    val legDelay = if (leg.delayMinutes > 0) " (+${leg.delayMinutes}')" else ""
                                    Text(
                                        text = "${leg.startTime}$legDelay",
                                        fontWeight = FontWeight.Bold,
                                        color = if (leg.delayMinutes > 2) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                                    )
                                }
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "Ab ${leg.startTime}: ${leg.fromStop}",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    text = "An ${leg.endTime}: ${leg.toStop}",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                }
            }
        }

        // DROPDOWN 1: ALTERNATIVEN (SPÄTERE / ANDERE VERBINDUNGEN)
        if (connections.size > 1) {
            Spacer(modifier = Modifier.height(10.dp))
            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { alternativesDropdownExpanded = !alternativesDropdownExpanded },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Alternativen (${connections.size - 1} weitere)",
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(text = if (alternativesDropdownExpanded) "▲" else "▼")
                    }
                }

                DropdownMenu(
                    expanded = alternativesDropdownExpanded,
                    onDismissRequest = { alternativesDropdownExpanded = false },
                    modifier = Modifier.fillMaxWidth(0.92f)
                ) {
                    connections.forEachIndexed { index, conn ->
                        val isSelected = index == selectedConnectionIndex
                        val delayStr = if (conn.firstDelayMinutes > 0) " (+${conn.firstDelayMinutes}')" else ""
                        val bestTag = if (index == 0) " · Beste" else ""
                        val transferTag = if (conn.transfersCount == 0) "Direkt" else "${conn.transfersCount}× Umstieg"
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(
                                        text = "${conn.firstDepartureTime}$delayStr  |  ${conn.summaryLines}$bestTag",
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "An ${conn.finalArrivalTime} Uhr (${conn.totalDurationMinutes} Min., $transferTag)",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            },
                            onClick = {
                                if (isSpeaking) {
                                    tts?.stop()
                                    isSpeaking = false
                                }
                                selectedConnectionIndex = index
                                alternativesDropdownExpanded = false
                            }
                        )
                    }
                }
            }
        }

        // DROPDOWN 2: ANDERE ORTE / HALTESTELLEN (ALLES ANDERE SAUBER EINGEKLAPPT)
        if (placeCandidates.size > 1) {
            Spacer(modifier = Modifier.height(8.dp))
            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { placesDropdownExpanded = !placesDropdownExpanded },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Andere Orte / Haltestellen (${placeCandidates.size - 1} weitere)",
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(text = if (placesDropdownExpanded) "▲" else "▼")
                    }
                }

                DropdownMenu(
                    expanded = placesDropdownExpanded,
                    onDismissRequest = { placesDropdownExpanded = false },
                    modifier = Modifier.fillMaxWidth(0.92f)
                ) {
                    placeCandidates.forEachIndexed { idx, candidate ->
                        val isCurrent = idx == selectedPlaceIndex
                        val typePrefix = if (candidate.isStop) "🚏 " else ""
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = "$typePrefix${candidate.fullDisplay}",
                                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal
                                )
                            },
                            onClick = {
                                placesDropdownExpanded = false
                                selectedPlaceIndex = idx
                                val startCoords = activeStartCoords
                                if (startCoords != null) {
                                    scope.launch {
                                        isLoading = true
                                        try {
                                            loadRouteForPlace(
                                                startLat = startCoords.first,
                                                startLon = startCoords.second,
                                                dest = candidate,
                                                preferredMode = activePreferredMode,
                                                modeDisplay = activeModeDisplay
                                            )
                                        } finally {
                                            isLoading = false
                                        }
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Suppress("DEPRECATION")
suspend fun resolveCityNameFromCoords(context: Context, lat: Double, lon: Double): String? =
    withContext(Dispatchers.IO) {
        try {
            val geocoder = Geocoder(context, Locale.GERMANY)
            val list = geocoder.getFromLocation(lat, lon, 1)
            val addr = list?.firstOrNull()
            addr?.locality ?: addr?.subAdminArea
        } catch (e: Exception) {
            try {
                val url = "https://api.transitous.org/api/v1/reverse-geocode?place=$lat,$lon"
                val arr = JSONArray(httpGet(url))
                if (arr.length() > 0) {
                    val obj = arr.getJSONObject(0)
                    val areas = obj.optJSONArray("areas")
                    if (areas != null && areas.length() > 0) {
                        areas.getJSONObject(0).optString("name", "").ifBlank { null }
                    } else null
                } else null
            } catch (_: Exception) {
                null
            }
        }
    }

private fun distanceMetersBetween(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
    val results = FloatArray(1)
    Location.distanceBetween(lat1, lon1, lat2, lon2, results)
    return results[0]
}

suspend fun searchNearbyPoisOverpass(
    centerLat: Double,
    centerLon: Double,
    category: PoiCategory,
    radiusMeters: Int = 1500
): List<GeoCandidate> = withContext(Dispatchers.IO) {
    try {
        val query = """
            [out:json][timeout:10];
            (
              ${(listOf(category.overpassFilter) + category.extraFilters).joinToString("\n") {
                  "nwr$it(around:$radiusMeters,$centerLat,$centerLon);"
              }}
            );
            out center;
        """.trimIndent()
        var response: JSONObject? = null
        var lastError: Exception? = null
        for (endpoint in listOf("https://overpass.private.coffee/api/interpreter", "https://overpass-api.de/api/interpreter")) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            try {
                val result = JSONObject(httpGet("$endpoint?data=${URLEncoder.encode(query, "UTF-8")}"))
                check(!result.has("remark")) { "Umgebungssuche hat das Zeitlimit erreicht." }
                response = result
                break
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
            }
        }
        val root = response ?: throw IllegalStateException("Umgebungssuche nicht erreichbar.", lastError)
        val elements = root.optJSONArray("elements") ?: return@withContext emptyList()

        val found = mutableListOf<GeoCandidate>()
        for (i in 0 until elements.length()) {
            val el = elements.getJSONObject(i)
            val tags = el.optJSONObject("tags") ?: continue
            val lat = if (el.has("lat")) el.getDouble("lat") else el.optJSONObject("center")?.optDouble("lat", Double.NaN) ?: Double.NaN
            val lon = if (el.has("lon")) el.getDouble("lon") else el.optJSONObject("center")?.optDouble("lon", Double.NaN) ?: Double.NaN
            if (lat.isNaN() || lon.isNaN()) continue

            val rawName = tags.optString("name", "").trim()
            val displayName = if (rawName.isNotBlank()) rawName else category.label
            val street = tags.optString("addr:street", "").trim()
            val houseNo = tags.optString("addr:housenumber", "").trim()
            val dist = distanceMetersBetween(centerLat, centerLon, lat, lon).toInt()
            val addrPart = listOf(street, houseNo).filter { it.isNotBlank() }.joinToString(" ")
            val label = if (addrPart.isNotBlank()) "$addrPart · ca. $dist m" else "ca. $dist m entfernt"

            found.add(
                GeoCandidate(
                    name = displayName,
                    areaLabel = label,
                    lat = lat,
                    lon = lon,
                    isStop = category.isStop,
                    isStreet = false
                )
            )
        }
        found.sortedBy { distanceMetersBetween(centerLat, centerLon, it.lat, it.lon) }.take(6)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw IllegalStateException("Umgebungssuche nicht erreichbar. Bitte erneut versuchen.", e)
    }
}

/**
 * Priorisiert passende Ortsnamen; erst danach Haltestellen und kurze Entfernung.
 * Benachbarte Haltestellen mit anderen Namen bleiben als Alternativen erhalten.
 */
suspend fun searchTransitStopsAndPlaces(
    rawQuery: String,
    biasLat: Double,
    biasLon: Double,
    cityContext: String
): List<GeoCandidate> = withContext(Dispatchers.IO) {
    try {
        val cleanQuery = rawQuery.trim()
            .replace(Regex("\\bs[- ]?bahnhof\\s+", RegexOption.IGNORE_CASE), "S ")
            .replace(Regex("\\bu[- ]?bahnhof\\s+", RegexOption.IGNORE_CASE), "U ")
        val urls = mutableListOf(
            "https://api.transitous.org/api/v1/geocode?text=${URLEncoder.encode(cleanQuery, "UTF-8")}&place=$biasLat,$biasLon"
        )
        if (cityContext.isNotBlank() && !cleanQuery.contains(cityContext, ignoreCase = true)) {
            val withCity = "$cleanQuery, $cityContext"
            urls.add(
                0,
                "https://api.transitous.org/api/v1/geocode?text=${URLEncoder.encode(withCity, "UTF-8")}&place=$biasLat,$biasLon"
            )
        }

        val rawCandidates = mutableListOf<GeoCandidate>()
        for (url in urls) {
            val arr = try { JSONArray(httpGet(url)) } catch (_: Exception) { continue }
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val name = obj.optString("name", cleanQuery).trim()
                val type = obj.optString("type", "").uppercase(Locale.US)
                val lat = obj.optDouble("lat", Double.NaN)
                val lon = obj.optDouble("lon", Double.NaN)
                if (lat.isNaN() || lon.isNaN()) continue

                val distFromUser = distanceMetersBetween(biasLat, biasLon, lat, lon)
                if (distFromUser > 45_000f && placeNameMatchScore(cleanQuery, name) < 200) continue

                val areaParts = mutableListOf<String>()
                val areas = obj.optJSONArray("areas")
                if (areas != null) {
                    for (a in 0 until areas.length()) {
                        val areaObj = areas.getJSONObject(a)
                        val areaName = areaObj.optString("name", "").trim()
                        if (areaName.isNotBlank() && !areaParts.contains(areaName) && areaName != "Deutschland") {
                            areaParts.add(areaName)
                        }
                    }
                }
                val zip = obj.optString("zip", "").trim()
                val areaText = buildString {
                    if (zip.isNotBlank()) append("$zip ")
                    append(areaParts.take(3).joinToString(", "))
                }.trim()

                val isStop = type == "STOP"
                val isStreet = type == "ADDRESS"
                rawCandidates.add(
                    GeoCandidate(
                        name = name,
                        areaLabel = areaText,
                        lat = lat,
                        lon = lon,
                        isStop = isStop,
                        isStreet = isStreet
                    )
                )
            }
        }

        // Exact destination name takes precedence over a closer, unrelated station.
        val sorted = rawCandidates.sortedWith(
            compareByDescending<GeoCandidate> { placeNameMatchScore(cleanQuery, it.name) }
                .thenByDescending { it.isStop }
                .thenByDescending { it.isStreet }
                .thenBy { distanceMetersBetween(biasLat, biasLon, it.lat, it.lon) }
        )

        val distinct = mutableListOf<GeoCandidate>()
        for (cand in sorted) {
            val isDuplicate = distinct.any { existing ->
                normalizedPlaceName(existing.name) == normalizedPlaceName(cand.name) &&
                    distanceMetersBetween(existing.lat, existing.lon, cand.lat, cand.lon) < 100f
            }
            if (!isDuplicate) {
                distinct.add(cand)
            }
            if (distinct.size >= 6) break
        }
        distinct
    } catch (_: Exception) {
        emptyList()
    }
}

suspend fun fetchConnectionsForCoords(
    startLat: Double,
    startLon: Double,
    destLat: Double,
    destLon: Double,
    destName: String,
    preferredMode: String? = null,
    customDepartureMillis: Long? = null
): List<TransitConnection> = withContext(Dispatchers.IO) {
    val modesParam = when (preferredMode) {
        "BUS" -> "&transitModes=BUS"
        "SUBWAY" -> "&transitModes=SUBWAY"
        "SUBURBAN" -> "&transitModes=SUBURBAN,RAIL"
        "TRAM" -> "&transitModes=TRAM"
        "REGIONAL_RAIL" -> "&transitModes=REGIONAL_RAIL,RAIL"
        else -> ""
    }

    // A faster walking trip can otherwise suppress all requested transit trips.
    val directModesParam = if (preferredMode == null) "" else "&directModes="
    val referenceMillis = customDepartureMillis ?: System.currentTimeMillis()
    val isoFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    val nowIsoEncoded = URLEncoder.encode(isoFmt.format(Date(referenceMillis)), "UTF-8")

    val planUrl = "https://api.transitous.org/api/v1/plan" +
            "?fromPlace=$startLat,$startLon" +
            "&toPlace=$destLat,$destLon" +
            "&time=$nowIsoEncoded" +
            "&arriveBy=false" +
            "&maxPreTransitTime=1800" +
            "&maxPostTransitTime=1800" +
            "&numItineraries=10$modesParam$directModesParam"

    val root = JSONObject(httpGet(planUrl))
    val itineraries = JSONArray()
    for (key in listOf("itineraries", "direct")) {
        val source = root.optJSONArray(key) ?: continue
        for (index in 0 until source.length()) itineraries.put(source.getJSONObject(index))
    }
    val resultList = mutableListOf<TransitConnection>()
    val nowMillis = referenceMillis
    val minAllowedDeparture = referenceMillis - 60 * 1000L

    for (i in 0 until itineraries.length()) {
        val itinerary = itineraries.getJSONObject(i)
        val legsJson = itinerary.getJSONArray("legs")
        val totalDurSec = itinerary.optInt("duration", 0)
        val rawParsedLegs = mutableListOf<RouteLegInfo>()
        val transitLineNames = mutableListOf<String>()

        var firstTransitDepartureTime = ""
        var firstTransitDepartureMillis: Long? = null
        var firstTransitDelay = 0
        var firstTransitLine = ""
        var firstTransitDir = ""
        var finalArrTime = ""
        var finalArrMillis: Long? = null

        for (j in 0 until legsJson.length()) {
            val leg = legsJson.getJSONObject(j)
            val mode = leg.optString("mode", "")
            val isWalk = mode.equals("WALK", ignoreCase = true)
            val durSec = leg.optInt("duration", 0)
            val dist = leg.optDouble("distance", 0.0).toInt()
            val originName = leg.optJSONObject("from")?.optString("name") ?: "Start"
            val arrivalStopName = leg.optJSONObject("to")?.optString("name") ?: destName

            val startTimeStr = leg.optString("startTime", "")
            val endTimeStr = leg.optString("endTime", "")
            val scheduledStartStr = leg.optString("scheduledStartTime", startTimeStr)

            val formattedStart = formatIsoTime(startTimeStr)
            val formattedEnd = formatIsoTime(endTimeStr)
            finalArrTime = formattedEnd
            finalArrMillis = parseIsoMillis(endTimeStr)

            if (isWalk) {
                rawParsedLegs.add(
                    RouteLegInfo(
                        isWalk = true,
                        isTransfer = false,
                        lineName = "Fußweg",
                        direction = "",
                        fromStop = originName,
                        toStop = arrivalStopName,
                        startTime = formattedStart,
                        endTime = formattedEnd,
                        delayMinutes = 0,
                        durationMinutes = maxOf(1, durSec / 60),
                        distanceMeters = dist
                    )
                )
            } else {
                val routeShort = leg.optString("routeShortName", "")
                val routeLong = leg.optString("routeLongName", "")
                val displayName = formatGermanLineName(mode, routeShort, routeLong)
                val headsign = leg.optString("headsign", destName)
                val delayMins = calculateDelayMinutes(scheduledStartStr, startTimeStr)

                if (firstTransitDepartureTime.isEmpty()) {
                    firstTransitDepartureTime = formattedStart
                    firstTransitDepartureMillis = parseIsoMillis(startTimeStr)
                    firstTransitDelay = delayMins
                    firstTransitLine = displayName
                    firstTransitDir = headsign
                }

                transitLineNames.add(displayName)
                rawParsedLegs.add(
                    RouteLegInfo(
                        isWalk = false,
                        isTransfer = false,
                        lineName = displayName,
                        direction = headsign,
                        fromStop = originName,
                        toStop = arrivalStopName,
                        startTime = formattedStart,
                        endTime = formattedEnd,
                        delayMinutes = delayMins,
                        durationMinutes = maxOf(1, durSec / 60),
                        distanceMeters = dist
                    )
                )
            }
        }

        if (rawParsedLegs.isEmpty()) continue
        if (transitLineNames.isEmpty()) {
            val startMillis = parseIsoMillis(legsJson.getJSONObject(0).optString("startTime")) ?: referenceMillis
            firstTransitDepartureMillis = startMillis
            firstTransitDepartureTime = SimpleDateFormat("HH:mm", Locale.GERMANY).format(Date(startMillis))
            firstTransitLine = "Fußweg"
        }

        if (firstTransitDepartureMillis != null && firstTransitDepartureMillis < minAllowedDeparture) {
            continue
        }

        val finalLegs = mutableListOf<RouteLegInfo>()
        var seenFirstTransit = false
        for (idx in rawParsedLegs.indices) {
            val current = rawParsedLegs[idx]
            val hasNextTransit = rawParsedLegs.drop(idx + 1).any { !it.isWalk }

            if (!current.isWalk) {
                if (seenFirstTransit && finalLegs.lastOrNull()?.isTransfer == false) {
                    val prevTransit = finalLegs.last()
                    val waitMins = computeMinutesDiff(prevTransit.endTime, current.startTime)
                    finalLegs.add(
                        RouteLegInfo(
                            isWalk = false,
                            isTransfer = true,
                            lineName = "Umstieg",
                            direction = "",
                            fromStop = current.fromStop,
                            toStop = current.fromStop,
                            startTime = prevTransit.endTime,
                            endTime = current.startTime,
                            delayMinutes = 0,
                            durationMinutes = maxOf(1, waitMins),
                            distanceMeters = 0
                        )
                    )
                }
                seenFirstTransit = true
                finalLegs.add(current)
            } else {
                if (seenFirstTransit && hasNextTransit) {
                    val nextTransit = rawParsedLegs.drop(idx + 1).first { !it.isWalk }
                    val prevTransit = finalLegs.lastOrNull { !it.isWalk && !it.isTransfer }
                    val waitMins = if (prevTransit != null) {
                        computeMinutesDiff(prevTransit.endTime, nextTransit.startTime)
                    } else {
                        current.durationMinutes
                    }
                    finalLegs.add(
                        current.copy(
                            isTransfer = true,
                            fromStop = current.fromStop,
                            durationMinutes = maxOf(1, waitMins)
                        )
                    )
                } else if (current.distanceMeters >= 40 || current.durationMinutes >= 2) {
                    finalLegs.add(current)
                }
            }
        }

        val depMs = firstTransitDepartureMillis ?: nowMillis
        val arrMs = finalArrMillis ?: (depMs + totalDurSec * 1000L)

        resultList.add(
            TransitConnection(
                summaryLines = if (transitLineNames.isEmpty()) "Zu Fuß" else transitLineNames.joinToString(" → "),
                firstLine = firstTransitLine,
                firstDirection = firstTransitDir,
                firstDepartureTime = firstTransitDepartureTime,
                finalArrivalTime = finalArrTime,
                departureMillis = depMs,
                arrivalMillis = arrMs,
                firstDelayMinutes = firstTransitDelay,
                totalDurationMinutes = maxOf(1, totalDurSec / 60),
                transfersCount = maxOf(0, transitLineNames.size - 1),
                legs = finalLegs
            )
        )
    }

    resultList.sortedWith(compareBy<TransitConnection> { it.arrivalMillis }.thenBy { it.departureMillis })
}

private fun computeMinutesDiff(hhMmStart: String, hhMmEnd: String): Int {
    return try {
        val p1 = hhMmStart.split(":")
        val p2 = hhMmEnd.split(":")
        val m1 = p1[0].toInt() * 60 + p1[1].toInt()
        var m2 = p2[0].toInt() * 60 + p2[1].toInt()
        if (m2 < m1) m2 += 24 * 60
        maxOf(0, m2 - m1)
    } catch (_: Exception) {
        1
    }
}

private fun formatIsoTime(isoStr: String): String = parseIsoMillis(isoStr)?.let {
    SimpleDateFormat("HH:mm", Locale.GERMANY).format(Date(it))
} ?: "—"

private fun calculateDelayMinutes(scheduledIso: String, actualIso: String): Int {
    return try {
        val t1 = parseIsoMillis(scheduledIso) ?: return 0
        val t2 = parseIsoMillis(actualIso) ?: return 0
        maxOf(0, ((t2 - t1) / 60000L).toInt())
    } catch (_: Exception) {
        0
    }
}

private fun httpGet(urlString: String): String {
    val conn = URL(urlString).openConnection() as HttpURLConnection
    conn.requestMethod = "GET"
    conn.setRequestProperty("User-Agent", "AbfahrtenNextAndHere/4.1 (https://github.com/ClaudeHoeni/LippstadtBus)")
    conn.connectTimeout = 12000
    conn.readTimeout = 12000
    return try {
        if (conn.responseCode !in 200..299) throw IllegalStateException("HTTP ${conn.responseCode}")
        conn.inputStream.bufferedReader().use { it.readText() }
    } finally {
        conn.disconnect()
    }
}

suspend fun checkForGitHubUpdate(context: Context): AppUpdateInfo? = withContext(Dispatchers.IO) {
    try {
        val jsonStr = httpGet("https://api.github.com/repos/ClaudeHoeni/LippstadtBus/releases/tags/v-latest")
        val obj = JSONObject(jsonStr)
        val remoteTitle = obj.optString("name", "")
        val remoteRun = Regex("\\((\\d+)\\)").find(remoteTitle)?.groupValues?.get(1)?.toIntOrNull()
            ?: return@withContext null
        val remoteVersionCode = 100 + remoteRun

        if (remoteVersionCode <= BuildConfig.VERSION_CODE) return@withContext null

        val assets = obj.optJSONArray("assets") ?: return@withContext null
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            val url = asset.optString("browser_download_url", "")
            if (url.endsWith(".apk")) {
                return@withContext AppUpdateInfo(title = remoteTitle, downloadUrl = url)
            }
        }
        null
    } catch (_: Exception) {
        null
    }
}

suspend fun downloadAndInstallApk(context: Context, update: AppUpdateInfo) {
    val apkFile = withContext(Dispatchers.IO) {
        val targetFile = File(context.cacheDir, "update.apk")
        val conn = URL(update.downloadUrl).openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", "AbfahrtenNextAndHereUpdater/1.0")
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        conn.inputStream.use { input ->
            targetFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        targetFile
    }

    val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        apkFile
    )
    val installIntent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/vnd.android.package-archive")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(installIntent)
}
fun setDepartureAlarm(context: Context, departureMillis: Long, minutesBefore: Int, label: String): String {
    val now = System.currentTimeMillis()
    val alarmMillis = departureMillis - minutesBefore * 60_000L
    if (minutesBefore < 0 || alarmMillis <= now) return "Die Erinnerungszeit ist bereits vorbei."
    val alarm = Calendar.getInstance().apply { timeInMillis = alarmMillis }
    val nextOccurrence = Calendar.getInstance().apply {
        timeInMillis = now
        set(Calendar.HOUR_OF_DAY, alarm.get(Calendar.HOUR_OF_DAY))
        set(Calendar.MINUTE, alarm.get(Calendar.MINUTE))
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        if (timeInMillis <= now) add(Calendar.DAY_OF_YEAR, 1)
    }
    if (kotlin.math.abs(nextOccurrence.timeInMillis - alarmMillis) >= 60_000L) {
        return "Diese Erinnerung liegt an einem späteren Datum. Bitte in der Uhr-App mit Datum anlegen."
    }
    val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
        putExtra(AlarmClock.EXTRA_HOUR, alarm.get(Calendar.HOUR_OF_DAY))
        putExtra(AlarmClock.EXTRA_MINUTES, alarm.get(Calendar.MINUTE))
        putExtra(AlarmClock.EXTRA_MESSAGE, label)
        putExtra(AlarmClock.EXTRA_SKIP_UI, false)
    }
    return try {
        context.startActivity(intent)
        "Wecker-App geöffnet: Bitte Erinnerung um ${SimpleDateFormat("HH:mm", Locale.GERMANY).format(Date(alarmMillis))} Uhr bestätigen."
    } catch (_: ActivityNotFoundException) {
        "Keine kompatible Wecker-App installiert."
    } catch (_: SecurityException) {
        "Die Wecker-App erlaubt das Anlegen dieser Erinnerung nicht."
    }
}
