package com.infraxcoders.bmpcc.ui

import android.Manifest
import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.infraxcoders.bmpcc.core.Monitor
import com.infraxcoders.bmpcc.core.Optics
import com.infraxcoders.bmpcc.core.SkyProjection
import com.infraxcoders.bmpcc.core.Solar
import com.infraxcoders.bmpcc.core.SunPlan
import com.infraxcoders.bmpcc.core.SkyPoint
import com.infraxcoders.bmpcc.core.Skyline
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import com.infraxcoders.bmpcc.core.Vec3
import com.infraxcoders.bmpcc.data.RecceStore
import com.infraxcoders.bmpcc.platform.hasPermission
import com.infraxcoders.bmpcc.platform.rememberPermissionAsker
import java.time.ZoneId
import kotlin.math.abs
import com.infraxcoders.bmpcc.core.PreviewView as PhoneView

/**
 * A correction to the phone's compass, in degrees (added to the heading), set by aligning on the real sun or nudging.
 * Kept while the app runs: magnetic interference changes from place to place, so it isn't saved.
 */
object CompassCorrection {
    var offset by mutableStateOf(0.0)
}

/** The magnetometer's own accuracy status (SensorManager.SENSOR_STATUS_*), or null before it reports. */
@Composable
fun rememberCompassStatus(): State<Int?> {
    val context = LocalContext.current
    val status = remember { mutableStateOf<Int?>(null) }
    DisposableEffect(Unit) {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val mag = sm?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(ev: SensorEvent) { if (status.value != ev.accuracy) status.value = ev.accuracy }
            override fun onAccuracyChanged(s: Sensor?, accuracy: Int) { status.value = accuracy }
        }
        if (sm != null && mag != null) sm.registerListener(listener, mag, SensorManager.SENSOR_DELAY_UI)
        onDispose { sm?.unregisterListener(listener) }
    }
    return status
}

/** Camera orientation in the world (East-North-Up, true north), plus the compass's own accuracy estimate. */
data class CameraPose(val forward: Vec3, val right: Vec3, val up: Vec3, val headingAccuracyDeg: Double?)

/**
 * Live camera orientation from the rotation-vector sensor (gyro + accelerometer + compass), corrected from magnetic
 * to true north with the local magnetic declination. [screenRotation] is the display rotation in degrees.
 */
@Composable
fun rememberCameraPose(screenRotation: Int, latitude: Double, longitude: Double, headingOffset: Double = 0.0): State<CameraPose?> {
    val context = LocalContext.current
    val pose = remember { mutableStateOf<CameraPose?>(null) }
    DisposableEffect(screenRotation, latitude, longitude, headingOffset) {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sensor = sm?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val declination = GeomagneticField(latitude.toFloat(), longitude.toFloat(), 0f, System.currentTimeMillis()).declination.toDouble()
        val dec = Math.toRadians(declination + headingOffset)
        val r = FloatArray(9)
        // Device axes that point screen-right and screen-up for each display rotation.
        val (rightDev, upDev) = when (screenRotation) {
            90 -> floatArrayOf(0f, -1f, 0f) to floatArrayOf(1f, 0f, 0f)
            180 -> floatArrayOf(-1f, 0f, 0f) to floatArrayOf(0f, -1f, 0f)
            270 -> floatArrayOf(0f, 1f, 0f) to floatArrayOf(-1f, 0f, 0f)
            else -> floatArrayOf(1f, 0f, 0f) to floatArrayOf(0f, 1f, 0f)
        }
        fun world(v: FloatArray): Vec3 {
            // R maps device → world (x east, y north, z up, magnetic). Then rotate magnetic → true north by the declination.
            val e = r[0] * v[0] + r[1] * v[1] + r[2] * v[2]
            val n = r[3] * v[0] + r[4] * v[1] + r[5] * v[2]
            val u = r[6] * v[0] + r[7] * v[1] + r[8] * v[2]
            val cosD = Math.cos(dec); val sinD = Math.sin(dec)
            // True azimuth = magnetic azimuth + declination (east positive).
            return Vec3(e * cosD + n * sinD, n * cosD - e * sinD, u.toDouble())
        }
        var smooth: CameraPose? = null
        val listener = object : SensorEventListener {
            override fun onSensorChanged(ev: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(r, ev.values.copyOf(minOf(4, ev.values.size)))
                val f = world(floatArrayOf(0f, 0f, -1f)); val rt = world(rightDev); val up = world(upDev)
                val acc = if (ev.values.size > 4 && ev.values[4] > 0) Math.toDegrees(ev.values[4].toDouble()) else null
                val prev = smooth
                val k = 0.25
                val next = if (prev == null) CameraPose(f, rt, up, acc) else CameraPose(
                    (prev.forward * (1 - k) + f * k).normalized(), (prev.right * (1 - k) + rt * k).normalized(),
                    (prev.up * (1 - k) + up * k).normalized(), acc,
                )
                smooth = next
                pose.value = next
            }
            override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {}
        }
        if (sm != null && sensor != null) sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        onDispose { sm?.unregisterListener(listener) }
    }
    return pose
}

/**
 * The sun over the live camera: today's path with hour marks, the sun at the planner's time, compass points on the
 * horizon, and "Mark this spot" to find when the sun lines up with, or rises above, what the crosshair points at.
 */
@Composable
fun SunArScreen(nav: Navigator, d: Dest.Sun) {
    FullScreen()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val lat = d.latitude; val lon = d.longitude
    if (lat == null || lon == null) { LaunchedEffect(Unit) { nav.pop() }; return }
    val phone = remember { PhoneCamera() }
    var hasCamera by remember { mutableStateOf(context.hasPermission(Manifest.permission.CAMERA)) }
    val asker = rememberPermissionAsker { }
    LaunchedEffect(Unit) { if (!hasCamera) asker.withPermission(Manifest.permission.CAMERA) { hasCamera = true } }
    val rotation = rememberDisplayRotation()
    val landscape = rotation == 90 || rotation == 270
    val pose = rememberCameraPose(rotation, lat, lon, CompassCorrection.offset)
    val compassStatus = rememberCompassStatus()
    // Skyline recording: sweep the crosshair along the tops of buildings / hills.
    var recording by remember { mutableStateOf(false) }
    val recorded = remember { mutableStateMapOf<Int, Double>() }
    LaunchedEffect(recording) {
        if (!recording) return@LaunchedEffect
        snapshotFlow { pose.value }.collect { p ->
            if (p != null) {
                val (az, el) = SunPlan.direction(p.forward)
                val b = Skyline.bin(az); val v = el.coerceIn(-10.0, 85.0)
                // Only real changes, so the screen doesn't redraw at sensor rate.
                if (recorded[b]?.let { abs(it - v) < 0.2 } != true) recorded[b] = v
            }
        }
    }
    val zone = ZoneId.systemDefault()
    val path = remember(lat, lon, SunPlan.dayBounds(d.time, zone).first) { SunPlan.path(d.time, lat, lon, zone, 10) }
    var screen by remember { mutableStateOf(IntSize.Zero) }
    var report by remember { mutableStateOf<String?>(null) }
    val sessions by RecceStore.sessions.collectAsState()
    val shot = sessions.firstOrNull { it.id == d.sessionId }?.scenes?.firstOrNull { it.id == d.sceneId }?.shots?.firstOrNull { it.id == d.shotId }
    val sun = Solar.position(d.time, lat, lon)

    Box(Modifier.fillMaxSize().background(Color.Black).onSizeChanged { screen = it }) {
        if (hasCamera) CameraPreview(context, lifecycleOwner, phone, rotation)
        val skyPoints = if (recording) recorded.entries.map { SkyPoint(it.key.toDouble(), it.value) } else d.skyline
        SkyOverlay(pose, phone, landscape, screen, path, d.time, lat, lon, skyPoints)
        if (recording) Text(
            "Sweep the crosshair slowly along the tops of buildings, trees and hills (${recorded.size}° recorded). Tap Done when you're round.",
            color = Color.White, fontSize = 13.sp,
            modifier = Modifier.align(Alignment.Center).padding(top = 120.dp, start = 24.dp, end = 24.dp)
                .background(Color(0xCC004D66), RoundedCornerShape(10.dp)).padding(10.dp),
        )

        // Crosshair: what "Mark this spot" measures.
        Canvas(Modifier.fillMaxSize()) {
            val c = Offset(size.width / 2, size.height / 2); val l = 16.dp.toPx()
            drawLine(Color.White, c - Offset(l, 0f), c + Offset(l, 0f), 1.5.dp.toPx())
            drawLine(Color.White, c - Offset(0f, l), c + Offset(0f, l), 1.5.dp.toPx())
        }

        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            BackSquare { nav.pop() }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Sun at ${clockText(d.time)}", color = Color(0xFFFFD27A), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(sunText(sun), color = Color.White, fontSize = 12.sp)
            }
            AimText(pose)
        }

        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(12.dp)
                .background(Color(0xCC0B1E45), RoundedCornerShape(16.dp)).padding(12.dp),
        ) {
            report?.let { Text(it, color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(bottom = 6.dp)) }
            val (start, end) = SunPlan.dayBounds(d.time, zone)
            val last = ((end - start) / 60_000 - 1).toFloat()
            Slider(
                ((d.time - start) / 60_000).toFloat().coerceIn(0f, last), { d.time = start + it.toLong() * 60_000 }, valueRange = 0f..last,
                colors = SliderDefaults.colors(thumbColor = Color(0xFFFFD27A), activeTrackColor = Color(0xFFFFB347)),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Pill("Mark this spot", color = Brand.light) {
                    val p = pose.value
                    report = if (p == null) "No compass on this phone." else {
                        val (az, el) = SunPlan.direction(p.forward)
                        spotText(SunPlan.spot(az, el, d.time, lat, lon, zone), az, el)
                    }
                }
                if (shot != null && d.sceneId != null && d.sessionId != null) Pill("Use ${clockText(d.time)} for shot ${shot.shotNumber}", color = Brand.accent, textColor = Color.White) {
                    RecceStore.updateShot(d.sessionId, d.sceneId, shot.id) { it.copy(plannedTime = d.time) }
                    report = "Saved ${clockText(d.time)} with shot ${shot.shotNumber}."
                }
            }
            // Compass correction and the skyline.
            Row(
                Modifier.padding(top = 6.dp).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                val now = System.currentTimeMillis()
                val realSun = Solar.position(now, lat, lon)
                if (realSun.elevation > 0) Pill("Align on the sun", color = Brand.light) {
                    val p = pose.value
                    report = if (p == null) "No compass on this phone." else {
                        val (az, _) = SunPlan.direction(p.forward)
                        val fix = SunPlan.azimuthDifference(realSun.azimuth, az)
                        CompassCorrection.offset = SunPlan.azimuthDifference(CompassCorrection.offset + fix, 0.0)
                        fmt("Compass corrected by %+.0f°. (Put the crosshair on the real sun first, then tap.)", fix)
                    }
                }
                Pill("−1°", color = Brand.lightChip) { CompassCorrection.offset -= 1 }
                Pill("+1°", color = Brand.lightChip) { CompassCorrection.offset += 1 }
                if (CompassCorrection.offset != 0.0) Pill(fmt("%+.0f° · reset", CompassCorrection.offset), color = Brand.lightChip) { CompassCorrection.offset = 0.0 }
                Spacer(Modifier.width(8.dp))
                if (!recording) Pill(if (d.skyline.isEmpty()) "Record skyline" else "Re-record skyline", color = Brand.light) {
                    recorded.clear()
                    d.skyline.forEach { recorded[Skyline.bin(it.azimuth)] = it.elevation }
                    recording = true
                } else {
                    Pill("Done (${recorded.size}°)", color = Brand.accent, textColor = Color.White) {
                        val pts = recorded.entries.sortedBy { it.key }.map { SkyPoint(it.key.toDouble(), it.value) }
                        d.skyline = pts; d.skylineLoaded = true
                        val sid = d.sessionId
                        if (sid != null) RecceStore.update(sid) { it.copy(skyline = pts) }
                        recording = false
                        val sky = Skyline(pts)
                        val periods = SunPlan.directSunPeriods(d.time, lat, lon, ZoneId.systemDefault(), sky)
                        report = "Skyline saved (${sky.recordedDegrees}°). Direct sun: " +
                            (if (periods.isEmpty()) "none that day." else periods.joinToString(", ") { "${clockText(it.first)}–${clockText(it.second)}" }) +
                            if (sid == null) " (Not part of a recce, so it's kept only until you leave the planner.)" else ""
                    }
                    Pill("Cancel", color = Brand.lightChip) { recording = false }
                }
            }
            CompassNote(pose, compassStatus)
        }
    }
}

/** "Aim 245° South-West / 12° up" for the crosshair; reads the pose here so only this text updates. */
@Composable
private fun AimText(pose: State<CameraPose?>) {
    val p = pose.value ?: return
    val (az, el) = SunPlan.direction(p.forward)
    Text(fmt("Aim %.0f° %s\n%.0f° %s", az, Solar.compass(az), abs(el), if (el >= 0) "up" else "down"),
        color = Color.White, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
        modifier = Modifier.background(Color(0x99000000), RoundedCornerShape(8.dp)).padding(6.dp))
}

@Composable
private fun CompassNote(pose: State<CameraPose?>, status: State<Int?>) {
    val p = pose.value
    val acc = p?.headingAccuracyDeg?.let { Math.round(it / 5.0) * 5 } // coarse, so it doesn't redraw every event
    val poor = status.value.let { it == SensorManager.SENSOR_STATUS_UNRELIABLE || it == SensorManager.SENSOR_STATUS_ACCURACY_LOW }
    if (poor) Text(
        "Compass needs calibrating: move the phone in a figure-8 a few times, away from metal and cars.",
        color = Color(0xFFFFB347), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp),
    )
    Text(
        (if (p == null) "Waiting for the rotation sensor (none on some phones: then the sun can't be placed on the picture). " else "") +
            (acc?.let { "Compass ±$it°. " } ?: "") +
            "If the drawn sun is off from the real one, put the crosshair on the real sun and tap “Align on the sun”, or nudge ±1°. " +
            "Record the skyline to include buildings and hills.",
        color = Brand.muted, fontSize = 11.sp, modifier = Modifier.widthIn(max = 640.dp).padding(top = 4.dp),
    )
}

private fun spotText(r: SunPlan.SpotReport, az: Double, el: Double): String {
    val lines = mutableListOf(fmt("Spot: %.0f° %s, %.0f° %s.", az, Solar.compass(az), abs(el), if (el >= 0) "up" else "down"))
    val closest = r.closestTime
    if (closest != null && r.passesThrough) lines += "The sun passes right through it at ${clockText(closest)}."
    else if (closest != null) lines += fmt("Closest at %s, %.0f° away.", clockText(closest), r.closestDegrees)
    for ((t, diff) in r.sameDirection) lines += fmt("In this direction at %s, %.0f° %s it.", clockText(t), abs(diff), if (diff >= 0) "above" else "below")
    val from = r.aboveFrom; val until = r.aboveUntil
    lines += if (from != null && until != null) "The sun is higher than this spot from ${clockText(from)} to ${clockText(until)}."
    else "The sun never gets as high as this spot today."
    return lines.joinToString("\n")
}

/** Day path, hour marks, sun disc and horizon compass points, projected with the live camera orientation. */
@Composable
private fun SkyOverlay(
    pose: State<CameraPose?>, phone: PhoneCamera, landscape: Boolean, screen: IntSize,
    path: List<SunPlan.Sample>, time: Long, lat: Double, lon: Double, skyline: List<SkyPoint> = emptyList(),
) {
    if (screen.width == 0) return
    val measurer = rememberTextMeasurer(cacheSize = 48)
    val video = Monitor.aspectFill(if (landscape) phone.streamAspect else 1 / phone.streamAspect, screen.width.toDouble(), screen.height.toDouble())
    val view = PhoneView.fromFormat(phone.longSideFov, phone.streamAspect, !landscape)
    val th = Optics.halfTan(view.horizontalFov) / phone.zoom
    val tv = Optics.halfTan(view.verticalFov) / phone.zoom
    val zone = ZoneId.systemDefault()
    Canvas(Modifier.fillMaxSize()) {
        // Read the pose while drawing, so sensor updates only redraw (no recomposition).
        val p = pose.value ?: return@Canvas
        fun scr(az: Double, el: Double): Offset? = SkyProjection.project(SunPlan.vector(az, el), p.forward, p.right, p.up, th, tv)?.let { (x, y) ->
            if (abs(x) > 4 || abs(y) > 4) null
            else Offset((video.midX + x * video.width / 2).toFloat(), (video.midY - y * video.height / 2).toFloat())
        }
        // Horizon with compass points.
        val horizon = Path(); var started = false
        for (az in 0..360 step 2) {
            val o = scr(az.toDouble(), 0.0)
            if (o == null) { started = false; continue }
            if (!started) { horizon.moveTo(o.x, o.y); started = true } else horizon.lineTo(o.x, o.y)
        }
        drawPath(horizon, Color(0x88FFFFFF), style = Stroke(1.dp.toPx()))
        for ((label, az) in listOf("N" to 0.0, "NE" to 45.0, "E" to 90.0, "SE" to 135.0, "S" to 180.0, "SW" to 225.0, "W" to 270.0, "NW" to 315.0)) {
            scr(az, 0.0)?.let { drawLabel(measurer, label, Offset(it.x - 6.dp.toPx(), it.y + 2.dp.toPx()), TextStyle(color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)) }
        }
        // The recorded skyline (breaks across unrecorded gaps).
        if (skyline.isNotEmpty()) {
            val sky = Path(); var on = false; var lastAz = -1000
            for (pt in skyline.sortedBy { it.azimuth }) {
                val az = pt.azimuth.toInt()
                val o = scr(pt.azimuth, pt.elevation)
                if (o == null || az - lastAz > Skyline.MAX_GAP) on = false
                if (o != null) { if (!on) { sky.moveTo(o.x, o.y); on = true } else sky.lineTo(o.x, o.y) }
                lastAz = az
            }
            drawPath(sky, Color(0xFF5AC8FA), style = Stroke(2.5.dp.toPx()))
        }
        // The day's path (above −2°), golden hour orange, hour marks.
        val line = Path(); started = false
        for (s in path) {
            val o = if (s.position.elevation > -2) scr(s.position.azimuth, s.position.elevation) else null
            if (o == null) { started = false; continue }
            if (!started) { line.moveTo(o.x, o.y); started = true } else line.lineTo(o.x, o.y)
            if (s.position.elevation <= Solar.GOLDEN_HIGH) drawCircle(Color(0xFFFF9F0A), 3.dp.toPx(), o)
            val local = java.time.Instant.ofEpochMilli(s.time).atZone(zone)
            if (local.minute == 0) {
                drawCircle(Color.White, 3.dp.toPx(), o)
                drawLabel(measurer, "${local.hour}:00", Offset(o.x + 5.dp.toPx(), o.y - 14.dp.toPx()), TextStyle(color = Color.White, fontSize = 10.sp))
            }
        }
        drawPath(line, Color(0xFFFFD27A), style = Stroke(2.dp.toPx()))
        // The sun at the chosen time.
        val sun = Solar.position(time, lat, lon)
        scr(sun.azimuth, sun.elevation)?.let {
            drawCircle(Color(0x66FFE066), 22.dp.toPx(), it)
            drawCircle(Color(0xFFFFE066), 12.dp.toPx(), it)
        }
    }
}
