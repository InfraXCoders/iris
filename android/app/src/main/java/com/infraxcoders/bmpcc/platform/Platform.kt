package com.infraxcoders.bmpcc.platform

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import java.io.File

fun Context.hasPermission(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

/** Runs [onGranted] once the permission is allowed (asking first if needed). */
class PermissionAsker(private val ask: (String) -> Unit, private val context: Context) {
    var pending: (() -> Unit)? = null
    fun withPermission(permission: String, onGranted: () -> Unit) {
        if (context.hasPermission(permission)) onGranted() else { pending = onGranted; ask(permission) }
    }
}

@Composable
fun rememberPermissionAsker(onDenied: (String) -> Unit = {}): PermissionAsker {
    val context = LocalContext.current
    var asker: PermissionAsker? = null
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val a = asker ?: return@rememberLauncherForActivityResult
        if (granted) a.pending?.invoke() else onDenied("Permission denied")
        a.pending = null
    }
    val a = remember { PermissionAsker({ launcher.launch(it) }, context) }
    asker = a
    return a
}

/** One-shot location from GPS or the network. */
object Locator {
    @SuppressLint("MissingPermission")
    fun request(context: Context, onResult: (Location?, String?) -> Unit) {
        if (!context.hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) &&
            !context.hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
        ) return onResult(null, "Location access is off.")
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter {
            runCatching { lm.isProviderEnabled(it) }.getOrDefault(false)
        }
        if (providers.isEmpty()) return onResult(null, "Turn on Location in the phone's settings.")
        var done = false
        val handler = Handler(Looper.getMainLooper())
        fun finish(loc: Location?, err: String?) { if (!done) { done = true; onResult(loc, err) } }
        // Fall back to the last known fix after 20 s.
        handler.postDelayed({
            val last = providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
            finish(last, if (last == null) "Couldn't get a location fix. Try outdoors." else null)
        }, 20_000)
        for (p in providers) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                lm.getCurrentLocation(p, null, context.mainExecutor) { loc -> if (loc != null) finish(loc, null) }
            } else {
                @Suppress("DEPRECATION")
                lm.requestSingleUpdate(p, object : LocationListener {
                    override fun onLocationChanged(location: Location) = finish(location, null)
                    @Deprecated("Deprecated in Java")
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                    override fun onProviderEnabled(provider: String) {}
                    override fun onProviderDisabled(provider: String) {}
                }, Looper.getMainLooper())
            }
        }
    }
}

/** Live speech-to-text (English/Hindi) with Android's speech recogniser. */
class Dictation(private val context: Context) {
    var transcript by mutableStateOf("")
        private set
    var isListening by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    private var recognizer: SpeechRecognizer? = null
    private var committed = ""

    companion object {
        val languages = listOf(
            "en-IN" to "English (India) / Hinglish",
            "hi-IN" to "Hindi",
            "en-US" to "English (US)",
            "en-GB" to "English (UK)",
        )
    }

    fun start(language: String, existing: String) {
        error = null
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            error = "Speech recognition isn't available on this phone. You can still type the note."
            return
        }
        committed = existing.trim()
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { isListening = true }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(code: Int) {
                isListening = false
                if (code != SpeechRecognizer.ERROR_NO_MATCH && code != SpeechRecognizer.ERROR_CLIENT) {
                    error = when (code) {
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone access is off."
                        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "No connection for speech recognition. Type the note instead."
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Didn't hear anything."
                        else -> "Speech recognition stopped (code $code)."
                    }
                }
            }
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                transcript = listOf(committed, text).filter { it.isNotBlank() }.joinToString(" ")
                isListening = false
            }
            override fun onPartialResults(partial: Bundle?) {
                val text = partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                if (text.isNotBlank()) transcript = listOf(committed, text).filter { it.isNotBlank() }.joinToString(" ")
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
        }
        r.startListening(intent)
        isListening = true
    }

    fun stop() { recognizer?.stopListening() }

    fun release() {
        recognizer?.destroy()
        recognizer = null
        isListening = false
    }
}

/** Loads a photo, upright (applies the EXIF rotation) and scaled down to about [maxSide] pixels. */
object Images {
    private val cache = object : android.util.LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun load(file: File, maxSide: Int = 1600): Bitmap? {
        if (!file.exists()) return null
        val key = "${file.path}@$maxSide"
        cache.get(key)?.let { return it }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val bmp = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val rotation = runCatching { ExifInterface(file.path).rotationDegrees }.getOrDefault(0)
        val upright = if (rotation == 0) bmp else Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rotation.toFloat()) }, true)
        cache.put(key, upright)
        return upright
    }
}

/** Opens the share sheet for a file. */
fun share(context: Context, file: File, mime: String) {
    val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, file.nameWithoutExtension)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, "Share ${file.name}"))
}
