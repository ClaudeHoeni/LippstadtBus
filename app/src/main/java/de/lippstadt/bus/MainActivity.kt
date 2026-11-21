package de.lippstadt.bus

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.os.Bundle
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ParsedVoiceQuery(
    val origin: String?,
    val destination: String,
    val preferredMode: String?,
    val modeDisplayName: String?
)

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

private fun cleanPlaceArticle(place: String): String {
    return place
        .replace(Regex("^(dem|der|den|das|die)\\s+", RegexOption.IGNORE_CASE), "")
        .trim()
}

fun parseNaturalTransitRequest(rawInput: String): ParsedVoiceQuery {
    val text = rawInput.trim().trimEnd('?', '!', '.')
    val lower = text.lowercase(Locale.GERMANY)

    val (modeCode, modeLabel) = when {
        Regex("\\b(u-bahn|ubahn|u bahn)\\b").containsMatchIn(lower) -> "SUBWAY" to "U-Bahn"
        Regex("\\b(s-bahn|sbahn|s bahn)\\b").containsMatchIn(lower) -> "SUBURBAN" to "S-Bahn"
        Regex("\\b(tram|straßenbahn|strassenbahn)\\b").containsMatchIn(lower) -> "TRAM" to "Tram"
        Regex("\\b(zug|bahn|regionalbahn|re|rb)\\b").containsMatchIn(lower) -> "REGIONAL_RAIL" to "Regionalbahn"
        Regex("\\b(bus|stadtbus|nachtbus)\\b").containsMatchIn(lower) -> "BUS" to "Bus"
        else -> null to null
    }

    val fromToRegex = Regex(
        "(?:von|ab)\\s+(.+?)\\s+(?:in\\s+richtung|richtung|nach|zum|zur|zu)\\s+(.+)$",
        RegexOption.IGNORE_CASE
    )
    val fromToMatch = fromToRegex.find(text)
    if (fromToMatch != null) {
        val rawOrigin = cleanPlaceArticle(fromToMatch.groupValues[1])
        val rawDest = cleanPlaceArticle(fromToMatch.groupValues[2])
        if (rawOrigin.isNotBlank() && rawDest.isNotBlank()) {
            return ParsedVoiceQuery(
                origin = rawOrigin,
                destination = rawDest,
                preferredMode = modeCode,
                modeDisplayName = modeLabel
            )
        }
    }

    val targetRegex = Regex(
        "(?:in\\s+richtung|richtung|nach|zum|zur|zu)\\s+(.+)$",
        RegexOption.IGNORE_CASE
    )
    val match = targetRegex.find(text)
    var extractedDest = if (match != null) {
        match.groupValues[1].trim()
    } else {
        text.replace(
            Regex(
                "^(wie\\s+komme\\s+ich\\s+|wann\\s+fährt\\s+|wann\\s+faehrt\\s+|nächster\\s+|naechster\\s+|nächste\\s+|naechste\\s+|mit\\s+dem\\s+|mit\\s+der\\s+|bus\\s+|u-bahn\\s+|ubahn\\s+|s-bahn\\s+|sbahn\\s+)+",
                RegexOption.IGNORE_CASE
            ),
            ""
        ).trim()
    }

    extractedDest = cleanPlaceArticle(extractedDest)
    if (extractedDest.isBlank()) {
        extractedDest = text
    }

    return ParsedVoiceQuery(
        origin = null,
        destination = extractedDest,
        preferredMode = modeCode,
        modeDisplayName = modeLabel
    )
}

/**
 * Übersetzt englische API-Verkehrsmittelnamen konsequent in sauberes Deutsch.
 */
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

    // Doppelungen vermeiden (z. B. wenn shortClean schon "U7", "S1", "RE 1" oder "Bus C1" enthält)
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
    var statusMessage by remember { mutableStateOf("Bereit. Ziel eingeben oder sprechen.") }
    var isLoading by remember { mutableStateOf(false) }
    var isListening by remember { mutableStateOf(false) }
    var isSpeaking by remember { mutableStateOf(false) }

    var connections by remember { mutableStateOf<List<TransitConnection>>(emptyList()) }
    var selectedConnectionIndex by remember { mutableStateOf(0) }
    var alternativesDropdownExpanded by remember { mutableStateOf(false) }

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
        detectCurrentLocation()
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

        val parsed = parseNaturalTransitRequest(rawText)
        destinationInput = if (parsed.origin != null) {
            "Von ${parsed.origin} nach ${parsed.destination}"
        } else {
            parsed.destination
        }

        scope.launch {
            isLoading = true
            connections = emptyList()
            selectedConnectionIndex = 0
            alternativesDropdownExpanded = false

            try {
                statusMessage = "Ermittle aktuellen GPS-Standort..."
                val locData = detectCurrentLocation()
                if (locData == null) {
                    statusMessage = "GPS-Standort nicht verfügbar. Bitte Standortdienst aktivieren."
                    isLoading = false
                    return@launch
                }

                val (baseLat, baseLon, cityContextName) = locData
                val modeHint = if (parsed.modeDisplayName != null) " (${parsed.modeDisplayName})" else ""
                val areaHint = if (cityContextName.isNotBlank()) " in $cityContextName" else ""
                val routeLabel = if (parsed.origin != null) {
                    "ab \"${parsed.origin}\" nach \"${parsed.destination}\""
                } else {
                    "nach \"${parsed.destination}\""
                }
                statusMessage = "Suche$modeHint $routeLabel$areaHint..."

                val results = fetchConnections(
                    fallbackLat = baseLat,
                    fallbackLon = baseLon,
                    originQuery = parsed.origin,
                    destQuery = parsed.destination,
                    cityContext = cityContextName,
                    preferredMode = parsed.preferredMode
                )
                connections = results
                statusMessage = if (results.isEmpty()) {
                    "Keine passenden Verbindungen$modeHint gefunden."
                } else {
                    "Beste Verbindung$modeHint$areaHint:"
                }
            } catch (e: Exception) {
                statusMessage = "Fehler: ${e.localizedMessage ?: "Netzwerkproblem"}"
            } finally {
                isLoading = false
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
            putExtra(RecognizerIntent.EXTRA_PROMPT, "z. B. Wie komme ich mit dem nächsten Bus nach...")
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

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = destinationInput,
                onValueChange = { destinationInput = it },
                label = { Text("Ziel oder \"von ... nach ...\"") },
                placeholder = { Text("z. B. mit dem Bus zum Bahnhof") },
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

        // HAUPTBILDSCHIRM: AKTUELL BESTE VERBINDUNG DIE JETZT ABFÄHRT
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
                        0 -> "Direktverbindung"
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

        // DROPDOWN-MENÜ: ALTERNATIVEN
        if (connections.size > 1) {
            Spacer(modifier = Modifier.height(12.dp))
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
                        val bestTag = if (index == 0) " · Beste Verbindung" else ""
                        val transferTag = if (conn.transfersCount == 0) "Direkt" else "${conn.transfersCount}× Umstieg"
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(
                                        text = "${conn.firstDepartureTime}$delayStr  |  ${conn.summaryLines}$bestTag",
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "An ${conn.finalArrivalTime} Uhr (${conn.totalDurationMinutes} Min., $transferTag) · Ab ${conn.legs.firstOrNull { !it.isWalk && !it.isTransfer }?.fromStop ?: ""}",
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
                                statusMessage = if (index == 0) {
                                    "Beste Verbindung:"
                                } else {
                                    "Alternative (${conn.firstDepartureTime} Uhr):"
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

fun buildEnrichedQuery(query: String, cityContext: String): String {
    val cleanCity = cityContext.replace(Regex("^\\d{5}\\s+"), "").trim()
    return if (
        cityContext.isBlank() ||
        query.contains(cityContext, ignoreCase = true) ||
        (cleanCity.isNotBlank() && query.contains(cleanCity, ignoreCase = true)) ||
        query.any { it.isDigit() && query.length >= 5 }
    ) {
        query
    } else {
        "$query, $cityContext"
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

private fun geocodePlace(placeQuery: String, biasLat: Double, biasLon: Double): Triple<Double, Double, String>? {
    return try {
        val encoded = URLEncoder.encode(placeQuery, "UTF-8")
        val url = "https://api.transitous.org/api/v1/geocode?text=$encoded&place=$biasLat,$biasLon"
        val arr = JSONArray(httpGet(url))
        if (arr.length() == 0) return null
        val obj = arr.getJSONObject(0)
        Triple(
            obj.getDouble("lat"),
            obj.getDouble("lon"),
            obj.optString("name", placeQuery)
        )
    } catch (_: Exception) {
        null
    }
}

suspend fun fetchConnections(
    fallbackLat: Double,
    fallbackLon: Double,
    originQuery: String?,
    destQuery: String,
    cityContext: String,
    preferredMode: String? = null
): List<TransitConnection> = withContext(Dispatchers.IO) {
    val startLat: Double
    val startLon: Double
    if (!originQuery.isNullOrBlank()) {
        val enrichedOrigin = buildEnrichedQuery(originQuery, cityContext)
        val resolvedOrigin = geocodePlace(enrichedOrigin, fallbackLat, fallbackLon)
            ?: return@withContext emptyList()
        startLat = resolvedOrigin.first
        startLon = resolvedOrigin.second
    } else {
        startLat = fallbackLat
        startLon = fallbackLon
    }

    val enrichedDest = buildEnrichedQuery(destQuery, cityContext)
    val resolvedDest = geocodePlace(enrichedDest, startLat, startLon)
        ?: return@withContext emptyList()
    val destLat = resolvedDest.first
    val destLon = resolvedDest.second
    val destName = resolvedDest.third

    // Fernzüge (ICE/IC) ausschließen, damit für Stadt-/Regionalziele der echte Nahverkehr gewählt wird
    val modesParam = if (preferredMode != null) {
        "&transitModes=$preferredMode"
    } else {
        "&transitModes=BUS,SUBWAY,SUBURBAN,TRAM,REGIONAL_RAIL,FERRY"
    }

    val planUrl = "https://api.transitous.org/api/v1/plan" +
            "?fromPlace=$startLat,$startLon" +
            "&toPlace=$destLat,$destLon" +
            "&numItineraries=12$modesParam"

    val root = JSONObject(httpGet(planUrl))
    val itineraries = root.optJSONArray("itineraries") ?: return@withContext emptyList()
    val resultList = mutableListOf<TransitConnection>()
    val nowMillis = System.currentTimeMillis()
    val minAllowedDeparture = nowMillis - 60 * 1000L // bereits abgefahrene Verbindungen ausfiltern
    val maxWindowMillis = nowMillis + 60 * 60 * 1000L

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

        if (transitLineNames.isEmpty()) continue

        // Verbindungen, die schon abgefahren sind, überspringen
        if (firstTransitDepartureMillis != null && firstTransitDepartureMillis < minAllowedDeparture) {
            continue
        }

        // Umstiege explizit zwischen allen ÖPNV-Abschnitten einbauen
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

        if (firstTransitDepartureMillis != null && firstTransitDepartureMillis > maxWindowMillis && resultList.size >= 2) {
            continue
        }

        val depMs = firstTransitDepartureMillis ?: nowMillis
        val arrMs = finalArrMillis ?: (depMs + totalDurSec * 1000L)

        resultList.add(
            TransitConnection(
                summaryLines = transitLineNames.joinToString(" → "),
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

    // Beste Verbindung = früheste Ankunft unter den aktuell abfahrenden Verbindungen, danach früheste Abfahrt
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

private fun formatIsoTime(isoStr: String): String {
    return if (isoStr.length >= 16 && isoStr.contains("T")) {
        isoStr.substringAfter("T").take(5)
    } else {
        SimpleDateFormat("HH:mm", Locale.GERMANY).format(Date())
    }
}

private fun parseIsoMillis(isoStr: String): Long? {
    return try {
        if (isoStr.length < 19) return null
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        fmt.parse(isoStr.take(19))?.time
    } catch (_: Exception) {
        null
    }
}

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
    conn.setRequestProperty("User-Agent", "AbfahrtenNextAndHere/3.6 (https://github.com/ClaudeHoeni/LippstadtBus)")
    conn.connectTimeout = 12000
    conn.readTimeout = 12000
    if (conn.responseCode !in 200..299) {
        throw IllegalStateException("HTTP ${conn.responseCode}")
    }
    return conn.inputStream.bufferedReader().use { it.readText() }
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