suspend fun findNearestTransitStop(
        lat: Double,
        lon: Double,
        cityContext: String
    ): GeoCandidate? {
        val stopCategory = PoiCategory(
            label = "Haltestelle",
            fallbackKeyword = "Haltestelle",
            overpassQueries = listOf(
                """["highway"="bus_stop"]""",
                """["public_transport"="platform"]""",
                """["railway"="station"]"""
            ),
            isTransitStopSearch = true
        )
        val nearby = searchNearbyPoisRobust(
            centerLat = lat,
            centerLon = lon,
            category = stopCategory,
            cityContext = cityContext,
            radiusMeters = 1500
        )
        return nearby
            .filter { it.isStop && distanceMetersBetween(lat, lon, it.lat, it.lon) <= 1500f }
            .minByOrNull { distanceMetersBetween(lat, lon, it.lat, it.lon) }
    }
