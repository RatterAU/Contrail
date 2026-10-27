package app.contrail.ui.map

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.ByteArrayOutputStream

/**
 * NASA GIBS infrared satellite tiles (Himawari, GOES-East, GOES-West) as a cloud overlay. The tiles are opaque
 * grey, which would hide the map; MapLibre's downloads pass through [interceptor], which turns warm (clear) sky
 * transparent and cloud white, more solid the colder (higher) its top.
 */
object CloudTiles {
    private const val BASE = "https://gibs.earthdata.nasa.gov/wmts/epsg3857/best"
    /**
     * Each satellite clipped to the longitudes it sees well (west, east): near the edge of its disk the image is
     * smeared, and an unclipped GOES-West drew that smear over eastern Australia. Africa and Europe aren't covered.
     */
    private val LAYERS = listOf(
        "Himawari_AHI_Band13_Clean_Infrared" to (70f to 180f),
        "GOES-West_ABI_Band13_Clean_Infrared" to (-180f to -105f),
        "GOES-East_ABI_Band13_Clean_Infrared" to (-105f to -20f),
    )

    /** GIBS serves the newest image at "default"; [bucket] changes the URL so tiles cached earlier aren't reused. */
    fun urls(bucket: Long) = LAYERS.map { "$BASE/${it.first}/default/default/GoogleMapsCompatible_Level6/{z}/{y}/{x}.png?t=$bucket" }

    /** (west, east) for each of [urls]. */
    val bounds = LAYERS.map { it.second }

    const val MAX_ZOOM = 6f

    val interceptor = Interceptor { chain ->
        val res = chain.proceed(chain.request())
        val url = chain.request().url
        if (!res.isSuccessful || url.host != "gibs.earthdata.nasa.gov" || !url.encodedPath.contains("Clean_Infrared")) return@Interceptor res
        val bmp = res.body.bytes().let { BitmapFactory.decodeByteArray(it, 0, it.size) } ?: return@Interceptor res
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        recolour(px)
        val out = Bitmap.createBitmap(px, bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
        val png = ByteArrayOutputStream().also { out.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        res.newBuilder().body(png.toResponseBody("image/png".toMediaType())).build()
    }

    /**
     * ARGB pixels in place. Grey is brightness temperature: clear ground and sea are about 90–130, cloud tops
     * brighten as they get colder and higher. Coloured pixels are the coldest tops (storms): solid white.
     */
    fun recolour(px: IntArray) {
        for (i in px.indices) {
            val c = px[i]
            val a = c ushr 24
            if (a == 0) continue
            val r = c shr 16 and 0xFF
            val g = c shr 8 and 0xFF
            val b = c and 0xFF
            if (maxOf(r, g, b) - minOf(r, g, b) > 30) { px[i] = 230 shl 24 or 0xFFFFFF; continue }
            val alpha = ((r - CLEAR) * 230 / (OPAQUE - CLEAR)).coerceIn(0, 230)
            px[i] = alpha shl 24 or 0xFFFFFF
        }
    }

    private const val CLEAR = 135
    private const val OPAQUE = 200
}
