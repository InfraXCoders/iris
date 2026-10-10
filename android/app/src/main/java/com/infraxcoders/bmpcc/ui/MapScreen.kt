package com.infraxcoders.bmpcc.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.infraxcoders.bmpcc.data.RecceStore
import com.infraxcoders.bmpcc.platform.Places
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.Marker
import java.io.File
import java.text.DateFormat
import java.util.Date

/**
 * Every recce with a GPS position on an OpenStreetMap map. Tap a pin for the recce, then open it or get directions.
 * Map tiles need internet (cached afterwards); the pins work offline.
 */
@Composable
fun RecceMapScreen(nav: Navigator, focusSessionId: String?) {
    val context = LocalContext.current
    val sessions by RecceStore.sessions.collectAsState()
    val located = sessions.filter { it.latitude != null && it.longitude != null }
    var selected by remember { mutableStateOf(focusSessionId) }
    var fitted by remember { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val map = remember {
        Configuration.getInstance().apply {
            load(context, context.getSharedPreferences("osmdroid", android.content.Context.MODE_PRIVATE))
            userAgentValue = context.packageName // OpenStreetMap's tile policy asks for an identifying user agent.
            osmdroidBasePath = File(context.cacheDir, "osmdroid")
            osmdroidTileCache = File(osmdroidBasePath, "tiles")
            tileFileSystemCacheMaxBytes = 100L * 1024 * 1024
            tileFileSystemCacheTrimBytes = 80L * 1024 * 1024
        }
        MapView(context).apply {
            setDestroyMode(false) // detached once, in onDispose below
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            minZoomLevel = 2.0
            overlays.add(CopyrightOverlay(context))
        }
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) map.onResume()
            if (e == Lifecycle.Event.ON_PAUSE) map.onPause()
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) map.onResume()
        onDispose { lifecycle.removeObserver(observer); map.onPause(); map.onDetach() }
    }
    // Pins, and on first show: the chosen recce, or all of them.
    LaunchedEffect(located) {
        map.overlays.removeAll { it is Marker }
        for (s in located) {
            val lat = s.latitude ?: continue; val lon = s.longitude ?: continue
            map.overlays.add(Marker(map).apply {
                position = GeoPoint(lat, lon)
                title = s.projectName
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                setOnMarkerClickListener { _, _ -> selected = s.id; true }
            })
        }
        map.invalidate()
        if (!fitted && located.isNotEmpty()) {
            fitted = true
            val focus = located.firstOrNull { it.id == focusSessionId }
            val points = located.map { GeoPoint(it.latitude!!, it.longitude!!) }
            val show = {
                if (focus != null || points.distinct().size == 1) {
                    val p = focus?.let { GeoPoint(it.latitude!!, it.longitude!!) } ?: points.first()
                    map.controller.setZoom(15.0); map.controller.setCenter(p)
                } else map.zoomToBoundingBox(BoundingBox.fromGeoPoints(points).increaseByScale(1.3f), false, 48)
            }
            if (map.width > 0) show() else map.addOnFirstLayoutListener { _, _, _, _, _ -> show() }
        }
    }

    Screen("Recce map", onBack = { nav.pop() }) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            AndroidView({ map }, Modifier.fillMaxSize())
            if (located.isEmpty()) Text(
                "No recces with a location yet. In a recce, tap “Use my location”.",
                color = Color.White, fontSize = 14.sp,
                modifier = Modifier.align(Alignment.Center).padding(24.dp).background(Color(0xCC0B1E45), RoundedCornerShape(12.dp)).padding(14.dp),
            )
            located.firstOrNull { it.id == selected }?.let { s ->
                Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(12.dp)
                        .background(Color(0xEE0B1E45), RoundedCornerShape(16.dp)).padding(14.dp),
                ) {
                    Text(s.projectName, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${s.locationName} · ${DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(s.timestamp))} · " +
                            "${s.scenes.size} scene(s), ${s.shotCount} shot(s)",
                        color = Brand.muted, fontSize = 12.sp,
                    )
                    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pill("Open recce", color = Brand.accent, textColor = Color.White) { nav.push(Dest.Session(s.id)) }
                        Pill("Directions", color = Brand.light) { Places.openInMaps(context, s.latitude!!, s.longitude!!, s.projectName) }
                        Pill("Close", color = Brand.lightChip) { selected = null }
                    }
                }
            }
        }
    }
}
