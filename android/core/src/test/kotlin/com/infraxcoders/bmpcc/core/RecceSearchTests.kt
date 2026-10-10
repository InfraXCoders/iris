package com.infraxcoders.bmpcc.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecceSearchTests {
    private val day = 86_400_000L
    private val now = 1_800_000_000_000L

    private fun recce(name: String, place: String, ageDays: Int, gps: Boolean, interior: Boolean, shotNote: String = "", voice: String = ""): RecceSession {
        val sid = newId()
        val scene = RecceScene(sessionId = sid, sceneNumber = "1", isInterior = interior, isDay = true, locationDescription = "$place street",
            shots = listOf(RecceShot(sceneId = "", shotNumber = "1A", notes = shotNote)))
        return RecceSession(
            id = sid, projectName = name, locationName = place, timestamp = now - ageDays * day,
            latitude = if (gps) 28.55 else null, longitude = if (gps) 77.2 else null, scenes = listOf(scene),
            voiceNotes = if (voice.isEmpty()) emptyList() else listOf(RecceNote(sessionId = sid, rawTranscription = voice)),
            modificationTimestamp = now - ageDays * day,
        )
    }

    private val all = listOf(
        recce("Monsoon", "Hauz Khas", 2, true, false, shotNote = "Backlit by the window, café sign"),
        recce("Ad film", "Gurgaon", 20, false, true, voice = "Generator needed near parking"),
        recce("Short", "Shimla", 200, true, true),
    )

    @Test fun textSearchAcrossShotsAndNotes() {
        val r = RecceSearch.search(all, RecceSearch.Filters(query = "cafe window"), now)
        assertEquals(listOf("Monsoon"), r.map { it.session.projectName })
        assertTrue(r[0].matches.contains("Shot 1A notes"))
        assertEquals("Ad film", RecceSearch.search(all, RecceSearch.Filters(query = "generator"), now).single().session.projectName)
        assertTrue(RecceSearch.search(all, RecceSearch.Filters(query = "generator window"), now).isEmpty())
        assertEquals(3, RecceSearch.search(all, RecceSearch.Filters(), now).size)
    }

    @Test fun filtersAndSort() {
        assertEquals(listOf("Monsoon", "Ad film"), RecceSearch.search(all, RecceSearch.Filters(time = RecceSearch.When.MONTH), now).map { it.session.projectName })
        assertEquals(listOf("Monsoon", "Short"), RecceSearch.search(all, RecceSearch.Filters(withLocation = true), now).map { it.session.projectName })
        assertEquals(listOf("Ad film", "Short"), RecceSearch.search(all, RecceSearch.Filters(scene = RecceSearch.SceneKind.INT, sort = RecceSearch.Sort.NAME), now).map { it.session.projectName })
        assertTrue(RecceSearch.Filters(withLocation = true).active)
    }

    @Test fun placeNames() {
        assertEquals("Hauz Khas, New Delhi", PlaceName.format("12", "Hauz Khas", "New Delhi", "South Delhi", "Delhi", "India"))
        assertEquals("Shimla, Himachal Pradesh", PlaceName.format("8Q7J+2M", null, "Shimla", null, "Himachal Pradesh", "India"))
        assertEquals("India", PlaceName.format(null, null, null, null, null, "India"))
        assertNull(PlaceName.format(null, null, null, null, null, null))
    }
}
