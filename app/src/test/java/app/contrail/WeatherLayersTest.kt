package app.contrail

import app.contrail.data.feed.feedJson
import app.contrail.data.weather.Sigmet
import app.contrail.domain.WindGrid
import app.contrail.domain.WindLevel
import app.contrail.domain.WindSample
import app.contrail.ui.map.CloudTiles
import app.contrail.ui.map.MapController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherLayersTest {
    private fun sample(speed: Float, from: Float) = WindSample(FloatArray(3) { speed }, FloatArray(3) { from })

    @Test fun westerlyBlowsEast() {
        val (u, v) = WindGrid.toVector(100.0, 270.0)
        assertEquals(100.0, u, 1e-9)
        assertEquals(0.0, v, 1e-9)
    }

    @Test fun bilinearBetweenPointsAndNullOutsideTheGrid() {
        val step = 1.0
        val grid = WindGrid(step, mapOf(
            WindGrid.key(0, 0, step) to sample(0f, 270f),
            WindGrid.key(0, 1, step) to sample(100f, 270f),
            WindGrid.key(1, 0, step) to sample(0f, 270f),
            WindGrid.key(1, 1, step) to sample(100f, 270f),
        ))
        assertEquals(50.0, grid.sample(0.5, 0.5, WindLevel.FL340)!!.first, 1e-6)
        assertNull(grid.sample(5.0, 5.0, WindLevel.FL340))
    }

    @Test fun antimeridianPointsShareKeys() {
        assertEquals(WindGrid.key(0, 190, 1.0), WindGrid.key(0, -170, 1.0))
        assertEquals(-170.0, WindGrid.latLon(0, 190, 1.0).second, 1e-9)
    }

    @Test fun gridStaysWithinTheRequestBudget() {
        for ((lat, lon) in listOf(0.5 to 0.8, 4.0 to 6.0, 30.0 to 60.0, 160.0 to 360.0)) {
            val step = WindGrid.stepFor(lat, lon)
            assertTrue("$lat x $lon at $step", WindGrid.points(-lat / 2, -lon / 2, lat / 2, lon / 2, step).size <= WindGrid.MAX_POINTS * 1.2)
        }
    }

    @Test fun clearSkyIsTransparentCloudAndStormsAreWhite() {
        fun grey(g: Int) = (0xFF shl 24) or (g shl 16) or (g shl 8) or g
        val px = intArrayOf(grey(110), grey(200), 0xFF00C0FF.toInt())
        CloudTiles.recolour(px)
        assertEquals(0, px[0] ushr 24)
        assertEquals(230, px[1] ushr 24)
        assertEquals(0xFFFFFF, px[1] and 0xFFFFFF)
        assertEquals(230 shl 24 or 0xFFFFFF, px[2])
    }

    @Test fun sigmetsWithOneOrSeveralAreasBothParse() {
        // Shapes from aviationweather.gov isigmet, 2026-10-27: a multi-area SIGMET nests its coordinate lists.
        val list = feedJson.decodeFromString<List<Sigmet>>(
            """[{"hazard":"ICE","qualifier":"SEV","base":7500,"top":21000,"validTimeTo":1793094480,"coords":[{"lon":14.9,"lat":-40.8},{"lon":23.5,"lat":-49.3},{"lon":20.2,"lat":-50.9}]},
               {"hazard":"TS","qualifier":"EMBD","base":null,"top":45000,"validTimeTo":1793094480,"coords":[[{"lon":1,"lat":1},{"lon":2,"lat":1},{"lon":2,"lat":2}],[{"lon":5,"lat":5},{"lon":6,"lat":5},{"lon":6,"lat":6}]]}]""",
        )
        assertEquals(listOf(1, 2), list.map { it.areas.size })
        // Each area is a polygon plus a label point.
        assertEquals(6, MapController.hazardFeatures(list, 0).features()!!.size)
    }

    @Test fun hazardLabels() {
        assertEquals("Severe turbulence\nFL250–FL380", MapController.hazardLabel(Sigmet("TURB", "SEV", 25_000, 38_000)))
        assertEquals("Thunderstorms\nUp to FL450", MapController.hazardLabel(Sigmet("TS", "EMBD", null, 45_000)))
        assertEquals("Severe icing\nSFC–FL100", MapController.hazardLabel(Sigmet("ICE", "SEV", 0, 10_000)))
    }
}
