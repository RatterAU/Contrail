package app.contrail

import app.contrail.data.feed.parseReadsb
import app.contrail.domain.Filters
import app.contrail.domain.Track
import app.contrail.ui.map.Area
import app.contrail.ui.map.MapController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MapRenderTest {
    private val now = 1_000_000L
    private val tracks = parseReadsb(
        """{"ac":[
        {"hex":"aaaaaa","flight":"NEAR1","alt_baro":10000,"gs":250,"track":90,"lat":-34.9,"lon":138.6,"seen":0,"seen_pos":0},
        {"hex":"bbbbbb","flight":"FAR1","alt_baro":35000,"gs":450,"track":90,"lat":-20.0,"lon":150.0,"seen":0,"seen_pos":0}
        ]}""",
        now,
    ).map { Track.start(it, now) }
    private val adelaide = Area(-35.5, 138.0, -34.5, 139.0)

    private fun hexes(area: Area?, selected: String? = null) =
        MapController.aircraftFeatures(tracks, selected, Filters(), now, area).features()!!.map { it.getStringProperty("hex") }.toSet()

    @Test fun linesCrossTheAntimeridianTheShortWay() {
        val pts = listOf(178.0, -179.0, 179.5, -178.0).map { org.maplibre.geojson.Point.fromLngLat(it, 0.0) }
        assertEquals(listOf(178.0, 181.0, 179.5, 182.0), MapController.unwrapped(pts).map { it.longitude() })
        // Sydney to Los Angeles: every step stays short, none jumps ~360 degrees.
        val gc = MapController.unwrapped(MapController.greatCircle(-33.9, 151.2, 33.9, -118.4))
        assertTrue(gc.zipWithNext().all { (a, b) -> kotlin.math.abs(b.longitude() - a.longitude()) < 5 })
    }

    @Test fun frameRateFollowsZoom() {
        // Zoomed in on a city, planes visibly move: fast frames. Zoomed out to a continent: slow frames.
        assertEquals(100L, MapController.frameIntervalMs(14.0, -35.0))
        assertEquals(2_000L, MapController.frameIntervalMs(4.0, -35.0))
        val z8 = MapController.frameIntervalMs(8.0, -35.0)
        assertTrue("zoom 8 should sit between the clamps, was $z8", z8 in 500..1_500)
        var last = Long.MAX_VALUE
        for (z in 3..16) MapController.frameIntervalMs(z.toDouble(), 0.0).also { assertTrue(it <= last); last = it }
    }

    @Test fun offScreenAircraftAreCulledButSelectionIsKept() {
        assertEquals(setOf("aaaaaa", "bbbbbb"), hexes(null))
        assertEquals(setOf("aaaaaa"), hexes(adelaide))
        assertEquals(setOf("aaaaaa", "bbbbbb"), hexes(adelaide, selected = "bbbbbb"))
    }

    @Test fun areaContainsIsInclusive() {
        assertTrue(adelaide.contains(-35.5, 138.0))
        assertTrue(!adelaide.contains(-35.6, 138.5))
    }

    private fun trackThrough(vararg fixes: Triple<Double, Double, Int>): Track {
        var t = Track.start(tracks[0].aircraft.copy(lat = fixes[0].first, lon = fixes[0].second, altBaro = fixes[0].third, groundSpeed = 0.0, positionTime = 0, seenTime = 0), 0)
        fixes.drop(1).forEachIndexed { i, (lat, lon, alt) ->
            val ms = (i + 1) * 1_000L
            t = t.update(t.aircraft.copy(lat = lat, lon = lon, altBaro = alt, positionTime = ms, seenTime = ms), ms)
        }
        return t
    }

    private fun lines(t: Track) = MapController.trailFeatures(listOf(t), null, 100_000).features()!!

    @Test fun trailMergesSameColourSegmentsAndSplitsAtGaps() {
        // Level at 35,000 ft: one line however many fixes.
        assertEquals(1, lines(trackThrough(Triple(-35.0, 138.0, 35000), Triple(-35.0, 138.1, 35000), Triple(-35.0, 138.2, 35000), Triple(-35.0, 138.3, 35000))).size)
        // Descending through bands: a new line per colour, joined end to end.
        val descent = lines(trackThrough(Triple(-35.0, 138.0, 35000), Triple(-35.0, 138.1, 35000), Triple(-35.0, 138.2, 3000), Triple(-35.0, 138.3, 3000)))
        assertEquals(2, descent.size)
        val first = (descent[0].geometry() as org.maplibre.geojson.LineString).coordinates()
        val second = (descent[1].geometry() as org.maplibre.geojson.LineString).coordinates()
        assertEquals(first.last(), second.first())
        // A 2° jump (no coverage in between) is not drawn as a straight line.
        assertEquals(2, lines(trackThrough(Triple(-35.0, 138.0, 35000), Triple(-35.0, 138.1, 35000), Triple(-35.0, 140.1, 35000), Triple(-35.0, 140.2, 35000))).size)
    }

    @Test fun selectedTrailStopsAtItsLastFixAndTheTailReachesThePlane() {
        val t = trackThrough(Triple(-35.0, 138.0, 35000), Triple(-35.0, 138.1, 35000), Triple(-35.0, 138.2, 35000))
        val body = MapController.trailFeatures(emptyList(), t, 100_000).features()!!.single().geometry() as org.maplibre.geojson.LineString
        assertEquals(138.2, body.coordinates().last().longitude(), 1e-9)
        val tail = MapController.tailFeatures(t, 100_000).features()!!.single().geometry() as org.maplibre.geojson.LineString
        assertEquals(138.2, tail.coordinates().first().longitude(), 1e-9)
        assertEquals(2, tail.coordinates().size)
        assertSame(MapController.tailFeatures(null, now), MapController.trailFeatures(emptyList(), null, now))
    }

    @Test fun emptyLayersReuseOneInstanceSoRenderCanSkipThem() {
        // MapController.render skips a source when handed the same object as last time.
        assertSame(MapController.trailFeatures(emptyList(), null, now), MapController.trailFeatures(emptyList(), null, now))
        assertSame(MapController.routeFeatures(null, null, null, now), MapController.scrubFeatures(null))
    }
}
