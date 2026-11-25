package com.moto.speedalert

import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

/**
 * Downloads roads (with speed-limit tags) around the rider from the OpenStreetMap
 * Overpass API, a ~2.6 km square at a time, and keeps the last few squares in memory.
 */
class RoadFetcher {
    private class Area(val s: Double, val w: Double, val n: Double, val e: Double, val roads: List<Road>) {
        /** Inside, and at least [margin] degrees from every edge. */
        fun covers(lat: Double, lon: Double, margin: Double) =
            lat > s + margin && lat < n - margin && lon > w + margin && lon < e - margin
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val areas = ArrayDeque<Area>()
    @Volatile private var fetching = false
    @Volatile private var retryAfter = 0L
    @Volatile var lastError: String? = null
        private set

    /** Roads around the point, or empty if not downloaded yet (a download is started). */
    @Synchronized
    fun roadsAt(lat: Double, lon: Double): List<Road> {
        val area = areas.firstOrNull { it.covers(lat, lon, 0.0) }
        // Start fetching the next square before we ride off the edge of this one.
        if (areas.none { it.covers(lat, lon, EDGE_MARGIN) }) fetchAsync(lat, lon)
        return area?.roads ?: emptyList()
    }

    val hasData: Boolean @Synchronized get() = areas.isNotEmpty()

    private fun fetchAsync(lat: Double, lon: Double) {
        val now = System.currentTimeMillis()
        if (fetching || now < retryAfter) return
        fetching = true
        executor.execute {
            try {
                val s = lat - HALF
                val n = lat + HALF
                val w = lon - HALF
                val e = lon + HALF
                val roads = Roads.parseOverpass(download(s, w, n, e))
                synchronized(this) {
                    areas.addFirst(Area(s, w, n, e, roads))
                    while (areas.size > MAX_AREAS) areas.removeLast()
                }
                lastError = null
                Log.i(TAG, "loaded ${roads.size} roads")
            } catch (t: Throwable) {
                lastError = t.message ?: t.javaClass.simpleName
                retryAfter = System.currentTimeMillis() + 20_000
                Log.w(TAG, "road download failed", t)
            } finally {
                fetching = false
            }
        }
    }

    private fun download(s: Double, w: Double, n: Double, e: Double): String {
        val query = "[out:json][timeout:25];" +
            "way[\"highway\"~\"^(motorway|trunk|primary|secondary|tertiary|unclassified|residential|" +
            "living_street|road|motorway_link|trunk_link|primary_link|secondary_link|tertiary_link)$\"]" +
            "($s,$w,$n,$e);out tags geom qt;"
        var last: Exception? = null
        for (endpoint in ENDPOINTS) {
            try {
                val conn = URL(endpoint).openConnection() as HttpURLConnection
                conn.connectTimeout = 10_000
                conn.readTimeout = 30_000
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("User-Agent", "MotoSpeedAlert/1.0 (Android)")
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                conn.outputStream.use { it.write(("data=" + URLEncoder.encode(query, "UTF-8")).toByteArray()) }
                if (conn.responseCode != 200) throw RuntimeException("HTTP ${conn.responseCode}")
                return conn.inputStream.bufferedReader().use { it.readText() }
            } catch (ex: Exception) {
                last = ex
            }
        }
        throw last ?: RuntimeException("no endpoint")
    }

    fun shutdown() = executor.shutdownNow()

    companion object {
        private const val TAG = "RoadFetcher"
        private const val HALF = 0.012 // degrees, ~1.3 km
        private const val EDGE_MARGIN = 0.004 // ~450 m
        private const val MAX_AREAS = 6
        private val ENDPOINTS = listOf(
            "https://overpass-api.de/api/interpreter",
            "https://overpass.kumi.systems/api/interpreter",
        )
    }
}
