package de.lippstadt.bus.data

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class PoiCategory(
    val label: String,
    val overpassQueries: List<String>,
    val fallbackKeyword: String,
    val isTransitStopSearch: Boolean = false,
    /** Nur Treffer, deren Name dies enthält (Markensuche, z. B. "rewe"). */
    val nameFilter: String? = null,
    /**
     * Küchen-Suche (Inder, Italiener …): Namensmuster für die schnelle Umkreissuche, die keine
     * Küche kennt. Overpass sucht parallel direkt nach "cuisine".
     */
    val nameRegex: String? = null
)

data class ParsedVoiceQuery(
    val destination: String,
    val preferredMode: String?,
    val modeDisplayName: String?,
    val poiCategory: PoiCategory?,
    val poiAnchorPlace: String?,
    val targetTimeMillis: Long? = null,
    val timeDisplayLabel: String? = null,
    /** Ziel sieht aus wie ein Straßenname -> Straßensuche im Umkreis mit Auswahl. */
    val isStreet: Boolean = false
)

private val GERMAN = Locale.GERMANY
private val ARTICLE = Regex("^(dem|der|den|das|die|einem|einer|einen|eine|ein)\\s+", RegexOption.IGNORE_CASE)

private fun cleanPlaceArticle(place: String): String = place.replace(ARTICLE, "").trim()

val TRANSIT_STOP_CATEGORY = PoiCategory(
    label = "Nächste Haltestelle",
    overpassQueries = listOf(
        """["highway"="bus_stop"]""",
        """["public_transport"="platform"]""",
        """["railway"~"station|halt|tram_stop"]"""
    ),
    fallbackKeyword = "Haltestelle",
    isTransitStopSearch = true
)

val TRAIN_STATION_CATEGORY = PoiCategory(
    label = "Nächster Bahnhof",
    overpassQueries = listOf("""["railway"~"station|halt"]"""),
    fallbackKeyword = "Bahnhof"
)

/** "Bahnhof" ohne Ortsnamen = nächster Bahnhof (sonst fand die Textsuche z. B. "Nauen, Bahnhof" 43 km entfernt). */
private val GENERIC_STATION = Regex(
    "^(?:(?:den|der|die|zum|zur)\\s+)?(?:nächste[nrs]?\\s+|naechste[nrs]?\\s+)?(bahnhof|bhf|s-bahnhof|u-bahnhof|bahnstation|s-bahn|u-bahn)" +
        "(?:\\s+(?:in\\s+der\\s+nähe|in\\s+der\\s+naehe|hier|in\\s+meiner\\s+nähe))?$",
    RegexOption.IGNORE_CASE
)

private class PoiRule(
    val regex: Regex,
    val category: PoiCategory,
    /** Markennamen: Suche dann gezielt nach diesem Namen statt nach jeder Filiale. */
    val brands: Set<String> = emptySet()
)

private fun rx(words: String) = Regex("\\b($words)\\b")

private val POI_RULES = listOf(
    PoiRule(rx("bushaltestelle|haltestelle|busstop|bushalt"), TRANSIT_STOP_CATEGORY),
    PoiRule(
        rx("arzt|ärzte|aerzte|doktor|arztpraxis|hausarzt|klinik"),
        PoiCategory("Arzt / Praxis", listOf("""["amenity"="doctors"]""", """["amenity"="clinic"]""", """["healthcare"="doctor"]"""), "Arzt")
    ),
    PoiRule(
        rx("spätkauf|spaetkauf|späti|spaeti|kiosk|trinkhalle"),
        PoiCategory("Spätkauf / Kiosk", listOf("""["shop"="convenience"]""", """["shop"="kiosk"]"""), "Kiosk")
    ),
    PoiRule(
        rx("bäckerei|baeckerei|bäcker|baecker|brötchen|broetchen"),
        PoiCategory("Bäckerei", listOf("""["shop"="bakery"]"""), "Bäckerei")
    ),
    PoiRule(
        rx("döner|doener|kebab"),
        PoiCategory("Döner / Imbiss", listOf("""["amenity"="fast_food"]["cuisine"~"kebab",i]""", """["amenity"="fast_food"]"""), "Döner")
    ),
    PoiRule(
        rx("pommesbude|pommes|imbiss|currywurst|fritten|snack"),
        PoiCategory("Imbiss / Pommesbude", listOf("""["amenity"="fast_food"]"""), "Imbiss")
    ),
    PoiRule(rx("apotheke"), PoiCategory("Apotheke", listOf("""["amenity"="pharmacy"]"""), "Apotheke")),
    PoiRule(
        rx("drogerie|dm|rossmann"),
        PoiCategory("Drogerie", listOf("""["shop"="chemist"]"""), "Drogerie"),
        brands = setOf("dm", "rossmann")
    ),
    PoiRule(
        rx("supermarkt|edeka|rewe|aldi|lidl|penny|netto|kaufland|einkaufen"),
        PoiCategory("Supermarkt", listOf("""["shop"="supermarket"]""", """["shop"="discount"]"""), "Supermarkt"),
        brands = setOf("edeka", "rewe", "aldi", "lidl", "penny", "netto", "kaufland")
    ),
    PoiRule(
        rx("geldautomat|bankautomat|sparkasse|volksbank|bank"),
        PoiCategory("Geldautomat / Bank", listOf("""["amenity"="atm"]""", """["amenity"="bank"]"""), "Bank"),
        brands = setOf("sparkasse", "volksbank")
    ),
    PoiRule(
        rx("kunst|galerie|galerien|gallery|ausstellung|ausstellungen|kunsthalle|kunstmuseum|vernissage|atelier"),
        PoiCategory(
            "Galerie / Ausstellung",
            listOf("""["tourism"="gallery"]""", """["shop"="art"]""", """["amenity"="arts_centre"]""", """["tourism"="museum"]"""),
            "Galerie"
        )
    ),
    PoiRule(rx("café|cafe|kaffee"), PoiCategory("Café", listOf("""["amenity"="cafe"]"""), "Café")),
    // Bewusst NICHT "essen" allein – sonst wird "nach Essen" (Stadt) zur Restaurantsuche.
    PoiRule(
        rx("restaurant|essen gehen|was essen|mittagessen|abendessen"),
        PoiCategory("Restaurant", listOf("""["amenity"="restaurant"]"""), "Restaurant")
    ),
    PoiRule(rx("toilette|wc|klo"), PoiCategory("Toilette / WC", listOf("""["amenity"="toilets"]"""), "WC")),
    PoiRule(
        rx("friedhof|hauptfriedhof|grab|beerdigung"),
        PoiCategory("Friedhof", listOf("""["landuse"="cemetery"]""", """["amenity"="grave_yard"]"""), "Friedhof")
    ),
    PoiRule(rx("friseur|frisör|frisoer|haare\\s+schneiden"), PoiCategory("Friseur", listOf("""["shop"="hairdresser"]"""), "Friseur")),
    PoiRule(rx("krankenhaus|notaufnahme|klinikum"), PoiCategory("Krankenhaus", listOf("""["amenity"="hospital"]"""), "Krankenhaus")),
    PoiRule(rx("polizei|polizeiwache"), PoiCategory("Polizei", listOf("""["amenity"="police"]"""), "Polizei")),
    PoiRule(rx("tankstelle|tanken"), PoiCategory("Tankstelle", listOf("""["amenity"="fuel"]"""), "Tankstelle")),
    PoiRule(
        rx("paketshop|post|dhl|packstation"),
        PoiCategory("Post / Packstation", listOf("""["amenity"="post_office"]""", """["amenity"="parcel_locker"]"""), "Post")
    )
)


private fun cuisine(label: String, cuisineRegex: String, names: String, diet: String? = null): PoiCategory {
    val filter = if (diet != null) """["$diet"~"yes|only"]""" else """["cuisine"~"$cuisineRegex",i]"""
    return PoiCategory(
        label = label,
        overpassQueries = listOf("""["amenity"="restaurant"]$filter""", """["amenity"="fast_food"]$filter"""),
        fallbackKeyword = label,
        nameRegex = names
    )
}

private val CUISINE_RULES = listOf(
    PoiRule(
        rx("inder|indisch|indische[nrs]?|indian|curry"),
        cuisine("Indisches Restaurant", "indian", "india|indisch|indian|taj|curry|masala|punjab|bombay|delhi|maharaja|ganesha|tandoor|bollywood")
    ),
    PoiRule(
        rx("vietnamese|vietnamesen|vietnamesisch|vietnamesische[nrs]?|pho"),
        cuisine("Vietnamesisches Restaurant", "vietnamese", "vietnam|pho|saigon|hanoi|banh|bun|viet")
    ),
    PoiRule(
        rx("chinese|chinesen|chinesisch|chinesische[nrs]?|china"),
        cuisine("Chinesisches Restaurant", "chinese", "china|chines|peking|beijing|canton|kanton|shanghai|wok|dragon|drachen|mandarin|hongkong")
    ),
    PoiRule(
        rx("asiate|asiaten|asiatisch|asiatische[nrs]?|asia|wok|sushi|japaner|japanisch|thai|thailänder|koreaner|koreanisch"),
        cuisine(
            "Asiatisches Restaurant", "asian|chinese|japanese|thai|vietnamese|sushi|korean",
            "asia|wok|sushi|thai|vietnam|pho|japan|korea|ramen|bao|china|saigon|bangkok|tokyo|kyoto"
        )
    ),
    PoiRule(
        rx("italiener|italienisch|italienische[nrs]?|pizza|pizzeria|pasta"),
        cuisine("Italienisches Restaurant", "italian|pizza", "pizza|pizzeria|italia|trattoria|ristorante|osteria|roma|napoli|venezia|pasta|toscana|sicilia|bella")
    ),
    PoiRule(
        rx("grieche|griechen|griechisch|griechische[nrs]?"),
        cuisine("Griechisches Restaurant", "greek", "griech|hellas|akropolis|athen|olymp|mykonos|zorbas|taverna|sirtaki|kreta|rhodos")
    ),
    PoiRule(
        rx("türke|türken|türkisch|türkische[nrs]?"),
        cuisine("Türkisches Restaurant", "turkish|kebab", "türk|istanbul|anatol|kebab|döner|bosporus|ankara|izmir")
    ),
    PoiRule(
        rx("mexikaner|mexikanisch|mexikanische[nrs]?|tacos?|burrito"),
        cuisine("Mexikanisches Restaurant", "mexican", "mexi|taco|burrito|cantina|chili|azteca")
    ),
    PoiRule(
        rx("vegan|veganer|vegane[nrs]?"),
        cuisine("Veganes Restaurant", "", "vegan|veggie|plant", diet = "diet:vegan")
    ),
    PoiRule(
        rx("vegetarisch|vegetarier|vegetarische[nrs]?|veggie"),
        cuisine("Vegetarisches Restaurant", "", "vegan|veggie|vegetar|plant", diet = "diet:vegetarian")
    ),
    PoiRule(
        rx("burger|hamburger"),
        cuisine("Burger", "burger", "burger")
    )
)

private data class PoiHit(val category: PoiCategory, val keywordStart: Int, val keywordEnd: Int)

private fun detectPoiCategory(text: String): PoiHit? {
    val lower = text.lowercase(GERMAN)
    // Küchen zuerst prüfen ("Asia Wok" vor "Imbiss"), dann die übrigen Kategorien
    for (rule in CUISINE_RULES + POI_RULES) {
        val m = rule.regex.find(lower) ?: continue
        val keyword = m.value
        val category = if (keyword in rule.brands) {
            val brand = keyword.replaceFirstChar { it.titlecase(GERMAN) }
            rule.category.copy(
                label = brand,
                overpassQueries = rule.category.overpassQueries.map { "$it[\"name\"~\"$keyword\",i]" },
                fallbackKeyword = brand,
                nameFilter = keyword
            )
        } else {
            rule.category
        }
        return PoiHit(category, m.range.first, m.range.last + 1)
    }
    return null
}

// ---------------------------------------------------------------- Zeit

private val DAYTIME_HOURS = linkedMapOf(
    "nachmittags" to 15, "nachmittag" to 15,
    "vormittags" to 10, "vormittag" to 10,
    "früh" to 7, "frueh" to 7, "morgens" to 7,
    "mittags" to 12, "mittag" to 12,
    "abends" to 18, "abend" to 18,
    "nachts" to 22
)

private val WEEKDAYS = mapOf(
    "montag" to Calendar.MONDAY, "dienstag" to Calendar.TUESDAY, "mittwoch" to Calendar.WEDNESDAY,
    "donnerstag" to Calendar.THURSDAY, "freitag" to Calendar.FRIDAY, "samstag" to Calendar.SATURDAY,
    "sonnabend" to Calendar.SATURDAY, "sonntag" to Calendar.SUNDAY
)

private val REL_MINUTES = Regex("\\bin\\s+(\\d+|einer|einem|einer\\s+halben)\\s*(stunden?|std|minuten?|min)\\b", RegexOption.IGNORE_CASE)
private val DAYTIME = Regex("\\b(${DAYTIME_HOURS.keys.joinToString("|")})\\b", RegexOption.IGNORE_CASE)
private val WEEKDAY = Regex(
    "\\b(?:am\\s+|nächsten\\s+|naechsten\\s+|kommenden\\s+)?(${WEEKDAYS.keys.joinToString("|")})\\b",
    RegexOption.IGNORE_CASE
)
private val REL_DAY = Regex("\\b(übermorgen|uebermorgen|morgen|heute)\\b", RegexOption.IGNORE_CASE)

// Kein abschließendes \b nach dem Punkt – sonst greift "am 24.12." am Satzende nicht.
private val DATE = Regex("\\b(?:am\\s+)?(\\d{1,2})\\.(\\d{1,2})\\.(\\d{2,4})?(?!\\d)")
private val TIME = Regex(
    "\\b(?:um|ab|gegen)\\s+(\\d{1,2})(?::(\\d{2}))?\\s*(?:uhr(?:\\s*(\\d{1,2}))?)?\\b" +
        "|\\b(\\d{1,2}):(\\d{2})\\s*(?:uhr)?\\b" +
        "|\\b(\\d{1,2})\\s*uhr(?:\\s*(\\d{1,2}))?\\b",
    RegexOption.IGNORE_CASE
)

private data class TimeExtraction(val remainingText: String, val millis: Long?, val label: String?)

private fun labelFor(cal: Calendar, tz: TimeZone): String =
    SimpleDateFormat("EEEE, dd.MM. 'ab' HH:mm 'Uhr'", GERMAN).apply { timeZone = tz }.format(Date(cal.timeInMillis))

private fun extractFutureDateTime(rawText: String, nowMillis: Long, tz: TimeZone): TimeExtraction {
    var working = rawText
    val cal = Calendar.getInstance(tz, GERMAN).apply { timeInMillis = nowMillis }

    REL_MINUTES.find(working)?.let { m ->
        val amountRaw = m.groupValues[1].lowercase(GERMAN)
        val isHour = m.groupValues[2].lowercase(GERMAN).let { it.startsWith("st") }
        val minutes = when {
            amountRaw.contains("halben") -> 30
            amountRaw == "einer" || amountRaw == "einem" -> if (isHour) 60 else 1
            else -> (amountRaw.toIntOrNull() ?: 0) * if (isHour) 60 else 1
        }
        cal.add(Calendar.MINUTE, minutes)
        working = working.replace(m.value, " ")
        return TimeExtraction(working.collapseSpaces(), cal.timeInMillis, labelFor(cal, tz))
    }

    var hasCustomDate = false
    var hasCustomTime = false
    var defaultHour: Int? = null

    DAYTIME.find(working)?.let { m ->
        defaultHour = DAYTIME_HOURS[m.groupValues[1].lowercase(GERMAN)]
        working = working.replace(m.value, " ")
    }

    WEEKDAY.find(working)?.let { m ->
        val targetDow = WEEKDAYS[m.groupValues[1].lowercase(GERMAN)] ?: return@let
        var daysDiff = (targetDow - cal.get(Calendar.DAY_OF_WEEK) + 7) % 7
        if (daysDiff == 0) daysDiff = 7
        cal.add(Calendar.DAY_OF_YEAR, daysDiff)
        hasCustomDate = true
        working = working.replace(m.value, " ")
    }

    var isToday = false
    REL_DAY.find(working)?.let { m ->
        when (m.groupValues[1].lowercase(GERMAN)) {
            "übermorgen", "uebermorgen" -> { cal.add(Calendar.DAY_OF_YEAR, 2); hasCustomDate = true }
            "morgen" -> { cal.add(Calendar.DAY_OF_YEAR, 1); hasCustomDate = true }
            "heute" -> isToday = true
        }
        working = working.replace(m.value, " ")
    }

    DATE.find(working)?.let { m ->
        val d = m.groupValues[1].toIntOrNull() ?: return@let
        val mo = m.groupValues[2].toIntOrNull() ?: return@let
        if (d !in 1..31 || mo !in 1..12) return@let
        cal.set(Calendar.MONTH, mo - 1)
        cal.set(Calendar.DAY_OF_MONTH, d)
        val yRaw = m.groupValues[3]
        if (yRaw.isNotBlank()) {
            val y = yRaw.toInt()
            cal.set(Calendar.YEAR, if (y < 100) 2000 + y else y)
        } else if (cal.timeInMillis < nowMillis - 12 * 3_600_000L) {
            cal.add(Calendar.YEAR, 1)
        }
        hasCustomDate = true
        working = working.replace(m.value, " ")
    }

    TIME.find(working)?.let { m ->
        val g = m.groupValues
        val hourStr = listOf(g[1], g[4], g[6]).firstOrNull { it.isNotBlank() }
        val minStr = listOf(g[2], g[3], g[5], g[7]).firstOrNull { it.isNotBlank() }
        var hour = hourStr?.toIntOrNull() ?: return@let
        val minute = minStr?.toIntOrNull() ?: 0
        // "um 7 abends" → 19 Uhr
        val dh = defaultHour
        if (dh != null && dh >= 15 && hour in 1..11) hour += 12
        if (hour !in 0..23 || minute !in 0..59) return@let
        cal.set(Calendar.HOUR_OF_DAY, hour)
        cal.set(Calendar.MINUTE, minute)
        hasCustomTime = true
        working = working.replace(m.value, " ")
    }

    if (!hasCustomTime && defaultHour != null) {
        cal.set(Calendar.HOUR_OF_DAY, defaultHour!!)
        cal.set(Calendar.MINUTE, 0)
        hasCustomTime = true
    }

    val cleaned = working.collapseSpaces()
    if (!hasCustomDate && !hasCustomTime) {
        // "heute" allein bedeutet "jetzt" – keine Sonderzeit setzen.
        return TimeExtraction(if (isToday) cleaned else rawText, null, null)
    }

    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    if (hasCustomDate && !hasCustomTime) {
        cal.set(Calendar.HOUR_OF_DAY, 8)
        cal.set(Calendar.MINUTE, 0)
    }
    if (!hasCustomDate && !isToday && cal.timeInMillis < nowMillis - 5 * 60_000L) {
        cal.add(Calendar.DAY_OF_YEAR, 1)
    }
    return TimeExtraction(cleaned, cal.timeInMillis, labelFor(cal, tz))
}

private fun String.collapseSpaces() = replace(Regex("\\s{2,}"), " ").trim()

// ---------------------------------------------------------------- Ziel

private val FROM_TO = Regex("\\b(?:von|ab)\\s+.+?\\s+(?:in\\s+richtung|richtung|nach|zum|zur|zu)\\s+(.+)$", RegexOption.IGNORE_CASE)
private val TO = Regex("\\b(?:in\\s+richtung|richtung|nach|zum|zur|zu)\\s+(.+)$", RegexOption.IGNORE_CASE)
private val LEADING_FILLER = Regex(
    "^(wie\\s+komme\\s+ich\\s+|wo\\s+ist\\s+(?:hier\\s+)?(?:die\\s+|der\\s+|das\\s+|ein\\s+|eine\\s+)?|" +
        "wo\\s+gibt\\s+es\\s+(?:hier\\s+)?|zeig\\s+mir\\s+|suche\\s+|ich\\s+suche\\s+|bring\\s+mich\\s+|" +
        "wann\\s+fährt\\s+|wann\\s+faehrt\\s+|fährt\\s+|faehrt\\s+|der\\s+|die\\s+|das\\s+|ein\\s+|eine\\s+|" +
        "nächster\\s+|naechster\\s+|nächste\\s+|naechste\\s+|nächsten\\s+|naechsten\\s+|" +
        "mit\\s+dem\\s+|mit\\s+der\\s+|bus\\s+|zug\\s+|bahn\\s+|s-bahn\\s+|sbahn\\s+)+",
    RegexOption.IGNORE_CASE
)
private val ANCHOR = Regex(
    "^(?:am|an\\s+der|an\\s+dem|beim|bei\\s+der|bei\\s+dem|bei|in|nahe|neben)\\s+(.+)$",
    RegexOption.IGNORE_CASE
)
private val NEARBY = Regex("^(der\\s+nähe|der\\s+naehe|nähe|naehe|hier|mir|umgebung|der\\s+umgebung)$", RegexOption.IGNORE_CASE)

private fun detectMode(lower: String): Pair<String?, String?> = when {
    Regex("\\b(s-bahn|sbahn|s bahn)\\b").containsMatchIn(lower) -> "SUBURBAN" to "S-Bahn"
    Regex("\\b(tram|straßenbahn|strassenbahn)\\b").containsMatchIn(lower) -> "TRAM" to "Tram"
    Regex("\\b(zug|bahn|regionalbahn|re|rb)\\b").containsMatchIn(lower) -> "REGIONAL_RAIL" to "Zug"
    Regex("\\b(bus|stadtbus|nachtbus)\\b").containsMatchIn(lower) -> "BUS" to "Bus"
    else -> null to null
}

private fun extractDestinationPhrase(text: String): String {
    FROM_TO.find(text)?.let { m ->
        val d = cleanPlaceArticle(m.groupValues[1])
        if (d.isNotBlank()) return d
    }
    TO.find(text)?.let { m ->
        val d = cleanPlaceArticle(m.groupValues[1].replace(LEADING_FILLER, ""))
        if (d.isNotBlank()) return d
    }
    val stripped = cleanPlaceArticle(text.replace(LEADING_FILLER, ""))
    return stripped.ifBlank { text }
}

fun parseNaturalTransitRequest(
    rawInput: String,
    nowMillis: Long = System.currentTimeMillis(),
    timeZone: TimeZone = TimeZone.getDefault()
): ParsedVoiceQuery {
    // Punkt erst NACH der Zeitextraktion entfernen, sonst greift "am 24.12." nicht.
    val time = extractFutureDateTime(rawInput.trim().trimEnd('?', '!'), nowMillis, timeZone)
    val text = time.remainingText.trim().trimEnd('.', '?', '!', ' ')
    val (modeCode, modeLabel) = detectMode(text.lowercase(GERMAN))

    val destPhrase = extractDestinationPhrase(text)
    if (GENERIC_STATION.matches(destPhrase.trim())) {
        return ParsedVoiceQuery(
            destination = "Nächster Bahnhof",
            preferredMode = modeCode,
            modeDisplayName = modeLabel,
            poiCategory = TRAIN_STATION_CATEGORY,
            poiAnchorPlace = null,
            targetTimeMillis = time.millis,
            timeDisplayLabel = time.label
        )
    }
    val poi = detectPoiCategory(destPhrase)
    if (poi != null) {
        val afterKeyword = destPhrase.substring(poi.keywordEnd).trim()
        val anchorRaw = ANCHOR.find(afterKeyword)?.groupValues?.get(1)?.trim()
        val anchorPlace = anchorRaw
            ?.takeUnless { NEARBY.matches(it) }
            ?.let { cleanPlaceArticle(it) }
            ?.ifBlank { null }
        return ParsedVoiceQuery(
            destination = if (anchorPlace != null) "${poi.category.label} am $anchorPlace" else "${poi.category.label} in der Nähe",
            preferredMode = modeCode,
            modeDisplayName = modeLabel,
            poiCategory = poi.category,
            poiAnchorPlace = anchorPlace,
            targetTimeMillis = time.millis,
            timeDisplayLabel = time.label
        )
    }

    return ParsedVoiceQuery(
        destination = destPhrase,
        preferredMode = modeCode,
        modeDisplayName = modeLabel,
        poiCategory = null,
        poiAnchorPlace = null,
        targetTimeMillis = time.millis,
        timeDisplayLabel = time.label,
        isStreet = StreetMatch.looksLikeStreet(destPhrase)
    )
}
