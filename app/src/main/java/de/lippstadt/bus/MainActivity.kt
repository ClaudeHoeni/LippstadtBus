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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class TransitConnection(
    val line: String,
    val direction: String,
    val departureTime: String,
    val delayMinutes: Int,
    val originStop: String,
    val destinationStop: String,
    val walkInfo: String
)

data class CityOption(
    val name: String,
    val lat: Double?,
    val lon: Double?
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
    var availableUpdate by remember { mutableStateOf<AppUpdateInfo?>(null) }
    var isUpdating by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        availableUpdate = checkForGitHubUpdate(context)
    }
    var newCityInput by remember { mutableStateOf("") }

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
                    "Keine Verbindungen gefunden."
                } else {
                    val areaLabel = if (cityContextName.isNotBlank()) " in $cityContextName" else ""
                    "Nächste Abfahrten$areaLabel:"
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
            .padding(16.dp)
    ) {
        Text(
            text = "Öffi Finder",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        availableUpdate?.let { upd ->
            Spacer(modifier = Modifier.height(8.dp))
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
        Spacer(modifier = Modifier.height(8.dp))

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

        Spacer(modifier = Modifier.height(10.dp))

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
        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = { searchTransit() },
            enabled = !isLoading,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (isLoading) "Suche läuft..." else "Nächste Verbindung finden")
        }

        Spacer(modifier = Modifier.height(12.dp))
        Text(text = statusMessage, style = MaterialTheme.typography.bodyMedium)
        Spacer(modifier = Modifier.height(12.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(connections) { conn ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = conn.line,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            val delayText = if (conn.delayMinutes > 0) " (+${conn.delayMinutes} Min)" else " (pünktlich)"
                            Text(
                                text = "${conn.departureTime}$delayText",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (conn.delayMinutes > 2) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        if (conn.walkInfo.isNotBlank()) {
                            Text(
                                text = conn.walkInfo,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                        Text(text = "Richtung: ${conn.direction}")
                        Text(text = "Ab: ${conn.originStop}")
                        Text(text = "An: ${conn.destinationStop}")
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

    val planUrl = "https://api.transitous.org/api/v1/plan" +
            "?fromPlace=$fromLat,$fromLon" +
            "&toPlace=$destLat,$destLon" +
            "&numItineraries=5"

    val root = JSONObject(httpGet(planUrl))
    val itineraries = root.optJSONArray("itineraries") ?: return@withContext emptyList()
    val resultList = mutableListOf<TransitConnection>()

    for (i in 0 until itineraries.length()) {
        val itinerary = itineraries.getJSONObject(i)
        val legs = itinerary.getJSONArray("legs")
        var initialWalkNote = ""

        for (j in 0 until legs.length()) {
            val leg = legs.getJSONObject(j)
            val mode = leg.optString("mode", "")

            if (mode.equals("WALK", ignoreCase = true)) {
                val durationSec = leg.optInt("duration", 0)
                val dist = leg.optDouble("distance", 0.0).toInt()
                if (j == 0 && durationSec > 60) {
                    val walkMins = maxOf(1, durationSec / 60)
                    initialWalkNote = "Fußweg zur Haltestelle: ca. $walkMins Min. ($dist m)"
                }
                continue
            }

            val routeShort = leg.optString("routeShortName", "")
            val displayName = if (routeShort.isNotBlank()) {
                "$mode $routeShort"
            } else {
                leg.optString("routeLongName", mode.ifBlank { "ÖPNV" })
            }
            val headsign = leg.optString("headsign", destName)
            val originName = leg.optJSONObject("from")?.optString("name") ?: "Haltestelle"
            val arrivalStopName = leg.optJSONObject("to")?.optString("name") ?: destName

            val startTimeStr = leg.optString("startTime", "")
            val scheduledStartStr = leg.optString("scheduledStartTime", startTimeStr)
            val formattedTime = if (startTimeStr.length >= 16 && startTimeStr.contains("T")) {
                startTimeStr.substringAfter("T").take(5)
            } else {
                SimpleDateFormat("HH:mm", Locale.GERMANY).format(Date())
            }

            val delayMins = calculateDelayMinutes(scheduledStartStr, startTimeStr)

            resultList.add(
                TransitConnection(
                    line = displayName,
                    direction = headsign,
                    departureTime = formattedTime,
                    delayMinutes = delayMins,
                    originStop = originName,
                    destinationStop = arrivalStopName,
                    walkInfo = initialWalkNote
                )
            )
            break
        }
    }
    resultList
}

private fun calculateDelayMinutes(scheduledIso: String, actualIso: String): Int {
    return try {
        if (scheduledIso.isBlank() || actualIso.isBlank() || scheduledIso == actualIso) return 0
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        val t1 = fmt.parse(scheduledIso.take(19))?.time ?: return 0
        val t2 = fmt.parse(actualIso.take(19))?.time ?: return 0
        maxOf(0, ((t2 - t1) / 60000L).toInt())
    } catch (_: Exception) {
        0
    }
}

private fun httpGet(urlString: String): String {
    val conn = URL(urlString).openConnection() as HttpURLConnection
    conn.requestMethod = "GET"
    conn.setRequestProperty("User-Agent", "OeffiFinderAndroid/2.3 (https://github.com/ClaudeHoeni/LippstadtBus)")
    conn.connectTimeout = 12000
    conn.readTimeout = 12000
    if (conn.responseCode !in 200..299) {
        throw IllegalStateException("HTTP ${conn.responseCode}")
    }
    return conn.inputStream.bufferedReader().use { it.readText() }
}

data class AppUpdateInfo(
    val title: String,
    val downloadUrl: String
)

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
        val targetFile = java.io.File(context.cacheDir, "update.apk")
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

    val uri = androidx.core.content.FileProvider.getUriForFile(
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