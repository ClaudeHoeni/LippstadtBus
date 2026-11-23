package de.lippstadt.bus.data

import org.json.JSONObject
import java.time.ZoneId

/**
 * Wandelt die Antwort von transitous.org (/api/v1/plan, MOTIS) in [TransitConnection]s um.
 * Reine Logik ohne Netzwerk – in Unit-Tests prüfbar.
 */
object MotisPlanParser {

    private data class RawLeg(val info: RouteLegInfo, val startMillis: Long?, val endMillis: Long?)

    fun parse(
        json: String,
        destName: String,
        destLat: Double,
        destLon: Double,
        referenceMillis: Long,
        zone: ZoneId = ZoneId.systemDefault()
    ): List<TransitConnection> {
        val root = JSONObject(json)
        val itineraries = root.optJSONArray("itineraries") ?: return emptyList()
        val minAllowedDeparture = referenceMillis - 60_000L
        val result = mutableListOf<TransitConnection>()

        for (i in 0 until itineraries.length()) {
            val itinerary = itineraries.optJSONObject(i) ?: continue
            val legsJson = itinerary.optJSONArray("legs") ?: continue
            if (legsJson.length() == 0) continue
            val totalDurSec = itinerary.optInt("duration", 0)

            val rawLegs = mutableListOf<RawLeg>()
            for (j in 0 until legsJson.length()) {
                val leg = legsJson.optJSONObject(j) ?: continue
                rawLegs += parseLeg(leg, isLast = j == legsJson.length() - 1, destName, destLat, destLon, zone)
            }
            if (rawLegs.isEmpty()) continue

            val transitLegs = rawLegs.filter { !it.info.isWalk }
            val walkMeters = rawLegs.filter { it.info.isWalk }.sumOf { it.info.distanceMeters }
            val last = rawLegs.last()
            val arrMillis = last.endMillis
                ?: ((rawLegs.first().startMillis ?: referenceMillis) + totalDurSec * 1000L)

            if (transitLegs.isEmpty()) {
                val first = rawLegs.first()
                result += TransitConnection(
                    summaryLines = "Fußweg zum Ziel",
                    firstLine = "Fußweg",
                    firstDirection = destName,
                    firstDepartureTime = first.info.startTime,
                    finalArrivalTime = last.info.endTime,
                    departureMillis = first.startMillis ?: referenceMillis,
                    arrivalMillis = arrMillis,
                    firstDelayMinutes = 0,
                    totalDurationMinutes = maxOf(1, totalDurSec / 60),
                    transfersCount = 0,
                    totalWalkMeters = walkMeters,
                    isWalkOnly = true,
                    legs = rawLegs.map { it.info }
                )
                continue
            }

            val firstTransit = transitLegs.first()
            val depMillis = firstTransit.startMillis ?: referenceMillis
            if (depMillis < minAllowedDeparture) continue

            result += TransitConnection(
                summaryLines = transitLegs.joinToString(" → ") { it.info.lineName },
                firstLine = firstTransit.info.lineName,
                firstDirection = firstTransit.info.direction,
                firstDepartureTime = firstTransit.info.startTime,
                finalArrivalTime = last.info.endTime,
                departureMillis = depMillis,
                arrivalMillis = arrMillis,
                firstDelayMinutes = firstTransit.info.delayMinutes,
                totalDurationMinutes = maxOf(1, totalDurSec / 60),
                transfersCount = transitLegs.size - 1,
                totalWalkMeters = walkMeters,
                isWalkOnly = false,
                legs = buildFinalLegs(rawLegs)
            )
        }

        return sortConnections(result)
    }

    private fun parseLeg(
        leg: JSONObject,
        isLast: Boolean,
        destName: String,
        destLat: Double,
        destLon: Double,
        zone: ZoneId
    ): RawLeg {
        val mode = leg.optString("mode", "")
        val isWalk = mode.equals("WALK", true) || mode.equals("FOOT", true)
        val durSec = leg.optInt("duration", 0)
        val dist = leg.optDouble("distance", 0.0).let { if (it.isNaN()) 0 else it.toInt() }
        val fromObj = leg.optJSONObject("from")
        val toObj = leg.optJSONObject("to")

        val fromName = placeName(fromObj, "Aktueller Standort")
        val toName = if (isLast) destName else placeName(toObj, destName)

        val startIso = leg.optString("startTime", "")
        val endIso = leg.optString("endTime", "")
        val startMillis = parseIsoMillis(startIso)
        val endMillis = parseIsoMillis(endIso)
        val scheduledStart = parseIsoMillis(leg.optString("scheduledStartTime", startIso))

        val startText = startMillis?.let { formatHhMm(it, zone) } ?: "--:--"
        val endText = endMillis?.let { formatHhMm(it, zone) } ?: "--:--"
        val durMin = when {
            startMillis != null && endMillis != null -> maxOf(1, minutesBetween(startMillis, endMillis))
            else -> maxOf(1, durSec / 60)
        }
        val delay = if (scheduledStart != null && startMillis != null) minutesBetween(scheduledStart, startMillis) else 0

        val info = RouteLegInfo(
            isWalk = isWalk,
            isTransfer = false,
            lineName = if (isWalk) "Fußweg" else formatGermanLineName(
                mode, leg.optString("routeShortName", ""), leg.optString("routeLongName", "")
            ),
            direction = if (isWalk) "" else sanitizeTranscriptWords(leg.optString("headsign", destName)),
            fromStop = fromName,
            toStop = toName,
            fromLat = fromObj.coord("lat"),
            fromLon = fromObj.coord("lon"),
            toLat = toObj.coord("lat") ?: destLat,
            toLon = toObj.coord("lon") ?: destLon,
            startTime = startText,
            endTime = endText,
            delayMinutes = if (isWalk) 0 else delay,
            durationMinutes = durMin,
            distanceMeters = dist
        )
        return RawLeg(info, startMillis, endMillis)
    }

    private fun placeName(obj: JSONObject?, fallback: String): String {
        val raw = obj?.optString("name", "")?.trim().orEmpty()
        // MOTIS liefert für Start/Ziel-Koordinaten die Platzhalter "START"/"END".
        if (raw.isBlank() || raw.equals("START", true) || raw.equals("END", true)) return fallback
        return sanitizeTranscriptWords(raw)
    }

    private fun JSONObject?.coord(key: String): Double? =
        this?.optDouble(key, Double.NaN)?.takeIf { !it.isNaN() }

    /** Fügt Umstiegs-Abschnitte ein und entfernt winzige Fußwege. */
    private fun buildFinalLegs(raw: List<RawLeg>): List<RouteLegInfo> {
        val out = mutableListOf<RouteLegInfo>()
        var prevTransit: RawLeg? = null
        for ((idx, current) in raw.withIndex()) {
            val nextTransit = raw.drop(idx + 1).firstOrNull { !it.info.isWalk }
            if (!current.info.isWalk) {
                val prev = prevTransit
                if (prev != null && out.lastOrNull()?.isTransfer != true) {
                    out += RouteLegInfo(
                        isWalk = false,
                        isTransfer = true,
                        lineName = "Umstieg",
                        direction = "",
                        fromStop = current.info.fromStop,
                        toStop = current.info.fromStop,
                        fromLat = current.info.fromLat,
                        fromLon = current.info.fromLon,
                        toLat = current.info.fromLat,
                        toLon = current.info.fromLon,
                        startTime = prev.info.endTime,
                        endTime = current.info.startTime,
                        delayMinutes = 0,
                        durationMinutes = waitMinutes(prev, current),
                        distanceMeters = 0
                    )
                }
                prevTransit = current
                out += current.info
            } else if (prevTransit != null && nextTransit != null) {
                out += current.info.copy(
                    isTransfer = true,
                    durationMinutes = waitMinutes(prevTransit, nextTransit)
                )
            } else if (current.info.distanceMeters >= 40 || current.info.durationMinutes >= 2) {
                out += current.info
            }
        }
        return out
    }

    private fun waitMinutes(prev: RawLeg, next: RawLeg): Int {
        val a = prev.endMillis
        val b = next.startMillis
        return if (a != null && b != null) maxOf(1, minutesBetween(a, b)) else 1
    }

    /** Früheste Ankunft zuerst; bei < 5 Min. Unterschied: weniger Umstiege, dann weniger Fußweg. */
    fun sortConnections(list: List<TransitConnection>): List<TransitConnection> =
        list.sortedWith { a, b ->
            val arrDiffMins = (a.arrivalMillis - b.arrivalMillis) / 60_000L
            if (kotlin.math.abs(arrDiffMins) >= 5) {
                a.arrivalMillis.compareTo(b.arrivalMillis)
            } else {
                val t = a.transfersCount.compareTo(b.transfersCount)
                if (t != 0) t else a.totalWalkMeters.compareTo(b.totalWalkMeters)
            }
        }

    fun directWalk(
        startLat: Double,
        startLon: Double,
        destName: String,
        destLat: Double,
        destLon: Double,
        nowMillis: Long,
        zone: ZoneId = ZoneId.systemDefault()
    ): TransitConnection {
        val meters = distanceMeters(startLat, startLon, destLat, destLon).toInt()
        val mins = walkMinutesFor(meters)
        val arr = nowMillis + mins * 60_000L
        val nowText = formatHhMm(nowMillis, zone)
        val arrText = formatHhMm(arr, zone)
        return TransitConnection(
            summaryLines = "Zu Fuß · ${formatDistance(meters)}",
            firstLine = "Fußweg",
            firstDirection = destName,
            firstDepartureTime = nowText,
            finalArrivalTime = arrText,
            departureMillis = nowMillis,
            arrivalMillis = arr,
            firstDelayMinutes = 0,
            totalDurationMinutes = mins,
            transfersCount = 0,
            totalWalkMeters = meters,
            isWalkOnly = true,
            legs = listOf(
                RouteLegInfo(
                    isWalk = true, isTransfer = false, lineName = "Fußweg", direction = destName,
                    fromStop = "Aktueller Standort", toStop = destName,
                    fromLat = startLat, fromLon = startLon, toLat = destLat, toLon = destLon,
                    startTime = nowText, endTime = arrText, delayMinutes = 0,
                    durationMinutes = mins, distanceMeters = meters
                )
            )
        )
    }
}
