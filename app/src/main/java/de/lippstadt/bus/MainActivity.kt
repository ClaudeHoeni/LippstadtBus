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
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
    val destination: String,
    val preferredMode: String?,      // z. B. "BUS", "SUBWAY", "SUBURBAN", "TRAM", "RAIL" oder null für alle
    val modeDisplayName: String?     // z. B. "Bus", "U-Bahn", "S-Bahn", "Tram", "Bahn"
)

data class RouteLegInfo(
    val isWalk: Boolean,
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
    val firstDelayMinutes: Int,
    val totalDurationMinutes: Int,
    val transfersCount: Int,
    val legs: List<RouteLegInfo>
)

data class CityOption(
    val name: String,
    val lat: Double?,
    val lon: Double?
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

/**
 * Erkennt natürliche Sätze wie:
 * - "Wie komme ich mit dem nächsten Bus nach Hauptbahnhof?"
 * - "Wie komme ich mit der U-Bahn nach Alexanderplatz?"
 * - "Nächste S-Bahn zum Potsdamer Platz"
 */
fun parseNaturalTransitRequest(rawInput: String): ParsedVoiceQuery {
    val text = rawInput.trim().trimEnd('?', '!', '.')
    val lower = text.lowercase(Locale.GERMANY)

    val (modeCode, modeLabel) = when {
        Regex("\\b(u-bahn|ubahn|u bahn)\\b").containsMatchIn(lower) -> "SUBWAY" to "U-Bahn"
        Regex("\\b(s-bahn|sbahn|s bahn)\\b").containsMatchIn(lower) -> "SUBURBAN" to "S-Bahn"
        Regex("\\b(tram|straßenbahn|strassenbahn)\\b").containsMatchIn(lower) -> "TRAM" to "Tram"
        Regex("\\b(zug|bahn|regionalbahn|re|rb)\\b").containsMatchIn(lower) -> "RAIL" to "Bahn"
        Regex("\\b(bus|stadtbus|nachtbus)\\b").containsMatchIn(lower) -> "BUS" to "Bus"
        else -> null to null
    }

    // Ziel hinter "nach", "zum", "zur", "zu", "in Richtung", "richtung" herausziehen
    val targetRegex = Regex(
        "(?:in\\s+richtung|richtung|nach|zum|zur|zu)\\s+(.+)$",
        RegexOption.IGNORE_CASE
    )
    val match = targetRegex.find(text)
    var extractedDest = if (match != null) {
        match.groupValues[1].trim()
    } else {
        // Falls kein "nach/zum" gesprochen wurde, bekannte Einleitungsfloskeln entfernen
        text.replace(
            Regex(
                "^(wie\\s+komme\\s+ich\\s+|wann\\s+fährt\\s+|wann\\s+faehrt\\s+|nächster\\s+|naechster\\s+|nächste\\s+|naechste\\s+|mit\\s+dem\\s+|mit\\s+der\\s+|bus\\s+|u-bahn\\s+|ubahn\\s+|s-bahn\\s+|sbahn\\s+)+",
                RegexOption.IGNORE_CASE
            ),
            ""
        ).trim()
    }

    // Führende Artikel am Ziel bereinigen ("zum Bahnhof" -> "Bahnhof", "zur Erlenstraße" -> "Erlenstraße")
    extractedDest = extractedDest.replace(
        Regex("^(dem|der|den|das|die)\\s+", RegexOption.IGNORE_CASE),
        ""
    ).trim()

    if (extractedDest.isBlank()) {
        extractedDest = text
    }

    return ParsedVoiceQuery(
        destination = extractedDest,
        preferredMode = modeCode,
        modeDisplayName = modeLabel
    )
}

private fun loadSavedCities(context: Context): List<CityOption> {
    val defaultCities = listOf(
        CityOption("GPS (Auto)", null, null),
        CityOption("Berlin", 52.5200, 13.4050),
        CityOption("Potsdam", 52.3906, 13.0645),
        CityOption("59555 Lippstadt", 51.6754, 8.3446),
        CityOption("Hamburg", 53.5511, 9.9937)
    )
    val prefs = context.getSharedPreferences("oeffi_prefs", Context.MODE_PRIVATE)
    val customRaw = prefs.getString("custom_cities", "") ?: ""
    if (customRaw.isBlank()) return defaultCities

    val customList = customRaw.split(";").mapNotNull { entry ->
        val parts = entry.split("|")
        if (parts.size == 3) {
            val lat = parts[1].toDoubleOrNull()
            val lon = parts[2].toDoubleOrNull()
            if (lat != null && lon != null) CityOption(parts[0], lat, lon) else null
        } else null
    }
    return defaultCities + customList
}

private fun saveCustomCity(context: Context, city: CityOption) {
    if (city.lat == null || city.lon == null) return
    val prefs = context.getSharedPreferences("oeffi_prefs", Context.MODE_PRIVATE)
    val existing = prefs.getString("custom_cities", "") ?: ""
    val entry = "${city.name.replace(";", "").replace("|", "")}|${city.lat}|${city.lon}"
    val updated = if (existing.isBlank()) entry else "$existing;$entry"
    prefs.edit().putString("custom_cities", updated).apply()
}

@Composable
fun TransitFinderScreen(externalVoiceTrigger: Int) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val fusedLocationClient = remember { LocationServices.getFusedLocationProviderClient(context) }

    var cities by remember { mutableStateOf(loadSavedCities(context)) }
    var selectedCity by remember { mutableStateOf(cities.first()) }
    var detectedGpsCity by remember { mutableStateOf<String?>(null) }

    var destinationInput by remember { mutableStateOf("") }
    var statusMessage by remember { mutableStateOf("Bereit. Ziel eingeben oder z. B. sagen: \"Wie komme ich mit dem nächsten Bus nach...\"") }
    var isLoading by remember { mutableStateOf(false) }
    var isListening by remember { mutableStateOf(false) }

    var connections by remember { mutableStateOf<List<TransitConnection>>(emptyList()) }
    var selectedConnectionIndex by remember { mutableStateOf(0) }
    var nextHourDropdownExpanded by remember { mutableStateOf(false) }

    var showAddCityDialog by remember { mutableStateOf(false) }
    var newCityInput by remember { mutableStateOf("") }

    var availableUpdate by remember { mutableStateOf<AppUpdateInfo?>(null) }
    var isUpdating by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        availableUpdate = checkForGitHubUpdate(context)
    }

    @SuppressLint("MissingPermission")
    fun searchTransit(rawQueryOverride: String? = null) {
        val rawText = (rawQueryOverride ?: destinationInput).trim()
        if (rawText.isBlank()) {
            statusMessage = "Bitte ein Ziel oder eine Frage eingeben."
            return
        }

        val parsed = parseNaturalTransitRequest(rawText)
        destinationInput = parsed.destination

        scope.launch {
            isLoading = true
            connections = emptyList()
            selectedConnectionIndex = 0
            nextHourDropdownExpanded = false

            try {
                val lat: Double
                val lon: Double
                val cityContextName: String

                if (selectedCity.lat != null && selectedCity.lon != null) {
                    lat = selectedCity.lat!!
                    lon = selectedCity.lon!!
                    cityContextName = selectedCity.name
                } else {
                    statusMessage = "Ermittle aktuellen GPS-Standort..."
                    val location = fusedLocationClient.getCurrentLocation(
                        Priority.PRIORITY_HIGH_ACCURACY, null
                    ).await() ?: fusedLocationClient.lastLocation.await()

                    if (location == null) {
                        statusMessage = "GPS nicht verfügbar. Wähle oben eine Stadt."
                        isLoading = false
                        return@launch
                    }
                    lat = location.latitude
                    lon = location.longitude
                    val resolvedCity = resolveCityNameFromCoords(context, lat, lon)
                    detectedGpsCity = resolvedCity
                    cityContextName = resolvedCity ?: ""
                }

                val modeHint = if (parsed.modeDisplayName != null) " (${parsed.modeDisplayName})" else ""
                val areaHint = if (cityContextName.isNotBlank()) " in $cityContextName" else ""
                statusMessage = "Suche$modeHint nach \"${parsed.destination}\"$areaHint..."

                val results = fetchConnections(
                    fromLat = lat,
                    fromLon = lon,
                    query = parsed.destination,
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
        val recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "de-DE")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "z. B. Wie komme ich mit dem nächsten Bus nach...")
        }
        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                isListening = true
                statusMessage = "Höre zu... Sag z. B. \"Wie komme ich mit dem nächsten Bus nach...\""
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
        ) == PackageManager.PERMISSION_GRANTED

        if (hasAudio && (hasLoc || selectedCity.lat != null)) {
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

    if (showAddCityDialog) {
        AlertDialog(
            onDismissRequest = { showAddCityDialog = false },
            title = { Text("Stadt / Urlaubsort hinzufügen") },
            text = {
                OutlinedTextField(
                    value = newCityInput,
                    onValueChange = { newCityInput = it },
                    label = { Text("Stadt oder PLZ (z. B. München, Wien)") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val cityQuery = newCityInput.trim()
                    if (cityQuery.isNotBlank()) {
                        showAddCityDialog = false
                        newCityInput = ""
                        scope.launch {
                            statusMessage = "Suche Koordinaten für \"$cityQuery\"..."
                            val found = lookupCityCoordinates(cityQuery)
                            if (found != null) {
                                saveCustomCity(context, found)
                                cities = loadSavedCities(context)
                                selectedCity = found
                                statusMessage = "Ort \"${found.name}\" hinzugefügt."
                            } else {
                                statusMessage = "Ort \"$cityQuery\" nicht gefunden."
                            }
                        }
                    }
                }) {
                    Text("Speichern")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddCityDialog = false }) {
                    Text("Abbrechen")
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(14.dp)
    ) {
        Text(
            text = "Abfahrten Next&Here",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )

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

        Spacer(modifier = Modifier.height(6.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            cities.forEach { city ->
                val label = if (city.lat == null && detectedGpsCity != null) {
                    "GPS ($detectedGpsCity)"
                } else {
                    city.name
                }
                FilterChip(
                    selected = selectedCity.name == city.name,
                    onClick = { selectedCity = city },
                    label = { Text(label) }
                )
            }
            OutlinedButton(
                onClick = { showAddCityDialog = true },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Text("+ Ort")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = destinationInput,
                onValueChange = { destinationInput = it },
                label = { Text("Ziel oder Satz eingeben") },
                placeholder = { Text("z. B. mit dem Bus nach Bahnhof") },
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

        // HAUPTBILDSCHIRM: AKTUELL BESTE VERBINDUNG
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

                    Spacer(modifier = Modifier.height(10.dp))

                    activeConn.legs.forEachIndexed { idx, leg ->
                        if (idx > 0) {
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                        if (leg.isWalk) {
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

        // DROPDOWN-MENÜ FÜR WEITERE ABFAHRTEN DER NÄCHSTEN 1 STUNDE
        if (connections.size > 1) {
            Spacer(modifier = Modifier.height(12.dp))
            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { nextHourDropdownExpanded = !nextHourDropdownExpanded },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Abfahrten der nächsten 1 Std. (${connections.size})",
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(text = if (nextHourDropdownExpanded) "▲" else "▼")
                    }
                }

                DropdownMenu(
                    expanded = nextHourDropdownExpanded,
                    onDismissRequest = { nextHourDropdownExpanded = false },
                    modifier = Modifier.fillMaxWidth(0.92f)
                ) {
                    connections.forEachIndexed { index, conn ->
                        val isSelected = index == selectedConnectionIndex
                        val delayStr = if (conn.firstDelayMinutes > 0) " (+${conn.firstDelayMinutes}')" else ""
                        val bestTag = if (index == 0) " · Beste" else ""
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(
                                        text = "${conn.firstDepartureTime}$delayStr  |  ${conn.summaryLines}$bestTag",
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "An ${conn.finalArrivalTime} Uhr (${conn.totalDurationMinutes} Min.) · Ab ${conn.legs.firstOrNull { !it.isWalk }?.fromStop ?: ""}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            },
                            onClick = {
                                selectedConnectionIndex = index
                                nextHourDropdownExpanded = false
                                statusMessage = if (index == 0) {
                                    "Beste Verbindung:"
                                } else {
                                    "Ausgewählte Verbindung (${conn.firstDepartureTime} Uhr):"
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
            null
        }
    }

suspend fun lookupCityCoordinates(cityQuery: String): CityOption? = withContext(Dispatchers.IO) {
    try {
        val url = "https://api.transitous.org/api/v1/geocode?text=${URLEncoder.encode(cityQuery, "UTF-8")}"
        val arr = JSONArray(httpGet(url))
        if (arr.length() == 0) return@withContext null
        val obj = arr.getJSONObject(0)
        val name = obj.optString("name", cityQuery)
        val lat = obj.getDouble("lat")
        val lon = obj.getDouble("lon")
        CityOption(name = name, lat = lat, lon = lon)
    } catch (e: Exception) {
        null
    }
}

suspend fun fetchConnections(
    fromLat: Double,
    fromLon: Double,
    query: String,
    cityContext: String,
    preferredMode: String? = null
): List<TransitConnection> = withContext(Dispatchers.IO) {
    val enrichedQuery = buildEnrichedQuery(query, cityContext)
    val encodedQuery = URLEncoder.encode(enrichedQuery, "UTF-8")

    val geoUrl = "https://api.transitous.org/api/v1/geocode?text=$encodedQuery&place=$fromLat,$fromLon"
    val geoArray = JSONArray(httpGet(geoUrl))
    if (geoArray.length() == 0) return@withContext emptyList()

    val destObj = geoArray.getJSONObject(0)
    val destLat = destObj.getDouble("lat")
    val destLon = destObj.getDouble("lon")
    val destName = destObj.optString("name", enrichedQuery)

    val modeParam = if (preferredMode != null) "&transitModes=$preferredMode" else ""
    val planUrl = "https://api.transitous.org/api/v1/plan" +
            "?fromPlace=$fromLat,$fromLon" +
            "&toPlace=$destLat,$destLon" +
            "&numItineraries=12$modeParam"

    val root = JSONObject(httpGet(planUrl))
    val itineraries = root.optJSONArray("itineraries") ?: return@withContext emptyList()
    val resultList = mutableListOf<TransitConnection>()
    val nowMillis = System.currentTimeMillis()
    val maxWindowMillis = nowMillis + 60 * 60 * 1000L

    for (i in 0 until itineraries.length()) {
        val itinerary = itineraries.getJSONObject(i)
        val legsJson = itinerary.getJSONArray("legs")
        val totalDurSec = itinerary.optInt("duration", 0)
        val parsedLegs = mutableListOf<RouteLegInfo>()
        val transitLineNames = mutableListOf<String>()

        var firstTransitDepartureTime = ""
        var firstTransitDepartureMillis: Long? = null
        var firstTransitDelay = 0
        var firstTransitLine = ""
        var firstTransitDir = ""
        var finalArrTime = ""

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

            if (isWalk) {
                if (dist >= 40 || durSec >= 60) {
                    parsedLegs.add(
                        RouteLegInfo(
                            isWalk = true,
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
                }
            } else {
                val routeShort = leg.optString("routeShortName", "")
                val displayName = if (routeShort.isNotBlank()) {
                    "$mode $routeShort"
                } else {
                    leg.optString("routeLongName", mode.ifBlank { "ÖPNV" })
                }
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
                parsedLegs.add(
                    RouteLegInfo(
                        isWalk = false,
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

        if (firstTransitDepartureMillis != null && firstTransitDepartureMillis > maxWindowMillis && resultList.size >= 2) {
            continue
        }

        resultList.add(
            TransitConnection(
                summaryLines = transitLineNames.joinToString(" → "),
                firstLine = firstTransitLine,
                firstDirection = firstTransitDir,
                firstDepartureTime = firstTransitDepartureTime,
                finalArrivalTime = finalArrTime,
                firstDelayMinutes = firstTransitDelay,
                totalDurationMinutes = maxOf(1, totalDurSec / 60),
                transfersCount = maxOf(0, transitLineNames.size - 1),
                legs = parsedLegs
            )
        )
    }
    resultList
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
    conn.setRequestProperty("User-Agent", "AbfahrtenNextAndHere/3.2 (https://github.com/ClaudeHoeni/LippstadtBus)")
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
        val prefs = context.getSharedPreferences("oeffi_prefs", Context.MODE_PRIVATE)
        val installedTitle = prefs.getString("installed_release_name", "") ?: ""

        if (remoteTitle.isBlank() || remoteTitle == installedTitle) return@withContext null

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
        context.getSharedPreferences("oeffi_prefs", Context.MODE_PRIVATE)
            .edit().putString("installed_release_name", update.title).apply()
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