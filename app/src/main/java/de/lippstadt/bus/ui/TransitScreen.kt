package de.lippstadt.bus.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import de.lippstadt.bus.SystemActions
import de.lippstadt.bus.TransitViewModel
import de.lippstadt.bus.data.NavTarget
import de.lippstadt.bus.data.RouteLegInfo
import de.lippstadt.bus.data.TransitConnection
import de.lippstadt.bus.data.bearingDegrees
import de.lippstadt.bus.data.buildLegAnnouncement
import de.lippstadt.bus.data.buildTransferOrWalkWarningText
import de.lippstadt.bus.data.distanceMeters
import de.lippstadt.bus.data.formatDistance
import de.lippstadt.bus.data.transferLabel
import kotlin.math.abs

private val QUICK_QUERIES = listOf("Nächste Haltestelle", "Zum Bahnhof", "Bäckerei", "Apotheke", "Supermarkt")

@Composable
fun TransitScreen(vm: TransitViewModel, voiceTrigger: Int) {
    val context = LocalContext.current
    var pendingVoice by rememberSaveable { mutableStateOf(false) }
    var askedLocation by rememberSaveable { mutableStateOf(false) }

    fun hasAudio() = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        if (vm.hasLocationPermission()) vm.refreshLocation()
        if (pendingVoice) {
            pendingVoice = false
            if (perms[Manifest.permission.RECORD_AUDIO] == true || hasAudio()) vm.startVoiceInput()
            else vm.postStatus("Mikrofon-Berechtigung wurde abgelehnt.")
        }
    }

    fun startVoice() {
        if (hasAudio() && vm.hasLocationPermission()) {
            vm.startVoiceInput()
        } else {
            pendingVoice = true
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
        if (!vm.hasLocationPermission() && !askedLocation) {
            askedLocation = true
            permissionLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
        }
    }

    LaunchedEffect(voiceTrigger) {
        if (voiceTrigger > 0) startVoice()
    }

    val conn = vm.activeConnection
    val place = vm.activePlace

    fun openMap(lat: Double?, lon: Double?, label: String) {
        if (!SystemActions.openInMaps(context, lat, lon, label)) vm.postStatus("Keine Karten-App gefunden.")
    }

    Scaffold(
        containerColor = AppColors.Background,
        bottomBar = {
            BottomActionBar(
                isListening = vm.isListening,
                isSpeaking = vm.isSpeaking,
                canSpeak = conn != null,
                canMap = place != null || conn != null,
                onVoice = { startVoice() },
                onSpeak = { vm.toggleSpeakActive() },
                buildNumber = vm.buildNumber,
                onMap = {
                    val last = conn?.legs?.lastOrNull()
                    openMap(place?.lat ?: last?.toLat, place?.lon ?: last?.toLon, place?.name ?: last?.toStop ?: "")
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Header(
                city = vm.gpsCity,
                gpsActive = vm.gpsActive,
                isLocating = vm.isLocating,
                onToggleGps = { vm.toggleGps() }
            )

            vm.availableUpdate?.let { upd ->
                DarkCard(contentPadding = PaddingValues(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            SectionLabel("Update verfügbar", AppColors.Pink)
                            VSpace(4.dp)
                            Text(upd.title, style = MaterialTheme.typography.bodyMedium, color = AppColors.Text)
                        }
                        Pill(
                            text = if (vm.isUpdating) "Lädt …" else "Installieren",
                            onClick = { vm.installUpdate() },
                            filled = true,
                            color = AppColors.Pink,
                            enabled = !vm.isUpdating
                        )
                    }
                }
            }

            SearchCard(vm = vm, onVoice = { startVoice() })

            val loc = vm.userLocation
            if (place != null && loc != null) {
                val stopLeg = conn?.firstTransitLeg
                val stopTarget = if (stopLeg?.fromLat != null && stopLeg.fromLon != null) {
                    NavTarget(stopLeg.fromStop, stopLeg.fromLat, stopLeg.fromLon)
                } else {
                    null
                }
                CompassCard(
                    userLat = loc.lat,
                    userLon = loc.lon,
                    destTarget = NavTarget(place.name, place.lat, place.lon),
                    stopTarget = stopTarget,
                    onOpenMap = { openMap(it.lat, it.lon, it.name) }
                )
            }

            if (conn != null) {
                ConnectionCard(
                    conn = conn,
                    destinationLabel = place?.fullDisplay,
                    isSpeaking = vm.isSpeaking,
                    onToggleSpeak = { vm.toggleSpeakActive() },
                    onSpeakLeg = { vm.speak(buildLegAnnouncement(it)) },
                    onAlarm = { mins ->
                        vm.postStatus(
                            SystemActions.setDepartureAlarm(
                                context, conn.departureMillis, mins,
                                "Abfahrt ${conn.firstLine} (${conn.firstDepartureTime} Uhr)"
                            )
                        )
                    },
                    onOpenMap = { lat, lon, label -> openMap(lat, lon, label) }
                )
            }

            if (vm.connections.size > 1) {
                DarkCard {
                    SectionLabel("Alternativen")
                    VSpace(8.dp)
                    vm.connections.forEachIndexed { idx, c ->
                        val delay = if (c.firstDelayMinutes > 0) " +${c.firstDelayMinutes}" else ""
                        SelectableRow(
                            selected = idx == vm.selectedConnection,
                            title = c.summaryLines,
                            subtitle = "an ${c.finalArrivalTime} · ${c.totalDurationMinutes} Min · ${transferLabel(c)}",
                            trailing = c.firstDepartureTime + delay,
                            onClick = { vm.selectConnection(idx) },
                            accent = if (c.firstDelayMinutes > 2) AppColors.Pink else AppColors.Orange
                        )
                    }
                }
            }

            if (vm.places.size > 1) {
                DarkCard {
                    SectionLabel("Weitere Ziele", AppColors.Pink)
                    VSpace(8.dp)
                    vm.places.forEachIndexed { idx, p ->
                        val dist = loc?.let { formatDistance(distanceMeters(it.lat, it.lon, p.lat, p.lon).toInt()) }
                        SelectableRow(
                            selected = idx == vm.selectedPlace,
                            title = (if (p.isStop) "🚏 " else "") + p.name,
                            subtitle = p.areaLabel,
                            trailing = dist,
                            onClick = { vm.selectPlace(idx) },
                            accent = AppColors.Pink
                        )
                    }
                }
            }

            if (conn == null && !vm.isLoading) {
                DarkCard {
                    SectionLabel("Schnellsuche", AppColors.Violet)
                    VSpace(10.dp)
                    QUICK_QUERIES.chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            row.forEach { q ->
                                Pill(q, onClick = { vm.search(q) }, modifier = Modifier.weight(1f))
                            }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                        VSpace(8.dp)
                    }
                    Text(
                        "Tipp: „Morgen um 7 zum Bahnhof“ oder „Apotheke am Marktplatz“",
                        style = MaterialTheme.typography.bodySmall,
                        color = AppColors.TextMuted
                    )
                }
            }

            VSpace(8.dp)
        }
    }
}

@Composable
private fun Header(city: String?, gpsActive: Boolean, isLocating: Boolean, onToggleGps: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Dot(AppColors.Orange, 14.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("ABFAHRTEN", style = MaterialTheme.typography.labelSmall, color = AppColors.TextMuted)
            Text("Next & Here", style = MaterialTheme.typography.headlineSmall, color = AppColors.Text)
        }
        val (dotColor, label) = when {
            isLocating -> AppColors.Orange to "GPS sucht …"
            gpsActive -> AppColors.Success to (city?.takeIf { it.isNotBlank() } ?: "GPS an")
            else -> AppColors.TextMuted to "GPS aus"
        }
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(AppColors.Card)
                .clickable(onClickLabel = "GPS ein- oder ausschalten", onClick = onToggleGps)
                .padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Dot(dotColor, 8.dp)
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = AppColors.Text, maxLines = 1)
        }
    }
}

@Composable
private fun SearchCard(vm: TransitViewModel, onVoice: () -> Unit) {
    val focus = LocalFocusManager.current
    ActionCard(
        onCircleClick = onVoice,
        circleContentDescription = if (vm.isListening) "Zuhören beenden" else "Ziel sprechen",
        circleColor = if (vm.isListening) AppColors.Pink else AppColors.Orange,
        circleContent = { GlyphText(if (vm.isListening) "■" else "🎤") }
    ) {
        SectionLabel("Wohin?")
        VSpace(10.dp)
        OutlinedTextField(
            value = vm.query,
            // Früher wurde hier bei jedem Tastendruck getrimmt – Leerzeichen ließen sich nicht tippen.
            onValueChange = { vm.query = it },
            placeholder = { Text("Ziel, Haltestelle, Bäckerei …", color = AppColors.TextMuted) },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                focus.clearFocus()
                vm.search()
            }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = AppColors.Text,
                unfocusedTextColor = AppColors.Text,
                focusedContainerColor = AppColors.Background,
                unfocusedContainerColor = AppColors.Background,
                focusedBorderColor = AppColors.Orange,
                unfocusedBorderColor = AppColors.Outline,
                cursorColor = AppColors.Orange
            ),
            modifier = Modifier.fillMaxWidth()
        )
        VSpace(10.dp)
        if (vm.isLoading) {
            LoadingRow(vm.status)
        } else {
            Text(vm.status, style = MaterialTheme.typography.bodyMedium, color = AppColors.TextMuted)
        }
        vm.timeLabel?.let {
            VSpace(6.dp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Dot(AppColors.Pink, 6.dp)
                Spacer(Modifier.width(6.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = AppColors.Pink, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** Liefert die Blickrichtung des Geräts (0–360°) oder null ohne Kompass-Sensor. */
@Composable
private fun rememberDeviceAzimuth(): Float? {
    val context = LocalContext.current
    var azimuth by remember { mutableStateOf<Float?>(null) }
    DisposableEffect(Unit) {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sensor = sm?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (sm == null || sensor == null) {
            onDispose { }
        } else {
            val rot = FloatArray(9)
            val orientation = FloatArray(3)
            @Suppress("DEPRECATION")
            val displayRotation = (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)
                ?.defaultDisplay?.rotation ?: Surface.ROTATION_0
            val rotationOffset = when (displayRotation) {
                Surface.ROTATION_90 -> 90f
                Surface.ROTATION_180 -> 180f
                Surface.ROTATION_270 -> 270f
                else -> 0f
            }
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    SensorManager.getRotationMatrixFromVector(rot, event.values)
                    SensorManager.getOrientation(rot, orientation)
                    val deg = (Math.toDegrees(orientation[0].toDouble()).toFloat() + rotationOffset + 360f) % 360f
                    val prev = azimuth
                    // Nur bei spürbarer Änderung neu zeichnen (spart Recompositions)
                    if (prev == null || abs(angleDiff(prev, deg)) > 1.5f) azimuth = deg
                }
                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
            }
            sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
            onDispose { sm.unregisterListener(listener) }
        }
    }
    return azimuth
}

private fun angleDiff(a: Float, b: Float): Float = ((b - a + 540f) % 360f) - 180f

@Composable
private fun CompassCard(
    userLat: Double,
    userLon: Double,
    destTarget: NavTarget,
    stopTarget: NavTarget?,
    onOpenMap: (NavTarget) -> Unit
) {
    var toStop by remember(destTarget, stopTarget) { mutableStateOf(false) }
    val target = if (toStop && stopTarget != null) stopTarget else destTarget
    val azimuth = rememberDeviceAzimuth()
    val dist = distanceMeters(userLat, userLon, target.lat, target.lon).toInt()
    val bearing = bearingDegrees(userLat, userLon, target.lat, target.lon)
    val arrowAngle = if (azimuth != null) (bearing - azimuth + 360f) % 360f else bearing

    ActionCard(
        onCircleClick = { onOpenMap(target) },
        circleContentDescription = "Auf Karte öffnen",
        circleContent = { CompassArrow(arrowAngle, Modifier.size(30.dp)) }
    ) {
        SectionLabel(if (toStop && stopTarget != null) "Zur Haltestelle" else "Zum Ziel", AppColors.Pink)
        VSpace(6.dp)
        Text(
            target.name,
            style = MaterialTheme.typography.titleMedium,
            color = AppColors.Text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            "${formatDistance(dist)} Luftlinie · " + if (azimuth != null) "Pfeil folgt Kompass" else "Richtung ${bearing.toInt()}°",
            style = MaterialTheme.typography.bodySmall,
            color = AppColors.TextMuted
        )
        if (stopTarget != null && stopTarget.name != destTarget.name) {
            VSpace(10.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("Ziel", onClick = { toStop = false }, filled = !toStop, color = AppColors.Pink)
                Pill("Haltestelle", onClick = { toStop = true }, filled = toStop, color = AppColors.Pink)
            }
        }
    }
}

@Composable
private fun ConnectionCard(
    conn: TransitConnection,
    destinationLabel: String?,
    isSpeaking: Boolean,
    onToggleSpeak: () -> Unit,
    onSpeakLeg: (RouteLegInfo) -> Unit,
    onAlarm: (Int) -> Unit,
    onOpenMap: (Double?, Double?, String) -> Unit
) {
    var showDetails by rememberSaveable(conn.departureMillis, conn.summaryLines) { mutableStateOf(false) }
    val delayed = conn.firstDelayMinutes > 2

    ActionCard(
        onCircleClick = onToggleSpeak,
        circleContentDescription = if (isSpeaking) "Vorlesen stoppen" else "Verbindung vorlesen",
        circleColor = if (isSpeaking) AppColors.Pink else AppColors.Orange,
        circleContent = { GlyphText(if (isSpeaking) "■" else "🔊") }
    ) {
        SectionLabel(if (conn.isWalkOnly) "Zu Fuß" else "Nächste Abfahrt")
        VSpace(8.dp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                conn.summaryLines,
                style = MaterialTheme.typography.titleLarge,
                color = AppColors.Text,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.width(8.dp))
            Text(
                conn.firstDepartureTime,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = if (delayed) AppColors.Pink else AppColors.Orange
            )
        }
        VSpace(4.dp)
        val subtitle = if (conn.isWalkOnly) {
            "${formatDistance(conn.totalWalkMeters)} · ca. ${conn.totalDurationMinutes} Min · an ${conn.finalArrivalTime}"
        } else {
            "an ${conn.finalArrivalTime} · ${conn.totalDurationMinutes} Min · ${transferLabel(conn)}"
        }
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = AppColors.TextMuted)
        if (conn.firstDelayMinutes > 0) {
            Text(
                "+${conn.firstDelayMinutes} Min Verspätung",
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.Pink,
                fontWeight = FontWeight.Bold
            )
        }
        buildTransferOrWalkWarningText(conn)?.let {
            VSpace(4.dp)
            Text(it, style = MaterialTheme.typography.bodySmall, color = AppColors.Violet)
        }

        if (!conn.isWalkOnly) {
            val minsUntil = ((conn.departureMillis - System.currentTimeMillis()) / 60_000L).toInt()
            val options = listOf(5, 10, 15, 20).filter { it <= minsUntil - 2 }
            if (options.isNotEmpty()) {
                VSpace(12.dp)
                Text("⏰ Wecker vor Abfahrt", style = MaterialTheme.typography.labelMedium, color = AppColors.TextMuted)
                VSpace(6.dp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    options.forEach { m ->
                        Pill("$m′", onClick = { onAlarm(m) }, modifier = Modifier.weight(1f))
                    }
                }
            }
        }

        VSpace(12.dp)
        Pill(
            text = if (showDetails) "Details ▲" else "Details (${conn.legs.size}) ▼",
            onClick = { showDetails = !showDetails },
            filled = showDetails,
            modifier = Modifier.fillMaxWidth()
        )
        if (showDetails) {
            VSpace(12.dp)
            LegTimeline(conn, destinationLabel, onSpeakLeg, onOpenMap)
        }
    }
}

@Composable
private fun LegTimeline(
    conn: TransitConnection,
    destinationLabel: String?,
    onSpeakLeg: (RouteLegInfo) -> Unit,
    onOpenMap: (Double?, Double?, String) -> Unit
) {
    var expanded by remember(conn) { mutableStateOf<Int?>(null) }
    Column {
        conn.legs.forEachIndexed { idx, leg ->
            val color = when {
                leg.isTransfer -> AppColors.Pink
                leg.isWalk -> AppColors.TextMuted
                else -> AppColors.Orange
            }
            TimelineRow(color = color, showLine = true) {
                when {
                    leg.isTransfer -> {
                        Text(
                            "Umstieg an ${leg.fromStop}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = AppColors.Pink,
                            fontWeight = FontWeight.SemiBold
                        )
                        val walk = if (leg.distanceMeters > 0) " · ${leg.distanceMeters} m Fußweg" else ""
                        Text("${leg.durationMinutes} Min Übergang$walk", style = MaterialTheme.typography.bodySmall, color = AppColors.TextMuted)
                    }
                    leg.isWalk -> {
                        Text(
                            "Fußweg → ${leg.toStop}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = AppColors.Text,
                            modifier = Modifier.clickable { onOpenMap(leg.toLat, leg.toLon, leg.toStop) }
                        )
                        Text(
                            "${leg.durationMinutes} Min · ${formatDistance(leg.distanceMeters)} · ${leg.startTime}–${leg.endTime}",
                            style = MaterialTheme.typography.bodySmall,
                            color = AppColors.TextMuted
                        )
                    }
                    else -> {
                        val isOpen = expanded == idx
                        Column(Modifier.clickable { expanded = if (isOpen) null else idx }) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "${leg.lineName} → ${leg.direction}",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = AppColors.Text,
                                    modifier = Modifier.weight(1f)
                                )
                                val d = if (leg.delayMinutes > 0) " +${leg.delayMinutes}" else ""
                                Text(
                                    leg.startTime + d,
                                    fontWeight = FontWeight.Bold,
                                    color = if (leg.delayMinutes > 2) AppColors.Pink else AppColors.Orange
                                )
                            }
                            Text("ab ${leg.startTime}  ${leg.fromStop}", style = MaterialTheme.typography.bodySmall, color = AppColors.TextMuted)
                            Text("an ${leg.endTime}  ${leg.toStop}", style = MaterialTheme.typography.bodySmall, color = AppColors.TextMuted)
                        }
                        if (isOpen) {
                            VSpace(8.dp)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Pill("Einstieg", onClick = { onOpenMap(leg.fromLat, leg.fromLon, leg.fromStop) }, modifier = Modifier.weight(1f))
                                Pill("Ausstieg", onClick = { onOpenMap(leg.toLat, leg.toLon, leg.toStop) }, modifier = Modifier.weight(1f))
                                Pill("Ansage", onClick = { onSpeakLeg(leg) }, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
        val last = conn.legs.lastOrNull()
        val goal = destinationLabel ?: last?.toStop ?: conn.firstDirection
        TimelineRow(color = AppColors.Violet, showLine = false) {
            Text(
                "Ziel: $goal",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = AppColors.Text,
                modifier = Modifier.clickable { onOpenMap(last?.toLat, last?.toLon, goal) }
            )
            Text("Ankunft ${conn.finalArrivalTime} Uhr", style = MaterialTheme.typography.bodySmall, color = AppColors.TextMuted)
        }
    }
}

@Composable
private fun TimelineRow(color: Color, showLine: Boolean, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Column(
            modifier = Modifier
                .width(18.dp)
                .fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(5.dp))
            Dot(color, 10.dp)
            if (showLine) {
                Box(
                    Modifier
                        .padding(vertical = 3.dp)
                        .width(2.dp)
                        .weight(1f)
                        .background(AppColors.Outline)
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f).padding(bottom = 14.dp)) {
            content()
        }
    }
}

@Composable
private fun BottomActionBar(
    isListening: Boolean,
    isSpeaking: Boolean,
    canSpeak: Boolean,
    canMap: Boolean,
    onVoice: () -> Unit,
    onSpeak: () -> Unit,
    onMap: () -> Unit,
    buildNumber: Int
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
            .background(AppColors.Card)
            .navigationBarsPadding()
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Pill(
                text = if (isListening) "■ Stopp" else "🎤 Sprechen",
                onClick = onVoice,
                filled = true,
                color = if (isListening) AppColors.Pink else AppColors.Orange,
                modifier = Modifier.weight(1.3f)
            )
            Pill(
                text = if (isSpeaking) "■ Stopp" else "🔊 Vorlesen",
                onClick = onSpeak,
                enabled = canSpeak,
                modifier = Modifier.weight(1f)
            )
            Pill(
                text = "🗺 Karte",
                onClick = onMap,
                enabled = canMap,
                modifier = Modifier.weight(1f)
            )
        }
        Text(
            text = "Version $buildNumber",
            fontSize = 10.sp,
            color = AppColors.TextMuted,
            modifier = Modifier
                .align(Alignment.End)
                .padding(top = 4.dp)
        )
    }
}
