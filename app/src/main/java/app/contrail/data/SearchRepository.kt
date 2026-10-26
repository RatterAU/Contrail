package app.contrail.data

import app.contrail.data.enrich.Airport
import app.contrail.data.feed.TrafficRepository
import app.contrail.data.static.StaticData
import app.contrail.domain.Aircraft
import app.contrail.domain.RemoteQuery
import app.contrail.domain.SearchQuery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class SearchResults(val aircraft: List<Aircraft>, val airports: List<Airport>, val failed: Boolean)

class SearchRepository(private val traffic: TrafficRepository) {
    suspend fun search(raw: String): SearchResults = coroutineScope {
        val q = raw.trim()
        val static = StaticData.get()
        val u = q.uppercase().replace(" ", "")
        val local = traffic.tracks.value.values.map { it.aircraft }.filter { a ->
            listOfNotNull(a.callsign, a.registration?.replace("-", ""), a.hex.uppercase(), a.typeCode).any { it.uppercase().startsWith(u.replace("-", "")) }
        }
        var failed = false
        val remote = SearchQuery.classify(q, static::icaoForIataAirline).map { rq ->
            async {
                try {
                    traffic.scheduler.run { s ->
                        when (rq) {
                            is RemoteQuery.Hex -> s.hex(rq.hex)
                            is RemoteQuery.Callsign -> s.callsign(rq.callsign)
                            is RemoteQuery.Registration -> s.registration(rq.reg)
                            is RemoteQuery.Type -> s.type(rq.icaoType)
                            is RemoteQuery.Squawk -> s.squawk(rq.code)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    failed = true
                    emptyList()
                }
            }
        }.awaitAll().flatten()
        // Feed results join the live picture so selecting one opens its sheet straight away.
        withContext(Dispatchers.Default) {
            traffic.merge(remote)
            val aircraft = (local + remote).distinctBy { it.hex }.sortedBy { it.callsign ?: it.registration ?: it.hex }.take(60)
            // ~18k airports: scan off the main thread.
            SearchResults(aircraft, static.searchAirports(q), failed)
        }
    }
}
