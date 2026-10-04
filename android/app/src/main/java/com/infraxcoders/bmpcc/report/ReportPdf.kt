package com.infraxcoders.bmpcc.report

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.infraxcoders.bmpcc.core.NotesSorter
import com.infraxcoders.bmpcc.core.RecceNote
import com.infraxcoders.bmpcc.core.RecceScene
import com.infraxcoders.bmpcc.core.RecceSession
import com.infraxcoders.bmpcc.core.RecceShot
import com.infraxcoders.bmpcc.core.Solar
import com.infraxcoders.bmpcc.data.RecceStore
import com.infraxcoders.bmpcc.platform.Images
import com.infraxcoders.bmpcc.ui.SunText
import com.infraxcoders.bmpcc.ui.degreesText
import com.infraxcoders.bmpcc.ui.fmt
import java.io.File
import java.text.DateFormat
import java.time.ZoneId
import java.util.Date
import kotlin.math.min

/**
 * A4 recce report: a summary page (location, sun, director and location notes, scenes), one page per shot
 * (reference photo with frame lines, settings, markers, notes) and a sorted-notes page.
 */
object ReportPdf {
    private const val W = 595
    private const val H = 842
    private const val M = 36f
    private val accent = Color.rgb(255, 85, 0)
    private val frameBlue = Color.rgb(64, 179, 255)

    fun pageCount(s: RecceSession): Int = 1 + s.shotCount + if (s.voiceNotes.isEmpty()) 0 else 1

    fun make(context: Context, s: RecceSession): File {
        val doc = PdfDocument()
        val total = pageCount(s)
        var number = 0
        fun page(draw: (Writer) -> Unit) {
            number++
            val p = doc.startPage(PdfDocument.PageInfo.Builder(W, H, number).create())
            val w = Writer(p.canvas)
            draw(w)
            w.footer("${s.projectName} · Recce report · BMPCC Control", "Page $number of $total")
            doc.finishPage(p)
        }
        page { summary(it, s) }
        for (scene in s.sortedScenes) for (shot in scene.sortedShots) {
            page { shotPage(it, scene, shot, s.sortedNotes.filter { n -> n.shotId == shot.id }) }
        }
        if (s.voiceNotes.isNotEmpty()) page { notesPage(it, s) }
        val file = File(RecceStore.exportsDir, "${RecceStore.safeName(s.projectName)}-${RecceStore.safeName(s.locationName)}-Recce.pdf")
        file.outputStream().use { doc.writeTo(it) }
        doc.close()
        return file
    }

    /** Simple top-to-bottom text writer for one page. */
    private class Writer(val c: Canvas) {
        var y = M
        val left = M
        val width = W - 2 * M

        fun text(t: String, size: Float = 9f, bold: Boolean = false, color: Int = Color.BLACK, x: Float = left, w: Float = width, maxLines: Int = 12): Float {
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = size; this.color = color; typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            }
            val layout = StaticLayout.Builder.obtain(t, 0, t.length, paint, w.toInt())
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setMaxLines(maxLines)
                .setEllipsize(android.text.TextUtils.TruncateAt.END).build()
            c.save(); c.translate(x, y); layout.draw(c); c.restore()
            return layout.height.toFloat()
        }

        fun line(t: String, size: Float = 9f, bold: Boolean = false, color: Int = Color.BLACK, maxLines: Int = 12) {
            y += text(t, size, bold, color, maxLines = maxLines) + 2
        }

        fun heading(t: String) { y += 8; line(t.uppercase(), 8f, true, accent) }

        fun kv(k: String, v: String, x: Float = left, w: Float = width) {
            val h1 = text(k, 9f, color = Color.GRAY, x = x, w = 105f)
            val h2 = text(v.ifEmpty { "—" }, 9f, x = x + 110f, w = w - 110f, maxLines = 3)
            y += maxOf(h1, h2) + 2
        }

        fun footer(l: String, r: String) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 7f; color = Color.GRAY }
            c.drawText(l, left, H - 20f, p)
            c.drawText(r, W - M - p.measureText(r), H - 20f, p)
        }
    }

    private fun summary(w: Writer, s: RecceSession) {
        w.line(s.projectName, 22f, true)
        w.line("Recce · ${s.locationName}", 13f)
        w.line(DateFormat.getDateTimeInstance(DateFormat.FULL, DateFormat.SHORT).format(Date(s.timestamp)), 10f, color = Color.GRAY)
        w.heading("Location")
        w.kv("Place", s.locationName)
        val lat = s.latitude
        val lon = s.longitude
        if (lat != null && lon != null) {
            w.kv("Coordinates", fmt("%.5f, %.5f", lat, lon))
            val zone = ZoneId.systemDefault()
            val day = Solar.day(s.timestamp, lat, lon, zone)
            w.heading("Sun (${zone.id})")
            w.kv("Sunrise / sunset", "${SunText.time(day.sunrise)} / ${SunText.time(day.sunset)}")
            w.kv("Solar noon", "${SunText.time(day.solarNoon)}, ${day.noonElevation.degreesText()} high")
            w.kv("Golden hour", "${SunText.range(day.goldenMorning)}  ·  ${SunText.range(day.goldenEvening)}")
            w.kv("Blue hour", "${SunText.range(day.blueMorning)}  ·  ${SunText.range(day.blueEvening)}")
        }
        w.heading("Director / DoP notes")
        w.line(s.directorDopNotes.ifEmpty { "—" }, maxLines = 10)
        w.heading("Location notes")
        w.line(s.generalLocationNotes.ifEmpty { "—" }, maxLines = 10)
        w.heading("Scenes")
        if (s.scenes.isEmpty()) w.line("No scenes yet.", color = Color.GRAY)
        s.sortedScenes.take(20).forEach { sc -> w.kv("Scene ${sc.sceneNumber}", "${sc.heading} · ${sc.lightingCondition.label} · ${sc.shots.size} shot(s)") }
        if (s.scenes.size > 20) w.line("+ ${s.scenes.size - 20} more scenes", 8f, color = Color.GRAY)
    }

    private fun shotPage(w: Writer, scene: RecceScene, shot: RecceShot, notes: List<RecceNote>) {
        w.line("Scene ${scene.sceneNumber} · Shot ${shot.shotNumber}", 16f, true)
        w.line("${scene.heading} · ${shot.shotType.longName} · ${shot.cameraMovement.label}", 10f, color = Color.GRAY)
        val refs = shot.references.sortedBy { it.timestamp }
        val ref = refs.firstOrNull()
        val bmp = ref?.let { Images.load(File(RecceStore.referencesDir, it.fileName), 1400) }
        w.y += 6
        if (ref != null && bmp != null) {
            val maxH = 300f
            val scale = min(w.width / bmp.width, maxH / bmp.height)
            val dw = bmp.width * scale
            val dh = bmp.height * scale
            val dx = w.left + (w.width - dw) / 2
            val dst = RectF(dx, w.y, dx + dw, w.y + dh)
            w.c.drawBitmap(bmp, null, dst, Paint(Paint.FILTER_BITMAP_FLAG))
            val fx = ref.frameX; val fy = ref.frameY; val fw = ref.frameWidth; val fh = ref.frameHeight
            if (fx != null && fy != null && fw != null && fh != null) {
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f; color = frameBlue }
                w.c.drawRect(dx + (fx * dw).toFloat(), dst.top + (fy * dh).toFloat(), dx + ((fx + fw) * dw).toFloat(), dst.top + ((fy + fh) * dh).toFloat(), p)
            }
            w.y += dh + 4
            if (refs.size > 1) w.line("+ ${refs.size - 1} more reference photo(s) in the app", 8f, color = Color.GRAY)
        } else {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.LTGRAY }
            w.c.drawRect(w.left, w.y, w.left + w.width, w.y + 80, p)
            w.text("No reference photo", 9f, color = Color.GRAY, x = w.left + 8, w = 200f)
            w.y += 86
        }
        val top = w.y
        val colW = (w.width - 16) / 2
        // Left column
        w.heading("Camera & lens")
        w.kv("Camera", shot.cameraModel, w.left, colW)
        if (shot.baseCamera.sensorModes.size > 1) w.kv("Recording mode", shot.sensorMode.name, w.left, colW)
        w.kv("Lens", shot.lensModel, w.left, colW)
        w.kv("Focal length", shot.focalLength, w.left, colW)
        w.kv("Aperture", shot.aperture, w.left, colW)
        w.kv("Aspect ratio", shot.aspectRatio, w.left, colW)
        shot.reference?.let { w.kv("Field of view", "${it.deliveredFov.horizontal.degreesText()} × ${it.deliveredFov.vertical.degreesText()}", w.left, colW) }
        w.heading("Exposure")
        w.kv("Frame rate", shot.fps, w.left, colW)
        w.kv("Shutter", shot.shutter, w.left, colW)
        w.kv("ISO", shot.iso, w.left, colW)
        w.kv("ND", shot.nd, w.left, colW)
        w.kv("White balance", shot.whiteBalance, w.left, colW)
        val leftBottom = w.y
        // Right column
        w.y = top
        val rx = w.left + colW + 16
        w.y += 8; w.y += w.text("POSITION", 8f, true, accent, x = rx, w = colW) + 2
        w.kv("Camera", shot.cameraPosition, rx, colW)
        w.kv("Subject", shot.subjectPosition, rx, colW)
        w.kv("Height", shot.cameraHeight, rx, colW)
        w.kv("Distance", shot.estimatedDistance?.let { fmt("%.1f m", it) } ?: "", rx, colW)
        w.kv("Subject moves", shot.subjectMovement, rx, colW)
        if (shot.markers.isNotEmpty()) {
            w.y += 8; w.y += w.text("MARKERS", 8f, true, accent, x = rx, w = colW) + 2
            shot.markers.take(8).forEach { m -> w.kv(m.type.label, "${(m.x * 100).toInt()}% across, ${(m.y * 100).toInt()}% down", rx, colW) }
        }
        w.y = maxOf(leftBottom, w.y)
        w.heading("Notes")
        w.line(shot.notes.ifEmpty { "—" }, maxLines = 6)
        notes.take(6).forEach { w.line("• ${it.rawTranscription}", maxLines = 3) }
    }

    private fun notesPage(w: Writer, s: RecceSession) {
        val summary = NotesSorter.summarize(s.sortedNotes.map { it.rawTranscription })
        w.line("Recce notes, auto-sorted", 16f, true)
        w.line("Sorted by keywords (English, Hindi and Hinglish). Notes can appear in more than one group.", 9f, color = Color.GRAY)
        fun group(title: String, items: List<String>) {
            w.heading(title)
            items.take(12).forEach { w.line("• $it", maxLines = 3) }
            if (items.size > 12) w.line("+ ${items.size - 12} more", 8f, color = Color.GRAY)
        }
        group("Composition", summary.composition)
        group("Lighting", summary.lighting)
        group("Movement", summary.movement)
        group("All notes", summary.general)
    }
}
