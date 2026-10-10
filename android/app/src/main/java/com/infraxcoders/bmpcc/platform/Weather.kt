package com.infraxcoders.bmpcc.platform

import com.infraxcoders.bmpcc.core.CloudForecast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** Hourly cloud forecast from Open-Meteo (free, no key; needs internet). Cached for 30 minutes per place. */
object Weather {
    private class Entry(val at: Long, val hours: List<CloudForecast.Hour>)
    private val cache = HashMap<String, Entry>()

    suspend fun forecast(latitude: Double, longitude: Double): List<CloudForecast.Hour> {
        val key = String.format(java.util.Locale.US, "%.2f,%.2f", latitude, longitude)
        synchronized(cache) { cache[key]?.takeIf { System.currentTimeMillis() - it.at < 30 * 60_000 }?.let { return it.hours } }
        val hours = withContext(Dispatchers.IO) {
            val c = URL(CloudForecast.url(latitude, longitude)).openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 10_000; c.readTimeout = 15_000
                c.setRequestProperty("Accept", "application/json")
                if (c.responseCode != 200) throw IllegalStateException("The forecast service answered ${c.responseCode}.")
                CloudForecast.parse(c.inputStream.bufferedReader().use { it.readText() })
            } finally { c.disconnect() }
        }
        synchronized(cache) { cache[key] = Entry(System.currentTimeMillis(), hours) }
        return hours
    }
}
