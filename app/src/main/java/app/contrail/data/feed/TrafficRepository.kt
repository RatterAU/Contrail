package app.contrail.data.feed

import app.contrail.domain.Aircraft
import app.contrail.domain.Track
import app.contrail.domain.TrailPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** Live picture of all known aircraft, keyed by ICAO hex. */
class TrafficRepository(val scheduler: FeedScheduler, private val clock: () -> Long = System::currentTimeMillis) {
    private val _tracks = MutableStateFlow<Map<String, Track>>(emptyMap())
    val tracks: StateFlow<Map<String, Track>> = _tracks

    /** Hexes kept even when they leave the polled area (selected, followed, watched). */
    @Volatile var pinned: Set<String> = emptySet()

    suspend fun refreshArea(lat: Double, lon: Double, radiusNm: Int) {
        val reports = scheduler.run { it.point(lat, lon, radiusNm) }
        withContext(Dispatchers.Default) { merge(reports) }
    }

    suspend fun refreshHex(hex: String) {
        val reports = scheduler.run { it.hex(hex) }
        withContext(Dispatchers.Default) { merge(reports) }
    }

    fun addHistory(hex: String, history: List<TrailPoint>) {
        _tracks.update { old -> old[hex]?.let { old + (hex to it.withHistory(history)) } ?: old }
    }

    fun merge(reports: List<Aircraft>) {
        val now = clock()
        _tracks.update { old ->
            val next = HashMap<String, Track>(old.size + reports.size)
            old.forEach { (hex, t) ->
                if (now - t.aircraft.seenTime < DROP_AFTER_MS || hex in pinned) next[hex] = t
            }
            reports.forEach { a -> next[a.hex] = next[a.hex]?.update(a, now) ?: Track.start(a, now) }
            next
        }
    }

    companion object {
        const val FADE_AFTER_MS = 30_000L
        const val DROP_AFTER_MS = 90_000L
    }
}
