package de.lippstadt.bus.data

import java.util.Locale

/** Erkennt Straßennamen und vergleicht sie fehlertolerant (ohne Netzwerk, testbar). */
object StreetMatch {

    private val SUFFIX = Regex(
        "(straße|strasse|str\\.?|weg|allee|platz|gasse|ring|damm|ufer|pfad|promenade|chaussee|steig|stieg|" +
            "wall|graben|garten|gärten|feld|kamp|winkel|höhe|anger|twiete|trift)$",
        RegexOption.IGNORE_CASE
    )
    private val PREFIX = Regex(
        "^(im|in\\s+den|in\\s+der|unter\\s+den|unter\\s+der|am|an\\s+der|an\\s+den|an\\s+dem|auf\\s+dem|auf\\s+der|" +
            "auf\\s+den|hinter\\s+dem|hinter\\s+der|hinter\\s+den|vor\\s+dem|vor\\s+der|beim|über\\s+den|zwischen\\s+den)\\s+\\S+",
        RegexOption.IGNORE_CASE
    )

    /** "Erwitter Straße", "Gottes Garten", "Im Ried", "Unter den Eichen", "Am Anhalter Bahnhof" … */
    fun looksLikeStreet(text: String): Boolean {
        val t = text.trim()
        if (t.isBlank()) return false
        val last = t.split(Regex("\\s+")).last()
        return SUFFIX.containsMatchIn(last) || PREFIX.containsMatchIn(t)
    }

    /** Varianten für die Suche: Spracherkennung trennt oft Wörter ("Gottes Garten" statt "Gottesgarten"). */
    fun variants(text: String): List<String> {
        val t = text.trim().replace(Regex("\\s+"), " ")
        val expanded = t.replace(Regex("(?i)str\\.?$"), "straße")
        val words = expanded.split(" ")
        val out = mutableListOf(t, expanded)
        if (words.size >= 2 && !PREFIX.containsMatchIn(expanded)) {
            out += words.first() + words.drop(1).joinToString("") { it.lowercase(Locale.GERMANY) }
            out += words.joinToString("-")
        }
        return out.distinct()
    }

    fun normalize(s: String): String = s.lowercase(Locale.GERMANY)
        .replace(Regex("str\\.?$"), "strasse")
        .replace("ß", "ss").replace("ä", "ae").replace("ö", "oe").replace("ü", "ue")
        .replace(Regex("[^a-z0-9]"), "")

    /** true, wenn [name] zur Eingabe passen könnte (Leerzeichen/Bindestriche/Tippfehler egal). */
    fun similar(name: String, query: String): Boolean {
        val n = normalize(name)
        val q = normalize(query)
        if (n.isEmpty() || q.isEmpty()) return false
        if (n == q) return true
        if (q.length >= 5 && (n.contains(q) || q.contains(n) && n.length >= 5)) return true
        val dist = levenshtein(n, q)
        return 1.0 - dist.toDouble() / maxOf(n.length, q.length) >= 0.8
    }

    private fun levenshtein(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            prev = cur
        }
        return prev[b.length]
    }
}
