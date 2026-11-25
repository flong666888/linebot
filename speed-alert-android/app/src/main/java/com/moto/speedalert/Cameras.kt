package com.moto.speedalert

import java.io.Reader

/** One fixed speed-enforcement point from the NPA open data. */
data class Camera(
    val lat: Double,
    val lon: Double,
    val limit: Int,
    /** Allowed travel headings (degrees); -1 means "any direction". */
    val h1: Int,
    val h2: Int,
    val section: Boolean,
    val address: String,
) {
    /** True if a vehicle travelling on [heading] is photographed by this camera. */
    fun appliesTo(heading: Double, tolerance: Double = 50.0): Boolean {
        if (h1 < 0) return true
        if (Geo.angleDiff(heading, h1.toDouble()) <= tolerance) return true
        return h2 >= 0 && Geo.angleDiff(heading, h2.toDouble()) <= tolerance
    }
}

data class CameraHit(val camera: Camera, val distance: Double)

class CameraDb(val cameras: List<Camera>) {

    /**
     * The nearest camera that is ahead of us (within [aheadCone] degrees of our heading),
     * within [maxDistance] metres, and which photographs traffic in our direction.
     */
    fun nearestAhead(
        lat: Double, lon: Double, heading: Double,
        maxDistance: Double, aheadCone: Double = 40.0,
    ): CameraHit? {
        var best: CameraHit? = null
        // ~0.01 deg is ~1.1 km; cheap prefilter before the exact distance.
        val pad = maxDistance / 100_000.0 + 0.002
        for (c in cameras) {
            if (kotlin.math.abs(c.lat - lat) > pad || kotlin.math.abs(c.lon - lon) > pad) continue
            val d = Geo.distance(lat, lon, c.lat, c.lon)
            if (d > maxDistance) continue
            // Very close: bearing to the point is unstable, accept it.
            if (d > 30) {
                val b = Geo.bearing(lat, lon, c.lat, c.lon)
                if (Geo.angleDiff(b, heading) > aheadCone) continue
            }
            if (!c.appliesTo(heading)) continue
            if (best == null || d < best.distance) best = CameraHit(c, d)
        }
        return best
    }

    companion object {
        /** Parses the asset produced by tools/build_cameras.py. */
        fun parse(reader: Reader): CameraDb {
            val list = ArrayList<Camera>(2048)
            reader.forEachLine { line ->
                val p = line.split(',', limit = 7)
                if (p.size < 7) return@forEachLine
                try {
                    list += Camera(
                        lat = p[0].toDouble(),
                        lon = p[1].toDouble(),
                        limit = p[2].toInt(),
                        h1 = p[3].toInt(),
                        h2 = p[4].toInt(),
                        section = p[5] == "1",
                        address = p[6].trim('"'),
                    )
                } catch (_: NumberFormatException) {
                }
            }
            return CameraDb(list)
        }
    }
}
