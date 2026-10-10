package com.infraxcoders.bmpcc.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.infraxcoders.bmpcc.core.Solar
import com.infraxcoders.bmpcc.core.TimeRange
import com.infraxcoders.bmpcc.core.Skyline
import com.infraxcoders.bmpcc.core.SunPlan
import java.text.DateFormat
import java.time.Instant
import java.time.ZoneId
import java.util.Date

object SunText {
    fun time(ms: Long?): String = if (ms == null) "—" else DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ms))
    fun range(r: TimeRange?): String = if (r == null) "—" else "${time(r.start)} – ${time(r.end)}"
}

/** Sunrise, sunset, golden and blue hour for a location and date, in this phone's time zone. */
@Composable
fun SunCard(latitude: Double, longitude: Double, date: Long, skyline: Skyline? = null) {
    val zone = ZoneId.systemDefault()
    val dayStart = SunPlan.dayBounds(date, zone).first
    val day = remember(latitude, longitude, dayStart) { Solar.day(date, latitude, longitude, zone) }
    Column(Modifier.cardRow().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        when {
            day.isPolarDay -> Text("The sun doesn't set on this day.")
            day.isPolarNight -> Text("The sun doesn't rise on this day.")
            else -> {
                SunRow("Sunrise", SunText.time(day.sunrise))
                SunRow("Sunset", SunText.time(day.sunset))
            }
        }
        if (skyline != null) {
            val periods = remember(latitude, longitude, dayStart, skyline) { SunPlan.directSunPeriods(date, latitude, longitude, zone, skyline) }
            SunRow("Direct sun (skyline)", if (periods.isEmpty()) "none" else periods.joinToString("\n") { "${SunText.time(it.first)} – ${SunText.time(it.second)}" })
        }
        SunRow("Solar noon", "${SunText.time(day.solarNoon)} · ${day.noonElevation.degreesText()} high")
        SunRow("Golden hour (morning)", SunText.range(day.goldenMorning))
        SunRow("Golden hour (evening)", SunText.range(day.goldenEvening))
        SunRow("Blue hour (morning)", SunText.range(day.blueMorning))
        SunRow("Blue hour (evening)", SunText.range(day.blueEvening))
        val today = Instant.ofEpochMilli(date).atZone(zone).toLocalDate() == java.time.LocalDate.now(zone)
        if (today) {
            val now = Solar.position(System.currentTimeMillis(), latitude, longitude)
            RowDivider()
            if (now.elevation > Solar.HORIZON) {
                SunRow("Sun now", "${now.elevation.degreesText()} high, ${now.compass} (${now.azimuth.toInt()}°)")
                Text("For backlight, point the camera towards ${Solar.compass(now.backlightHeading)} (into the sun).", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            } else SunRow("Sun now", "Below the horizon")
        }
        Text(
            "Golden hour: sun 6° above to 4° below the horizon. Blue hour: 4° to 6° below.",
            style = MaterialTheme.typography.bodySmall, color = Color.Gray,
        )
    }
}

@Composable
private fun SunRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f))
        MonoText(value)
    }
}
