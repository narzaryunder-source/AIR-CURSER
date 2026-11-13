package com.aircursor.assistant

import java.util.Locale

/** What the person asked for, after reading their words (English, Hindi in Latin letters, or a mix). */
sealed class Command {
    data class Open(val target: String) : Command()
    data class CloseApp(val target: String) : Command()
    data class Search(val query: String) : Command()
    data class PlayYouTube(val query: String) : Command()
    data class Type(val text: String) : Command()
    data class Toggle(val feature: String, val on: Boolean?) : Command()
    data class Scroll(val down: Boolean) : Command()
    data class Browser(val action: String) : Command()
    data class Window(val action: String) : Command()
    data class Volume(val action: String) : Command()
    data class Rename(val name: String) : Command()
    object Screenshot : Command()
    object Time : Command()
    object Date : Command()
    object Battery : Command()
    object Lock : Command()
    object Help : Command()
    object Nothing : Command()
}

/**
 * Turns a spoken sentence into a [Command]. Pure Kotlin (no Android), so it is unit-tested.
 * Anything it does not understand becomes a web search – the assistant never just says "no".
 */
object AssistantBrain {

    /** Features the assistant can switch on and off, with the words people use for them. */
    val FEATURES = linkedMapOf(
        "fps" to listOf("fps meter", "fps counter", "frame rate", "fps"),
        "keyboard" to listOf("keyboard", "keypad"),
        "game" to listOf("game mode", "gaming mode", "turbo mode", "turbo", "boost mode", "boost", "game"),
        "shields" to listOf("ad blocker", "adblocker", "ad block", "adblock", "shields", "shield", "ads"),
        "dark" to listOf("dark mode", "dark theme", "night mode", "dark"),
        "torch" to listOf("flashlight", "flash light", "torch", "flash"),
        "gpu" to listOf("phone gpu", "virgl", "gpu"),
        "wifi" to listOf("wi-fi", "wifi", "wi fi", "internet"),
        "bluetooth" to listOf("bluetooth", "blue tooth"),
        "desktop" to listOf("desktop site", "desktop mode", "desktop version"),
    )

    private val WAKE = listOf("hey", "hi", "hello", "ok", "okay", "oye", "suno", "listen", "yo", "air", "jarvis", "friday", "assistant")
    private val FILLER = listOf(
        "please", "plz", "pls", "zara", "jara", "jaldi", "yaar", "yar", "bhai", "bro", "na", "ji", "can you", "could you",
        "would you", "will you", "i want to", "i want you to", "i wanna", "for me", "mere liye", "mujhe", "muje", "kripya",
    )

    fun parse(raw: String, name: String = "Air"): Command {
        var t = normalize(raw)
        if (t.isEmpty()) return Command.Nothing
        // "Hey Air, …" / "Jarvis …": drop the wake words and the assistant's own name.
        val names = (WAKE + normalize(name).split(' ').filter { it.isNotEmpty() }).toSet()
        var words = t.split(' ').toMutableList()
        while (words.isNotEmpty() && words[0] in names) words.removeAt(0)
        t = words.joinToString(" ")
        if (t.startsWith("what can you") || t.startsWith("what all can you") || t == "help" || t.contains("kya kar sakte") ||
            t.contains("kya kya kar sakte")
        ) return Command.Help
        for (f in FILLER) t = (" $t ").replace(" $f ", " ").trim()
        t = t.replace(Regex("\\s+"), " ").trim()
        if (t.isEmpty()) return Command.Nothing
        words = t.split(' ').toMutableList()

        rename(t)?.let { return it }
        if (t in setOf("help", "what can you do", "kya kar sakte ho", "kya kya kar sakte ho", "tum kya kar sakte ho") ||
            t.startsWith("what can you") || t.contains("kya kya kar sakte")
        ) return Command.Help
        val askWords = listOf("what", "kya", "kitne", "kitna", "bata", "tell", "abhi", "current", "hua")
        if ((t.contains("time") || t.contains("baje") || t.contains("samay")) && !t.contains("timer") && words.size <= 6 &&
            (t == "time" || askWords.any { t.contains(it) })
        ) return Command.Time
        if ((t.contains("date") || t.contains("tareekh") || t.contains("tarikh") || t.contains("aaj kya din") || t.contains("which day")) &&
            words.size <= 6 && !t.contains("update")
        ) return Command.Date
        if (t.contains("battery") && words.size <= 6) return Command.Battery
        if (t.contains("screenshot") || t.contains("screen shot")) return Command.Screenshot
        if (Regex("\\b(lock)\\b").containsMatchIn(t) && (t.contains("pc") || t.contains("computer") || t.contains("screen") || t == "lock" || t.contains("karo") || t.contains("kar do"))) {
            return Command.Lock
        }
        volume(t)?.let { return it }
        scroll(t)?.let { return it }
        nav(t)?.let { return it }
        window(t)?.let { return it }
        type(t)?.let { return it }
        youtube(t)?.let { return it }
        toggle(t)?.let { return it }
        open(t)?.let { return it }
        search(t)?.let { return it }
        return Command.Search(t)
    }

    /** Hindi command words (Devanagari, from the Hindi recognizer) → the same words in Latin letters. */
    private val HINDI = linkedMapOf(
        "यूट्यूब" to "youtube", "खोलो" to "kholo", "खोल दो" to "khol do", "चलाओ" to "chalao", "चला दो" to "chala do",
        "बजाओ" to "bajao", "लगाओ" to "lagao", "सुनाओ" to "sunao", "गाने" to "gaane", "गाना" to "gaana", "बंद करो" to "band karo",
        "बंद कर दो" to "band kar do", "बंद" to "band", "चालू करो" to "chalu karo", "चालू" to "chalu", "ऑन" to "on", "ऑफ" to "off",
        "सर्च करो" to "search karo", "सर्च" to "search", "ढूंढो" to "dhundo", "खोजो" to "khojo", "लिखो" to "likho",
        "नीचे" to "neeche", "ऊपर" to "upar", "पीछे जाओ" to "peeche jao", "पीछे" to "peeche", "नया टैब" to "naya tab",
        "टाइम" to "time", "समय" to "samay", "कितने बजे" to "kitne baje", "बैटरी" to "battery", "आवाज़" to "awaz", "आवाज" to "awaz",
        "बढ़ाओ" to "badhao", "कम करो" to "kam karo", "कम" to "kam", "स्क्रीनशॉट" to "screenshot", "पे" to "pe", "पर" to "par",
        "के बारे में बताओ" to "ke baare mein batao", "हाथ" to "haath", "कीबोर्ड" to "keyboard", "टॉर्च" to "torch", "फ्लैशलाइट" to "flashlight",
        "वाईफाई" to "wifi", "ब्लूटूथ" to "bluetooth", "गेम मोड" to "game mode", "करो" to "karo", "कर दो" to "kar do", "और" to "and",
        "तुम्हारा नाम" to "tumhara naam", "है" to "hai", "क्या" to "kya", "मदद" to "help", "डार्क मोड" to "dark mode",
    )

    fun normalize(s: String): String = HINDI.entries.fold(s) { acc, (hi, en) -> acc.replace(hi, en) }.lowercase(Locale.ROOT)
        .replace(Regex("[\"“”‘’!?¿¡,;:।]"), " ")
        .replace(Regex("(?<![a-z0-9])\\.|\\.(?![a-z0-9])"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun rename(t: String): Command? {
        val m = Regex("^(?:your|tumhara|tumhaara|aapka|apka|tera) (?:name|naam) (?:is |hai |will be |hoga )?(.+?)(?: hai| hoga)?$").find(t)
            ?: Regex("^(?:call yourself|i will call you|i'll call you|tumhara naam rakho|naam rakho) (.+)$").find(t)
            ?: Regex("^(?:apna )?naam (.+?) (?:rakho|rakh do|rakhlo|rakh lo)$").find(t)
        val n = m?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() && it.split(' ').size <= 3 } ?: return null
        return Command.Rename(n.split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.titlecase(Locale.ROOT) } })
    }

    private fun volume(t: String): Command? {
        if (!(t.contains("volume") || t.contains("awaz") || t.contains("awaaz") || t.contains("aawaz") || t.contains("sound") ||
                t == "mute" || t == "unmute" || t.startsWith("mute ") || t.startsWith("unmute"))
        ) return null
        return when {
            t.contains("unmute") || t.contains("sound on") -> Command.Volume("unmute")
            t.contains("mute") || t.contains("sound off") || t.contains("band") -> Command.Volume("mute")
            listOf("up", "badhao", "badha", "increase", "louder", "tez", "jyada", "zyada", "high", "full", "max").any { t.contains(it) } -> Command.Volume("up")
            listOf("down", "kam", "decrease", "lower", "dheere", "dhire", "low", "less").any { t.contains(it) } -> Command.Volume("down")
            else -> null
        }
    }

    private fun scroll(t: String): Command? = when {
        t.startsWith("scroll down") || t == "scroll" || t == "neeche" || t == "niche" || t.startsWith("neeche ") || t.startsWith("niche ") ||
            t.endsWith(" neeche karo") || t == "page down" || t == "down" || t == "aur neeche" -> Command.Scroll(true)
        t.startsWith("scroll up") || t == "upar" || t == "oopar" || t.startsWith("upar ") || t.startsWith("oopar ") || t == "page up" ||
            t == "up" -> Command.Scroll(false)
        else -> null
    }

    private fun nav(t: String): Command? = when (t) {
        "back", "go back", "peeche", "peeche jao", "piche", "piche jao", "wapas", "wapas jao", "vapas", "vapas jao", "previous page" ->
            Command.Browser("back")
        "forward", "go forward", "aage", "aage jao", "next page" -> Command.Browser("forward")
        "refresh", "reload", "refresh karo", "reload karo", "refresh the page", "reload the page", "page refresh karo" -> Command.Browser("reload")
        "new tab", "open new tab", "open a new tab", "naya tab", "naya tab kholo", "new tab kholo", "new tab open karo" -> Command.Browser("newtab")
        "close tab", "close this tab", "close the tab", "tab band karo", "tab close karo", "ye tab band karo", "is tab ko band karo" ->
            Command.Browser("closetab")
        "next tab", "agla tab", "switch tab", "dusra tab" -> Command.Browser("nexttab")
        "previous tab", "pichla tab", "last tab" -> Command.Browser("prevtab")
        else -> null
    }

    private fun window(t: String): Command? = when (t) {
        "minimize", "minimise", "minimize karo", "minimise karo", "minimize window", "chhota karo", "chota karo", "hide", "hide window" ->
            Command.Window("minimize")
        "maximize", "maximise", "maximize karo", "full screen", "fullscreen", "full screen karo", "bada karo", "badha karo", "maximize window" ->
            Command.Window("maximize")
        "close", "close window", "close this", "close it", "band karo", "band kar do", "window band karo", "ise band karo", "isko band karo" ->
            Command.Window("close")
        "show desktop", "desktop dikhao", "go to desktop", "desktop pe jao", "home", "minimize all", "sab minimize karo" -> Command.Window("desktop")
        else -> null
    }

    private fun type(t: String): Command? {
        val m = Regex("^(?:type|write|likho|likh do|likh|type karo|type kar do|enter) (.+)$").find(t)
            ?: Regex("^(.+?) (?:likho|likh do|type karo|type kar do|likh dijiye)$").find(t)
        val text = m?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return Command.Type(text)
    }

    private val PLAY = "(?:chalao|chala do|chalado|lagao|laga do|bajao|baja do|play karo|play kar do|play|sunao|suna do|dikhao|dikha do|chalaao)"

    /** "arijit singh ke" → "arijit singh" (Hindi joining words at the end). */
    private fun clean(q: String) = q.trim().replace(Regex(" (?:ke|ki|ka|wala|wale|waali|wali)$"), "").trim()

    private fun youtube(t: String): Command? {
        Regex("^(?:open )?(?:youtube|you tube) (?:and )?(?:play|search|search for|pe search karo) (.+)$").find(t)?.let { return Command.PlayYouTube(clean(it.groupValues[1])) }
        Regex("^(?:play|chalao|bajao|lagao|sunao) (.+?) (?:on|in) (?:youtube|you tube)$").find(t)?.let { return Command.PlayYouTube(clean(it.groupValues[1])) }
        Regex("^(?:youtube|you tube) (?:pe|par|per|on|me|mein) (.+?)(?: $PLAY)?$").find(t)?.let {
            val q = it.groupValues[1].trim()
            if (q.isNotEmpty() && q != "jao") return Command.PlayYouTube(clean(q))
        }
        Regex("^(.+?) (?:youtube|you tube) (?:pe|par|per|on|me|mein) $PLAY$").find(t)?.let { return Command.PlayYouTube(clean(it.groupValues[1])) }
        Regex("^(?:search|find|dhundo) (.+?) (?:on|in) (?:youtube|you tube)$").find(t)?.let { return Command.PlayYouTube(clean(it.groupValues[1])) }
        Regex("^(.+?) (song|songs|gaana|gana|gaane|gane|video|videos|music|trailer) $PLAY$").find(t)?.let {
            val kind = it.groupValues[2].let { w -> if (w.startsWith("ga")) "songs" else w }
            return Command.PlayYouTube(clean(it.groupValues[1]) + " " + kind)
        }
        Regex("^play (.+)$").find(t)?.let { return Command.PlayYouTube(clean(it.groupValues[1])) }
        Regex("^(.+?) (?:bajao|baja do|sunao|suna do)$").find(t)?.let { return Command.PlayYouTube(clean(it.groupValues[1])) }
        return null
    }

    fun feature(name: String): String? {
        val n = name.trim()
        for ((key, words) in FEATURES) if (words.any { n == it || n == "the $it" }) return key
        return null
    }

    private fun toggle(t: String): Command? {
        val onWords = "(?:on|chalu|chaalu|start|enable|activate|shuru)"
        val offWords = "(?:off|band|bandh|disable|deactivate|stop|hatao|hata do)"
        Regex("^(?:turn|switch|put) on (?:the )?(.+)$").find(t)?.let { m -> feature(m.groupValues[1])?.let { return Command.Toggle(it, true) } }
        Regex("^(?:turn|switch|put) (?:the )?(.+?) on$").find(t)?.let { m -> feature(m.groupValues[1])?.let { return Command.Toggle(it, true) } }
        Regex("^(?:turn|switch|shut) off (?:the )?(.+)$").find(t)?.let { m -> feature(m.groupValues[1])?.let { return Command.Toggle(it, false) } }
        Regex("^(?:turn|switch) (?:the )?(.+?) off$").find(t)?.let { m -> feature(m.groupValues[1])?.let { return Command.Toggle(it, false) } }
        Regex("^(?:enable|activate|start) (?:the )?(.+)$").find(t)?.let { m -> feature(m.groupValues[1])?.let { return Command.Toggle(it, true) } }
        Regex("^(?:disable|deactivate|stop) (?:the )?(.+)$").find(t)?.let { m -> feature(m.groupValues[1])?.let { return Command.Toggle(it, false) } }
        Regex("^(.+?) $onWords(?: karo| kar do| kardo| ho jao| karna)?$").find(t)?.let { m -> feature(m.groupValues[1])?.let { return Command.Toggle(it, true) } }
        Regex("^(.+?) $offWords(?: karo| kar do| kardo| ho jao| karna)?$").find(t)?.let { m ->
            val f = feature(m.groupValues[1])
            if (f != null) return Command.Toggle(f, false)
            // "YouTube band karo": close that app's window.
            if (Regex(" (?:band|bandh|hatao)").containsMatchIn(t)) return Command.CloseApp(m.groupValues[1])
        }
        Regex("^(?:toggle) (?:the )?(.+)$").find(t)?.let { m -> feature(m.groupValues[1])?.let { return Command.Toggle(it, null) } }
        return null
    }

    private fun open(t: String): Command? {
        Regex("^(?:open|launch|start|run|go to|goto|visit|show me|take me to|kholo|khol do) (?:the )?(.+?)(?: app| website| site| page)?$").find(t)?.let {
            return Command.Open(it.groupValues[1].trim())
        }
        Regex("^(.+?)(?: app| website| site| page)? (?:kholo|khol do|kholdo|khol|open karo|open kar do|open kardo|open|chalu karo|chalao|chala do|start karo|launch karo|pe jao|par jao|per jao|dikhao|dikha do)$").find(t)?.let {
            return Command.Open(it.groupValues[1].trim())
        }
        return null
    }

    private fun search(t: String): Command? {
        Regex("^(?:search|search for|google|find|look up|lookup|search karo|dhundo|dhoondo|khojo|search the web for) (.+)$").find(t)?.let {
            return Command.Search(it.groupValues[1].trim())
        }
        Regex("^(.+?) (?:search karo|search kar do|search|google karo|dhundo|dhoondo|khojo|dhund do|ke bare me batao|ke bare mein batao|ke baare mein batao|ke baare me batao|ke bare mein bataao)$").find(t)?.let {
            return Command.Search(it.groupValues[1].trim())
        }
        return null
    }
}
