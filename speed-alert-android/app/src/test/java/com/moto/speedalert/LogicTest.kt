package com.moto.speedalert

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LogicTest {

    // ---- overspeed ----------------------------------------------------------

    @Test
    fun beepsAt57InA50Zone() {
        val d = OverspeedDetector()
        val threshold = 50 + 7
        assertFalse(d.update(56.9, threshold))
        assertFalse(d.update(57.0, threshold)) // first fix over: wait for confirmation
        assertTrue(d.update(57.4, threshold)) // second consecutive fix: beep
        assertTrue(d.update(60.0, threshold))
        assertFalse(d.update(56.5, threshold)) // stops as soon as we slow down
    }

    @Test
    fun singleGpsSpikeDoesNotBeep() {
        val d = OverspeedDetector()
        assertFalse(d.update(70.0, 57))
        assertFalse(d.update(50.0, 57))
        assertFalse(d.update(70.0, 57))
    }

    @Test
    fun otherLimitsUseSameTolerance() {
        for ((limit, beepAt) in listOf(40 to 47, 60 to 67, 70 to 77)) {
            val d = OverspeedDetector()
            d.update(beepAt.toDouble(), limit + 7)
            assertTrue(d.update(beepAt.toDouble(), limit + 7))
            val d2 = OverspeedDetector()
            d2.update(beepAt - 0.5, limit + 7)
            assertFalse(d2.update(beepAt - 0.5, limit + 7))
        }
    }

    // ---- limit priority -----------------------------------------------------

    private fun road(maxspeed: Int?) = Road(1, "中正路", "primary", 0, maxspeed, null, null,
        doubleArrayOf(25.0, 25.01), doubleArrayOf(121.5, 121.5))

    @Test
    fun limitPriority() {
        val r = LimitResolver(holdMillis = 30_000)
        val cam = CameraHit(Camera(25.0, 121.5, 60, -1, -1, false, "x"), 150.0)
        assertEquals(ResolvedLimit(60, LimitSource.CAMERA),
            r.resolve(0, true, 50, cam, 200.0, RoadMatch(road(40), 5.0, true)))
        assertEquals(ResolvedLimit(40, LimitSource.ROAD),
            r.resolve(1000, true, 50, null, 200.0, RoadMatch(road(40), 5.0, true)))
        // Road without a maxspeed tag: hold the previous limit for a while...
        assertEquals(ResolvedLimit(40, LimitSource.HELD),
            r.resolve(20_000, true, 50, null, 200.0, RoadMatch(road(null), 5.0, true)))
        // ...then fall back to the default.
        assertEquals(ResolvedLimit(50, LimitSource.DEFAULT),
            r.resolve(40_000, true, 50, null, 200.0, null))
        assertEquals(ResolvedLimit(70, LimitSource.MANUAL),
            r.resolve(40_000, false, 70, cam, 200.0, null))
    }

    // ---- OSM parsing and matching -------------------------------------------

    @Test
    fun parsesMaxspeedValues() {
        assertEquals(50, Roads.parseMaxspeed("50"))
        assertEquals(60, Roads.parseMaxspeed("60;50"))
        assertEquals(48, Roads.parseMaxspeed("30 mph"))
        assertNull(Roads.parseMaxspeed("TW:urban"))
        assertNull(Roads.parseMaxspeed("none"))
        assertNull(Roads.parseMaxspeed(""))
    }

    private val overpassJson = """
        {"elements":[
          {"type":"way","id":1,"tags":{"highway":"primary","name":"南北大道","maxspeed":"60"},
           "geometry":[{"lat":25.000,"lon":121.5000},{"lat":25.010,"lon":121.5000}]},
          {"type":"way","id":2,"tags":{"highway":"residential","name":"東西小路","maxspeed":"30"},
           "geometry":[{"lat":25.005,"lon":121.4950},{"lat":25.005,"lon":121.5050}]},
          {"type":"way","id":3,"tags":{"highway":"secondary","name":"單行道","oneway":"yes",
             "maxspeed:motorcycle":"40","maxspeed":"50"},
           "geometry":[{"lat":25.020,"lon":121.5000},{"lat":25.020,"lon":121.5100}]},
          {"type":"way","id":4,"tags":{"highway":"primary","name":"分向道",
             "maxspeed:forward":"70","maxspeed:backward":"50"},
           "geometry":[{"lat":25.030,"lon":121.5000},{"lat":25.040,"lon":121.5000}]}
        ]}
    """.trimIndent()

    @Test
    fun matchesRoadByHeadingAtIntersection() {
        val roads = Roads.parseOverpass(overpassJson)
        assertEquals(4, roads.size)
        // Right at the crossing of 南北大道 and 東西小路.
        val north = Roads.match(roads, 25.005, 121.50001, heading = 2.0)!!
        assertEquals("南北大道", north.road.name)
        assertEquals(60, north.limit)
        val east = Roads.match(roads, 25.005, 121.50001, heading = 88.0)!!
        assertEquals("東西小路", east.road.name)
        assertEquals(30, east.limit)
    }

    @Test
    fun motorcycleAndDirectionalLimits() {
        val roads = Roads.parseOverpass(overpassJson)
        val oneway = Roads.match(roads, 25.0201, 121.505, heading = 90.0)!!
        assertEquals(40, oneway.limit) // maxspeed:motorcycle wins
        val fwd = Roads.match(roads, 25.035, 121.50001, heading = 0.0)!!
        assertEquals(70, fwd.limit)
        val back = Roads.match(roads, 25.035, 121.50001, heading = 180.0)!!
        assertEquals(50, back.limit)
    }

    @Test
    fun noRoadWhenFarAway() {
        val roads = Roads.parseOverpass(overpassJson)
        assertNull(Roads.match(roads, 25.1, 121.6, heading = 0.0))
    }

    // ---- cameras ------------------------------------------------------------

    @Test
    fun cameraOnlyAlertsAheadAndInItsDirection() {
        // Southbound-only camera ~330 m north of us.
        val cam = Camera(25.003, 121.5, 50, 180, -1, false, "測試路")
        val db = CameraDb(listOf(cam))
        // Riding south toward it from the north: alert.
        assertNotNull(db.nearestAhead(25.006, 121.5, 180.0, 400.0))
        // Riding north toward it from the south: camera shoots the other direction.
        assertNull(db.nearestAhead(25.0, 121.5, 0.0, 400.0))
        // Already passed it (it is behind us).
        assertNull(db.nearestAhead(25.0, 121.5, 180.0, 400.0))
        // Too far.
        assertNull(db.nearestAhead(25.010, 121.5, 180.0, 400.0))
    }

    @Test
    fun twoWayCamera() {
        val cam = Camera(25.003, 121.5, 50, 0, 180, false, "雙向")
        val db = CameraDb(listOf(cam))
        assertNotNull(db.nearestAhead(25.006, 121.5, 180.0, 400.0))
        assertNotNull(db.nearestAhead(25.0, 121.5, 0.0, 400.0))
        assertNull(db.nearestAhead(25.003, 121.497, 90.0, 400.0)) // crossing street
    }

    @Test
    fun bundledCameraAssetLoads() {
        val f = File("src/main/assets/cameras.csv")
        val db = f.reader(Charsets.UTF_8).use { CameraDb.parse(it) }
        assertTrue("expected >1000 cameras, got ${db.cameras.size}", db.cameras.size > 1000)
        assertTrue(db.cameras.all { it.limit in 20..120 })
    }
}
