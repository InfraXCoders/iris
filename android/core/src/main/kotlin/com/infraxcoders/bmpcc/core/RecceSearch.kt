package com.infraxcoders.bmpcc.core

import java.text.Normalizer

/** Finding recces: free-text search across everything in a recce, plus filters and sorting. */
object RecceSearch {
    enum class When(val label: String, val days: Int?) {
        ANY("Any time", null), WEEK("Last 7 days", 7), MONTH("Last 30 days", 30), YEAR("Last 12 months", 365)
    }

    enum class SceneKind(val label: String) { ANY("Any scene"), INT("INT."), EXT("EXT."), DAY("Day"), NIGHT("Night") }

    enum class Sort(val label: String) { RECENT("Recently changed"), DATE("Recce date"), NAME("Project name") }

    data class Filters(
        val query: String = "",
        val time: When = When.ANY,
        val scene: SceneKind = SceneKind.ANY,
        val withLocation: Boolean = false,
        val sort: Sort = Sort.RECENT,
    ) {
        val active: Boolean get() = query.isNotBlank() || time != When.ANY || scene != SceneKind.ANY || withLocation
    }

    /** A recce that matches, with where the words were found ("Scene 2: EXT. Rooftop", "Shot 1A notes"…). */
    data class Hit(val session: RecceSession, val matches: List<String>)

    fun search(sessions: List<RecceSession>, f: Filters, now: Long = System.currentTimeMillis()): List<Hit> {
        val words = normalise(f.query).split(' ').filter { it.isNotEmpty() }
        val hits = sessions.mapNotNull { s ->
            if (f.withLocation && !s.hasLocation) return@mapNotNull null
            f.time.days?.let { d -> if (s.timestamp < now - d * 86_400_000L) return@mapNotNull null }
            if (f.scene != SceneKind.ANY && s.scenes.none { sc ->
                    when (f.scene) {
                        SceneKind.INT -> sc.isInterior
                        SceneKind.EXT -> !sc.isInterior
                        SceneKind.DAY -> sc.isDay
                        SceneKind.NIGHT -> !sc.isDay
                        SceneKind.ANY -> true
                    }
                }
            ) return@mapNotNull null
            if (words.isEmpty()) return@mapNotNull Hit(s, emptyList())
            val fields = fieldsOf(s)
            val haystack = fields.joinToString("\n") { normalise(it.second) }
            if (words.any { it !in haystack }) return@mapNotNull null
            Hit(s, fields.filter { (_, text) -> val t = normalise(text); words.any { it in t } }.map { it.first }.distinct().take(3))
        }
        return when (f.sort) {
            Sort.RECENT -> hits.sortedByDescending { it.session.modificationTimestamp }
            Sort.DATE -> hits.sortedByDescending { it.session.timestamp }
            Sort.NAME -> hits.sortedBy { it.session.projectName.lowercase() }
        }
    }

    /** (where, text) for everything searchable in a recce. */
    fun fieldsOf(s: RecceSession): List<Pair<String, String>> = buildList {
        add("Project" to s.projectName)
        add("Location" to s.locationName)
        add("Director / DoP notes" to s.directorDopNotes)
        add("Location notes" to s.generalLocationNotes)
        for (sc in s.sortedScenes) {
            val name = "Scene ${sc.sceneNumber}"
            add("$name: ${sc.heading}" to "${sc.heading} ${sc.locationDescription} ${sc.lightingCondition.label}")
            add("$name notes" to sc.notes)
            for (sh in sc.sortedShots) {
                val shot = "Shot ${sh.shotNumber}"
                add("$shot notes" to sh.notes)
                add("$shot (${sh.shotType.label})" to "${sh.shotType.label} ${sh.shotType.longName} ${sh.subjectPosition} ${sh.cameraPosition} ${sh.subjectMovement}")
                add("$shot lens" to "${sh.cameraModel} ${sh.lensModel} ${sh.focalLength}")
                add("$shot LUT" to (sh.lut?.substringAfter(':') ?: ""))
            }
        }
        for (n in s.sortedNotes) add("Voice note" to n.rawTranscription)
    }.filter { it.second.isNotBlank() }

    /** Lower case, accents removed, punctuation as spaces. */
    fun normalise(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase()
            .replace(Regex("[^\\p{L}\\p{N}.]+"), " ").trim()
}

/** A short place name from an address lookup, e.g. "Hauz Khas, New Delhi". */
object PlaceName {
    fun format(feature: String?, subLocality: String?, locality: String?, subAdmin: String?, admin: String?, country: String?): String? {
        val parts = mutableListOf<String>()
        fun add(v: String?) {
            // Skip house numbers and plus codes ("8Q7J+2M").
            val t = v?.trim()?.takeIf { it.isNotEmpty() && '+' !in it && !it.all { c -> c.isDigit() || c == ' ' || c == '-' } } ?: return
            if (parts.none { it.equals(t, ignoreCase = true) }) parts += t
        }
        add(subLocality ?: feature)
        add(locality ?: subAdmin)
        if (parts.size < 2) add(admin)
        if (parts.isEmpty()) add(country)
        return parts.take(2).joinToString(", ").ifEmpty { null }
    }
}
