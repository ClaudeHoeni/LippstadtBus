package de.chris.mamafon

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import de.chris.mamafon.service.RemoteControlAccessibilityService
import de.chris.mamafon.service.ScreenCaptureService
import de.chris.mamafon.webrtc.WebRtcManager
import org.json.JSONObject
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import kotlin.math.hypot
import kotlin.random.Random

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                MamaFonApp()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MamaFonApp() {
    val context = LocalContext.current
    val webRtcManager = remember { WebRtcManager(context) }
    val statusText by webRtcManager.statusText.collectAsState()
    val isConnected by webRtcManager.isConnected.collectAsState()

    var selectedTab by remember { mutableIntStateOf(0) } // 0 = Freigabe, 1 = Steuern
    var generatedSessionId by remember { mutableStateOf(generate6DigitCode()) }
    var inputSessionId by remember { mutableStateOf("") }
    var isSharingActive by remember { mutableStateOf(false) }
    var isViewingActive by remember { mutableStateOf(false) }
    var remoteVideoTrack by remember { mutableStateOf<VideoTrack?>(null) }
    var accessibilityEnabled by remember { mutableStateOf(RemoteControlAccessibilityService.isEnabled()) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                accessibilityEnabled = RemoteControlAccessibilityService.isEnabled()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        webRtcManager.onRemoteVideoTrack = { track ->
            remoteVideoTrack = track
        }
    }

    val projectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val serviceIntent = Intent(context, ScreenCaptureService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            isSharingActive = true
            webRtcManager.startHostSession(generatedSessionId, result.data!!)
        }
    }

    if (isViewingActive) {
        RemoteViewerScreen(
            webRtcManager = webRtcManager,
            remoteVideoTrack = remoteVideoTrack,
            statusText = statusText,
            onDisconnect = {
                webRtcManager.stopSession()
                context.stopService(Intent(context, ScreenCaptureService::class.java))
                remoteVideoTrack = null
                isViewingActive = false
            }
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("MamaFon – Fernwartung", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { if (!isSharingActive) selectedTab = 0 },
                    text = { Text("Bildschirm freigeben") },
                    icon = { Icon(Icons.Default.ScreenShare, contentDescription = null) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { if (!isSharingActive) selectedTab = 1 },
                    text = { Text("Handy steuern") },
                    icon = { Icon(Icons.Default.TouchApp, contentDescription = null) }
                )
            }

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (isConnected) Color(0xFFDFF6DD) else MaterialTheme.colorScheme.surfaceVariant
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (isConnected) Icons.Default.CheckCircle else Icons.Default.Info,
                        contentDescription = null,
                        tint = if (isConnected) Color(0xFF107C10) else MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(text = "Status: $statusText", style = MaterialTheme.typography.bodyMedium)
                }
            }

            if (selectedTab == 0) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("1. Bedienungshilfe für Fernsteuerung", fontWeight = FontWeight.Bold)
                        Text(
                            text = if (accessibilityEnabled)
                                "Aktiviert – Steuerbefehle (Tippen & Wischen) werden ausgeführt."
                            else
                                "Deaktiviert – Ohne Bedienungshilfe ist nur Bildschirmansicht möglich.",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (accessibilityEnabled) Color(0xFF107C10) else MaterialTheme.colorScheme.error
                        )
                        OutlinedButton(
                            onClick = {
                                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.SettingsAccessibility, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Bedienungshilfe-Einstellungen öffnen")
                        }
                    }
                }

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("2. Ihre Sitzungs-ID", fontWeight = FontWeight.Bold)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = generatedSessionId.chunked(3).joinToString(" "),
                                fontSize = 36.sp,
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 4.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            if (!isSharingActive) {
                                IconButton(onClick = { generatedSessionId = generate6DigitCode() }) {
                                    Icon(Icons.Default.Refresh, contentDescription = "Neue ID")
                                }
                            }
                        }

                        if (!isSharingActive) {
                            Button(
                                onClick = {
                                    val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                                    projectionLauncher.launch(mpm.createScreenCaptureIntent())
                                },
                                modifier = Modifier.fillMaxWidth().height(52.dp)
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Bildschirmfreigabe starten", fontSize = 16.sp)
                            }
                        } else {
                            Button(
                                onClick = {
                                    webRtcManager.stopSession()
                                    context.stopService(Intent(context, ScreenCaptureService::class.java))
                                    isSharingActive = false
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                modifier = Modifier.fillMaxWidth().height(52.dp)
                            ) {
                                Icon(Icons.Default.Stop, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Freigabe beenden", fontSize = 16.sp)
                            }
                        }
                    }
                }
            } else {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Text("Partner-Sitzungs-ID eingeben", fontWeight = FontWeight.Bold)
                        OutlinedTextField(
                            value = inputSessionId,
                            onValueChange = { if (it.length <= 6 && it.all { c -> c.isDigit() }) inputSessionId = it },
                            label = { Text("6-stellige ID") },
                            placeholder = { Text("z. B. 482910") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Button(
                            onClick = {
                                isViewingActive = true
                                webRtcManager.startViewerSession(inputSessionId)
                            },
                            enabled = inputSessionId.length == 6,
                            modifier = Modifier.fillMaxWidth().height(52.dp)
                        ) {
                            Icon(Icons.Default.Link, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Verbinden & Steuern", fontSize = 16.sp)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun RemoteViewerScreen(
    webRtcManager: WebRtcManager,
    remoteVideoTrack: VideoTrack?,
    statusText: String,
    onDisconnect: () -> Unit
) {
    var downX by remember { mutableFloatStateOf(0f) }
    var downY by remember { mutableFloatStateOf(0f) }
    var downTime by remember { mutableLongStateOf(0L) }
    var viewWidth by remember { mutableFloatStateOf(1f) }
    var viewHeight by remember { mutableFloatStateOf(1f) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF1E1E1E))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = statusText,
                color = Color.White,
                fontSize = 12.sp,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onDisconnect) {
                Text("Trennen", color = Color(0xFFFF6B6B), fontWeight = FontWeight.Bold)
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .pointerInteropFilter { event ->
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            downX = event.x
                            downY = event.y
                            downTime = System.currentTimeMillis()
                            true
                        }
                        MotionEvent.ACTION_UP -> {
                            val upX = event.x
                            val upY = event.y
                            val duration = (System.currentTimeMillis() - downTime).coerceAtLeast(50L)
                            val distance = hypot(upX - downX, upY - downY)

                            if (viewWidth > 0 && viewHeight > 0) {
                                if (distance < 25f) {
                                    val cmd = JSONObject().apply {
                                        put("type", "TAP")
                                        put("x", (upX / viewWidth).toDouble())
                                        put("y", (upY / viewHeight).toDouble())
                                    }
                                    webRtcManager.sendControlCommand(cmd)
                                } else {
                                    val cmd = JSONObject().apply {
                                        put("type", "SWIPE")
                                        put("x1", (downX / viewWidth).toDouble())
                                        put("y1", (downY / viewHeight).toDouble())
                                        put("x2", (upX / viewWidth).toDouble())
                                        put("y2", (upY / viewHeight).toDouble())
                                        put("duration", duration)
                                    }
                                    webRtcManager.sendControlCommand(cmd)
                                }
                            }
                            true
                        }
                        else -> true
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            if (remoteVideoTrack != null) {
                AndroidView(
                    factory = { ctx ->
                        SurfaceViewRenderer(ctx).apply {
                            init(webRtcManager.eglBase.eglBaseContext, null)
                            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                            setEnableHardwareScaler(true)
                            remoteVideoTrack.addSink(this)
                        }
                    },
                    update = { view ->
                        viewWidth = view.width.toFloat()
                        viewHeight = view.height.toFloat()
                    },
                    onRelease = { view ->
                        remoteVideoTrack.removeSink(view)
                        view.release()
                    },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Text(
                    text = "Warte auf Bildschirmsignal...",
                    color = Color.LightGray,
                    textAlign = TextAlign.Center
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF1E1E1E))
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            IconButton(onClick = {
                webRtcManager.sendControlCommand(JSONObject().put("type", "BACK"))
            }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück", tint = Color.White)
            }
            IconButton(onClick = {
                webRtcManager.sendControlCommand(JSONObject().put("type", "HOME"))
            }) {
                Icon(Icons.Default.Home, contentDescription = "Startseite", tint = Color.White)
            }
            IconButton(onClick = {
                webRtcManager.sendControlCommand(JSONObject().put("type", "RECENTS"))
            }) {
                Icon(Icons.Default.CropSquare, contentDescription = "Letzte Apps", tint = Color.White)
            }
        }
    }
}

private fun generate6DigitCode(): String = Random.nextInt(100000, 999999).toString()