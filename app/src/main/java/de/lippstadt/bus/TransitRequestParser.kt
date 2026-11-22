package de.lippstadt.bus

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

data class PoiCategory(
    val label: String,
    val overpassQueries: List<String>,
    val fallbackKeyword: String,
    val isTransitStopSearch: Boolean = false
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
    val map = listOf(
        Regex("\\b(bushaltestelle|haltestelle|busstop|station)\\b") to PoiCategory(
            label = "Nächste Haltestelle",
            overpassQueries = listOf("""["highway"="bus_stop"]""", """["public_transport"="platform"]""", """["railway"~"station|halt|tram_stop"]"""),
            fallbackKeyword = "Haltestelle",
            isTransitStopSearch = true
        ),
        Regex("\\b(arzt|ärzte|aerzte|doktor|arztpraxis|praxis|klinik)\\b") to PoiCategory(
            label = "Arzt / Praxis",
            overpassQueries = listOf("""["amenity"="doctors"]""", """["amenity"="clinic"]""", """["healthcare"="doctor"]"""),
            fallbackKeyword = "Arzt"
        ),
        Regex("\\b(spätkauf|spaetkauf|späti|spaeti|kiosk|trinkhalle)\\b") to PoiCategory(
            label = "Spätkauf / Kiosk",
            overpassQueries = listOf("""["shop"="convenience"]""", """["shop"="kiosk"]"""),
            fallbackKeyword = "Spätkauf"
        ),
        Regex("\\b(bäckerei|baeckerei|bäcker|baecker|brötchen|broetchen)\\b") to PoiCategory(
            label = "Bäckerei",
            overpassQueries = listOf("""["shop"="bakery"]""", """["amenity"="cafe"]["cuisine"~"bakery|pastry"]"""),
            fallbackKeyword = "Bäckerei"
        ),
        Regex("\\b(pommesbude|pommes|imbiss|currywurst|fritten|snack)\\b") to PoiCategory(
            label = "Imbiss / Pommesbude",
            overpassQueries = listOf("""["amenity"="fast_food"]"""),
            fallbackKeyword = "Imbiss"
        ),
        Regex("\\b(döner|doener|kebab)\\b") to PoiCategory(
            label = "Döner / Imbiss",
            overpassQueries = listOf("""["amenity"="fast_food"]"""),
            fallbackKeyword = "Döner"
        ),
        Regex("\\b(apotheke)\\b") to PoiCategory(
            label = "Apotheke",
            overpassQueries = listOf("""["amenity"="pharmacy"]"""),
            fallbackKeyword = "Apotheke"
        ),
        Regex("\\b(supermarkt|edeka|rewe|aldi|lidl|penny|netto|kaufland|einkaufen)\\b") to PoiCategory(
            label = "Supermarkt",
            overpassQueries = listOf("""["shop"="supermarket"]""", """["shop"="discount"]"""),
            fallbackKeyword = "Supermarkt"
        ),
        Regex("\\b(geldautomat|bankautomat|sparkasse|volksbank|bank)\\b") to PoiCategory(
            label = "Geldautomat / Bank",
            overpassQueries = listOf("""["amenity"="atm"]""", """["amenity"="bank"]"""),
            fallbackKeyword = "Bank"
        ),
        Regex("\\b(café|cafe|kaffee)\\b") to PoiCategory(
            label = "Café",
            overpassQueries = listOf("""["amenity"="cafe"]"""),
            fallbackKeyword = "Café"
        ),
        Regex("\\b(restaurant|essen)\\b") to PoiCategory(
            label = "Restaurant",
            overpassQueries = listOf("""["amenity"="restaurant"]"""),
            fallbackKeyword = "Restaurant"
        ),
        Regex("\\b(toilette|wc|klo)\\b") to PoiCategory(
            label = "Toilette / WC",
            overpassQueries = listOf("""["amenity"="toilets"]"""),
            fallbackKeyword = "WC"
        ),
        Regex("\\b(tankstelle|tanken)\\b") to PoiCategory(
            label = "Tankstelle",
            overpassQueries = listOf("""["amenity"="fuel"]"""),
            fallbackKeyword = "Tankstelle"
        ),
        Regex("\\b(paketshop|post|dhl|packstation)\\b") to PoiCategory(
            label = "Post / Packstation",
            overpassQueries = listOf("""["amenity"="post_office"]""", """["amenity"="parcel_locker"]"""),
            fallbackKeyword = "Post"
        )
    )
    for ((regex, cat) in map) {
        val m = regex.find(lowerText)
        if (m != null) return cat to m.value
    }
    return null
}

private fun extractFutureDateTime(rawText: String): Pair<String, Pair<Long, String>?> {
    var working = rawText
    val cal = Calendar.getInstance(Locale.GERMANY)
    val nowMillis = cal.timeInMillis
    var hasCustomDate = false
    var hasCustomTime = false

    val inRelMatch = Regex("\\bin\\s+(\\d+)\\s*(stunde|stunden|std|minute|minuten|min)\\b", RegexOption.IGNORE_CASE).find(working)
    if (inRelMatch != null) {
        val amount = inRelMatch.groupValues[1].toIntOrNull() ?: 0
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
            if (daysDiff == 0) daysDiff = 7
            cal.add(Calendar.DAY_OF_YEAR, daysDiff)
            hasCustomDate = true
            working = working.replace(dayMatch.value, " ")
        }
    }

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

    val dateRegex = Regex("\\b(?:am\\s+)?(\\d{1,2})\\.(\\d{1,2})\\.(?:(\\d{2,4}))?\\b")
    val dateMatch = dateRegex.find(working)
    if (dateMatch != null) {
        val d = dateMatch.groupValues[1].toIntOrNull()
        val m = dateMatch.groupValues[2].toIntOrNull()
        val yRaw = dateMatch.groupValues[3]
        if (d != null && m != null && d in 1..31 && m in 1..12) {
            cal.set(Calendar.MONTH, m - 1)
            cal.set(Calendar.DAY_OF_MONTH, d)
            if (yRaw.isNotBlank()) {
                val yInt = yRaw.toInt()
                cal.set(Calendar.YEAR, if (yInt < 100) 2000 + yInt else yInt)
            } else if (cal.timeInMillis < nowMillis - 12 * 3600 * 1000L) {
                cal.add(Calendar.YEAR, 1)
            }
            hasCustomDate = true
            working = working.replace(dateMatch.value, " ")
        }
    }

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
        }
    }

    if (!hasCustomDate && !hasCustomTime) {
        return rawText to null
    }

    if (hasCustomDate && !hasCustomTime) {
        cal.set(Calendar.HOUR_OF_DAY, 8)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
    }

    if (!hasCustomDate && hasCustomTime && cal.timeInMillis < nowMillis - 5 * 60 * 1000L) {
        cal.add(Calendar.DAY_OF_YEAR, 1)
    }

    val cleanedText = working.replace(Regex("\\s{2,}"), " ").trim()
    val labelFmt = SimpleDateFormat("EEEE, dd.MM. 'ab' HH:mm 'Uhr'", Locale.GERMANY)
    return cleanedText to (cal.timeInMillis to labelFmt.format(Date(cal.timeInMillis)))
}

fun parseNaturalTransitRequest(rawInput: String): ParsedVoiceQuery {
    val (textWithoutTime, parsedTimeInfo) = extractFutureDateTime(rawInput.trim().trimEnd('?', '!', '.'))
    val targetMillis = parsedTimeInfo?.first
    val timeLabel = parsedTimeInfo?.second

    val text = textWithoutTime.trim()
    val lower = text.lowercase(Locale.GERMANY)

    val (modeCode, modeLabel) = when {
        Regex("\\b(u-bahn|ubahn|u bahn)\\b").containsMatchIn(lower) -> "SUBWAY" to "U-Bahn"
        Regex("\\b(s-bahn|sbahn|s bahn)\\b").containsMatchIn(lower) -> "SUBURBAN" to "S-Bahn"
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
        val anchorRaw = anchorMatch?.groupValues?.get(1)?.trim()
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
        "(?:von|ab)\\s+.+?\\s+(?:in\\s+richtung|richtung|nach|zum|zur|zu)\\s+(.+)$",
        RegexOption.IGNORE_CASE
    )
    val fromToMatch = fromToRegex.find(text)
    if (fromToMatch != null) {
        val rawDest = cleanPlaceArticle(fromToMatch.groupValues[1])
        if (rawDest.isNotBlank()) {
            return ParsedVoiceQuery(
                origin = null,
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
                "^(wie\\s+komme\\s+ich\\s+|wo\\s+ist\\s+(?:hier\\s+)?(?:die\\s+|der\\s+)?(?:nächste\\s+|naechste\\s+)?|wann\\s+fährt\\s+|wann\\s+faehrt\\s+|fährt\\s+ein\\s+|faehrt\\s+ein\\s+|nächster\\s+|naechster\\s+|nächste\\s+|naechste\\s+|mit\\s+dem\\s+|mit\\s+der\\s+|bus\\s+|u-bahn\\s+|ubahn\\s+|s-bahn\\s+|sbahn\\s+|ein\\s+)+",
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