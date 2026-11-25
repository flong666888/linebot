package com.moto.speedalert

import org.json.JSONObject

/** A road from OpenStreetMap with its geometry and speed-limit tags. */
data class Road(
    val id: Long,
    val name: String?,
    val highway: String,
    val oneway: Int, // 0 = two-way, 1 = forward only, -1 = reverse only
    val maxspeed: Int?, // km/h, both directions
    val maxspeedForward: Int?,
    val maxspeedBackward: Int?,
    val lats: DoubleArray,
    val lons: DoubleArray,
) {
    /** Speed limit for travel along node order ([forward]=true) or against it. */
    fun limitFor(forward: Boolean): Int? =
        (if (forward) maxspeedForward else maxspeedBackward) ?: maxspeed

    override fun equals(other: Any?) = other is Road && other.id == id
    override fun hashCode() = id.hashCode()
}

data class RoadMatch(val road: Road, val distance: Double, val forward: Boolean) {
    val limit: Int? get() = road.limitFor(forward)
}

object Roads {

    /**
     * Parses an OSM maxspeed value: "50", "50 mph", "50;60" (takes the first).
     * Returns km/h, or null for things like "none", "signals", "TW:urban".
     */
    fun parseMaxspeed(raw: String?): Int? {
        if (raw.isNullOrBlank()) return null
        val first = raw.split(';').first().trim()
        val m = Regex("""^(\d+(?:\.\d+)?)\s*(mph|km/h|kmh|kph)?$""").find(first) ?: return null
        val v = m.groupValues[1].toDouble()
        val kmh = if (m.groupValues[2] == "mph") v * 1.609344 else v
        return if (kmh in 5.0..150.0) Math.round(kmh).toInt() else null
    }

    /** Parses an Overpass `out geom` JSON response. */
    fun parseOverpass(json: String): List<Road> {
        val root = JSONObject(json)
        val elements = root.optJSONArray("elements") ?: return emptyList()
        val out = ArrayList<Road>(elements.length())
        for (i in 0 until elements.length()) {
            val e = elements.getJSONObject(i)
            if (e.optString("type") != "way") continue
            val geom = e.optJSONArray("geometry") ?: continue
            if (geom.length() < 2) continue
            val tags = e.optJSONObject("tags") ?: JSONObject()
            val lats = DoubleArray(geom.length())
            val lons = DoubleArray(geom.length())
            for (j in 0 until geom.length()) {
                val g = geom.getJSONObject(j)
                lats[j] = g.getDouble("lat")
                lons[j] = g.getDouble("lon")
            }
            val ow = tags.optString("oneway")
            val highway = tags.optString("highway")
            val oneway = when {
                ow == "yes" || ow == "1" || ow == "true" -> 1
                ow == "-1" || ow == "reverse" -> -1
                ow.isEmpty() && (highway == "motorway" || tags.optString("junction") == "roundabout") -> 1
                else -> 0
            }
            // Motorcycle-specific limits win when present.
            fun tag(k: String): Int? =
                parseMaxspeed(tags.optString("$k:motorcycle", "").ifEmpty { null })
                    ?: parseMaxspeed(tags.optString(k, "").ifEmpty { null })
            out += Road(
                id = e.getLong("id"),
                name = tags.optString("name", "").ifEmpty { null },
                highway = highway,
                oneway = oneway,
                maxspeed = tag("maxspeed"),
                maxspeedForward = tag("maxspeed:forward"),
                maxspeedBackward = tag("maxspeed:backward"),
                lats = lats,
                lons = lons,
            )
        }
        return out
    }

    /**
     * Finds the road we are most likely riding on. [heading] may be null when we are
     * nearly stopped; then only distance counts.
     */
    fun match(
        roads: List<Road>, lat: Double, lon: Double, heading: Double?,
        maxDistance: Double = 30.0,
    ): RoadMatch? {
        var best: RoadMatch? = null
        var bestScore = Double.MAX_VALUE
        for (r in roads) {
            for (i in 0 until r.lats.size - 1) {
                val d = Geo.distanceToSegment(lat, lon, r.lats[i], r.lons[i], r.lats[i + 1], r.lons[i + 1])
                if (d > maxDistance) continue
                val segBearing = Geo.bearing(r.lats[i], r.lons[i], r.lats[i + 1], r.lons[i + 1])
                val forward: Boolean
                var penalty = 0.0
                if (heading == null) {
                    forward = r.oneway != -1
                } else {
                    val diffFwd = Geo.angleDiff(heading, segBearing)
                    forward = diffFwd <= 90
                    val diff = if (forward) diffFwd else 180 - diffFwd
                    // Wrong way on a one-way road: almost certainly a different road.
                    if ((r.oneway == 1 && !forward) || (r.oneway == -1 && forward)) penalty += 50
                    // Roads crossing our path (intersections) should lose to the one we ride on.
                    penalty += diff / 3.0
                }
                val score = d + penalty
                if (score < bestScore) {
                    bestScore = score
                    best = RoadMatch(r, d, forward)
                }
            }
        }
        return best
    }
}
