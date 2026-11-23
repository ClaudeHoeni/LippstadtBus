package de.lippstadt.bus.data

import java.util.Locale

private val NOISE_WORDS = Regex("""(?i)\b(ÖPNV|OEPNV|U-Bahn|U\s*-?\s*Bahn\s*[A-Z0-9]*|UBahn)\b""")
private val ARROW = Regex("""\s*→\s*""")
private val MULTI_SPACE = Regex("""\s{2,}""")

/** Entfernt störende Wörter (ÖPNV, U-Bahn) aus Sprach- und API-Texten. */
fun sanitizeTranscriptWords(text: String): String = text
    .replace(NOISE_WORDS, "")
    .replace(ARROW, " → ")
    .replace(MULTI_SPACE, " ")
    .trim()

fun formatGermanLineName(rawMode: String, routeShortName: String, routeLongName: String): String {
    val shortClean = sanitizeTranscriptWords(routeShortName.trim())
    val germanType = when (rawMode.uppercase(Locale.US)) {
        "BUS", "COACH" -> "Bus"
        "SUBURBAN" -> "S-Bahn"
        "TRAM" -> "Tram"
        "REGIONAL_RAIL", "REGIONAL_FAST_RAIL", "RAIL" -> "Zug"
        "HIGHSPEED_RAIL", "LONG_DISTANCE", "NIGHT_RAIL" -> "Fernzug"
        "FERRY" -> "Fähre"
        else -> ""
    }

    if (shortClean.isBlank()) {
        val cleanedLong = sanitizeTranscriptWords(routeLongName)
        return cleanedLong.ifBlank { germanType.ifBlank { "Linie" } }
    }

    val upperShort = shortClean.uppercase(Locale.US)
    return when {
        germanType == "S-Bahn" && upperShort.startsWith("S") -> "S-Bahn $shortClean"
        upperShort.startsWith("BUS ") -> shortClean
        upperShort.startsWith("RE") || upperShort.startsWith("RB") ||
            upperShort.startsWith("IC") || upperShort.startsWith("ICE") -> shortClean
        germanType.isBlank() -> shortClean
        else -> "$germanType $shortClean"
    }
}

fun formatTimeForSpeech(hhMm: String): String {
    val parts = hhMm.split(":")
    if (parts.size != 2) return hhMm
    val hour = parts[0].toIntOrNull() ?: return hhMm
    val min = parts[1].toIntOrNull() ?: return hhMm
    return if (min == 0) "$hour Uhr" else "$hour Uhr $min"
}

fun buildSpokenSummary(conn: TransitConnection): String {
    val sb = StringBuilder()
    if (conn.isWalkOnly) {
        val walkLeg = conn.legs.firstOrNull()
        val dist = walkLeg?.distanceMeters ?: conn.totalWalkMeters
        val mins = walkLeg?.durationMinutes ?: conn.totalDurationMinutes
        val target = walkLeg?.toStop ?: conn.firstDirection
        sb.append("Das Ziel $target ist zu Fuß erreichbar. ")
        sb.append("Entfernung circa $dist Meter, Gehzeit etwa $mins Minuten.")
        return sanitizeTranscriptWords(sb.toString())
    }

    val delayNote = if (conn.firstDelayMinutes > 0) ", aktuell ${conn.firstDelayMinutes} Minuten Verspätung" else ""
    val firstTransit = conn.firstTransitLeg
    if (firstTransit != null) {
        sb.append("Abfahrt um ${formatTimeForSpeech(conn.firstDepartureTime)}$delayNote ")
        sb.append("ab ${firstTransit.fromStop} mit ${firstTransit.lineName} ")
        sb.append("Richtung ${firstTransit.direction}. ")
    }

    for (leg in conn.legs) {
        if (leg.isTransfer) {
            sb.append("Umstieg an ${leg.fromStop}, ${leg.durationMinutes} Minuten Übergang. ")
        } else if (!leg.isWalk && leg !== firstTransit) {
            sb.append("Weiter um ${formatTimeForSpeech(leg.startTime)} mit ${leg.lineName} ")
            sb.append("Richtung ${leg.direction}. ")
        }
    }

    sb.append("Ankunft am Ziel um ${formatTimeForSpeech(conn.finalArrivalTime)}. ")
    sb.append("Gesamtdauer ${conn.totalDurationMinutes} Minuten.")
    return sanitizeTranscriptWords(sb.toString())
}

fun buildLegAnnouncement(leg: RouteLegInfo): String = sanitizeTranscriptWords(
    "Um ${formatTimeForSpeech(leg.startTime)} ab ${leg.fromStop} mit ${leg.lineName} " +
        "Richtung ${leg.direction}. Ankunft um ${formatTimeForSpeech(leg.endTime)} an ${leg.toStop}."
)

fun buildTransferOrWalkWarningText(conn: TransitConnection): String? {
    if (conn.isWalkOnly) return null
    val hasTransfer = conn.transfersCount > 0 || conn.legs.any { it.isTransfer }
    val lastLeg = conn.legs.lastOrNull()
    val hasFinalWalk = lastLeg?.isWalk == true && !lastLeg.isTransfer
    val walkMeters = lastLeg?.distanceMeters ?: 0
    return when {
        hasTransfer && hasFinalWalk -> "Verbindung mit Umstieg und Fußweg zum Ziel ($walkMeters m)."
        hasTransfer -> "Verbindung enthält mindestens einen Umstieg."
        hasFinalWalk -> "Letzter Abschnitt ist ein Fußweg zum Ziel ($walkMeters m)."
        else -> null
    }
}

fun transferLabel(conn: TransitConnection): String = when {
    conn.isWalkOnly -> "Fußweg"
    conn.transfersCount == 0 -> "Direkt"
    conn.transfersCount == 1 -> "1 Umstieg"
    else -> "${conn.transfersCount} Umstiege"
}
