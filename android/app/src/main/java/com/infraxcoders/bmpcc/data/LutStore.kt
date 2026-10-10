package com.infraxcoders.bmpcc.data

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import com.infraxcoders.bmpcc.core.BuiltInLooks
import com.infraxcoders.bmpcc.core.CubeParser
import com.infraxcoders.bmpcc.core.Lut3D
import com.infraxcoders.bmpcc.core.LutInput
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One LUT in the library: a built-in look or an imported .cube file. */
data class LutEntry(val id: String, val name: String, val builtIn: Boolean, val size: Int?)

/**
 * The LUT library: built-in looks plus imported .cube files (kept in the app's own storage). Remembers which input
 * each LUT expects (Rec.709 or Blackmagic Film Gen 5).
 */
object LutStore {
    private lateinit var appContext: Context
    private lateinit var prefs: SharedPreferences
    private val cache = HashMap<String, Lut3D>()
    val entries = mutableStateListOf<LutEntry>()
    /** Bumped when an input choice changes, so screens reading [input] update. */
    var version by mutableIntStateOf(0); private set

    private val dir: File get() = File(appContext.filesDir, "luts").apply { mkdirs() }

    fun init(context: Context) {
        appContext = context.applicationContext
        prefs = appContext.getSharedPreferences("luts", Context.MODE_PRIVATE)
        refresh()
    }

    private fun refresh() {
        entries.clear()
        BuiltInLooks.all.forEach { entries += LutEntry("builtin:${it.name}", it.name, true, it.size) }
        dir.listFiles { f -> f.name.endsWith(".cube", ignoreCase = true) }?.sortedBy { it.name.lowercase() }?.forEach { f ->
            entries += LutEntry("file:${f.name}", prefs.getString("name:${f.name}", null) ?: f.nameWithoutExtension, false, prefs.getInt("size:${f.name}", 0).takeIf { it > 0 })
        }
    }

    fun entry(id: String?): LutEntry? = if (id == null) null else entries.firstOrNull { it.id == id }
    fun nameOf(id: String?): String? = entry(id)?.name ?: id?.substringAfter(':')

    /** Already-loaded LUT (no file reading), for a first frame without waiting. */
    @Synchronized
    fun cached(id: String?): Lut3D? = if (id == null) null else cache[id]

    /** The LUT itself (parsed once, then cached); null if missing or unreadable. Call off the main thread. */
    @Synchronized
    fun load(id: String?): Lut3D? {
        if (id == null) return null
        cache[id]?.let { return it }
        val lut = when {
            id.startsWith("builtin:") -> BuiltInLooks.all.firstOrNull { it.name == id.removePrefix("builtin:") }
            id.startsWith("file:") -> runCatching {
                val f = File(dir, id.removePrefix("file:"))
                f.bufferedReader().useLines { CubeParser.parse(it, f.nameWithoutExtension) }
            }.getOrNull()
            else -> null
        } ?: return null
        cache[id] = lut
        return lut
    }

    fun input(id: String?): LutInput { version; return LutInput.of(id?.let { prefs.getString("input:$it", null) }) }
    fun chooseInput(id: String, input: LutInput) {
        prefs.edit().putString("input:$id", input.code).apply()
        version++
    }

    class ImportException(message: String) : Exception(message)

    /**
     * Copies a .cube file into the library (checked first). Reading and checking run in the background;
     * the library list is updated on the caller's (main) thread.
     */
    suspend fun import(uri: Uri): LutEntry {
        val cr = appContext.contentResolver
        val id = withContext(Dispatchers.IO) {
            var display = "LUT.cube"; var bytes = -1L
            runCatching {
                cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) { c.getString(0)?.let { display = it }; if (!c.isNull(1)) bytes = c.getLong(1) }
                }
            }
            if (bytes > 20_000_000) throw ImportException("This file is too large for a LUT (over 20 MB).")
            val base = display.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9 ._-]"), "_").trim().ifEmpty { "LUT" }
            var file = File(dir, "$base.cube"); var n = 2
            while (file.exists()) file = File(dir, "$base ($n).cube").also { n++ }
            // Copy first (capped), then check by parsing the copy line by line.
            val input = cr.openInputStream(uri) ?: throw ImportException("Couldn't read the file.")
            input.use { inp ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024); var total = 0L
                    while (true) {
                        val r = inp.read(buf); if (r < 0) break
                        total += r
                        if (total > 20_000_000) { out.close(); file.delete(); throw ImportException("This file is too large for a LUT (over 20 MB).") }
                        out.write(buf, 0, r)
                    }
                }
            }
            val lut = try { file.bufferedReader().useLines { CubeParser.parse(it, base) } } catch (e: CubeParser.CubeException) {
                file.delete(); throw ImportException("Not a usable .cube LUT: ${e.message}")
            } catch (e: OutOfMemoryError) {
                file.delete(); throw ImportException("This LUT is too big for this phone.")
            }
            prefs.edit().putString("name:${file.name}", lut.name.takeIf { it.isNotBlank() } ?: base).putInt("size:${file.name}", lut.size).apply()
            val newId = "file:${file.name}"
            synchronized(this@LutStore) { cache[newId] = lut }
            // Log-to-Rec.709 LUTs usually say so in their name.
            if (Regex("film|log|gen ?5|braw|bmd", RegexOption.IGNORE_CASE).containsMatchIn(display)) {
                prefs.edit().putString("input:$newId", LutInput.BMD_FILM_GEN5.code).apply()
            }
            newId
        }
        refresh()
        version++
        return entries.first { it.id == id }
    }

    fun delete(id: String) {
        if (!id.startsWith("file:")) return
        val name = id.removePrefix("file:")
        File(dir, name).delete()
        prefs.edit().remove("name:$name").remove("size:$name").remove("input:$id").apply()
        synchronized(this) { cache.remove(id) }
        refresh()
    }

    /** A graded copy of [source] (ARGB_8888). Runs on the caller's thread: call off the main thread. */
    fun graded(source: Bitmap, id: String?): Bitmap? {
        val lut = load(id) ?: return null
        val bmp = source.copy(Bitmap.Config.ARGB_8888, true) ?: return null
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        lut.applyToPixels(px, input(id))
        bmp.setPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        return bmp
    }
}
