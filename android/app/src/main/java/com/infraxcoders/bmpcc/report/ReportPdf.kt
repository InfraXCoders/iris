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
import com.infraxcoders.bmpcc.core.ReferenceCrop
import com.infraxcoders.bmpcc.core.ShotListLayout
import com.infraxcoders.bmpcc.core.ShotReference
import com.infraxcoders.bmpcc.platform.ReferenceImages
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
 * A4 shot list, Cadrage-style: the recce's name, date and place, the camera / lens kits (A, B…), then a card per
 * shot: the reference photo cut to the shot's frame for its current settings (what the selected camera, lens and
 * aspect ratio would show; dark only where the phone couldn't see) on the left; number, scene and shot, kit letter,
 * focal length, settings and notes on the right. Cards are measured first and never split across pages. Frame
 * guides on the pictures are optional. Then the auto-sorted notes.
 */
object ReportPdf {
    private const val W = 595
    private const val H = 842
    private const val M = 36f
    private val accent = Color.rgb(255, 85, 0)
    private val frameBlue = Color.rgb(64, 179, 255)
    private val titleBlue = Color.rgb(30, 155, 255)
    private val kitColours = listOf(Color.rgb(30, 155, 255), Color.rgb(46, 204, 113), Color.rgb(255, 149, 0), Color.rgb(175, 82, 222), Color.rgb(255, 59, 48))

    // Shot cards: the picture fits inside IMG_W x IMG_H at its own shape, details to its right.
    private const val IMG_W = 250f
    private const val IMG_H = 170f
    private const val NO_PHOTO_H = 56f
    private const val CARD_PAD = 12f
    private const val TEXT_GAP = 16f
    private const val BOTTOM = H - 40f

    private fun kitKey(sh: RecceShot) = listOf(sh.cameraModel, sh.sensorMode.name, sh.aspectRatio, sh.lens.series ?: sh.lensModel).joinToString("  |  ")

    private fun headerHeight(kits: Int, hasPlace: Boolean) = 30f + 16f + (if (hasPlace) 14f else 0f) + 8f + kits * 15f + 13f + 12f

    /** One shot card, measured before drawing. */
    private class Card(
        val scene: RecceScene, val shot: RecceShot, val kit: Int, val notes: List<RecceNote>,
        val ref: ShotReference?, val crop: ReferenceCrop.Result?, val image: ShotListLayout.Size?, val lines: List<TextLine>, val height: Float,
    )

    /** A line of the card's text column. */
    private class TextLine(val text: String, val size: Float, val color: Int, val maxLines: Int)

    /**
     * Makes the PDF. [frameGuides]: draw the frame edge and thirds on each picture. Reads and crops every photo:
     * call off the main thread.
     */
    fun make(context: Context, s: RecceSession, frameGuides: Boolean = false): File {
        val doc = PdfDocument()
        val rows = s.sortedScenes.flatMap { sc -> sc.sortedShots.map { sc to it } }
        val kits = rows.map { kitKey(it.second) }.distinct()
        val hasPlace = s.locationName.isNotBlank() || s.latitude != null
        val multiScene = s.scenes.size > 1
        val textWidth = (W - 2 * M) - IMG_W - TEXT_GAP
        // Measure every card (picture shape from the photo's size, text by layout) so cards are never split.
        val cards = rows.map { (scene, shot) ->
            val notes = s.sortedNotes.filter { n -> n.shotId == shot.id }
            val ref = shot.references.minByOrNull { it.timestamp }
            val size = ref?.let { ReferenceImages.photoSize(it) }
            val crop = if (ref != null && size != null) ReferenceCrop.forShot(ref, shot) else null
            val image = if (crop != null && size != null) ShotListLayout.fit(crop.aspect(size.first, size.second), IMG_W.toDouble(), IMG_H.toDouble()) else null
            val lines = textLines(shot, ref, crop, notes)
            val textH = titleHeight(shot, scene, multiScene, textWidth) + lines.sumOf { (measure(it.text, it.size, textWidth, it.maxLines) + 3f).toDouble() }.toFloat()
            val imgH = image?.height?.toFloat() ?: NO_PHOTO_H
            Card(scene, shot, kits.indexOf(kitKey(shot)), notes, ref, crop, image, lines, CARD_PAD + maxOf(imgH, textH) + CARD_PAD)
        }
        val pages = ShotListLayout.paginate(cards.map { it.height.toDouble() },
            (BOTTOM - M - headerHeight(kits.size, hasPlace)).toDouble(), (BOTTOM - M).toDouble())
        val total = pages.size + if (s.voiceNotes.isEmpty()) 0 else 1
        var number = 0
        fun page(draw: (Writer) -> Unit) {
            number++
            val p = doc.startPage(PdfDocument.PageInfo.Builder(W, H, number).create())
            val w = Writer(p.canvas)
            draw(w)
            w.footer("${s.projectName} · Shot list · BMPCC Control", "Page $number of $total")
            doc.finishPage(p)
        }
        pages.forEachIndexed { pg, idx ->
            page { w ->
                if (pg == 0) header(w, s, kits, hasPlace, frameGuides)
                if (rows.isEmpty()) w.line("No shots yet.", 10f, color = Color.GRAY)
                for (i in idx) shotCard(w, cards[i], multiScene, frameGuides)
            }
        }
        if (s.voiceNotes.isNotEmpty()) page { notesPage(it, s) }
        val file = File(RecceStore.exportsDir, "${RecceStore.safeName(s.projectName)}-${RecceStore.safeName(s.locationName)}-Recce.pdf")
        file.outputStream().use { doc.writeTo(it) }
        doc.close()
        return file
    }

    private fun paint(size: Float, bold: Boolean, color: Int) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size; this.color = color; typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    private fun layout(t: String, size: Float, bold: Boolean, color: Int, w: Float, maxLines: Int): StaticLayout =
        StaticLayout.Builder.obtain(t, 0, t.length, paint(size, bold, color), w.toInt().coerceAtLeast(1))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setMaxLines(maxLines)
            .setEllipsize(android.text.TextUtils.TruncateAt.END).build()

    private fun measure(t: String, size: Float, w: Float, maxLines: Int, bold: Boolean = false): Float =
        if (t.isBlank()) 0f else layout(t, size, bold, Color.BLACK, w, maxLines).height.toFloat()

    /** Simple top-to-bottom text writer for one page. */
    private class Writer(val c: Canvas) {
        var y = M
        val left = M
        val width = W - 2 * M

        fun text(t: String, size: Float = 9f, bold: Boolean = false, color: Int = Color.BLACK, x: Float = left, w: Float = width, maxLines: Int = 12): Float {
            val l = layout(t, size, bold, color, w, maxLines)
            c.save(); c.translate(x, y); l.draw(c); c.restore()
            return l.height.toFloat()
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

    /** Title, date, place (with the day's sun times) and the kits, A, B… */
    private fun header(w: Writer, s: RecceSession, kits: List<String>, hasPlace: Boolean, frameGuides: Boolean) {
        val top = w.y
        w.text(s.projectName, 22f, color = titleBlue, maxLines = 1)
        w.y = top + 30f
        w.text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(s.timestamp)), 10f, color = Color.GRAY, maxLines = 1)
        w.y += 16f
        if (hasPlace) {
            val lat = s.latitude; val lon = s.longitude
            val sun = if (lat != null && lon != null) {
                val day = Solar.day(s.timestamp, lat, lon, ZoneId.systemDefault())
                "  ·  Sunrise ${SunText.time(day.sunrise)}  ·  Sunset ${SunText.time(day.sunset)}  ·  Golden ${SunText.range(day.goldenEvening)}"
            } else ""
            w.text(s.locationName.ifBlank { fmt("%.5f, %.5f", lat ?: 0.0, lon ?: 0.0) } + sun, 8.5f, color = Color.GRAY, maxLines = 1)
            w.y += 14f
        }
        w.y += 8f
        kits.forEachIndexed { i, k ->
            badge(w.c, i, w.left + 6f, w.y + 6f)
            w.text(k, 9f, color = Color.DKGRAY, x = w.left + 18f, w = w.width - 18f, maxLines = 1)
            w.y += 15f
        }
        // Never more precise than it is: the phone's own lens data gives approximate frame lines.
        w.text("Pictures: reference photos cut to each shot's frame (dark = outside the phone's view)" +
            (if (frameGuides) ", with frame guides" else "") + ". Approximate unless marked calibrated.", 7.5f, color = Color.GRAY, maxLines = 1)
        w.y += 13f
        w.y += 12f
    }

    /** The kit letter in its colour. */
    private fun badge(c: Canvas, kit: Int, cx: Float, cy: Float) {
        val i = kit.coerceAtLeast(0)
        c.drawCircle(cx, cy, 6f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = kitColours[i % kitColours.size] })
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 7.5f; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }
        c.drawText(('A' + i).toString(), cx, cy + 2.7f, p)
    }

    /** Height of the card's title line (number and title, beside the kit letter and focal length). */
    private fun titleHeight(shot: RecceShot, scene: RecceScene, multiScene: Boolean, textWidth: Float): Float {
        val fw = paint(10f, false, Color.BLACK).measureText(shot.focalLength.ifBlank { "—" })
        return maxOf(measure(number(scene, shot, multiScene), 10f, 34f, 1, true), measure(title(scene, shot), 10f, textWidth - 34f - fw - 26f, 2)) + 6f
    }

    private fun number(scene: RecceScene, shot: RecceShot, multiScene: Boolean) = if (multiScene) "${scene.sceneNumber}.${shot.shotNumber}" else shot.shotNumber
    private fun title(scene: RecceScene, shot: RecceShot) = "${shot.shotType.longName} · ${scene.heading}"

    /** The card's text below the title: settings, extras, notes, where the picture comes from. */
    private fun textLines(shot: RecceShot, ref: ShotReference?, crop: ReferenceCrop.Result?, notes: List<RecceNote>): List<TextLine> {
        val out = mutableListOf<TextLine>()
        fun add(t: String, lines: Int, color: Int = Color.DKGRAY, size: Float = 8f) { if (t.isNotBlank()) out += TextLine(t, size, color, lines) }
        add(listOf(shot.lensModel, shot.aspectRatio, shot.aperture.ifBlank { null }, shot.cameraMovement.label)
            .filterNotNull().filter { it.isNotBlank() }.joinToString(" · "), 2)
        add(listOf("${shot.fps}p", shot.shutter, "ISO ${shot.iso}", shot.nd.takeIf { it.isNotBlank() && it != "None" }, shot.whiteBalance)
            .filterNotNull().joinToString(" · "), 1, Color.GRAY)
        add(listOfNotNull(
            shot.lut?.let { "LUT " + (com.infraxcoders.bmpcc.data.LutStore.nameOf(it) ?: it) },
            shot.plannedTime?.let { "Planned " + com.infraxcoders.bmpcc.ui.clockText(it) },
            shot.subjectPosition.takeIf { it.isNotBlank() }?.let { "Subject: $it" },
            shot.cameraPosition.takeIf { it.isNotBlank() }?.let { "Camera: $it" },
        ).joinToString(" · "), 2, Color.GRAY)
        add((listOf(shot.notes) + notes.map { it.rawTranscription }).filter { it.isNotBlank() }.joinToString("  ·  "), 4, Color.BLACK)
        if (ref != null && crop != null) {
            add(listOfNotNull(
                ReferenceImages.basisText(ref, shot, crop),
                if (ref.calibrated == true) "calibrated frame" else null,
            ).joinToString(" · "), 2, Color.GRAY, 7.5f)
        }
        val more = shot.references.size - 1
        if (more > 0) add("+ $more more reference photo${if (more == 1) "" else "s"} in the app", 1, Color.GRAY, 7.5f)
        return out
    }

    /** One shot card: the picture on the left, its details on the right. */
    private fun shotCard(w: Writer, card: Card, multiScene: Boolean, frameGuides: Boolean) {
        val shot = card.shot
        val rule = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(200, 200, 205); strokeWidth = 0.6f }
        w.c.drawLine(w.left, w.y, w.left + w.width, w.y, rule)
        val top = w.y + CARD_PAD
        picture(w.c, card, w.left, top, frameGuides)
        val tx = w.left + IMG_W + TEXT_GAP
        val tw = w.width - IMG_W - TEXT_GAP
        val right = w.left + w.width
        // Right: kit letter and focal length.
        val focalPaint = paint(10f, false, Color.BLACK)
        val focal = shot.focalLength.ifBlank { "—" }
        val fw = focalPaint.measureText(focal)
        w.c.drawText(focal, right - fw, top + 9f, focalPaint)
        badge(w.c, card.kit, right - fw - 12f, top + 5.5f)
        // Number and title, then the details.
        w.y = top
        w.text(number(card.scene, shot, multiScene), 10f, true, x = tx, w = 34f, maxLines = 1)
        w.text(title(card.scene, shot), 10f, x = tx + 34f, w = tw - 34f - fw - 26f, maxLines = 2)
        w.y = top + titleHeight(shot, card.scene, multiScene, tw)
        for (l in card.lines) w.y += w.text(l.text, l.size, color = l.color, x = tx, w = tw, maxLines = l.maxLines) + 3f
        w.y = top - CARD_PAD + card.height
    }

    /**
     * The shot's picture: its reference photo cut to the frame at the frame's own shape, fitted in the card; or, with
     * no photo, a plain "No reference photo" box (nothing is made up).
     */
    private fun picture(c: Canvas, card: Card, left: Float, top: Float, frameGuides: Boolean) {
        val ref = card.ref; val img = card.image
        val bmp = if (ref != null && img != null) ReferenceImages.cropped(ref, card.shot, 1200)?.bitmap else null
        if (bmp == null || img == null) {
            val box = RectF(left, top, left + IMG_W, top + NO_PHOTO_H)
            c.drawRect(box, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 0.8f; color = Color.LTGRAY })
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.GRAY; textSize = 8.5f; textAlign = Paint.Align.CENTER }
            c.drawText(if (ref == null) "No reference photo" else "Reference photo not on this phone", box.centerX(), box.centerY() + 3f, p)
            return
        }
        val dst = RectF(left, top, left + img.width.toFloat(), top + img.height.toFloat())
        c.drawBitmap(bmp, null, dst, Paint(Paint.FILTER_BITMAP_FLAG))
        if (frameGuides) {
            // The frame's edge, thirds and centre on the picture (the picture is the frame).
            val g = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 0.6f; color = Color.argb(150, 255, 255, 255) }
            for (i in 1..2) {
                val x = dst.left + dst.width() * i / 3; val y = dst.top + dst.height() * i / 3
                c.drawLine(x, dst.top, x, dst.bottom, g); c.drawLine(dst.left, y, dst.right, y, g)
            }
            val cx = dst.centerX(); val cy = dst.centerY()
            c.drawLine(cx - 6f, cy, cx + 6f, cy, g); c.drawLine(cx, cy - 6f, cx, cy + 6f, g)
            c.drawRect(dst, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.2f; color = frameBlue })
        } else {
            c.drawRect(dst, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 0.5f; color = Color.rgb(190, 190, 195) })
        }
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
