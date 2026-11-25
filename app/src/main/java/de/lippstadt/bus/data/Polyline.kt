package de.lippstadt.bus.data

/** Fußweg als Linie (für die Karte). */
data class WalkRoute(val points: List<Pair<Double, Double>>, val distanceMeters: Int, val durationMinutes: Int)

/** Dekodiert eine Google-/MOTIS-kodierte Polyline (MOTIS: Genauigkeit 7). */
object PolylineCodec {
    fun decode(encoded: String, precision: Int = 7): List<Pair<Double, Double>> {
        val factor = Math.pow(10.0, precision.toDouble())
        val out = mutableListOf<Pair<Double, Double>>()
        var index = 0
        var lat = 0L
        var lon = 0L
        while (index < encoded.length) {
            for (coord in 0..1) {
                var result = 0L
                var shift = 0
                var b: Int
                do {
                    if (index >= encoded.length) return out
                    b = encoded[index++].code - 63
                    result = result or ((b and 0x1f).toLong() shl shift)
                    shift += 5
                } while (b >= 0x20)
                val delta = if (result and 1L != 0L) (result shr 1).inv() else result shr 1
                if (coord == 0) lat += delta else lon += delta
            }
            out += (lat / factor) to (lon / factor)
        }
        return out
    }
}
