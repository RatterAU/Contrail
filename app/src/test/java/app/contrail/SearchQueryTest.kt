package app.contrail

import app.contrail.domain.RemoteQuery
import app.contrail.domain.SearchQuery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchQueryTest {
    private val airlines = mapOf("QF" to listOf("QFA"), "VA" to listOf("VOZ"))
    private fun c(q: String) = SearchQuery.classify(q) { airlines[it].orEmpty() }

    @Test fun iataFlightNumberMapsToIcaoCallsign() {
        assertTrue(RemoteQuery.Callsign("QFA1") in c("QF1"))
        assertTrue(RemoteQuery.Callsign("QFA746") in c("qf 746"))
        assertTrue("leading zeros dropped", RemoteQuery.Callsign("VOZ7") in c("VA007"))
    }

    @Test fun icaoCallsign() = assertTrue(RemoteQuery.Callsign("QFA746") in c("QFA746"))

    @Test fun registrations() {
        assertTrue(RemoteQuery.Registration("VH-VZC") in c("vh-vzc"))
        assertTrue(RemoteQuery.Registration("N12345") in c("N12345"))
    }

    @Test fun hexAndSquawk() {
        assertTrue(RemoteQuery.Hex("7C6DD6") in c("7c6dd6"))
        assertEquals(listOf(RemoteQuery.Squawk("7700")), c("7700"))
    }

    @Test fun typeCode() = assertTrue(RemoteQuery.Type("A388") in c("A388"))

    @Test fun tooShortIsNothing() = assertEquals(emptyList<RemoteQuery>(), c("Q"))
}
