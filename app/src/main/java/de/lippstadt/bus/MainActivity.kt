package de.lippstadt.bus

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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

data class BusConnection(
    val line: String,
    val direction: String,
    val departureTime: String,
    val delayMinutes: Int,
    val originStop: String,
    val destinationStop: String
)

class MainActivity : ComponentActivity() {
    companion object {
        const val ACTION_VOICE_SEARCH = "de.lippstadt.bus.ACTION_VOICE_SEARCH"
        const val TEST_LIPPSTADT_LAT = 51.6754
        const val TEST_LIPPSTADT_LON = 8.3446
    }

    private var voiceTriggerCounter by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.action == ACTION_VOICE_SEARCH) {
            voiceTriggerCounter++
        }
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    BusFinderScreen(externalVoiceTrigger = voiceTriggerCounter)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == ACTION_VOICE_SEARCH) {
            voiceTriggerCounter++
        }
    }
}

@Composable
fun BusFinderScreen(externalVoiceTrigger: Int) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val fusedLocationClient = remember { LocationServices.getFusedLocationProviderClient(context) }

    var destinationInput by remember { mutableStateOf("") }
    var statusMessage by remember { mutableStateOf("Bereit. Ziel eingeben, Mikrofon nutzen oder Lippstadt-Test starten.") }
    var isLoading by remember { mutableStateOf(false) }
    var isListening by remember { mutableStateOf(false) }
    var useSimulatedLippstadtLocation by remember { mutableStateOf(false) }
    var connections by remember { mutableStateOf<List<BusConnection>>(emptyList()) }

    @SuppressLint("MissingPermission")
    fun searchBus(
        queryOverride: String? = null,
        forceSimulatedLocation: Boolean = useSimulatedLippstadtLocation
    ) {
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

                if (forceSimulatedLocation) {
                    lat = MainActivity.TEST_LIPPSTADT_LAT
                    lon = MainActivity.TEST_LIPPSTADT_LON
                    statusMessage = "[TESTMODUS] Simuliere Standort Marktplatz Lippstadt -> \"$target\"..."
                } else {
                    statusMessage = "Ermittle GPS-Standort..."
                    val location = fusedLocationClient.getCurrentLocation(
                        Priority.PRIORITY_HIGH_ACCURACY, null
                    ).await() ?: fusedLocationClient.lastLocation.await()

                    if (location == null) {
                        statusMessage = "GPS-Position nicht verfuegbar. Nutze den Testmodus fuer Lippstadt."
                        isLoading = false
                        return@launch
                    }
                    lat = location.latitude
                    lon = location.longitude
                    statusMessage = "Suche Busverbindungen nach \"$target\"..."
                }

                val results = fetchConnections(
                    fromLat = lat,
                    fromLon = lon,
                    query = target
                )
                connections = results
                statusMessage = if (results.isEmpty()) {
                    "Keine direkten Busverbindungen gefunden."
                } else {
                    val prefix = if (forceSimulatedLocation) "[TEST LIPPSTADT] " else ""
                    "${prefix}Naechste Verbindungen gefunden:"
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
            statusMessage = "Spracherkennung ist auf diesem Geraet nicht verfuegbar."
            return
        }
        val recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "de-DE")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Wohin in Lippstadt?")
        }
        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                isListening = true
                statusMessage = "Hoere zu... Bitte Ziel nennen."
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                isListening = false
            }
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
                    searchBus(queryOverride = spoken)
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
        if (pendingVoiceAfterPermission && audioOk) {
            pendingVoiceAfterPermission = false
            startVoiceInput()
        } else {
            statusMessage = "Berechtigungen aktualisiert."
        }
    }

    fun triggerVoiceWithPermissionCheck() {
        val hasAudio = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        val hasLoc = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (hasAudio && (hasLoc || useSimulatedLippstadtLocation)) {
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
            .padding(16.dp)
    ) {
        Text(
            text = "Oeffi Lippstadt (59555)",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = destinationInput,
                onValueChange = { destinationInput = it },
                label = { Text("Ziel / Haltestelle / Adresse") },
                placeholder = { Text("z. B. Bahnhof, Klinikum, Marktstrasse") },
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
            onClick = { searchBus() },
            enabled = !isLoading,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (isLoading) "Suche laeuft..." else "Naechsten Bus finden")
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Standort Lippstadt simulieren",
                style = MaterialTheme.typography.bodySmall
            )
            Switch(
                checked = useSimulatedLippstadtLocation,
                onCheckedChange = { useSimulatedLippstadtLocation = it }
            )
        }

        OutlinedButton(
            onClick = {
                useSimulatedLippstadtLocation = true
                if (destinationInput.isBlank()) {
                    destinationInput = "Bahnhof"
                }
                searchBus(queryOverride = destinationInput, forceSimulatedLocation = true)
            },
            enabled = !isLoading,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Test: Ab Marktplatz Lippstadt suchen")
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
                                text = "Linie ${conn.line}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            val delayText = if (conn.delayMinutes > 0) " (+${conn.delayMinutes} Min)" else " (puenktlich)"
                            Text(
                                text = "${conn.departureTime}$delayText",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (conn.delayMinutes > 2) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(text = "Richtung: ${conn.direction}")
                        Text(text = "Ab: ${conn.originStop}")
                        Text(text = "An: ${conn.destinationStop}")
                    }
                }
            }
        }
    }
}

suspend fun fetchConnections(
    fromLat: Double,
    fromLon: Double,
    query: String
): List<BusConnection> = withContext(Dispatchers.IO) {
    val enrichedQuery = if (query.contains("lippstadt", ignoreCase = true) || query.contains("59555")) {
        query
    } else {
        "$query, 59555 Lippstadt"
    }
    val encodedQuery = URLEncoder.encode(enrichedQuery, "UTF-8")

    val apiHost = "v6.db.transport.rest"
    val locUrl = "https://$apiHost/locations?query=$encodedQuery&results=1&stops=true&addresses=true&poi=true"
    val locArray = JSONArray(httpGet(locUrl))
    if (locArray.length() == 0) return@withContext emptyList()

    val destObj = locArray.getJSONObject(0)
    val destLat: Double
    val destLon: Double
    val destName = destObj.optString("name", enrichedQuery)

    if (destObj.has("location")) {
        val loc = destObj.getJSONObject("location")
        destLat = loc.getDouble("latitude")
        destLon = loc.getDouble("longitude")
    } else {
        destLat = destObj.getDouble("latitude")
        destLon = destObj.getDouble("longitude")
    }

    val journeyUrl = "https://$apiHost/journeys" +
            "?from.latitude=$fromLat&from.longitude=$fromLon&from.address=Mein+Standort" +
            "&to.latitude=$destLat&to.longitude=$destLon&to.address=${URLEncoder.encode(destName, "UTF-8")}" +
            "&results=5&nationalExpress=false&national=false&regionalExp=false&regional=false&suburban=false&ferry=false&subway=false&tram=false&bus=true"

    val root = JSONObject(httpGet(journeyUrl))
    val journeys = root.optJSONArray("journeys") ?: return@withContext emptyList()
    val resultList = mutableListOf<BusConnection>()

    for (i in 0 until journeys.length()) {
        val journey = journeys.getJSONObject(i)
        val legs = journey.getJSONArray("legs")

        for (j in 0 until legs.length()) {
            val leg = legs.getJSONObject(j)
            if (leg.optBoolean("walking", false)) continue

            val lineObj = leg.optJSONObject("line") ?: continue
            val lineName = lineObj.optString("name", "Bus")
            val direction = leg.optString("direction", destName)
            val originName = leg.optJSONObject("origin")?.optString("name") ?: "Haltestelle"
            val arrivalStopName = leg.optJSONObject("destination")?.optString("name") ?: destName

            val depStr = leg.optString("departure", leg.optString("plannedDeparture", ""))
            val formattedTime = if (depStr.length >= 16 && depStr.contains("T")) {
                depStr.substringAfter("T").take(5)
            } else {
                SimpleDateFormat("HH:mm", Locale.GERMANY).format(Date())
            }
            val delaySec = leg.optInt("departureDelay", 0)

            resultList.add(
                BusConnection(
                    line = lineName,
                    direction = direction,
                    departureTime = formattedTime,
                    delayMinutes = delaySec / 60,
                    originStop = originName,
                    destinationStop = arrivalStopName
                )
            )
            break
        }
    }
    resultList
}

private fun httpGet(urlString: String): String {
    val conn = URL(urlString).openConnection() as HttpURLConnection
    conn.requestMethod = "GET"
    conn.setRequestProperty("User-Agent", "LippstadtBusApp/1.2")
    conn.connectTimeout = 10000
    conn.readTimeout = 10000
    return conn.inputStream.bufferedReader().use { it.readText() }
}
