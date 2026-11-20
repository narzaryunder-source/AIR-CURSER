package com.aircursor.air

import java.io.File
import java.util.Locale

/**
 * App links that belong to an app in the PC (claude://, vscode://, cursor://…). PC apps open their
 * sign-in pages in Air; the page then hands the sign-in back with such a link, which must reach the
 * PC app – not an app on the phone (the phone's Claude app would get the PC's sign-in).
 */
internal object PcLinks {
    /** Links the phone always opens itself: calls, mail, maps, payments, app stores, intent links. */
    private val PHONE = setOf(
        "intent", "android-app", "tel", "sms", "smsto", "mms", "mailto", "geo", "maps", "market", "upi",
        "whatsapp", "tg", "sip", "fb", "instagram", "snssdk1233", "spotify", "vnd.youtube", "youtube",
    )

    private val SCHEME = Regex("^([A-Za-z][A-Za-z0-9+.-]*):")

    fun scheme(uri: String): String? = SCHEME.find(uri)?.groupValues?.get(1)?.lowercase(Locale.ROOT)

    /** True when a PC app's menu entry says it opens [scheme] links (MimeType=x-scheme-handler/…). */
    fun handledInPc(scheme: String, entries: Sequence<String>): Boolean {
        if (scheme in PHONE) return false
        val want = "x-scheme-handler/$scheme"
        return entries.any { text ->
            text.lineSequence().any { line ->
                line.startsWith("MimeType=") &&
                    line.removePrefix("MimeType=").split(';').any { it.trim().equals(want, ignoreCase = true) }
            }
        }
    }

    /** The PC's menu entries: the packages', the AirCursor copies and the user's own. */
    fun entries(rootfs: File): Sequence<String> =
        sequenceOf("usr/share/applications", "usr/local/share/applications", "root/.local/share/applications")
            .flatMap { dir -> File(rootfs, dir).listFiles { f -> f.name.endsWith(".desktop") }?.asSequence() ?: emptySequence() }
            .mapNotNull { f -> runCatching { f.readText() }.getOrNull() }

    /** [uri] is an app link a PC app opens. */
    fun forPc(uri: String, rootfs: File): Boolean {
        val s = scheme(uri) ?: return false
        if (s == "http" || s == "https" || s == "file" || s == "about" || s == "air") return false
        return handledInPc(s, entries(rootfs))
    }
}
