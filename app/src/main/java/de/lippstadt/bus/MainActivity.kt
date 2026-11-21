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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
    var statusMessage by remember { mutableStateOf("Bereit. Ziel eingeben oder Mikrofon tippen.") }
    var isLoading by remember { mutableStateOf(false) }
    var isListening by remember { mutableStateOf(false) }
    var connections by remember { mutableStateOf<List<TransitConnection>>(emptyList()) }

    var showAddCityDialog by remember { mutableStateOf(false) }
    var newCityInput by remember { mutableStateOf("") }

    var availableUpdate by remember { mutableStateOf<AppUpdateInfo?>(null) }
    var isUpdating by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        availableUpdate = checkForGitHubUpdate(context)
    }

    @SuppressLint("MissingPermission")
    fun searchTransit(queryOverride: String? = null) {
        val target = (queryOverride ?: destinationInput).trim()
        if (target.isBlank()) {
            statusMessage = "Bitte ein Ziel oder eine Adresse eingeben."
            return
        }
        scope.launch {
            isLoading = true
            connections = emptyList()

            try {
                val lat: Double
                val lon: Double
                val cityContextName: String

                if (selectedCity.lat != null && selectedCity.lon != null) {
                    lat = selectedCity.lat!!
                    lon = selectedCity.lon!!
                    cityContextName = selectedCity.name
                    statusMessage = "[${selectedCity.name}] Suche Verbindungen nach \"$target\"..."
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
                    val locLabel = if (cityContextName.isNotBlank()) " ($cityContextName)" else ""
                    statusMessage = "Standort erkannt$locLabel. Suche nach \"$target\"..."
                }

                val results = fetchConnections(
                    fromLat = lat,
                    fromLon = lon,
                    query = target,
                    cityContext = cityContextName
                )
                connections = results
                statusMessage = if (results.isEmpty()) {
                    "Keine Verbindungen in den nächsten 45 Min. gefunden."
                } else {
                    val areaLabel = if (cityContextName.isNotBlank()) " in $cityContextName" else ""
                    "Verbindungen$areaLabel (nächste 45 Min.):"
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
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Wohin möchtest du fahren?")
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
                    destinationInput = spoken
                    searchTransit(queryOverride = spoken)
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
            .padding(14.dp)
    ) {
        Text(
            text = "Öffi Finder",
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
                label = { Text("Ziel / Haltestelle / Adresse") },
                placeholder = { Text("z. B. Hauptbahnhof, Marktplatz") },
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
            Text(if (isLoading) "Suche läuft..." else "Verbindungen finden")
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(text = statusMessage, style = MaterialTheme.typography.bodyMedium)
        Spacer(modifier = Modifier.height(8.dp))

        // NEUE SPALTE / ÜBERSICHT: Abfahrten der nächsten 45 Minuten
        if (connections.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Text(
                        text = "Abfahrten in den nächsten 45 Minuten",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        connections.forEach { conn ->
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surface,
                                tonalElevation = 2.dp
                            ) {
                                Column(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        text = conn.firstDepartureTime + if (conn.firstDelayMinutes > 0) " (+${conn.firstDelayMinutes}')" else "",
                                        fontWeight = FontWeight.Bold,
                                        color = if (conn.firstDelayMinutes > 2) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                        fontSize = 14.sp
                                    )
                                    Text(
                                        text = conn.summaryLines,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 12.sp
                                    )
                                    Text(
                                        text = "An ${conn.finalArrivalTime} (${conn.totalDurationMinutes} Min)",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
        }

        // DETAILLIERTE VERBINDUNGEN MIT ALLEN UMSTIEGEN & LINIENNUMMERN
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(connections) { conn ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        // Kopfzeile der Verbindung: Linienkette + Gesamtdauer + Umstiege
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = conn.summaryLines,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f)
                            )
                            val transferLabel = when (conn.transfersCount) {
                                0 -> "Direkt"
                                1 -> "1 Umstieg"
                                else -> "${conn.transfersCount} Umstiege"
                            }
                            Text(
                                text = "$transferLabel · ${conn.totalDurationMinutes} Min",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        Text(
                            text = "Start: ${conn.firstDepartureTime} → Ziel: ${conn.finalArrivalTime}",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // Einzelne Fahrtabschnitte & Umstiege
                        conn.legs.forEachIndexed { idx, leg ->
                            if (idx > 0) {
                                Spacer(modifier = Modifier.height(6.dp))
                            }
                            if (leg.isWalk) {
                                Text(
                                    text = "↳ Fußweg (${leg.durationMinutes} Min, ${leg.distanceMeters} m) nach ${leg.toStop}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                            } else {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(
                                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                            shape = RoundedCornerShape(6.dp)
                                        )
                                        .padding(8.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = "${leg.lineName} → ${leg.direction}",
                                            fontWeight = FontWeight.Bold,
                                            style = MaterialTheme.typography.bodyMedium,
                                            modifier = Modifier.weight(1f)
                                        )
                                        val delayStr = if (leg.delayMinutes > 0) " (+${leg.delayMinutes}')" else ""
                                        Text(
                                            text = "${leg.startTime}$delayStr",
                                            fontWeight = FontWeight.Bold,
                                            color = if (leg.delayMinutes > 2) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    }
                                    Text(
                                        text = "Ab ${leg.startTime}: ${leg.fromStop}",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    Text(
                                        text = "An ${leg.endTime}: ${leg.toStop}",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }
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
    cityContext: String
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

    // Mehr Routen anfordern (numItineraries=10), um alle Abfahrten der nächsten 45 Minuten abzudecken
    val planUrl = "https://api.transitous.org/api/v1/plan" +
            "?fromPlace=$fromLat,$fromLon" +
            "&toPlace=$destLat,$destLon" +
            "&numItineraries=10"

    val root = JSONObject(httpGet(planUrl))
    val itineraries = root.optJSONArray("itineraries") ?: return@withContext emptyList()
    val resultList = mutableListOf<TransitConnection>()
    val nowMillis = System.currentTimeMillis()
    val maxWindowMillis = nowMillis + 45 * 60 * 1000L

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
                // Kurze Umsteigewege unter 40m ausblenden, relevante Fußwege anzeigen
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

        // Filter auf die nächsten 45 Minuten (sofern Zeitstempel vorliegt; mindestens die ersten 3 Treffer behalten)
        if (firstTransitDepartureMillis != null && firstTransitDepartureMillis > maxWindowMillis && resultList.size >= 3) {
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
    conn.setRequestProperty("User-Agent", "OeffiFinderAndroid/3.0 (https://github.com/ClaudeHoeni/LippstadtBus)")
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
        conn.setRequestProperty("User-Agent", "OeffiFinderUpdater/1.0")
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