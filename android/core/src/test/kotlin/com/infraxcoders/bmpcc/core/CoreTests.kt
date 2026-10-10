package com.infraxcoders.bmpcc.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.max

class OpticsTest {
    @Test fun fovMatchesAndroidForEveryCameraAndFocalLength() {
        for (g in Fixtures.list(Fixtures.android, "fov")) {
            val id = g.s("camera")
            val fov = if (id == "zero") Optics.fieldOfView(0.0, 23.1, 12.99) else {
                val c = BuiltInLibrary.camera(id)!!
                Optics.fieldOfView(g.d("focal"), c.sensorWidthMm, c.sensorHeightMm)
            }
            assertEquals("$id ${g.d("focal")}", g.d("h"), fov.horizontal, 1e-9)
            assertEquals(g.d("v"), fov.vertical, 1e-9)
            assertEquals(g.d("d"), fov.diagonal, 1e-9)
        }
    }

    @Test fun equivalentFocalMatchesAndroid() {
        for (g in Fixtures.list(Fixtures.android, "equivalentFocal")) {
            val c = BuiltInLibrary.camera(g.s("camera"))!!
            assertEquals(g.d("equivalent"), Optics.equivalentFocalLength(18.0, c.sensorWidthMm), g.d("equivalent") * 1e-4)
        }
    }

    @Test fun knownValues() {
        val f = Optics.fieldOfView(18.0, 23.10, 12.99)
        assertEquals(65.37, f.horizontal, 0.01)
        assertEquals(1.558, Optics.cropFactor(23.10), 0.001)
        val ff = Optics.fieldOfView(50.0, 36.0, 24.0)
        assertEquals(39.6, ff.horizontal, 0.05)
        assertEquals(46.8, ff.diagonal, 0.05)
    }

    @Test fun halfTanRoundTrip() {
        var a = 1.0
        while (a <= 170) { assertEquals(a, Optics.fovFromHalfTan(Optics.halfTan(a)), 1e-9); a += 7 }
    }
}

class FramingTest {
    private val bm = BuiltInLibrary.camera("bmpcc_6k_pro")!!
    private val sigma = BuiltInLibrary.lens("sigma_18_35_art")!!

    @Test fun androidHorizontalAndVerticalAgreeForSphericalLenses() {
        for (g in Fixtures.list(Fixtures.android, "androidFraming")) {
            if (g.s("lens") == "atlas_orion_40") continue
            val r = Framing.reference(BuiltInLibrary.camera(g.s("camera"))!!, BuiltInLibrary.lens(g.s("lens"))!!, g.d("focal"))!!
            assertEquals(g.d("hFov"), r.deliveredFov.horizontal, 1e-9)
            assertEquals(g.d("vFov"), r.deliveredFov.vertical, 1e-9)
            assertEquals(g.d("cropFactor"), r.cropFactor, 1e-9)
        }
    }

    @Test fun diagonalAndAnamorphicFixed() {
        val r = Framing.reference(bm, sigma, 18.0)!!
        assertEquals(76.47, Fixtures.list(Fixtures.android, "androidFraming")[0].d("diagonal"), 0.01)
        assertEquals(72.72, r.deliveredFov.diagonal, 0.01)
        val a = Framing.reference(BuiltInLibrary.camera("arri_alexa_35")!!, BuiltInLibrary.lens("atlas_orion_40")!!, 40.0)!!
        assertEquals(69.96, a.captureFov.horizontal, 0.01)
        assertEquals(55.98 / 19.22, a.deliveredAspect, 1e-9)
    }

    @Test fun aspectMasks() {
        val wide = Framing.reference(bm, sigma, 24.0, aspectRatio = 2.39)!!
        assertEquals(23.10, wide.deliveredWidthMm, 1e-9)
        assertEquals(23.10 / 2.39, wide.deliveredHeightMm, 1e-9)
        val square = Framing.reference(bm, sigma, 24.0, aspectRatio = 1.0)!!
        assertEquals(12.99, square.deliveredWidthMm, 1e-9)
        assertNull(Framing.reference(23.0, 13.0, 0.0))
    }

    @Test fun frameLines() {
        val r = Framing.reference(bm, sigma, 18.0)!!
        assertEquals(0.9166, Viewfinder.frameLines(r, PreviewView(70.0, 40.0)).widthFraction, 0.0005)
        val phone = PreviewView(70.0, 55.0)
        val w18 = Viewfinder.frameLines(r, phone).widthFraction
        val w35 = Viewfinder.frameLines(Framing.reference(bm, sigma, 35.0)!!, phone).widthFraction
        assertEquals(18.0 / 35.0, w35 / w18, 1e-9)
        val r35 = Framing.reference(bm, sigma, 35.0)!!
        assertEquals(Viewfinder.frameLines(r35, phone).widthFraction * 1.5, Viewfinder.frameLines(r35, phone.zoomed(1.5)).widthFraction, 1e-9)
    }

    @Test fun bestZoom() {
        val r = Framing.reference(bm, sigma, 35.0, aspectRatio = 2.39)!!
        val base = PreviewView(70.0, 55.0)
        val z = Viewfinder.bestZoom(r, base, 1.0, 10.0, 1.1)
        val lines = Viewfinder.frameLines(r, base.zoomed(z))
        assertTrue(lines.fits)
        assertEquals(1 / 1.1, max(lines.widthFraction, lines.heightFraction), 1e-9)
        val lf = Framing.reference(BuiltInLibrary.camera("arri_alexa_lf")!!, BuiltInLibrary.lens("arri_signature_18")!!, 18.0)!!
        assertEquals(1.0, Viewfinder.bestZoom(lf, base, 1.0, 10.0), 0.0)
        assertFalse(Viewfinder.frameLines(lf, base).fits)
    }

    @Test fun portraitSwapsAxes() {
        val land = PreviewView.fromFormat(70.0, 16.0 / 9.0, false)
        val port = PreviewView.fromFormat(70.0, 16.0 / 9.0, true)
        assertEquals(70.0, land.horizontalFov, 0.0)
        assertEquals(70.0, port.verticalFov, 0.0)
        assertEquals(land.verticalFov, port.horizontalFov, 0.0)
    }

    @Test fun screenGeometry() {
        val p = Viewfinder.aspectFit(4.0 / 3.0, 844.0, 390.0)
        assertEquals(520.0, p.width, 1e-9); assertEquals(162.0, p.x, 1e-9)
        val l = Viewfinder.aspectFit(4.0 / 3.0, 390.0, 844.0)
        assertEquals(292.5, l.height, 1e-9); assertEquals(275.75, l.y, 1e-9)
        val video = ScreenRect(100.0, 0.0, 400.0, 300.0)
        assertEquals(ScreenRect(200.0, 112.5, 200.0, 75.0), Viewfinder.frameRect(FrameLines(0.5, 0.25), video))
        assertEquals(400.0, Viewfinder.frameRect(FrameLines(1.4, 0.6), video).width, 0.0)
        val f = ScreenRect(0.0, 0.0, 200.0, 100.0)
        assertEquals(ScreenRect(10.0, 5.0, 180.0, 90.0), Viewfinder.safeArea(f, 0.9))
        assertEquals(0.25 to 0.75, Viewfinder.normalisedPoint(50.0, 75.0, f))
        assertNull(Viewfinder.normalisedPoint(201.0, 10.0, f))
    }

    @Test fun endToEndViewfinderLayout() {
        val ref = Framing.reference(bm, sigma, 35.0, aspectRatio = 2.39)!!
        val base = PreviewView.fromFormat(70.0, 4.0 / 3.0, false)
        val zoom = Viewfinder.bestZoom(ref, base, 1.0, 6.0)
        val lines = Viewfinder.frameLines(ref, base.zoomed(zoom))
        val video = Viewfinder.aspectFit(4.0 / 3.0, 844.0, 390.0)
        val frame = Viewfinder.frameRect(lines, video)
        assertTrue(lines.fits)
        assertEquals(2.39, frame.width / frame.height, 0.03)
        assertTrue(zoom > 1)
    }
}

class LibraryTest {
    @Test fun camerasMatchAndroidSeeder() {
        val golden = Fixtures.list(Fixtures.android, "cameras")
        assertEquals(golden.size, BuiltInLibrary.cameras.size)
        for ((g, c) in golden.zip(BuiltInLibrary.cameras)) {
            assertEquals(g.s("id"), c.id)
            assertEquals(g.s("model"), c.model)
            assertEquals(g.d("sensorWidthMm"), c.sensorWidthMm, 0.0)
            assertEquals(g.d("sensorHeightMm"), c.sensorHeightMm, 0.0)
            assertEquals(g.s("mount"), c.mount.label)
            assertEquals(g.s("verificationStatus"), c.verificationStatus.name)
            assertEquals(g.s("sensorFormatId"), c.sensorFormatId)
        }
    }

    @Test fun lensesMatchAndroidSeeder() {
        val golden = Fixtures.list(Fixtures.android, "lenses")
        assertEquals(golden.size, BuiltInLibrary.lenses.size)
        for ((g, l) in golden.zip(BuiltInLibrary.lenses)) {
            assertEquals(g.s("id"), l.id)
            assertEquals(g.s("model"), l.model)
            assertEquals(g.s("mount"), l.mount.label)
            assertEquals(g.s("lensType"), l.lensType.name)
            assertEquals(g.d("focalLengthMin"), l.focalLengthMin, 0.0)
            assertEquals(g.d("focalLengthMax"), l.focalLengthMax, 0.0)
            @Suppress("UNCHECKED_CAST")
            assertEquals((g["availableFocalLengths"] as List<Number>).map { it.toDouble() }, l.availableFocalLengths)
            assertEquals(g.d("maximumAperture"), l.maximumAperture, 0.0)
            assertEquals(g.d("minimumFocusDistance"), l.minimumFocusDistance, 0.0)
            assertEquals(g.d("anamorphicSqueeze"), l.anamorphicSqueeze, 0.0)
            assertEquals(g.d("imageCircleMm"), l.imageCircleMm, 0.0)
        }
    }

    @Test fun compatibilityMatchesAndroid() {
        for (g in Fixtures.list(Fixtures.android, "compatibility")) {
            val c = BuiltInLibrary.camera(g.s("camera"))!!
            val l = BuiltInLibrary.lens(g.s("lens"))!!
            assertEquals("${c.id} ${l.id}", g.s("status"), Compatibility.check(c, l, g["adapter"] as Boolean).name)
        }
    }

    @Test fun search() {
        assertEquals(listOf("arri_alexa_35", "arri_alexa_lf", "arri_alexa_mini"), BuiltInLibrary.searchCameras("arri").map { it.id })
        assertEquals(listOf("bmpcc_6k_pro"), BuiltInLibrary.searchCameras("pocket 6k").map { it.id })
        assertEquals(2, BuiltInLibrary.searchLenses("SIGMA").size)
        assertTrue(BuiltInLibrary.searchLenses("zzz").isEmpty())
        assertEquals(listOf(18.0, 20.0, 24.0, 28.0, 32.0, 35.0), BuiltInLibrary.lens("sigma_18_35_art")!!.quickFocalLengths)
    }
}

class NotesSorterTest {
    @Test fun everyNoteSortsLikeAndroid() {
        for (g in Fixtures.list(Fixtures.android, "notes")) {
            val text = g.s("text")
            val s = NotesSorter.sort(text)
            assertEquals(text, g.s("language"), s.language)
            assertEquals("composition: $text", g.optS("composition"), s.composition)
            assertEquals("lighting: $text", g.optS("lighting"), s.lighting)
            assertEquals("focal: $text", g.optS("focal"), s.focalLength)
            assertEquals(g.optS("general"), s.general)
        }
    }

    @Suppress("UNCHECKED_CAST")
    @Test fun summaryMatchesAndroid() {
        val texts = Fixtures.list(Fixtures.android, "notes").map { it.s("text") }
        val g = Fixtures.android["summary"] as Map<String, List<String>>
        val s = NotesSorter.summarize(texts)
        assertEquals(g["composition"], s.composition)
        assertEquals(g["lighting"], s.lighting)
        assertEquals(g["movement"], s.movement)
        assertEquals(g["general"], s.general)
        val e = Fixtures.android["emptySummary"] as Map<String, List<String>>
        assertEquals(e["composition"], NotesSorter.summarize(emptyList()).composition)
    }

    @Test fun languages() {
        assertEquals("English", NotesSorter.detectLanguage("Frame it wide"))
        assertEquals("Hinglish", NotesSorter.detectLanguage("yahan camera rakho"))
        assertEquals("Hindi", NotesSorter.detectLanguage("कैमरा यहाँ"))
    }
}

class SolarTest {
    @Test fun positionsMatchReference() {
        for (g in Fixtures.list(Fixtures.sun, "positions")) {
            val p = Solar.position((g.d("epoch") * 1000).toLong(), g.d("lat"), g.d("lon"))
            assertEquals("${g.s("place")} ${g.d("epoch")}", g.d("elevation"), p.elevation, 0.05)
            if (g.d("elevation") > -85) {
                val diff = abs(((p.azimuth - g.d("azimuth")) + 540) % 360 - 180)
                assertTrue("${g.s("place")} azimuth", diff < 0.1)
            }
        }
    }

    @Test fun dayEventsMatchReference() {
        for (g in Fixtures.list(Fixtures.sun, "events")) {
            val day = Solar.day((g.d("noon") * 1000).toLong(), g.d("lat"), g.d("lon"), ZoneId.of(g.s("tz")))
            val label = "${g.s("place")} ${g.s("date")}"
            fun close(v: Long?, key: String, tolSec: Double) {
                assertNotNull("$label $key missing", v)
                assertEquals("$label $key", g.d(key), v!! / 1000.0, tolSec)
            }
            close(day.sunrise, "sunrise", 60.0)
            close(day.sunset, "sunset", 60.0)
            close(day.solarNoon, "noon", 30.0)
            close(day.goldenMorning?.start, "goldenMorningStart", 180.0)
            close(day.goldenMorning?.end, "goldenMorningEnd", 180.0)
            close(day.goldenEvening?.start, "goldenEveningStart", 180.0)
            close(day.goldenEvening?.end, "goldenEveningEnd", 180.0)
            close(day.blueEvening?.start, "blueEveningStart", 180.0)
            close(day.blueEvening?.end, "blueEveningEnd", 180.0)
        }
    }

    @Test fun delhiMidsummerAndPolar() {
        val ist = ZoneId.of("Asia/Kolkata")
        val noon = LocalDateTime.of(2026, 6, 21, 12, 0).atZone(ist).toInstant().toEpochMilli()
        val day = Solar.day(noon, 28.6139, 77.2090, ist)
        fun hm(ms: Long) = java.time.Instant.ofEpochMilli(ms).atZone(ist).toLocalTime().toString().substring(0, 5)
        assertEquals("19:21", hm(day.sunset!!))
        assertTrue(hm(day.goldenEvening!!.start) in listOf("18:47", "18:48"))
        val utc = ZoneId.of("UTC")
        val winter = LocalDateTime.of(2026, 12, 21, 12, 0).atZone(utc).toInstant().toEpochMilli()
        assertTrue(Solar.day(winter, 78.22, 15.65, utc).isPolarNight)
        val summer = LocalDateTime.of(2026, 6, 21, 12, 0).atZone(utc).toInstant().toEpochMilli()
        assertTrue(Solar.day(summer, 78.22, 15.65, utc).isPolarDay)
        assertEquals("North-East", Solar.compass(22.5))
        assertEquals("West", Solar.compass(-90.0))
    }
}

class CatalogTest {
    @Test fun csvParser() {
        assertEquals(listOf(listOf("a", "b", "c"), listOf("1", "x, y", "say \"hi\""), listOf("2", "", "3")),
            Csv.parse("a,b,c\n1,\"x, y\",\"say \"\"hi\"\"\"\n\n2,,3\n"))
    }

    @Test fun databaseLoadsAndIsSane() {
        assertTrue(Catalog.databaseLenses.size > 250)
        assertTrue(Catalog.databaseCameras.size > 45)
        assertEquals(Catalog.lenses.size, Catalog.lenses.map { it.id }.toSet().size)
        assertEquals(Catalog.cameras.size, Catalog.cameras.map { it.id }.toSet().size)
        assertTrue(Catalog.databaseLenses.all { (it.sourceUrl ?: "").startsWith("http") })
        for (l in Catalog.databaseLenses) {
            assertTrue(l.id, l.focalLengthMin in 4.0..400.0 && l.focalLengthMax in l.focalLengthMin..2000.0)
            assertTrue(l.id, l.anamorphicSqueeze in 1.0..2.1)
        }
        for (c in Catalog.databaseCameras) {
            val first = c.sensorModes[0]
            assertTrue(c.id, c.sensorModes.all { it.widthMm * it.heightMm <= first.widthMm * first.heightMm + 0.01 })
        }
    }

    @Test fun zoomsAndPhotoLenses() {
        val z = Catalog.lens("fujinon_premista_28_100")!!
        assertEquals("Premista 28-100mm T2.9", z.model)
        assertEquals(LensType.ZOOM, z.lensType)
        assertTrue(z.isZoom)
        assertEquals(100.0, z.focalLengthMax, 0.0)
        assertTrue(FocalOptions.focals(z).let { it.first() == 28.0 && it.last() == 100.0 && 50.0 in it })
        val p = Catalog.lens("sigma_art_18_35_f18")!!
        assertEquals("Art DC HSM 18-35mm f/1.8", p.model)
        assertEquals(1.8, p.maximumAperture, 0.0)
        val prime = Catalog.lens("arri_signature_prime_47")!!
        assertEquals("Signature Prime 47mm T1.8", prime.model)
        assertTrue(FocalOptions.focals(prime).size >= 10)
    }

    @Test fun knownLensAndCoverage() {
        val l = Catalog.lens("arri_zeiss_master_anamorphic_50")!!
        assertEquals("Master Anamorphic 50mm T1.9", l.model)
        assertEquals(LensType.ANAMORPHIC, l.lensType)
        assertEquals("Orion", Catalog.lens("atlas_orion_40")!!.series)
        val cam = Catalog.camera("arri_alexa_35")!!
        assertNotEquals(Coverage.FULL, Coverage.evaluate(l, cam.sensorModes[0]))
        assertTrue(Coverage.largestCoveredMode(l, cam)!!.diagonalMm <= 29.27)
        assertEquals(Coverage.FULL, Coverage.evaluate(43.3, 36.0, 24.0))
        assertEquals(Coverage.CORNERS_VIGNETTE, Coverage.evaluate(40.0, 36.0, 24.0))
        assertEquals(Coverage.VIGNETTES, Coverage.evaluate(31.0, 36.0, 24.0))
        val used = cam.using(cam.sensorModes.last().id)
        assertEquals(cam.sensorModes.last().widthMm, used.sensorWidthMm, 0.0)
        assertEquals("T1.9", ShotPresets.tStopText(1.9))
        assertEquals("T2.0", ShotPresets.tStopText(2.0))
    }
}

class RecceJsonTest {
    private val androidJson = """
    {"id":"s1","locationName":"Old Fort","timestamp":1790000000000,"latitude":28.61,"longitude":77.24,
     "projectName":"Short Film","directorDopNotes":"Moody","generalLocationNotes":"Power at gate",
     "scenes":[{"id":"sc1","sessionId":"s1","sceneNumber":"4A","isInterior":false,"isDay":true,
       "locationDescription":"Courtyard","timeOfDay":"17:30","lightingCondition":"GOLDEN_HOUR","notes":"",
       "shots":[{"id":"sh1","sceneId":"sc1","shotNumber":"1","shotType":"TWO_SHOT","cameraPosition":"North wall",
         "subjectPosition":"Fountain","cameraHeight":"1.5m","cameraModel":"Pocket Cinema Camera 6K Pro",
         "lensModel":"18-35mm f/1.8 Art","focalLength":"24mm","aperture":"f/2.8","aspectRatio":"2.39:1","fps":"24",
         "shutter":"180°","iso":"400","nd":"None","whiteBalance":"5600K","cameraMovement":"PUSH_IN",
         "subjectMovement":"Walks in","estimatedDistance":3.5,"notes":"",
         "references":[{"id":"r1","shotId":"sh1","filePath":"/storage/x.jpg","timestamp":1790000000500}],
         "analysis":null,
         "markers":[{"id":"m1","shotId":"sh1","type":"KEY_LIGHT","x":0.25,"y":0.75,"label":null}],
         "creationTimestamp":1790000000000,"modificationTimestamp":1790000001000}]}],
     "creationTimestamp":1790000000000,"modificationTimestamp":1790000002000}
    """

    @Test fun decodesOldAndroidExport() {
        val s = RecceJson.decode(androidJson)
        assertEquals("Short Film", s.projectName)
        assertTrue(s.voiceNotes.isEmpty())
        val sc = s.scenes[0]
        assertEquals(LightingCondition.GOLDEN_HOUR, sc.lightingCondition)
        val sh = sc.shots[0]
        assertEquals(ShotType.TWO_SHOT, sh.shotType)
        assertEquals(CameraMovement.PUSH_IN, sh.cameraMovement)
        assertEquals(MarkerType.KEY_LIGHT, sh.markers[0].type)
        assertEquals("x.jpg", sh.references[0].fileName)
        assertEquals(24.0, sh.focalMm, 0.0)
        assertEquals("bmpcc_6k_pro", sh.baseCamera.id)
        assertNotNull(sh.reference)
    }

    @Test fun roundTrip() {
        val s = RecceJson.decode(androidJson)
        val withNote = s.copy(voiceNotes = listOf(RecceNote(sessionId = s.id, shotId = "sh1", rawTranscription = "khidki se roshni", timestamp = 5)))
        val again = RecceJson.decode(RecceJson.encode(withNote))
        assertEquals(withNote, again)
        val text = RecceJson.encode(withNote)
        assertTrue(text.contains("\"shotType\" : \"TWO_SHOT\""))
        assertEquals("Hinglish", again.voiceNotes[0].detectedLanguage)
    }

    @Test fun jsonEscapes() {
        val v = mapOf("a" to "line\n\"quoted\" \\ é", "n" to listOf(1L, 2.5, null, true))
        assertEquals(v, Json.parse(Json.write(v)))
    }

    @Test fun naturalSort() {
        val scenes = listOf("10", "2", "4A", "1").map { RecceScene(sceneNumber = it) }
        assertEquals(listOf("1", "2", "4A", "10"), scenes.sortedWith(naturalOrder { it.sceneNumber }).map { it.sceneNumber })
    }
}
