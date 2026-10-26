package app.contrail.data.enrich

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Airline tail/symbol marks from two open logo sets on GitHub (keyless, keyed by ICAO code): Jxck-S's
 * FlightAware-style tails first (square, best coverage of regionals), then sexym0nk3y's as a fallback.
 * Only logos on a transparent background are used; boxed or full-bleed ones are rejected, so nothing ugly
 * lands on the map. Downloads are cached on disk, so each airline is fetched once per install.
 */
class AirlineLogos(private val http: HttpClient, cacheDir: File) {
    private val dir = File(cacheDir, "airline-logos-v2").apply { mkdirs() }
    private val memory = HashMap<String, CompletableDeferred<Bitmap?>>()
    private val downloads = Semaphore(3)

    /** Already loaded (or known missing), without waiting. */
    fun peek(icao: String): Bitmap? = synchronized(memory) { memory[icao] }?.takeIf { it.isCompleted }?.getCompleted()

    /** The trimmed logo, or null if this airline has no clean logo. Concurrent calls share one download. */
    suspend fun get(icao: String): Bitmap? {
        val (d, owner) = synchronized(memory) {
            memory[icao]?.let { it to false } ?: CompletableDeferred<Bitmap?>().also { memory[icao] = it }.let { it to true }
        }
        if (owner) {
            try {
                d.complete(load(icao))
            } catch (e: CancellationException) {
                synchronized(memory) { memory.remove(icao) } // let the next caller retry
                d.cancel()
                throw e
            } catch (e: Exception) {
                synchronized(memory) { memory.remove(icao) } // offline or a bad response: no logo for now, retry later
                d.complete(null)
            }
        }
        return d.await()
    }

    private suspend fun load(icao: String): Bitmap? = withContext(Dispatchers.IO) {
        val file = File(dir, "$icao.png")
        val none = File(dir, "$icao.none")
        if (none.exists()) return@withContext null
        if (file.exists()) return@withContext file.readBytes().let(::decodeClean)
        // First set with a clean (transparent) logo wins; the chosen bytes are cached.
        for (url in SOURCES) {
            val bytes = downloads.withPermit { fetch(url.replace("{ICAO}", ALIASES[icao] ?: icao)) } ?: continue
            decodeClean(bytes)?.let { file.writeBytes(bytes); return@withContext it }
        }
        none.createNewFile()
        null
    }

    private fun decodeClean(bytes: ByteArray): Bitmap? {
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val px = IntArray(bmp.width * bmp.height).also { bmp.getPixels(it, 0, bmp.width, 0, 0, bmp.width, bmp.height) }
        if (!isCleanLogo(px, bmp.width, bmp.height)) return null
        val b = opaqueBounds(px, bmp.width, bmp.height) ?: return null
        return Bitmap.createBitmap(bmp, b[0], b[1], b[2] - b[0], b[3] - b[1])
    }

    /** Bytes, or null when the set has no logo for this airline. Network errors throw, so they aren't cached as "none". */
    private suspend fun fetch(url: String): ByteArray? {
        val res = http.get(url)
        if (res.status == HttpStatusCode.NotFound) return null
        if (!res.status.isSuccess()) throw java.io.IOException("logo HTTP ${res.status.value}")
        return res.bodyAsBytes()
    }

    companion object {
        private val SOURCES = listOf(
            "https://raw.githubusercontent.com/Jxck-S/airline-logos/main/flightaware_logos/{ICAO}.png",
            "https://raw.githubusercontent.com/sexym0nk3y/airline-logos/main/logos/{ICAO}.png",
        )

        /** Operators that fly in a parent's colours but have no logo of their own in either set. */
        private val ALIASES = mapOf("NWK" to "QLK")

        /**
         * A usable mark sits on transparency: clear corners, and well under the whole canvas is solid.
         * A square badge or photo-style logo fails this and is skipped. [argb] is row-major ARGB.
         */
        fun isCleanLogo(argb: IntArray, w: Int, h: Int): Boolean {
            if (w < 16 || h < 16) return false
            fun alpha(x: Int, y: Int) = argb[y * w + x] ushr 24
            val corners = listOf(alpha(0, 0), alpha(w - 1, 0), alpha(0, h - 1), alpha(w - 1, h - 1))
            if (corners.any { it > 24 }) return false
            val solid = argb.count { it ushr 24 > 200 }
            return solid > argb.size / 50 && solid < argb.size * 0.6
        }

        /** [left, top, right, bottom) of the visible pixels, or null if the image is empty. */
        fun opaqueBounds(argb: IntArray, w: Int, h: Int): IntArray? {
            var l = w; var t = h; var r = -1; var b = -1
            for (y in 0 until h) for (x in 0 until w) {
                if (argb[y * w + x] ushr 24 > 16) {
                    if (x < l) l = x
                    if (x > r) r = x
                    if (y < t) t = y
                    if (y > b) b = y
                }
            }
            return if (r < 0) null else intArrayOf(l, t, r + 1, b + 1)
        }
    }
}
