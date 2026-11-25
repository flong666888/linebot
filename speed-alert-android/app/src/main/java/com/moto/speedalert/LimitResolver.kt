package com.moto.speedalert

enum class LimitSource(val label: String) {
    CAMERA("測速照相點"),
    ROAD("道路資料"),
    HELD("沿用前段"),
    DEFAULT("預設值"),
    MANUAL("手動"),
}

data class ResolvedLimit(val limit: Int, val source: LimitSource)

/**
 * Picks the speed limit to enforce, in priority order:
 * 1. a speed camera just ahead (its posted limit is official),
 * 2. the matched OSM road's maxspeed,
 * 3. the last known limit, for up to [holdMillis] (roads often lack the tag on short pieces),
 * 4. the user's default.
 */
class LimitResolver(private val holdMillis: Long = 30_000) {
    private var lastKnown: Int? = null
    private var lastKnownAt = 0L

    fun resolve(
        now: Long,
        auto: Boolean,
        defaultLimit: Int,
        camera: CameraHit?,
        cameraLimitRange: Double,
        road: RoadMatch?,
    ): ResolvedLimit {
        if (!auto) return ResolvedLimit(defaultLimit, LimitSource.MANUAL)
        if (camera != null && camera.distance <= cameraLimitRange) {
            remember(camera.camera.limit, now)
            return ResolvedLimit(camera.camera.limit, LimitSource.CAMERA)
        }
        road?.limit?.let {
            remember(it, now)
            return ResolvedLimit(it, LimitSource.ROAD)
        }
        val held = lastKnown
        if (held != null && now - lastKnownAt <= holdMillis) {
            return ResolvedLimit(held, LimitSource.HELD)
        }
        return ResolvedLimit(defaultLimit, LimitSource.DEFAULT)
    }

    private fun remember(limit: Int, now: Long) {
        lastKnown = limit
        lastKnownAt = now
    }

    fun reset() {
        lastKnown = null
    }
}
