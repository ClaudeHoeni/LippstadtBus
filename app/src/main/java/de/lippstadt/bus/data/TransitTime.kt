package de.lippstadt.bus.data

import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

private val HH_MM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

fun parseIsoMillis(isoStr: String): Long? {
    if (isoStr.isBlank()) return null
    return try {
        OffsetDateTime.parse(isoStr).toInstant().toEpochMilli()
    } catch (_: DateTimeParseException) {
        try {
            LocalDateTime.parse(isoStr).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        } catch (_: DateTimeParseException) {
            null
        }
    }
}

/** Formatiert Epoch-Millis als lokale Uhrzeit "HH:mm". */
fun formatHhMm(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    Instant.ofEpochMilli(millis).atZone(zone).format(HH_MM)

/**
 * Wandelt einen ISO-Zeitstempel der API (meist UTC, z. B. "2026-11-23T08:15:00Z")
 * in lokale Uhrzeit um. Früher wurde einfach der Text nach "T" abgeschnitten –
 * dadurch wurden alle Zeiten in UTC (1–2 Std. falsch) angezeigt.
 */
fun isoToLocalHhMm(isoStr: String, zone: ZoneId = ZoneId.systemDefault()): String? =
    parseIsoMillis(isoStr)?.let { formatHhMm(it, zone) }

fun minutesBetween(startMillis: Long, endMillis: Long): Int =
    maxOf(0, ((endMillis - startMillis) / 60_000L).toInt())

private const val EARTH_RADIUS_M = 6_371_000.0

/** Luftlinie in Metern (Haversine) – ohne Android-Abhängigkeit, daher testbar. */
fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2) * sin(dLat / 2) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
    return 2 * EARTH_RADIUS_M * atan2(sqrt(a), sqrt(1 - a))
}

/** Peilung 0–360° (0 = Norden). */
fun bearingDegrees(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
    val p1 = Math.toRadians(lat1)
    val p2 = Math.toRadians(lat2)
    val dLon = Math.toRadians(lon2 - lon1)
    val y = sin(dLon) * cos(p2)
    val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dLon)
    val deg = Math.toDegrees(atan2(y, x))
    return ((deg + 360.0) % 360.0).toFloat()
}

fun formatDistance(meters: Int): String =
    if (meters >= 1000) String.format(java.util.Locale.GERMANY, "%.1f km", meters / 1000f) else "$meters m"

/** Gehzeit bei ca. 4,5 km/h. */
fun walkMinutesFor(meters: Int): Int = maxOf(1, (meters / 75.0).roundToInt())
