package app.contrail.data.feed

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

data class SourceHealth(val ok: Boolean, val latencyMs: Long?, val lastError: String?, val checkedAt: Long)

/**
 * Spreads requests across feeds round-robin, keeps each feed under 1 request/second,
 * and benches a failing feed for [backoffMs] so the others carry the load.
 */
class FeedScheduler(
    private val sources: List<AdsbSource>,
    private val clock: () -> Long = System::currentTimeMillis,
    private val backoffMs: Long = 60_000,
    private val timeoutMs: Long = 8_000,
    private val minIntervalMs: Long = 1_100,
) {
    private val mutex = Mutex()
    private var next = 0
    private val lastCall = mutableMapOf<String, Long>()
    private val benchedUntil = mutableMapOf<String, Long>()

    private val _health = MutableStateFlow<Map<String, SourceHealth>>(emptyMap())
    val health: StateFlow<Map<String, SourceHealth>> = _health

    val sourceNames get() = sources.map { it.name }

    suspend fun <T> run(block: suspend (AdsbSource) -> T): T {
        val order = mutex.withLock {
            val start = next
            next = (next + 1) % sources.size
            val now = clock()
            val rotated = sources.indices.map { sources[(start + it) % sources.size] }
            // Benched feeds go last rather than being skipped, so a total outage still retries.
            rotated.sortedBy { (benchedUntil[it.name] ?: 0) > now }
        }
        var last: Throwable? = null
        for (source in order) {
            mutex.withLock { lastCall[source.name] }?.let { prev ->
                val wait = prev + minIntervalMs - clock()
                if (wait > 0) delay(wait)
            }
            val started = clock()
            mutex.withLock { lastCall[source.name] = started }
            try {
                val result = withTimeout(timeoutMs) { block(source) }
                record(source.name, SourceHealth(true, clock() - started, null, clock()))
                mutex.withLock { benchedUntil.remove(source.name) }
                return result
            } catch (e: TimeoutCancellationException) {
                last = e
                fail(source.name, "Timed out")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                last = e
                fail(source.name, e.message ?: e::class.simpleName ?: "Error")
            }
        }
        throw FeedsUnavailableException(last)
    }

    private suspend fun fail(name: String, msg: String) {
        mutex.withLock { benchedUntil[name] = clock() + backoffMs }
        record(name, SourceHealth(false, null, msg, clock()))
    }

    private fun record(name: String, h: SourceHealth) = _health.update { it + (name to h) }
}

class FeedsUnavailableException(cause: Throwable?) : Exception("No flight data source is reachable", cause)
