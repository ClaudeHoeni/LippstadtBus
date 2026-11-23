package de.lippstadt.bus.data

data class GeoCandidate(
    val name: String,
    val areaLabel: String,
    val lat: Double,
    val lon: Double,
    val isStop: Boolean = false,
    val isStreet: Boolean = false
) {
    val fullDisplay: String
        get() = if (areaLabel.isNotBlank() && !name.contains(areaLabel, ignoreCase = true)) {
            "$name ($areaLabel)"
        } else {
            name
        }
}

data class RouteLegInfo(
    val isWalk: Boolean,
    val isTransfer: Boolean,
    val lineName: String,
    val direction: String,
    val fromStop: String,
    val toStop: String,
    val fromLat: Double?,
    val fromLon: Double?,
    val toLat: Double?,
    val toLon: Double?,
    val startTime: String,
    val endTime: String,
    val delayMinutes: Int,
    val durationMinutes: Int,
    val distanceMeters: Int
)

data class TransitConnection(
    val summaryLines: String,
    val firstLine: String,
    val firstDirection: String,
    val firstDepartureTime: String,
    val finalArrivalTime: String,
    val departureMillis: Long,
    val arrivalMillis: Long,
    val firstDelayMinutes: Int,
    val totalDurationMinutes: Int,
    val transfersCount: Int,
    val totalWalkMeters: Int,
    val isWalkOnly: Boolean = false,
    val legs: List<RouteLegInfo>
) {
    val firstTransitLeg: RouteLegInfo?
        get() = legs.firstOrNull { !it.isWalk && !it.isTransfer }
}

data class AppUpdateInfo(
    val title: String,
    val downloadUrl: String
)

/** Ziel für den Kompass-Pfeil. */
data class NavTarget(val name: String, val lat: Double, val lon: Double)
