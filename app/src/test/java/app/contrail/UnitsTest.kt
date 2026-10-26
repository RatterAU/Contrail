package app.contrail

import app.contrail.domain.AltitudeUnit
import app.contrail.domain.DistanceUnit
import app.contrail.domain.Fmt
import app.contrail.domain.SpeedUnit
import app.contrail.domain.Units
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class UnitsTest {
    @After fun reset() { Fmt.units = Units() }

    @Test fun aviationDefaults() {
        Fmt.units = Units(distance = DistanceUnit.NM)
        assertEquals("35,000 ft", Fmt.feet(35_000))
        assertEquals("450 kt", Fmt.knots(450.0))
        assertEquals("5.5 nm", Fmt.nm(5.5))
        assertEquals("+1,280 ft/min", Fmt.verticalRate(1_300.0))
    }

    @Test fun metricConversions() {
        Fmt.units = Units(AltitudeUnit.METRES, SpeedUnit.KMH, DistanceUnit.KM)
        assertEquals("10,668 m", Fmt.feet(35_000))
        assertEquals("833 km/h", Fmt.knots(450.0))
        assertEquals("19 km", Fmt.nm(10.0)) // decimals only below 10 of the shown unit
        assertEquals("9.3 km", Fmt.nm(5.0))
        assertEquals("−5.1 m/s", Fmt.verticalRate(-1_000.0))
        assertEquals("Level", Fmt.verticalRate(30.0))
    }

    @Test fun imperialSpeedAndDistance() {
        Fmt.units = Units(speed = SpeedUnit.MPH, distance = DistanceUnit.MI)
        assertEquals("518 mph", Fmt.knots(450.0))
        assertEquals("115 mi", Fmt.nm(100.0))
    }
}
