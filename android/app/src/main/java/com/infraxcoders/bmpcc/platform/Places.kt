package com.infraxcoders.bmpcc.platform

import android.content.Context
import android.content.Intent
import android.location.Address
import android.location.Geocoder
import android.net.Uri
import android.os.Build
import com.infraxcoders.bmpcc.core.PlaceName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.coroutines.resume

/** Place names from GPS (the phone's own geocoder: free, needs internet) and opening a spot in a maps app. */
object Places {
    /** "Hauz Khas, New Delhi" for a position, or null when the phone can't look it up (offline, no geocoder). */
    suspend fun name(context: Context, latitude: Double, longitude: Double): String? {
        if (!Geocoder.isPresent()) return null
        val g = Geocoder(context, Locale.getDefault())
        val address: Address? = runCatching {
            if (Build.VERSION.SDK_INT >= 33) suspendCancellableCoroutine { cont ->
                g.getFromLocation(latitude, longitude, 1, object : Geocoder.GeocodeListener {
                    override fun onGeocode(addresses: MutableList<Address>) { if (cont.isActive) cont.resume(addresses.firstOrNull()) }
                    override fun onError(errorMessage: String?) { if (cont.isActive) cont.resume(null) }
                })
            } else withContext(Dispatchers.IO) {
                @Suppress("DEPRECATION")
                g.getFromLocation(latitude, longitude, 1)?.firstOrNull()
            }
        }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }.getOrNull()
        return address?.let { PlaceName.format(it.featureName, it.subLocality, it.locality, it.subAdminArea, it.adminArea, it.countryName) }
    }

    /** Opens the spot in the phone's maps app (for directions). False when no app can show it. */
    fun openInMaps(context: Context, latitude: Double, longitude: Double, label: String): Boolean {
        val pos = String.format(Locale.US, "%.6f,%.6f", latitude, longitude)
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:$pos?q=$pos(${Uri.encode(label)})")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }.isSuccess
    }
}
