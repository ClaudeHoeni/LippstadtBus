package de.lippstadt.bus

import java.time.OffsetDateTime
import java.time.LocalDateTime
import java.time.ZoneId

internal fun parseIsoMillis(isoStr: String): Long? = try {
    OffsetDateTime.parse(isoStr).toInstant().toEpochMilli()
} catch (_: java.time.format.DateTimeParseException) {
    try {
        LocalDateTime.parse(isoStr).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    } catch (_: java.time.format.DateTimeParseException) {
        null
    }
}
