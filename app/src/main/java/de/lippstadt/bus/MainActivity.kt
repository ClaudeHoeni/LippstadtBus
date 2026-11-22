package de.lippstadt.bus

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Geocoder
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.os.Looper
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
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
    val fromLat: Double?,
    val fromLon: Double?,
    val toLat: Double?,
    val toLon: Double?,
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
    val totalWalkMeters: Int,
    val isWalkOnly: Boolean = false,
    val legs: List<RouteLegInfo>
)

data class AppUpdateInfo(
    val title: String,
    val downloadUrl: String
)

private fun setDepartureAlarm(context: Context, departureMillis: Long, minutesBefore: Int, label: String): String {
    val alarmMillis = departureMillis - minutesBefore * 60_000L
    val cal = Calendar.getInstance(Locale.GERMANY).apply {
        timeInMillis = alarmMillis
    }
    val hour = cal.get(Calendar.HOUR_OF_DAY)
    val minute = cal.get(Calendar.MINUTE)
    val formattedAlarm = String.format(Locale.GERMANY, "%02d:%02d", hour, minute)

    val setAlarmIntent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
        putExtra(AlarmClock.EXTRA_HOUR, hour)
        putExtra(AlarmClock.EXTRA_MINUTES, minute)
        putExtra(AlarmClock.EXTRA_MESSAGE, label)
        putExtra(AlarmClock.EXTRA_SKIP_UI, false)
    }

    return try {
        val resolved = setAlarmIntent.resolveActivity(context.packageManager)
        if (resolved != null) {
            context.startActivity(setAlarmIntent)
            "Wecker auf $formattedAlarm Uhr ($minutesBefore Min. vor Abfahrt) gestellt."
        } else {
            val showAlarmsIntent = Intent(AlarmClock.ACTION_SHOW_ALARMS)
            if (showAlarmsIntent.resolveActivity(context.packageManager) != null) {
                context.startActivity(showAlarmsIntent)
                "Uhr-App geöffnet – Wecker auf $formattedAlarm Uhr stellen ($minutesBefore Min. vorher)."
            } else {
                "Keine Uhr-App gefunden (Erinnerung: $formattedAlarm Uhr, $minutesBefore Min. vorher)."
            }
        }
    } catch (_: Exception) {
        try {
            context.startActivity(Intent(AlarmClock.ACTION_SHOW_ALARMS))
            "Uhr-App geöffnet – Wecker auf $formattedAlarm Uhr stellen ($minutesBefore Min. vorher)."
        } catch (_: Exception) {
            "Wecker konnte nicht automatisch gestellt werden (Zielzeit: $formattedAlarm Uhr)."
        }
    }
}

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
    if (conn.isWalkOnly) {
        val walkLeg = conn.legs.firstOrNull()
        val dist = walkLeg?.distanceMeters ?: conn.totalWalkMeters
        val mins = walkLeg?.durationMinutes ?: conn.totalDurationMinutes
        val target = walkLeg?.toStop ?: conn.firstDirection
        sb.append("Das Ziel $target ist zu Fuß erreichbar. ")
        sb.append("Entfernung circa $dist Meter, Gehzeit etwa $mins Minuten.")
        return sb.toString()
    }

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

private fun openStopInMaps(context: Context, lat: Double?, lon: Double?, label: String) {
    try {
        val uri = if (lat != null && lon != null) {
            Uri.parse("geo:$lat,$lon?q=$lat,$lon(${Uri.encode(label)})")
        } else {
            Uri.parse("geo:0,0?q=${Uri.encode(label)}")
        }
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (_: Exception) {
    }
}

private fun bearingDegreesBetween(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
    val results = FloatArray(2)
    Location.distanceBetween(lat1, lon1, lat2, lon2, results)
    val initialBearing = results[1]
    return (initialBearing + 360f) % 360f
}

@Composable
fun LiveCompassArrowCard(
    userLat: Double?,
    userLon: Double?,
    stopTarget: Triple<String, Double, Double>?,
    destTarget: Triple<String, Double, Double>?,
    onOpenMaps: (Double, Double, String) -> Unit
) {
    val context = LocalContext.current
    var preferFirstStop by remember { mutableStateOf(stopTarget != null) }

    LaunchedEffect(stopTarget, destTarget) {
        preferFirstStop = stopTarget != null
    }

    val activeTarget = if (preferFirstStop && stopTarget != null) stopTarget else (destTarget ?: stopTarget)
    if (userLat == null || userLon == null || activeTarget == null) return

    var deviceAzimuth by remember { mutableStateOf(0f) }
    var hasCompassSensor by remember { mutableStateOf(true) }

    DisposableEffect(Unit) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val rotSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (sensorManager == null || rotSensor == null) {
            hasCompassSensor = false
            onDispose { }
        } else {
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    val rotMatrix = FloatArray(9)
                    val orientation = FloatArray(3)
                    SensorManager.getRotationMatrixFromVector(rotMatrix, event.values)
                    SensorManager.getOrientation(rotMatrix, orientation)
                    val deg = Math.toDegrees(orientation[0].toDouble()).toFloat()
                    deviceAzimuth = (deg + 360f) % 360f
                }
                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
            }
            sensorManager.registerListener(listener, rotSensor, SensorManager.SENSOR_DELAY_UI)
            onDispose {
                sensorManager.unregisterListener(listener)
            }
        }
    }

    val (targetName, tLat, tLon) = activeTarget
    val distMeters = distanceMetersBetween(userLat, userLon, tLat, tLon).toInt()
    val targetBearing = bearingDegreesBetween(userLat, userLon, tLat, tLon)
    val relativeArrowAngle = (targetBearing - deviceAzimuth + 360f) % 360f

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Box(
                    modifier = Modifier
                        .size(84.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .clickable { onOpenMaps(tLat, tLon, targetName) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "⬆",
                        fontSize = 48.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.rotate(relativeArrowAngle)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (preferFirstStop && stopTarget != null) "Richtung Starthaltestelle" else "Richtung Ziel",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = targetName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    val distText = if (distMeters >= 1000) {
                        String.format(Locale.GERMANY, "%.1f km Luftlinie", distMeters / 1000f)
                    } else {
                        "$distMeters m entfernt"
                    }
                    Text(
                        text = if (hasCompassSensor) "$distText · Pfeil folgt Kompass" else "$distText · Richtung ${targetBearing.toInt()}°",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                OutlinedButton(
                    onClick = { onOpenMaps(tLat, tLon, targetName) },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text("🗺️", fontSize = 16.sp)
                }
            }

            if (stopTarget != null && destTarget != null && stopTarget.first != destTarget.first) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = preferFirstStop,
                        onClick = { preferFirstStop = true },
                        label = { Text("Zur Haltestelle", fontSize = 12.sp) },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = !preferFirstStop,
                        onClick = { preferFirstStop = false },
                        label = { Text("Direkt zum Ziel", fontSize = 12.sp) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
fun TransitFinderScreen(externalVoiceTrigger: Int) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val fusedLocationClient = remember { LocationServices.getFusedLocationProviderClient(context) }

    var detectedGpsCity by remember { mutableStateOf<String?>(null) }
    var liveUserCoords by remember { mutableStateOf<Pair<Double, Double>?>(null) }

    var destinationInput by remember { mutableStateOf("") }
    var statusMessage by remember { mutableStateOf("Bereit. Haltestelle, Ziel, Arzt oder Bäckerei sprechen.") }
    var isLoading by remember { mutableStateOf(false) }
    var isListening by remember { mutableStateOf(false) }
    var isSpeaking by remember { mutableStateOf(false) }

    var connections by remember { mutableStateOf<List<TransitConnection>>(emptyList()) }
    var selectedConnectionIndex by remember { mutableStateOf(0) }
    var detailsDropdownExpanded by remember { mutableStateOf(false) }
    var expandedLegIndex by remember { mutableStateOf<Int?>(null) }
    var alternativesDropdownExpanded by remember { mutableStateOf(false) }

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

    DisposableEffect(Unit) {
        val hasLoc = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasLoc) {
            onDispose { }
        } else {
            val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000L)
                .setMinUpdateIntervalMillis(1500L)
                .build()
            val callback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    val loc = result.lastLocation ?: return
                    liveUserCoords = loc.latitude to loc.longitude
                }
            }
            fusedLocationClient.requestLocationUpdates(req, callback, Looper.getMainLooper())
            onDispose {
                fusedLocationClient.removeLocationUpdates(callback)
            }
        }
    }

    fun speakText(text: String) {
        if (tts == null || !ttsReady) {
            statusMessage = "Sprachausgabe auf diesem Gerät nicht verfügbar."
            return
        }
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "conn_summary")
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
            speakText(buildSpokenSummary(conn))
        }
    }

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

        liveUserCoords = location.latitude to location.longitude
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
        val distToDest = distanceMetersBetween(startLat, startLon, dest.lat, dest.lon).toInt()
        val walkMins = maxOf(1, distToDest / 75)
        val nowFmt = SimpleDateFormat("HH:mm", Locale.GERMANY).format(Date())
        val arrFmt = SimpleDateFormat("HH:mm", Locale.GERMANY).format(Date(System.currentTimeMillis() + walkMins * 60_000L))
        val walkOption = TransitConnection(
            summaryLines = "🚶 Fußweg (${walkMins} Min., ${distToDest} m)",
            firstLine = "Fußweg",
            firstDirection = dest.name,
            firstDepartureTime = nowFmt,
            finalArrivalTime = arrFmt,
            departureMillis = System.currentTimeMillis(),
            arrivalMillis = System.currentTimeMillis() + walkMins * 60_000L,
            firstDelayMinutes = 0,
            totalDurationMinutes = walkMins,
            transfersCount = 0,
            totalWalkMeters = distToDest,
            isWalkOnly = true,
            legs = listOf(
                RouteLegInfo(
                    isWalk = true,
                    isTransfer = false,
                    lineName = "Fußweg",
                    direction = dest.name,
                    fromStop = "Aktueller Standort",
                    toStop = dest.name,
                    fromLat = startLat,
                    fromLon = startLon,
                    toLat = dest.lat,
                    toLon = dest.lon,
                    startTime = nowFmt,
                    endTime = arrFmt,
                    delayMinutes = 0,
                    durationMinutes = walkMins,
                    distanceMeters = distToDest
                )
            )
        )
        connections = if (distToDest <= 900 && results.none { it.isWalkOnly }) {
            listOf(walkOption) + results
        } else {
            results
        }
        selectedConnectionIndex = 0
        detailsDropdownExpanded = false
        expandedLegIndex = null
        alternativesDropdownExpanded = false
        statusMessage = if (results.isEmpty()) {
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

        val parsed = parseNaturalTransitRequest(rawText)
        destinationInput = parsed.destination

        scope.launch {
            isLoading = true
            connections = emptyList()
            placeCandidates = emptyList()
            selectedPlaceIndex = 0
            selectedConnectionIndex = 0
            detailsDropdownExpanded = false
            expandedLegIndex = null
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
                activeStartCoords = baseLat to baseLon

                var candidates: List<GeoCandidate> = if (parsed.poiCategory != null) {
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
                    searchNearbyPoisRobust(
                        centerLat = centerLat,
                        centerLon = centerLon,
                        category = parsed.poiCategory,
                        cityContext = cityContextName,
                        radiusMeters = if (parsed.poiAnchorPlace != null) 900 else 850
                    )
                } else {
                    searchTransitStopsAndPlaces(parsed.destination, baseLat, baseLon, cityContextName)
                }

                if (candidates.isEmpty() && parsed.poiCategory != null) {
                    statusMessage = "Erweitere Umkreissuche für ${parsed.poiCategory.label}..."
                    candidates = searchNearbyPoisRobust(
                        centerLat = centerLat,
                        centerLon = centerLon,
                        category = parsed.poiCategory,
                        cityContext = cityContextName,
                        radiusMeters = 1800
                    )
                }

                if (candidates.isEmpty()) {
                    statusMessage = "Ziel \"${parsed.destination}\" nicht gefunden."
                    isLoading = false
                    return@launch
                }

                placeCandidates = candidates
                selectedPlaceIndex = 0
                loadRouteForPlace(
                    startLat = baseLat,
                    startLon = baseLon,
                    dest = candidates.first(),
                    preferredMode = parsed.preferredMode,
                    modeDisplay = parsed.modeDisplayName
                )
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
            putExtra(RecognizerIntent.EXTRA_PROMPT, "z. B. Nächster Arzt, Spätkauf oder Bushaltestelle")
        }
        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                isListening = true
                statusMessage = "Höre zu... Bitte Ziel oder Wunsch nennen."
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

        // GROSSER, PRÄSENTER MIKROFON-BUTTON ALS HAUPTAKTION
        Button(
            onClick = { triggerVoiceWithPermissionCheck() },
            enabled = !isLoading,
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isListening) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(68.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    text = if (isListening) "🔴" else "🎤",
                    fontSize = 28.sp
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = if (isListening) "Ich höre zu..." else "Ziel sprechen",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = destinationInput,
            onValueChange = { destinationInput = it },
            label = { Text("Oder Ziel, Arzt, Spätkauf, Bäckerei + Enter") },
            placeholder = { Text("z. B. nächste Bushaltestelle oder Bäckerei") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { searchTransit() }),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = statusMessage,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(8.dp))

        val activeConn = connections.getOrNull(selectedConnectionIndex)
        val activeDestCandidate = placeCandidates.getOrNull(selectedPlaceIndex)

        // LIVE-NAVIGATIONSPFEIL (KOMPASS-RICHTUNGSPFEIL ZUR HALTESTELLE ODER ZUM ZIEL)
        if (activeDestCandidate != null) {
            val firstTransitLeg = activeConn?.legs?.firstOrNull { !it.isWalk && !it.isTransfer }
            val stopTriple = if (firstTransitLeg?.fromLat != null && firstTransitLeg.fromLon != null) {
                Triple(firstTransitLeg.fromStop, firstTransitLeg.fromLat, firstTransitLeg.fromLon)
            } else {
                null
            }
            val destTriple = Triple(activeDestCandidate.name, activeDestCandidate.lat, activeDestCandidate.lon)
            val currentPos = liveUserCoords ?: activeStartCoords

            LiveCompassArrowCard(
                userLat = currentPos?.first,
                userLon = currentPos?.second,
                stopTarget = stopTriple,
                destTarget = destTriple,
                onOpenMaps = { lat, lon, label -> openStopInMaps(context, lat, lon, label) }
            )
            Spacer(modifier = Modifier.height(10.dp))
        }

        // HAUPTERGEBNIS: KOMPAKTE ZUSAMMENFASSUNG + DETAILS ALS DROPDOWN
        if (activeConn != null) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { toggleSpeakConnection(activeConn) },
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
                    val summarySubtitle = if (activeConn.isWalkOnly) {
                        "Fußweg · ${activeConn.totalWalkMeters} m · ca. ${activeConn.totalDurationMinutes} Min."
                    } else {
                        val transferLabel = when (activeConn.transfersCount) {
                            0 -> "Direktverbindung"
                            1 -> "1 Umstieg"
                            else -> "${activeConn.transfersCount} Umstiege"
                        }
                        "Ankunft ${activeConn.finalArrivalTime} Uhr · Dauer ${activeConn.totalDurationMinutes} Min. · $transferLabel"
                    }
                    Text(
                        text = summarySubtitle,
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
                    if (!activeConn.isWalkOnly) {
                        val minsUntilDep = ((activeConn.departureMillis - System.currentTimeMillis()) / 60_000L).toInt()
                        if (minsUntilDep >= 12) {
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
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // DETAILS ALS DROPDOWN (EINZELNE ABSCHNITTE, UMSTIEGE, KARTEN-LINKS)
                    OutlinedButton(
                        onClick = { detailsDropdownExpanded = !detailsDropdownExpanded },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Verbindungs-Details (${activeConn.legs.size} Abschnitte)",
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(text = if (detailsDropdownExpanded) "▲" else "▼")
                        }
                    }

                    if (detailsDropdownExpanded) {
                        Spacer(modifier = Modifier.height(8.dp))
                        activeConn.legs.forEachIndexed { idx, leg ->
                            if (idx > 0) {
                                Spacer(modifier = Modifier.height(8.dp))
                            }
                            val isLegExpanded = expandedLegIndex == idx

                            if (leg.isTransfer) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.85f))
                                        .clickable { expandedLegIndex = if (isLegExpanded) null else idx }
                                        .padding(horizontal = 10.dp, vertical = 7.dp)
                                ) {
                                    val distNote = if (leg.distanceMeters > 0) ", ${leg.distanceMeters} m Fußweg" else ""
                                    Text(
                                        text = "↻ Umstieg an ${leg.fromStop} (${leg.durationMinutes} Min. Übergang$distNote)",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer
                                    )
                                    if (isLegExpanded) {
                                        Spacer(modifier = Modifier.height(6.dp))
                                        OutlinedButton(
                                            onClick = { openStopInMaps(context, leg.fromLat, leg.fromLon, leg.fromStop) }
                                        ) {
                                            Text("🗺️ Haltestelle auf Karte", fontSize = 12.sp)
                                        }
                                    }
                                }
                            } else if (leg.isWalk) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(6.dp))
                                        .clickable { openStopInMaps(context, leg.toLat, leg.toLon, leg.toStop) }
                                        .padding(vertical = 4.dp, horizontal = 4.dp)
                                ) {
                                    Text(
                                        text = "↳ Fußweg (${leg.durationMinutes} Min., ${leg.distanceMeters} m) → ${leg.toStop}  🗺️",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.secondary
                                    )
                                }
                            } else {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(
                                            color = if (isLegExpanded) {
                                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                            } else {
                                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                                            }
                                        )
                                        .clickable { expandedLegIndex = if (isLegExpanded) null else idx }
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

                                    if (isLegExpanded) {
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            OutlinedButton(
                                                onClick = { openStopInMaps(context, leg.fromLat, leg.fromLon, leg.fromStop) },
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Text("🗺️ Einstieg", fontSize = 12.sp)
                                            }
                                            OutlinedButton(
                                                onClick = { openStopInMaps(context, leg.toLat, leg.toLon, leg.toStop) },
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Text("🗺️ Ausstieg", fontSize = 12.sp)
                                            }
                                            OutlinedButton(
                                                onClick = {
                                                    speakText(
                                                        "Um ${formatTimeForSpeech(leg.startTime)} ab ${leg.fromStop} mit ${leg.lineName} Richtung ${leg.direction}. Ankunft um ${formatTimeForSpeech(leg.endTime)} an ${leg.toStop}."
                                                    )
                                                },
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Text("🔊 Ansage", fontSize = 12.sp)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // DROPDOWN: ALTERNATIVEN
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
                        val transferTag = if (conn.isWalkOnly) "Fußweg" else if (conn.transfersCount == 0) "Direkt" else "${conn.transfersCount}× Umstieg"
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
                                detailsDropdownExpanded = false
                                expandedLegIndex = null
                                alternativesDropdownExpanded = false
                            }
                        )
                    }
                }
            }
        }

        // DROPDOWN: ANDERE ORTE / HALTESTELLEN
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

/**
 * Fehlertolerante POI- und Haltestellensuche:
 * 1. Fragt der Reihe nach mehrere Overpass-Server ab (verhindert Timeouts bei überlastetem Hauptserver)
 * 2. Fallback über Transitous Reverse-Geocode / Geocode, falls Overpass gar nicht antwortet.
 */
suspend fun searchNearbyPoisRobust(
    centerLat: Double,
    centerLon: Double,
    category: PoiCategory,
    cityContext: String,
    radiusMeters: Int = 2000
): List<GeoCandidate> = withContext(Dispatchers.IO) {
    val found = mutableListOf<GeoCandidate>()

    // 1. Wenn speziell nach der nächsten Haltestelle gefragt wurde: direkt Transitous Reverse-Geocode nutzen (superschnell & exakt)
    if (category.isTransitStopSearch) {
        try {
            val revUrl = "https://api.transitous.org/api/v1/reverse-geocode?place=$centerLat,$centerLon"
            val arr = JSONArray(httpGet(revUrl))
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val type = obj.optString("type", "").uppercase(Locale.US)
                val name = obj.optString("name", "").trim()
                val lat = obj.optDouble("lat", Double.NaN)
                val lon = obj.optDouble("lon", Double.NaN)
                if (lat.isNaN() || lon.isNaN() || name.isBlank()) continue
                if (type == "STOP" || name.contains("Bhf", true) || name.contains("Bahnhof", true) || name.startsWith("S ") || name.startsWith("U ")) {
                    val dist = distanceMetersBetween(centerLat, centerLon, lat, lon).toInt()
                    found.add(
                        GeoCandidate(
                            name = name,
                            areaLabel = "ca. $dist m entfernt",
                            lat = lat,
                            lon = lon,
                            isStop = true,
                            isStreet = false
                        )
                    )
                }
            }
        } catch (_: Exception) {
        }
    }

    // 2. Overpass-Abfrage über mehrere Spiegelserver mit Fallback
    if (found.isEmpty()) {
        val unionLines = category.overpassQueries.joinToString("\n") { filter ->
            "  node$filter(around:$radiusMeters,$centerLat,$centerLon);\n  way$filter(around:$radiusMeters,$centerLat,$centerLon);"
        }
        val query = """
            [out:json][timeout:12];
            (
            $unionLines
            );
            out center 20;
        """.trimIndent()
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val endpoints = listOf(
            "https://overpass-api.de/api/interpreter?data=$encodedQuery",
            "https://overpass.kumi.systems/api/interpreter?data=$encodedQuery",
            "https://maps.mail.ru/osm/tools/overpass/api/interpreter?data=$encodedQuery"
        )

        for (ep in endpoints) {
            try {
                val root = JSONObject(httpGet(ep))
                val elements = root.optJSONArray("elements") ?: continue
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
                            isStop = category.isTransitStopSearch,
                            isStreet = false
                        )
                    )
                }
                if (found.isNotEmpty()) break
            } catch (_: Exception) {
                // Nächsten Overpass-Server probieren
            }
        }
    }

    // 3. Fallback über Transitous Geocode falls Overpass komplett blockiert ist
    if (found.isEmpty()) {
        val fallbackQuery = if (cityContext.isNotBlank()) "${category.fallbackKeyword} $cityContext" else category.fallbackKeyword
        found.addAll(searchTransitStopsAndPlaces(fallbackQuery, centerLat, centerLon, cityContext))
    }

    val sorted = found.sortedBy { distanceMetersBetween(centerLat, centerLon, it.lat, it.lon) }
    val distinct = mutableListOf<GeoCandidate>()
    for (cand in sorted) {
        val dup = distinct.any { distanceMetersBetween(it.lat, it.lon, cand.lat, cand.lon) < 40f }
        if (!dup) distinct.add(cand)
        if (distinct.size >= 6) break
    }
    distinct
}

suspend fun searchTransitStopsAndPlaces(
    rawQuery: String,
    biasLat: Double,
    biasLon: Double,
    cityContext: String
): List<GeoCandidate> = withContext(Dispatchers.IO) {
    try {
        val cleanQuery = rawQuery.trim()
        val queryWords = cleanQuery.split(Regex("\\s+")).filter { it.length >= 3 }

        val urls = mutableListOf(
            "https://api.transitous.org/api/v1/geocode?text=${URLEncoder.encode(cleanQuery, "UTF-8")}&place=$biasLat,$biasLon"
        )
        if (queryWords.size == 1 && cityContext.isNotBlank() && !cleanQuery.contains(cityContext, ignoreCase = true)) {
            val withCity = "$cleanQuery, $cityContext"
            urls.add(
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
                if (distFromUser > 120_000f) continue

                val areaParts = mutableListOf<String>()
                val areas = obj.optJSONArray("areas")
                if (areas != null) {
                    for (a in 0 until areas.length()) {
                        val areaObj = areas.getJSONObject(a)
                        val adminLevel = areaObj.optInt("adminLevel", 0)
                        val areaName = areaObj.optString("name", "").trim()
                        if (areaName.isBlank() || areaName == "Deutschland") continue
                        if (areaName.startsWith("Regierungsbezirk", ignoreCase = true)) continue
                        if (adminLevel in 1..5 && adminLevel != 0) continue
                        if (!areaParts.contains(areaName)) {
                            areaParts.add(areaName)
                        }
                    }
                }
                val zip = obj.optString("zip", "").trim()
                val compactCity = areaParts.takeLast(2).joinToString(", ")
                val areaText = buildString {
                    if (zip.isNotBlank()) append("$zip ")
                    append(compactCity)
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

        fun matchScore(c: GeoCandidate): Int {
            val qLower = cleanQuery.lowercase(Locale.GERMANY)
            val nameLower = c.name.lowercase(Locale.GERMANY)
            val fullLower = "${c.name} ${c.areaLabel}".lowercase(Locale.GERMANY)
            val nameWordHits = queryWords.count { w -> nameLower.contains(w.lowercase(Locale.GERMANY)) }
            val areaWordHits = queryWords.count { w -> fullLower.contains(w.lowercase(Locale.GERMANY)) }
            val exactBonus = when {
                nameLower == qLower -> 40
                nameLower.startsWith(qLower) -> 28
                nameLower.contains(qLower) -> 18
                else -> 0
            }
            val nameTokens = nameLower.split(Regex("[^a-zäöüß0-9]+")).filter { it.length >= 3 }
            val queryTokens = qLower.split(Regex("[^a-zäöüß0-9]+")).filter { it.length >= 3 }
            val extraTokens = nameTokens.count { tok -> queryTokens.none { qTok -> tok.contains(qTok) || qTok.contains(tok) } }
            val stopBonus = if (c.isStop) 8 else if (c.isStreet) 2 else 0
            return exactBonus + (nameWordHits * 15) + (areaWordHits * 6) + stopBonus - (extraTokens * 8)
        }

        val sorted = rawCandidates.sortedWith(
            compareByDescending<GeoCandidate> { matchScore(it) }
                .thenBy { distanceMetersBetween(biasLat, biasLon, it.lat, it.lon) }
        )

        val distinct = mutableListOf<GeoCandidate>()
        for (cand in sorted) {
            val isDuplicate = distinct.any { existing ->
                distanceMetersBetween(existing.lat, existing.lon, cand.lat, cand.lon) < 550f
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
            "&numItineraries=15$modesParam"

    val root = JSONObject(httpGet(planUrl))
    val itineraries = root.optJSONArray("itineraries") ?: return@withContext emptyList()
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
        var walkMetersSum = 0

        for (j in 0 until legsJson.length()) {
            val leg = legsJson.getJSONObject(j)
            val mode = leg.optString("mode", "")
            val isWalk = mode.equals("WALK", ignoreCase = true)
            val durSec = leg.optInt("duration", 0)
            val dist = leg.optDouble("distance", 0.0).toInt()
            val fromObj = leg.optJSONObject("from")
            val toObj = leg.optJSONObject("to")
            val originName = fromObj?.optString("name") ?: "Start"
            val arrivalStopName = if (j == legsJson.length() - 1) destName else (toObj?.optString("name") ?: destName)
            val fLat = fromObj?.optDouble("lat", Double.NaN)?.takeIf { !it.isNaN() }
            val fLon = fromObj?.optDouble("lon", Double.NaN)?.takeIf { !it.isNaN() }
            val tLat = toObj?.optDouble("lat", Double.NaN)?.takeIf { !it.isNaN() } ?: destLat
            val tLon = toObj?.optDouble("lon", Double.NaN)?.takeIf { !it.isNaN() } ?: destLon

            val startTimeStr = leg.optString("startTime", "")
            val endTimeStr = leg.optString("endTime", "")
            val scheduledStartStr = leg.optString("scheduledStartTime", startTimeStr)

            val formattedStart = formatIsoTime(startTimeStr)
            val formattedEnd = formatIsoTime(endTimeStr)
            finalArrTime = formattedEnd
            finalArrMillis = parseIsoMillis(endTimeStr)

            if (isWalk) {
                walkMetersSum += dist
                rawParsedLegs.add(
                    RouteLegInfo(
                        isWalk = true,
                        isTransfer = false,
                        lineName = "Fußweg",
                        direction = "",
                        fromStop = originName,
                        toStop = arrivalStopName,
                        fromLat = fLat,
                        fromLon = fLon,
                        toLat = tLat,
                        toLon = tLon,
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
                        fromLat = fLat,
                        fromLon = fLon,
                        toLat = tLat,
                        toLon = tLon,
                        startTime = formattedStart,
                        endTime = formattedEnd,
                        delayMinutes = delayMins,
                        durationMinutes = maxOf(1, durSec / 60),
                        distanceMeters = dist
                    )
                )
            }
        }

        // Reine Fußweg-Verbindungen ebenfalls zulassen (wichtig für nahe Ziele wie Bäckerei/Arzt/nächste Haltestelle)
        if (transitLineNames.isEmpty()) {
            if (rawParsedLegs.isNotEmpty()) {
                val firstWalk = rawParsedLegs.first()
                resultList.add(
                    TransitConnection(
                        summaryLines = "🚶 Fußweg zum Ziel",
                        firstLine = "Fußweg",
                        firstDirection = destName,
                        firstDepartureTime = firstWalk.startTime,
                        finalArrivalTime = finalArrTime,
                        departureMillis = nowMillis,
                        arrivalMillis = finalArrMillis ?: (nowMillis + totalDurSec * 1000L),
                        firstDelayMinutes = 0,
                        totalDurationMinutes = maxOf(1, totalDurSec / 60),
                        transfersCount = 0,
                        totalWalkMeters = walkMetersSum,
                        isWalkOnly = true,
                        legs = rawParsedLegs
                    )
                )
            }
            continue
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
                            fromLat = current.fromLat,
                            fromLon = current.fromLon,
                            toLat = current.fromLat,
                            toLon = current.fromLon,
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
                totalWalkMeters = walkMetersSum,
                isWalkOnly = false,
                legs = finalLegs
            )
        )
    }

    // Falls MOTIS gar keine Route zurückgibt (z. B. Ziel liegt 150m entfernt), direkten Fußweg erzeugen
    if (resultList.isEmpty()) {
        val straightMeters = distanceMetersBetween(startLat, startLon, destLat, destLon).toInt()
        val estWalkMins = maxOf(1, straightMeters / 75)
        val nowFmt = SimpleDateFormat("HH:mm", Locale.GERMANY).format(Date(referenceMillis))
        val arrMillis = referenceMillis + estWalkMins * 60_000L
        val arrFmt = SimpleDateFormat("HH:mm", Locale.GERMANY).format(Date(arrMillis))
        resultList.add(
            TransitConnection(
                summaryLines = "🚶 Fußweg zum Ziel",
                firstLine = "Fußweg",
                firstDirection = destName,
                firstDepartureTime = nowFmt,
                finalArrivalTime = arrFmt,
                departureMillis = referenceMillis,
                arrivalMillis = arrMillis,
                firstDelayMinutes = 0,
                totalDurationMinutes = estWalkMins,
                transfersCount = 0,
                totalWalkMeters = straightMeters,
                isWalkOnly = true,
                legs = listOf(
                    RouteLegInfo(
                        isWalk = true,
                        isTransfer = false,
                        lineName = "Fußweg",
                        direction = destName,
                        fromStop = "Aktueller Standort",
                        toStop = destName,
                        fromLat = startLat,
                        fromLon = startLon,
                        toLat = destLat,
                        toLon = destLon,
                        startTime = nowFmt,
                        endTime = arrFmt,
                        delayMinutes = 0,
                        durationMinutes = estWalkMins,
                        distanceMeters = straightMeters
                    )
                )
            )
        )
    }

    resultList.sortedWith { a, b ->
        val arrDiffMins = (a.arrivalMillis - b.arrivalMillis) / 60000L
        if (kotlin.math.abs(arrDiffMins) >= 5) {
            a.arrivalMillis.compareTo(b.arrivalMillis)
        } else {
            val transferCmp = a.transfersCount.compareTo(b.transfersCount)
            if (transferCmp != 0) transferCmp else a.totalWalkMeters.compareTo(b.totalWalkMeters)
        }
    }
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
    conn.setRequestProperty("User-Agent", "AbfahrtenNextAndHere/5.0 (https://github.com/ClaudeHoeni/LippstadtBus)")
    conn.connectTimeout = 10000
    conn.readTimeout = 10000
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
