package app.contrail

import app.contrail.data.feed.parseReadsb
import app.contrail.domain.AircraftKind
import app.contrail.domain.Filters
import app.contrail.domain.Geo
import app.contrail.domain.SkyGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkyGeometryTest {
    private val plane = parseReadsb("""{"ac":[{"hex":"7c0001","alt_baro":10000,"alt_geom":10000,"lat":0,"lon":0,"seen":0}],"now":0}""", 0).single()

    @Test fun overheadIsNinetyDegrees() {
        val s = SkyGeometry.locate(0.0, 0.0, 0.0, plane)
        assertEquals(90.0, s.elevation, 0.01)
        assertEquals(10000 / 6076.12, s.slantNm, 0.01)
    }

    @Test fun tenThousandFeetTenMilesAway() {
        val (lat, lon) = Geo.destination(0.0, 0.0, 90.0, 10.0)
        val s = SkyGeometry.locate(0.0, 0.0, 0.0, plane.copy(lat = lat, lon = lon))
        assertEquals(90.0, s.bearing, 0.1)
        // atan(10000 ft / 60761 ft) ≈ 9.3°, minus a hair for curvature.
        assertEquals(9.3, s.elevation, 0.15)
    }

    @Test fun farAwayLowPlaneIsBelowHorizon() {
        val (lat, lon) = Geo.destination(0.0, 0.0, 0.0, 200.0)
        val s = SkyGeometry.locate(0.0, 0.0, 0.0, plane.copy(lat = lat, lon = lon, altBaro = 1000, altGeom = 1000))
        assertTrue(s.elevation < 0)
    }

    // Phone upright in portrait, camera facing north: device X=east, Y=up, Z=south.
    private val facingNorth = floatArrayOf(
        1f, 0f, 0f,
        0f, 0f, -1f,
        0f, 1f, 0f,
    )

    @Test fun cameraPointingNorthLevel() {
        val (heading, pitch) = SkyGeometry.cameraPointing(facingNorth)
        assertEquals(0.0, heading, 0.01)
        assertEquals(0.0, pitch, 0.01)
    }

    @Test fun projectionCentreRightAndBehind() {
        val centre = SkyGeometry.project(facingNorth, 0.0, 0.0, 60.0, 2.0)
        assertNotNull(centre)
        assertEquals(0.5, centre!!.first, 1e-6)
        assertEquals(0.5, centre.second, 1e-6)
        val right = SkyGeometry.project(facingNorth, 20.0, 0.0, 60.0, 2.0)!!
        assertTrue("east of north appears right of centre", right.first > 0.5)
        val up = SkyGeometry.project(facingNorth, 0.0, 10.0, 60.0, 2.0)!!
        assertTrue("higher appears above centre", up.second < 0.5)
        assertNull("south is behind", SkyGeometry.project(facingNorth, 180.0, 0.0, 60.0, 2.0))
    }
}

class FiltersTest {
    private val base = parseReadsb("""{"ac":[{"hex":"7c0001","alt_baro":10000,"category":"A3","lat":0,"lon":0,"seen":0}],"now":0}""", 0).single()

    @Test fun defaultShowsEverything() = assertTrue(Filters().matches(base))

    @Test fun altitudeRange() {
        assertFalse(Filters(altMinFt = 20000).matches(base))
        assertFalse(Filters(altMaxFt = 5000).matches(base))
        assertTrue(Filters(altMinFt = 5000, altMaxFt = 15000).matches(base))
    }

    @Test fun kindsAndGround() {
        assertFalse(Filters(kinds = setOf(AircraftKind.HELICOPTER)).matches(base))
        assertFalse(Filters(hideGround = true).matches(base.copy(onGround = true)))
    }

    @Test fun emergenciesAlwaysShow() {
        assertTrue(Filters(kinds = emptySet(), altMinFt = 40000).matches(base.copy(squawk = "7700")))
    }
}
