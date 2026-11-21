package de.lippstadt.bus

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

data class PoiCategory(
    val label: String,
    val overpassFilter: String,
    val extraFilters: List<String> = emptyList(),
    val isStop: Boolean = false
)

data class ParsedVoiceQuery(
    val origin: String?,
    val destination: String,
    val preferredMode: String?,
    val modeDisplayName: String?,
    val poiCategory: PoiCategory?,
    val poiAnchorPlace: String?,
    val targetTimeMillis: Long? = null,
    val timeDisplayLabel: String? = null
)

private fun cleanPlaceArticle(place: String): String {
    return place
        .replace(Regex("^(dem|der|den|das|die|einem|einer|einen)\\s+", RegexOption.IGNORE_CASE), "")
        .trim()
}

private fun detectPoiCategory(lowerText: String): Pair<PoiCategory, String>? {
    if (Regex("\\b(bushaltestelle|bushaltestellen|haltestelle|haltestellen)\\b").containsMatchIn(lowerText) &&
        Regex("\\b(nächste|naechste|nächsten|naechsten|nähe|naehe|hier)\\b").containsMatchIn(lowerText)) {
        val match = Regex("\\b(bushaltestelle|bushaltestellen|haltestelle|haltestellen)\\b").find(lowerText)!!
        return PoiCategory("Bushaltestelle", """["highway"="bus_stop"]""",
            listOf("""["public_transport"="platform"]["bus"="yes"]"""), isStop = true) to match.value
    }
    val map = listOf(
        Regex("\\b(zahnarzt|zahnärztin|zahnaerztin|zahnarztpraxis)\\b") to PoiCategory("Zahnarzt", """[~"^(amenity|healthcare)$"~"^(dentist)$"]"""),
        Regex("\\b(arzt|ärztin|aerztin|arztpraxis|hausarzt|hausärztin|hausaerztin|ärzte|aerzte)\\b") to PoiCategory("Arztpraxis", """[~"^(amenity|healthcare)$"~"^(doctors|doctor|clinic)$"]"""),
        Regex("\\b(bäckerei|baeckerei|bäcker|baecker|brötchen|broetchen)\\b") to PoiCategory("Bäckerei", """["shop"="bakery"]"""),
        Regex("\\b(pommesbude|pommes|imbiss|currywurst|fritten|snack)\\b") to PoiCategory("Imbiss / Pommesbude", """["amenity"="fast_food"]"""),
        Regex("\\b(döner|doener|kebab)\\b") to PoiCategory("Döner / Imbiss", """["amenity"="fast_food"]"""),
        Regex("\\b(apotheke)\\b") to PoiCategory("Apotheke", """["amenity"="pharmacy"]"""),
        Regex("\\b(supermarkt|edeka|rewe|aldi|lidl|penny|netto|kaufland|einkaufen)\\b") to PoiCategory("Supermarkt", """["shop"="supermarket"]"""),
        Regex("\\b(geldautomat|bankautomat|sparkasse|volksbank|bank)\\b") to PoiCategory("Geldautomat / Bank", """["amenity"~"atm|bank"]"""),
        Regex("\\b(café|cafe|kaffee)\\b") to PoiCategory("Café", """["amenity"="cafe"]"""),
        Regex("\\b(restaurant|essen)\\b") to PoiCategory("Restaurant", """["amenity"="restaurant"]"""),
        Regex("\\b(toilette|wc|klo)\\b") to PoiCategory("Toilette / WC", """["amenity"="toilets"]"""),
        Regex("\\b(tankstelle|tanken)\\b") to PoiCategory("Tankstelle", """["amenity"="fuel"]"""),
        Regex("\\b(paketshop|post|dhl|packstation)\\b") to PoiCategory("Post / Packstation", """["amenity"~"post_office|parcel_locker"]""")
    )
    for ((regex, cat) in map) {
        val m = regex.find(lowerText)
        if (m != null) return cat to m.value
    }
    return null
}

private fun extractFutureDateTime(rawText: String, nowMillis: Long): Pair<String, Pair<Long, String>?> {
    var working = rawText
    val cal = Calendar.getInstance(Locale.GERMANY)
    cal.timeInMillis = nowMillis
    cal.set(Calendar.MILLISECOND, 0)
    var hasCustomDate = false
    var hasCustomTime = false

    // 1. Relative Zeit: "in 2 Stunden", "in 30 Minuten"
    val inRelMatch = Regex("\\bin\\s+(\\d+)\\s*(stunde|stunden|std|minute|minuten|min)\\b", RegexOption.IGNORE_CASE).find(working)
    if (inRelMatch != null) {
        val amount = inRelMatch.groupValues[1].toIntOrNull()
            ?: throw IllegalArgumentException("Die Zeitangabe ist zu groß.")
        require(amount in 0..525600) { "Die Zeitangabe ist zu groß." }
        val unit = inRelMatch.groupValues[2].lowercase(Locale.GERMANY)
        if (unit.startsWith("std") || unit.startsWith("stunde")) {
            cal.add(Calendar.HOUR_OF_DAY, amount)
        } else {
            cal.add(Calendar.MINUTE, amount)
        }
        working = working.replace(inRelMatch.value, " ")
        val fmt = SimpleDateFormat("EEE, dd.MM. 'um' HH:mm 'Uhr'", Locale.GERMANY)
        return working.trim() to (cal.timeInMillis to fmt.format(cal.time))
    }

    // 2. Wochentage ("Montag", "am Dienstag", "nächsten Freitag")
    val weekdays = mapOf(
        "montag" to Calendar.MONDAY,
        "dienstag" to Calendar.TUESDAY,
        "mittwoch" to Calendar.WEDNESDAY,
        "donnerstag" to Calendar.THURSDAY,
        "freitag" to Calendar.FRIDAY,
        "samstag" to Calendar.SATURDAY,
        "sonnabend" to Calendar.SATURDAY,
        "sonntag" to Calendar.SUNDAY
    )
    val dayRegex = Regex("\\b(?:am\\s+|nächsten\\s+|naechsten\\s+|kommenden\\s+)?(montag|dienstag|mittwoch|donnerstag|freitag|samstag|sonnabend|sonntag)\\b", RegexOption.IGNORE_CASE)
    val dayMatch = dayRegex.find(working)
    if (dayMatch != null) {
        val targetDow = weekdays[dayMatch.groupValues[1].lowercase(Locale.GERMANY)]
        if (targetDow != null) {
            val currentDow = cal.get(Calendar.DAY_OF_WEEK)
            var daysDiff = (targetDow - currentDow + 7) % 7
            if (daysDiff == 0) daysDiff = 7 // Nächster gleichnamiger Wochentag
            cal.add(Calendar.DAY_OF_YEAR, daysDiff)
            hasCustomDate = true
            working = working.replace(dayMatch.value, " ")
        }
    }

    // 3. "übermorgen" / "morgen" / "heute"
    val relDayRegex = Regex("\\b(übermorgen|uebermorgen|morgen|heute)\\b", RegexOption.IGNORE_CASE)
    val relDayMatch = relDayRegex.find(working)
    if (relDayMatch != null) {
        when (relDayMatch.groupValues[1].lowercase(Locale.GERMANY)) {
            "übermorgen", "uebermorgen" -> {
                cal.add(Calendar.DAY_OF_YEAR, 2)
                hasCustomDate = true
            }
            "morgen" -> {
                cal.add(Calendar.DAY_OF_YEAR, 1)
                hasCustomDate = true
            }
            "heute" -> {
                hasCustomDate = true
            }
        }
        working = working.replace(relDayMatch.value, " ")
    }

    // 4. Konkretes Datum ("am 25.11." oder "25.11.2026")
    val dateRegex = Regex("\\b(?:am\\s+)?(\\d{1,2})\\.(\\d{1,2})\\.(?:(\\d{4}|\\d{2})(?!\\d))?(?!\\d)")
    val dateMatch = dateRegex.find(working)
    if (dateMatch != null) {
        val d = dateMatch.groupValues[1].toIntOrNull()
        val m = dateMatch.groupValues[2].toIntOrNull()
        val yRaw = dateMatch.groupValues[3]
        if (d != null && m != null && d in 1..31 && m in 1..12) {
            val year = yRaw.toIntOrNull()?.let { if (it < 100) 2000 + it else it }
                ?: cal.get(Calendar.YEAR)
            val date = (cal.clone() as Calendar).apply {
                clear()
                isLenient = false
                set(year, m - 1, d, 8, 0, 0)
            }
            try {
                date.timeInMillis // Calendar otherwise normalizes e.g. 31 February silently.
            } catch (_: IllegalArgumentException) {
                throw IllegalArgumentException("Ungültiges Datum: ${dateMatch.value}")
            }
            cal.set(Calendar.DAY_OF_MONTH, 1)
            cal.set(Calendar.YEAR, year)
            cal.set(Calendar.MONTH, m - 1)
            cal.set(Calendar.DAY_OF_MONTH, d)
            hasCustomDate = true
            working = working.replace(dateMatch.value, " ")
        } else {
            throw IllegalArgumentException("Ungültiges Datum: ${dateMatch.value}")
        }
    }

    // 5. Uhrzeit ("um 9 Uhr", "um 14:30 Uhr", "ab 8:15", "9 Uhr 30")
    val timeRegex = Regex("\\b(?:um|ab|gegen)\\s+(\\d{1,2})(?::(\\d{2}))?\\s*(?:uhr(?:\\s*(\\d{1,2}))?)?\\b|\\b(\\d{1,2}):(\\d{2})\\s*(?:uhr)?\\b|\\b(\\d{1,2})\\s*uhr(?:\\s*(\\d{1,2}))?\\b", RegexOption.IGNORE_CASE)
    val timeMatch = timeRegex.find(working)
    if (timeMatch != null) {
        val hourStr = listOf(timeMatch.groupValues[1], timeMatch.groupValues[4], timeMatch.groupValues[6]).firstOrNull { it.isNotBlank() }
        val minStr = listOf(timeMatch.groupValues[2], timeMatch.groupValues[3], timeMatch.groupValues[5], timeMatch.groupValues[7]).firstOrNull { it.isNotBlank() }
        val hour = hourStr?.toIntOrNull()
        val minute = minStr?.toIntOrNull() ?: 0
        if (hour != null && hour in 0..23 && minute in 0..59) {
            cal.set(Calendar.HOUR_OF_DAY, hour)
            cal.set(Calendar.MINUTE, minute)
            cal.set(Calendar.SECOND, 0)
            hasCustomTime = true
            working = working.replace(timeMatch.value, " ")
        } else {
            throw IllegalArgumentException("Ungültige Uhrzeit: ${timeMatch.value}")
        }
    }

    if (!hasCustomDate && !hasCustomTime) {
        return rawText to null
    }

    // Falls nur ein Wochentag/Datum ohne Uhrzeit genannt wurde -> Standard 08:00 Uhr setzen
    if (hasCustomDate && !hasCustomTime) {
        if (relDayMatch?.value.equals("heute", ignoreCase = true)) {
            cal.timeInMillis = nowMillis
        } else {
            cal.set(Calendar.HOUR_OF_DAY, 8)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
        }
    }

    if (dateMatch != null && dateMatch.groupValues[3].isBlank() && cal.timeInMillis < nowMillis) {
        cal.add(Calendar.YEAR, 1)
    }

    // Falls nur eine Uhrzeit heute genannt wurde, die schon vorbei ist -> auf morgen schieben
    if (!hasCustomDate && hasCustomTime && cal.timeInMillis < nowMillis) {
        cal.add(Calendar.DAY_OF_YEAR, 1)
    }

    require(cal.timeInMillis >= nowMillis || (!hasCustomDate && !hasCustomTime)) {
        "Der gewünschte Zeitpunkt liegt in der Vergangenheit."
    }
    val cleanedText = working.replace(Regex("\\s{2,}"), " ").trim()
    val labelFmt = SimpleDateFormat("EEEE, dd.MM. 'ab' HH:mm 'Uhr'", Locale.GERMANY)
    return cleanedText to (cal.timeInMillis to labelFmt.format(cal.time))
}

fun parseNaturalTransitRequest(rawInput: String, nowMillis: Long = System.currentTimeMillis()): ParsedVoiceQuery {
    val (textWithoutTime, parsedTimeInfo) = extractFutureDateTime(rawInput.trim().trimEnd('?', '!'), nowMillis)
    val targetMillis = parsedTimeInfo?.first
    val timeLabel = parsedTimeInfo?.second

    val text = textWithoutTime.trim().trimEnd('.')
    val lower = text.lowercase(Locale.GERMANY)

    val (modeCode, modeLabel) = when {
        Regex("\\b(u-bahn|ubahn|u bahn)\\b").containsMatchIn(lower) -> "SUBWAY" to "U-Bahn"
        Regex("\\b(s-bahn|sbahn|s bahn)(?!hof)\\b").containsMatchIn(lower) -> "SUBURBAN" to "S-Bahn"
        Regex("\\b(tram|straßenbahn|strassenbahn)\\b").containsMatchIn(lower) -> "TRAM" to "Tram"
        Regex("\\b(zug|bahn|regionalbahn|re|rb)\\b").containsMatchIn(lower) -> "REGIONAL_RAIL" to "Regionalbahn"
        Regex("\\b(bus|stadtbus|nachtbus)\\b").containsMatchIn(lower) -> "BUS" to "Bus"
        else -> null to null
    }

    val poiHit = detectPoiCategory(lower)
    if (poiHit != null) {
        val (category, matchedKeyword) = poiHit
        val afterKeyword = lower.substringAfter(matchedKeyword).trim()
        val anchorMatch = Regex("^(?:am|an\\s+der|an\\s+dem|beim|bei\\s+der|bei\\s+dem|in|nahe)\\s+(.+)$", RegexOption.IGNORE_CASE)
            .find(afterKeyword)
        val anchorRaw = anchorMatch?.groupValues?.get(1)?.trim()?.trimEnd('.', '?', '!')
        val isNearbyPhrase = anchorRaw == null ||
                Regex("^(der\\s+nähe|der\\s+naehe|nähe|naehe|hier|mir|umgebung)$", RegexOption.IGNORE_CASE)
                    .matches(anchorRaw)

        val anchorPlace = if (isNearbyPhrase) null else cleanPlaceArticle(anchorRaw ?: "")
        return ParsedVoiceQuery(
            origin = null,
            destination = if (anchorPlace != null) "${category.label} am $anchorPlace" else "${category.label} in der Nähe",
            preferredMode = modeCode,
            modeDisplayName = modeLabel,
            poiCategory = category,
            poiAnchorPlace = anchorPlace?.ifBlank { null },
            targetTimeMillis = targetMillis,
            timeDisplayLabel = timeLabel
        )
    }

    val fromToRegex = Regex(
        "\\b(?:von|ab)\\s+(.+?)\\s+(?:in\\s+richtung|richtung|nach|zum|zur|zu)\\s+(.+)$",
        RegexOption.IGNORE_CASE
    )
    val fromToMatch = fromToRegex.find(text)
    if (fromToMatch != null) {
        val rawDest = cleanPlaceArticle(fromToMatch.groupValues[2])
        if (rawDest.isNotBlank()) {
            return ParsedVoiceQuery(
                origin = cleanPlaceArticle(fromToMatch.groupValues[1]),
                destination = rawDest,
                preferredMode = modeCode,
                modeDisplayName = modeLabel,
                poiCategory = null,
                poiAnchorPlace = null,
                targetTimeMillis = targetMillis,
                timeDisplayLabel = timeLabel
            )
        }
    }

    val targetRegex = Regex(
        "(?:in\\s+richtung|richtung|nach|zum|zur|zu)\\s+(.+)$",
        RegexOption.IGNORE_CASE
    )
    val match = targetRegex.find(text)
    var extractedDest = if (match != null) {
        match.groupValues[1].trim()
    } else {
        text.replace(
            Regex(
                "^(wie\\s+komme\\s+ich\\s+|wann\\s+fährt\\s+|wann\\s+faehrt\\s+|fährt\\s+ein\\s+|faehrt\\s+ein\\s+|nächster\\s+|naechster\\s+|nächste\\s+|naechste\\s+|mit\\s+dem\\s+|mit\\s+der\\s+|bus\\s+|u-bahn\\s+|ubahn\\s+|s-bahn\\s+|sbahn\\s+|ein\\s+)+",
                RegexOption.IGNORE_CASE
            ),
            ""
        ).trim()
    }

    extractedDest = cleanPlaceArticle(extractedDest)
    if (extractedDest.isBlank()) {
        extractedDest = text
    }

    return ParsedVoiceQuery(
        origin = null,
        destination = extractedDest,
        preferredMode = modeCode,
        modeDisplayName = modeLabel,
        poiCategory = null,
        poiAnchorPlace = null,
        targetTimeMillis = targetMillis,
        timeDisplayLabel = timeLabel
    )
}

/** Keep destination meaning ahead of proximity and generic stop preference. */
internal fun normalizedPlaceName(value: String): String = value.lowercase(Locale.GERMANY)
    .replace("ß", "ss").replace("ä", "ae").replace("ö", "oe").replace("ü", "ue")
    .replace(Regex("s[- ]?bahnhof"), "s ")
    .replace(Regex("u[- ]?bahnhof"), "u ")
    .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

internal fun placeNameMatchScore(query: String, name: String): Int {
    val wanted = normalizedPlaceName(query).split(" ").filter { it.isNotBlank() }
    val actual = normalizedPlaceName(name).split(" ").filter { it.isNotBlank() }
    if (wanted.isEmpty()) return 0
    if (wanted == actual) return 1000
    val matched = wanted.count { it in actual }
    return matched * 100 / wanted.size + (if (matched == wanted.size) 100 else 0) +
        (if (actual.take(wanted.size) == wanted) 50 else 0)
}
