package com.infraxcoders.bmpcc.platform

import android.content.Context
import android.content.Intent
import android.os.Build
import com.infraxcoders.bmpcc.BuildConfig
import java.io.File
import java.util.Date

/**
 * Keeps the last crash on the phone, so a tester can send it: the home screen offers "Share crash report" on the
 * next launch. Nothing is sent anywhere automatically.
 */
object CrashLog {
    private var file: File? = null

    fun install(context: Context) {
        val f = File(context.filesDir, "last_crash.txt")
        file = f
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                f.writeText(
                    "BMPCC Control v${BuildConfig.VERSION_NAME} build ${BuildConfig.BUILD_NUMBER}\n" +
                        "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}\n${Date()}\n\n" +
                        error.stackTraceToString().take(12_000),
                )
            }
            previous?.uncaughtException(thread, error)
        }
    }

    fun last(): String? = file?.takeIf { it.exists() }?.let { runCatching { it.readText() }.getOrNull() }

    fun clear() { file?.delete() }

    fun share(context: Context, text: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "BMPCC Control crash report")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(send, "Send crash report").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
