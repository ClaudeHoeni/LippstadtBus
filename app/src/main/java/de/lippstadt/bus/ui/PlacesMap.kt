package de.lippstadt.bus.ui

import android.graphics.drawable.GradientDrawable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import de.lippstadt.bus.data.GeoCandidate
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import java.io.File

private fun dotIcon(color: Int, sizePx: Int, strokeColor: Int) = GradientDrawable().apply {
    shape = GradientDrawable.OVAL
    setColor(color)
    setStroke(sizePx / 7, strokeColor)
    setSize(sizePx, sizePx)
}

/**
 * Vollbild-Karte (OpenStreetMap) mit eigenem Standort und ALLEN gefundenen Zielen als Marker.
 * Tippen auf einen Marker wählt dieses Ziel.
 */
@Composable
fun PlacesMapDialog(
    places: List<GeoCandidate>,
    selectedIndex: Int,
    userLat: Double?,
    userLon: Double?,
    onSelect: (Int) -> Unit,
    onGoogleMaps: () -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val density = context.resources.displayMetrics.density
    val mapView = remember {
        Configuration.getInstance().apply {
            userAgentValue = context.packageName
            osmdroidBasePath = File(context.cacheDir, "osmdroid")
            osmdroidTileCache = File(context.cacheDir, "osmdroid/tiles")
        }
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        }
    }
    DisposableEffect(Unit) {
        mapView.onResume()
        onDispose {
            mapView.onPause()
            mapView.onDetach()
        }
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(Modifier.fillMaxSize().background(AppColors.Background)) {
            AndroidView(
                factory = { mapView },
                modifier = Modifier.fillMaxSize(),
                update = { map ->
                    map.overlays.clear()
                    val points = mutableListOf<GeoPoint>()
                    places.forEachIndexed { idx, p ->
                        val gp = GeoPoint(p.lat, p.lon)
                        points += gp
                        val selected = idx == selectedIndex
                        map.overlays.add(Marker(map).apply {
                            position = gp
                            title = p.name
                            snippet = p.areaLabel
                            icon = dotIcon(
                                color = (if (selected) AppColors.Orange else AppColors.Pink).toArgb(),
                                sizePx = ((if (selected) 30 else 22) * density).toInt(),
                                strokeColor = android.graphics.Color.WHITE
                            )
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                            setOnMarkerClickListener { m, _ ->
                                m.showInfoWindow()
                                onSelect(idx)
                                true
                            }
                        })
                    }
                    if (userLat != null && userLon != null) {
                        val gp = GeoPoint(userLat, userLon)
                        points += gp
                        map.overlays.add(Marker(map).apply {
                            position = gp
                            title = "Mein Standort"
                            icon = dotIcon(AppColors.Success.toArgb(), (18 * density).toInt(), android.graphics.Color.WHITE)
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        })
                    }
                    map.post {
                        if (points.size >= 2) {
                            map.zoomToBoundingBox(BoundingBox.fromGeoPoints(points), false, (60 * density).toInt())
                        } else if (points.size == 1) {
                            map.controller.setZoom(16.0)
                            map.controller.setCenter(points.first())
                        }
                    }
                    map.invalidate()
                }
            )

            // Kopfzeile
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Pill("✕ Schließen", onClick = onClose, filled = true)
                Spacer(Modifier.weight(1f))
                Pill("Google Maps", onClick = onGoogleMaps, filled = true, color = AppColors.Pink)
            }

            // Ausgewähltes Ziel
            places.getOrNull(selectedIndex)?.let { p ->
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(12.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(AppColors.Card)
                        .padding(14.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Dot(AppColors.Orange, 10.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            p.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = AppColors.Text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (p.areaLabel.isNotBlank()) {
                        Text(p.areaLabel, style = MaterialTheme.typography.bodySmall, color = AppColors.TextMuted)
                    }
                    Text(
                        "${places.size} Ziele markiert · Marker antippen zum Auswählen",
                        style = MaterialTheme.typography.bodySmall,
                        color = AppColors.TextMuted
                    )
                }
            }
        }
    }
}
