package com.infraxcoders.bmpcc.ui

import android.Manifest
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.infraxcoders.bmpcc.core.Solar
import com.infraxcoders.bmpcc.core.SolarPosition
import com.infraxcoders.bmpcc.core.SunPlan
import com.infraxcoders.bmpcc.core.Skyline
import com.infraxcoders.bmpcc.core.CloudForecast
import com.infraxcoders.bmpcc.data.Settings
import com.infraxcoders.bmpcc.platform.Weather
import androidx.compose.ui.graphics.PathFillType
import com.infraxcoders.bmpcc.data.RecceStore
import com.infraxcoders.bmpcc.platform.Locator
import com.infraxcoders.bmpcc.platform.rememberPermissionAsker
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/** Time of day "16:30" in this phone's time zone. */
fun clockText(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))

/** "245° South-West, 18° high" for a sun position. */
fun sunText(p: SolarPosition): String =
    if (p.elevation < Solar.HORIZON) fmt("below the horizon (%.0f°)", p.elevation)
    else fmt("%.0f° %s, %.0f° high", p.azimuth, p.compass, p.elevation)

/**
 * Sun planner: pick a date and slide the time; see where the sun is, its path over the day, golden and blue hour,
 * and open the camera view (AR). From a shot, "Use for shot" saves the time with it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SunPlannerScreen(nav: Navigator, d: Dest.Sun) {
    val context = LocalContext.current
    val sessions by RecceStore.sessions.collectAsState()
    val session = d.sessionId?.let { id -> sessions.firstOrNull { it.id == id } }
    val shot = session?.scenes?.firstOrNull { it.id == d.sceneId }?.shots?.firstOrNull { it.id == d.shotId }
    val zone = ZoneId.systemDefault()
    // Location: the recce's, or one found here.
    LaunchedEffect(session?.latitude, session?.longitude) {
        if (d.latitude == null && session?.latitude != null) { d.latitude = session.latitude; d.longitude = session.longitude }
    }
    LaunchedEffect(session?.id) {
        if (!d.skylineLoaded && session != null) { d.skyline = session.skyline; d.skylineLoaded = true }
    }
    val skyline = remember(d.skyline) { Skyline(d.skyline).takeIf { !it.isEmpty } }
    var status by remember { mutableStateOf<String?>(null) }
    var pickDate by remember { mutableStateOf(false) }
    val asker = rememberPermissionAsker { status = "Location access is off." }
    val lat = d.latitude; val lon = d.longitude

    Screen("Sun planner", onBack = { nav.pop() }) { pad ->
        LazyColumn(contentPadding = pad) {
            section("Location") {
                item {
                    Column(Modifier.cardRow().padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Text(session?.locationName ?: "This location", fontWeight = FontWeight.SemiBold)
                        Text(if (lat != null && lon != null) fmt("%.5f, %.5f", lat, lon) else "No location yet", color = Brand.muted, fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton({
                                asker.withPermission(Manifest.permission.ACCESS_FINE_LOCATION) {
                                    status = "Locating…"
                                    Locator.request(context) { loc, err ->
                                        if (loc != null) { d.latitude = loc.latitude; d.longitude = loc.longitude; status = null } else status = err
                                    }
                                }
                            }) { Icon(Icons.Filled.MyLocation, null); Spacer(Modifier.width(6.dp)); Text("Use my location") }
                            if (session != null && lat != null && lon != null && (session.latitude != lat || session.longitude != lon)) TextButton({
                                RecceStore.update(session.id) { it.copy(latitude = lat, longitude = lon) }; status = "Saved to “${session.projectName}”."
                            }) { Text("Save to this recce") }
                        }
                        status?.let { Text(it, color = Brand.muted, fontSize = 12.sp) }
                    }
                }
            }
            if (lat == null || lon == null) {
                item { Hint("The sun's position depends on where you are. Use your location (outdoors works best).") }
                return@LazyColumn
            }
            val day = Solar.day(d.time, lat, lon, zone)
            val pos = Solar.position(d.time, lat, lon)
            section("Date and time") {
                item {
                    Column(Modifier.cardRow().padding(horizontal = 8.dp, vertical = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton({ d.time = Instant.ofEpochMilli(d.time).atZone(zone).minusDays(1).toInstant().toEpochMilli() }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous day") }
                            Text(
                                Instant.ofEpochMilli(d.time).atZone(zone).format(DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.getDefault())),
                                fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).clickable { pickDate = true }.padding(8.dp),
                            )
                            IconButton({ d.time = Instant.ofEpochMilli(d.time).atZone(zone).plusDays(1).toInstant().toEpochMilli() }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next day") }
                        }
                        val (start, end) = SunPlan.dayBounds(d.time, zone)
                        val last = ((end - start) / 60_000 - 1).toFloat()
                        val minutes = ((d.time - start) / 60_000).toFloat().coerceIn(0f, last)
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp)) {
                            Text(clockText(d.time), fontSize = 28.sp, fontFamily = FontFamily.Monospace, color = Color(0xFFFFD27A))
                            Spacer(Modifier.width(12.dp))
                            Text(sunText(pos) + if (SunPlan.behindSkyline(pos, skyline)) " · behind the skyline" else "",
                                fontSize = 13.sp, color = Color.White, modifier = Modifier.weight(1f))
                        }
                        Slider(
                            minutes, { d.time = start + it.toLong() * 60_000 }, valueRange = 0f..last,
                            colors = SliderDefaults.colors(thumbColor = Color(0xFFFFD27A), activeTrackColor = Color(0xFFFFB347)),
                            modifier = Modifier.padding(horizontal = 8.dp),
                        )
                        // Quick times.
                        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOfNotNull(
                                day.blueMorning?.start?.let { "Blue AM" to it }, day.sunrise?.let { "Sunrise" to it },
                                day.goldenMorning?.let { "Golden AM" to (it.start + it.end) / 2 }, "Noon" to day.solarNoon,
                                day.goldenEvening?.let { "Golden PM" to (it.start + it.end) / 2 }, day.sunset?.let { "Sunset" to it },
                                day.blueEvening?.let { "Blue PM" to (it.start + it.end) / 2 }, "Now" to System.currentTimeMillis(),
                            ).forEach { (label, t) -> Pill(label, color = Brand.lightChip) { d.time = t } }
                        }
                        if (pos.elevation > 0.5) Text(
                            fmt("Shadows %.1f× as long as objects are tall, pointing %.0f° (%s). For backlight, point the camera towards %s.",
                                1 / tan(Math.toRadians(pos.elevation)), (pos.azimuth + 180) % 360, Solar.compass((pos.azimuth + 180) % 360), Solar.compass(pos.backlightHeading)),
                            fontSize = 12.sp, color = Brand.muted, modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        )
                    }
                }
                item {
                    Column(Modifier.cardRow().padding(12.dp)) {
                        ActionButtonRow(Icons.Filled.ViewInAr, "See the sun in the camera (AR)") { nav.push(Dest.SunAr(d)) }
                        if (shot != null && session != null) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Use ${clockText(d.time)} for shot ${shot.shotNumber}", color = Color.White, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.fillMaxWidth().background(Brand.accent, RoundedCornerShape(12.dp))
                                    .clickable {
                                        RecceStore.updateShot(session.id, d.sceneId!!, shot.id) { it.copy(plannedTime = d.time) }
                                        status = "Saved ${clockText(d.time)} with shot ${shot.shotNumber}."
                                    }.padding(14.dp),
                            )
                            shot.plannedTime?.let { Text("Shot ${shot.shotNumber}: planned for ${clockText(it)}", color = Brand.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp)) }
                        }
                    }
                }
            }
            section("Weather") { item { WeatherCard(lat, lon, d.time) } }
            section("Buildings and hills") {
                item {
                    Column(Modifier.cardRow().padding(horizontal = 16.dp, vertical = 12.dp)) {
                        if (skyline == null) Text(
                            "Not recorded yet. In the camera view (AR), tap “Record skyline” and sweep the crosshair along the tops of " +
                                "buildings, trees and hills, standing where the camera will be. Then you'll see when they block the sun.",
                            color = Brand.muted, fontSize = 13.sp,
                        ) else {
                            val dayStart = SunPlan.dayBounds(d.time, zone).first
                            val periods = remember(dayStart, lat, lon, skyline) { SunPlan.directSunPeriods(d.time, lat, lon, zone, skyline) }
                            Text("Direct sun here", fontWeight = FontWeight.SemiBold)
                            Text(
                                if (periods.isEmpty()) "None on this day: the sun stays behind the skyline."
                                else periods.joinToString("\n") { "${clockText(it.first)} – ${clockText(it.second)}" },
                                fontFamily = FontFamily.Monospace, fontSize = 15.sp, color = Color(0xFFFFD27A),
                            )
                            Text("Skyline recorded over ${skyline.recordedDegrees}° of the horizon; the rest counts as open sky.",
                                color = Brand.muted, fontSize = 12.sp)
                            TextButton({
                                d.skyline = emptyList()
                                session?.let { RecceStore.update(it.id) { s -> s.copy(skyline = emptyList()) } }
                            }) { Text("Clear the skyline", color = Color(0xFFFF453A)) }
                        }
                    }
                }
            }
            section("Sun path", footer = "Centre = straight up, edge = horizon, north at the top. Dots every hour; orange = golden hour. " +
                "Grey = the recorded skyline (buildings, hills).") {
                item { SunDial(lat, lon, d.time, Modifier.cardRow().padding(12.dp), skyline) }
            }
            section("Sun times") { item { SunCard(lat, lon, d.time, skyline) } }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (pickDate) {
        val zoneNow = Instant.ofEpochMilli(d.time).atZone(zone)
        val state = rememberDatePickerState(initialSelectedDateMillis = zoneNow.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton({
                    state.selectedDateMillis?.let { utc ->
                        val date = Instant.ofEpochMilli(utc).atZone(ZoneOffset.UTC).toLocalDate()
                        d.time = date.atTime(zoneNow.toLocalTime()).atZone(zone).toInstant().toEpochMilli()
                    }
                    pickDate = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton({ pickDate = false }) { Text("Cancel") } },
        ) { DatePicker(state) }
    }
}

@Composable
private fun ActionButtonRow(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Brand.light, RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Brand.ink); Spacer(Modifier.width(10.dp)); Text(text, color = Brand.ink, fontWeight = FontWeight.SemiBold)
    }
}

/** Polar sun-path diagram: centre = zenith, edge = horizon, north up. */
@Composable
fun SunDial(lat: Double, lon: Double, time: Long, modifier: Modifier, skyline: Skyline? = null) {
    val zone = ZoneId.systemDefault()
    val (dayStart, _) = SunPlan.dayBounds(time, zone)
    val path = remember(lat, lon, dayStart) { SunPlan.path(time, lat, lon, zone, 5) }
    val day = remember(lat, lon, dayStart) { Solar.day(time, lat, lon, zone) }
    val now = Solar.position(time, lat, lon)
    val measurer = rememberTextMeasurer(cacheSize = 48)
    Box(modifier) {
        Canvas(Modifier.fillMaxWidth().aspectRatio(1f)) {
            val c = Offset(size.width / 2, size.height / 2)
            val r = size.minDimension / 2 - 22.dp.toPx()
            fun pt(az: Double, el: Double): Offset {
                val rr = (r * (90 - el.coerceIn(0.0, 90.0)) / 90).toFloat()
                val a = Math.toRadians(az)
                return Offset(c.x + rr * sin(a).toFloat(), c.y - rr * cos(a).toFloat())
            }
            val ring = Color(0x55FFFFFF)
            for (el in listOf(0, 30, 60)) drawCircle(ring, (r * (90 - el) / 90).toFloat(), c, style = Stroke(1.dp.toPx()))
            for (az in 0 until 360 step 45) drawLine(ring, c, pt(az.toDouble(), 0.0), 1.dp.toPx())
            // The skyline: from the horizon up to the recorded tops.
            if (skyline != null) {
                val sky = Path().apply {
                    fillType = PathFillType.EvenOdd
                    addOval(androidx.compose.ui.geometry.Rect(c, r.toFloat()))
                    for (az in 0..360) {
                        val p = pt(az.toDouble(), (skyline.elevation(az.toDouble()) ?: 0.0).coerceAtLeast(0.0))
                        if (az == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
                    }
                    close()
                }
                drawPath(sky, Color(0x8C6B7A99))
            }
            for ((label, az) in listOf("N" to 0.0, "E" to 90.0, "S" to 180.0, "W" to 270.0)) {
                val p = pt(az, -8.0).let { Offset(c.x + (it.x - c.x) * 1.09f, c.y + (it.y - c.y) * 1.09f) }
                drawLabel(measurer, label, Offset(p.x - 5.dp.toPx(), p.y - 8.dp.toPx()), TextStyle(color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold))
            }
            // The day's path above the horizon; golden hour in orange.
            val up = path.filter { it.position.elevation >= 0 }
            if (up.size > 1) {
                val line = Path()
                up.forEachIndexed { i, s -> val p = pt(s.position.azimuth, s.position.elevation); if (i == 0) line.moveTo(p.x, p.y) else line.lineTo(p.x, p.y) }
                drawPath(line, Color(0xFFFFD27A), style = Stroke(2.dp.toPx()))
                for (s in up) if (s.position.elevation <= Solar.GOLDEN_HIGH) drawCircle(Color(0xFFFF9F0A), 3.dp.toPx(), pt(s.position.azimuth, s.position.elevation))
                for (s in up) {
                    val local = Instant.ofEpochMilli(s.time).atZone(zone)
                    if (local.minute == 0) {
                        val p = pt(s.position.azimuth, s.position.elevation)
                        drawCircle(Color.White, 2.5.dp.toPx(), p)
                        drawLabel(measurer, "${local.hour}", Offset(p.x + 4.dp.toPx(), p.y - 6.dp.toPx()), TextStyle(color = Color(0xCCFFFFFF), fontSize = 9.sp))
                    }
                }
            }
            day.sunrise?.let { val p = Solar.position(it, lat, lon); drawCircle(Color(0xFFFF9F0A), 5.dp.toPx(), pt(p.azimuth, 0.0)) }
            day.sunset?.let { val p = Solar.position(it, lat, lon); drawCircle(Color(0xFFFF6B3D), 5.dp.toPx(), pt(p.azimuth, 0.0)) }
            if (now.elevation > Solar.HORIZON) {
                val p = pt(now.azimuth, now.elevation)
                drawCircle(Color(0xFFFFE066), 9.dp.toPx(), p)
                drawCircle(Color.White, 9.dp.toPx(), p, style = Stroke(2.dp.toPx()))
            }
        }
    }
}

/** Hourly cloud forecast for the chosen day (Open-Meteo), with the chosen hour spelled out. Off until the user turns it on. */
@Composable
private fun WeatherCard(lat: Double, lon: Double, time: Long) {
    Column(Modifier.cardRow().padding(horizontal = 16.dp, vertical = 12.dp)) {
        if (!Settings.weatherOn) {
            Text("Cloud forecast", fontWeight = FontWeight.SemiBold)
            Text("Hourly cloud cover for the next 16 days from Open-Meteo (free). Sends this location to their server; needs internet.",
                color = Brand.muted, fontSize = 12.sp)
            TextButton({ Settings.chooseWeather(true) }) { Text("Get the cloud forecast") }
            return@Column
        }
        var hours by remember(lat, lon) { mutableStateOf<List<CloudForecast.Hour>?>(null) }
        var error by remember(lat, lon) { mutableStateOf<String?>(null) }
        var retry by remember { mutableStateOf(0) }
        LaunchedEffect(lat, lon, retry) {
            error = null
            runCatching { Weather.forecast(lat, lon) }
                .onSuccess { hours = it }
                .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it; error = "Couldn't get the forecast. Check the internet connection." }
        }
        val list = hours
        val h = list?.let { CloudForecast.at(it, time) }
        when {
            error != null -> { Text(error ?: "", color = Brand.muted, fontSize = 13.sp); TextButton({ retry++ }) { Text("Try again") } }
            list == null -> Text("Getting the forecast…", color = Brand.muted, fontSize = 13.sp)
            h == null -> Text("No forecast for this time: forecasts cover the next ${CloudForecast.DAYS} days.", color = Brand.muted, fontSize = 13.sp)
            else -> {
                Text("${clockText(h.time)}  " + CloudForecast.describe(h), fontSize = 14.sp)
                CloudStrip(list, time, Modifier.fillMaxWidth().height(48.dp).padding(top = 8.dp))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(CloudForecast.ATTRIBUTION, color = Brand.muted, fontSize = 10.sp, modifier = Modifier.weight(1f))
            TextButton({ Settings.chooseWeather(false) }) { Text("Turn off", fontSize = 12.sp) }
        }
    }
}

/** The chosen day's cloud cover hour by hour (taller = cloudier), with the chosen hour marked. */
@Composable
private fun CloudStrip(hours: List<CloudForecast.Hour>, time: Long, modifier: Modifier) {
    val zone = ZoneId.systemDefault()
    val (start, end) = SunPlan.dayBounds(time, zone)
    val day = hours.filter { it.time in start until end }
    if (day.isEmpty()) return
    Canvas(modifier) {
        val n = 24
        val w = size.width / n
        for (h in day) {
            val i = ((h.time - start) / 3_600_000).toInt().coerceIn(0, n - 1)
            val bh = size.height * h.cloud / 100f
            val selected = time >= h.time && time < h.time + 3_600_000
            drawRect(
                if (selected) Color(0xFFFFD27A) else Color(0x99AFC0E6),
                Offset(i * w + 1, size.height - bh.coerceAtLeast(1f)),
                androidx.compose.ui.geometry.Size(w - 2, bh.coerceAtLeast(1f)),
            )
        }
    }
}
