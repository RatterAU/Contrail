package app.contrail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.ZoneId

/** The bundled TSVs are generated outside the app; check they load cleanly and contain what users look for. */
class StaticDataTest {
    private fun rows(name: String) = File("src/main/assets/$name").readLines().map { it.split('\t') }
    private val airports = rows("airports.tsv")

    @Test fun airportRowsAreWellFormed() {
        val bad = airports.filter { r ->
            r.size != 10 || r[5].toDoubleOrNull()?.let { it in -90.0..90.0 } != true ||
                r[6].toDoubleOrNull()?.let { it in -180.0..180.0 } != true || r[8] !in setOf("L", "M", "S")
        }
        assertEquals("malformed rows: ${bad.take(3)}", 0, bad.size)
    }

    @Test fun icaoCodesAreUnique() {
        val dupes = airports.groupBy { it[0] }.filterValues { it.size > 1 }.keys
        assertTrue("duplicate ICAO codes: ${dupes.take(5)}", dupes.isEmpty())
    }

    @Test fun timeZonesAreValid() {
        val bad = airports.map { it[9] }.filter { it.isNotEmpty() && runCatching { ZoneId.of(it) }.isFailure }.distinct()
        assertTrue("unknown zones: $bad", bad.isEmpty())
    }

    @Test fun homeRegionAirportsArePresent() {
        val byIcao = airports.associateBy { it[0] }
        for (icao in listOf("YPAD", "YPED", "YPPF")) {
            assertEquals("$icao zone", "Australia/Adelaide", byIcao[icao]?.get(9))
        }
    }

    @Test fun runwaysBelongToKnownAirports() {
        val icaos = airports.mapTo(HashSet()) { it[0] }
        val runways = rows("runways.tsv")
        assertTrue(runways.all { it.size == 11 })
        val orphans = runways.map { it[0] }.filter { it !in icaos }.distinct()
        // Runways are only looked up by airport, so an orphan is harmless, but a lot of them means a broken export.
        assertTrue("${orphans.size} runway airports missing", orphans.size < runways.size / 20)
        assertEquals(4, runways.count { it[0] == "YPPF" })
    }
}
