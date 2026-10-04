package com.infraxcoders.bmpcc.core

/** The result of sorting one recce note into categories. */
data class SortedNote(
    val language: String,
    val composition: String?,
    val lighting: String?,
    val focalLength: String?,
    val general: String?,
)

/** Notes grouped for the report. */
data class NotesSummary(
    val composition: List<String>,
    val lighting: List<String>,
    val movement: List<String>,
    val general: List<String>,
)

/**
 * Keyword-based organiser for spoken recce notes (English, Romanized Hinglish, Devanagari Hindi), identical to the
 * old AiRecceAnalyzer: keyword matching, not an AI model, so the app labels it "auto-sorted".
 */
object NotesSorter {
    const val UNKNOWN = "Not recorded / Unknown"

    private val hindiRomanKeywords = setOf(
        "hai", "hein", "rahi", "raha", "rahe", "thoda", "bahut", "achhi", "achha",
        "rakho", "rakhna", "karo", "karna", "aaye", "aagaya", "aajaye", "se", "ko",
        "pe", "par", "mein", "me", "aur", "ya", "yahan", "wahan", "kaafi", "chahiye", "lag",
    )
    private val compositionKeywords = listOf(
        "framing", "frame", "thirds", "left", "right", "center", "subject", "background",
        "foreground", "crop", "wide", "close up", "close-up", "tight", "angle", "medium shot",
        "composition", "depth of field", "rakho", "rakhna", "side", "left side", "right side",
        "piche", "aage", "thoda left", "thoda right", "achhi", "achha", "set karo", "chauda",
        "फ्रेम", "फ़्रेम", "सब्जेक्ट", "बैकग्राउंड", "लेफ्ट", "राइट", "सेंटर", "कोण", "चौड़ा", "रखो",
    )
    private val lightingKeywords = listOf(
        "light", "window", "hard light", "soft light", "shadow", "contrast", "bright", "dark",
        "sun", "flare", "highlight", "diffuse", "diffusion", "exposure", "roshni", "dhoop",
        "dhup", "ujala", "khidki", "khidkee", "लाइट", "रोशनी", "धूप", "उजाला", "छाया", "खिड़की",
    )
    private val movementKeywords = listOf(
        "move", "pan", "tilt", "dolly", "zoom", "track", "tracking", "handheld", "gimbal", "tripod",
        "ghumao", "chalao", "hilao", "shift", "move karo", "hatao", "घुमाओ", "चलाओ", "मूव",
    )
    private val focalRegex = Regex("(\\d+(?:\\.\\d+)?)\\s*(mm|millimeter)")

    /** "Hindi" if any Devanagari character, "Hinglish" if any word is common Romanized Hindi, else "English". */
    fun detectLanguage(text: String): String {
        if (text.any { it in 'ऀ'..'ॿ' }) return "Hindi"
        val words = text.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return if (words.any { it in hindiRomanKeywords }) "Hinglish" else "English"
    }

    fun sort(raw: String): SortedNote {
        val text = raw.lowercase()
        val focal = focalRegex.find(text)?.groupValues?.get(1)?.let { "${it}mm" }
        return SortedNote(
            language = detectLanguage(raw),
            composition = if (compositionKeywords.any { text.contains(it) }) raw else null,
            lighting = if (lightingKeywords.any { text.contains(it) }) raw else null,
            focalLength = focal,
            general = raw,
        )
    }

    fun isMovement(raw: String): Boolean {
        val text = raw.lowercase()
        return movementKeywords.any { text.contains(it) }
    }

    fun summarize(notes: List<String>): NotesSummary {
        val comp = mutableListOf<String>()
        val light = mutableListOf<String>()
        val move = mutableListOf<String>()
        val general = mutableListOf<String>()
        for (n in notes) {
            val s = sort(n)
            s.composition?.let { comp.add(it) }
            s.lighting?.let { light.add(it) }
            s.general?.let { general.add(it) }
            if (isMovement(n)) move.add(n)
        }
        fun orUnknown(l: List<String>) = l.ifEmpty { listOf(UNKNOWN) }
        return NotesSummary(orUnknown(comp), orUnknown(light), orUnknown(move), orUnknown(general))
    }
}
