package com.aircursor.assistant

/**
 * Settings and clean-up for the offline speech engine (Whisper).
 *
 * Tested on spoken Hinglish: in English mode with a Hinglish hint, Whisper writes commands in Latin
 * letters the way people type them ("youtube kholo", "wifi band karo"); in Hindi mode it often
 * wrote Urdu script or broken Devanagari.
 */
object WhisperText {
    /** Recognition language: English mode with a Hinglish hint (see above). */
    const val LANGUAGE = "en"

    /** Shows Whisper how commands look (words and spelling); it writes what it hears in this style. */
    const val HINT = "Hinglish voice commands: youtube kholo, awaz badhao, volume kam karo, wifi band karo, " +
        "bluetooth chalu karo, torch chalu karo, screenshot lo, kitne baje hai, naya tab kholo, " +
        "window minimize karo, google pe search karo, gaane chalao."

    /** Whisper's notes and filler when there were no words ("[BLANK_AUDIO]", "(music)", "♪"). */
    private val NOTES = Regex("\\[[^\\]]*]|\\([^)]*\\)|\\*[^*]*\\*|[♪♫]")

    /** Lines Whisper sometimes invents from silence or noise. */
    private val INVENTED = setOf(
        "thank you", "thanks for watching", "thank you for watching", "thanks", "bye", "you",
        "subscribe", "please subscribe", "like and subscribe",
    )

    /** The words from a transcript, or "" when nothing was said. */
    fun clean(raw: String): String {
        val t = raw.replace(NOTES, " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .trim('-', '–', '"', '“', '”', '\'', ' ')
            .trimEnd('.', '!', '?', '।', ',', ' ')
        if (t.none { it.isLetterOrDigit() }) return ""
        val key = t.lowercase().replace(Regex("[^a-z ]"), "").trim()
        return if (key in INVENTED) "" else t
    }
}

/** A Whisper model the phone can download (once) for offline voice. */
enum class WhisperModel(val file: String, val bytes: Long, val sha256: String, val label: String) {
    TINY(
        "ggml-tiny.bin", 77_691_713L,
        "be07e048e1e599ad46341c8d2a135645097a538221678b7acdd1b1919c6e1b21",
        "Small (75 MB)",
    ),
    BASE(
        "ggml-base.bin", 147_951_465L,
        "60ed5bc3dd14eea856493d334349b405782ddcaf0028d4b5df4088345fba2efe",
        "Recommended (140 MB)",
    ),
    ;

    val url get() = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$file"
}
