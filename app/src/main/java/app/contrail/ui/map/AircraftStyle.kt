package app.contrail.ui.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.core.graphics.PathParser
import androidx.core.graphics.createBitmap
import app.contrail.domain.Aircraft
import kotlin.math.roundToInt
import kotlin.math.abs
import java.util.Locale

/** Silhouettes drawn pointing north in a 64×64 box. [size] scales the icon on the map. */
enum class Shape(val size: Float, val path: String) {
    AIRLINER(0.9f, "M32,4C34,4 35,6 35,9L35,24L58,36L58,41L35,35L35,50L42,56L42,60L32,57L22,60L22,56L29,50L29,35L6,41L6,36L29,24L29,9C29,6 30,4 32,4Z"),
    HEAVY(1.15f, "M32,2C34.5,2 36,5 36,9L36,22L48,28L48,25L51,25L51,30L60,35L60,40L36,34L36,49L43,55L43,59L32,56L21,59L21,55L28,49L28,34L4,40L4,35L13,30L13,25L16,25L16,28L28,22L28,9C28,5 29.5,2 32,2Z"),
    LIGHT(0.7f, "M32,8C34,8 35,10 35,14L35,22L60,24L60,30L35,31L34,46L44,48L44,53L32,52L20,53L20,48L30,46L29,31L4,30L4,24L29,22L29,14C29,10 30,8 32,8Z"),
    HELICOPTER(0.8f, "M32,14C37,14 40,19 40,26C40,33 37,37 34,38L33,58L38,60L38,62L26,62L26,60L31,58L30,38C27,37 24,33 24,26C24,19 27,14 32,14ZM8,24L56,24L56,27L8,27ZM30.5,2L33.5,2L33.5,50L30.5,50Z"),
    GLIDER(0.8f, "M32,16C33.5,16 34,18 34,21L34,26L62,28L62,31L34,31L33,52L40,53L40,56L24,56L24,53L31,52L30,31L2,31L2,28L30,26L30,21C30,18 30.5,16 32,16Z"),
    FIGHTER(0.8f, "M32,4L35,18L35,28L54,44L54,49L35,44L35,50L42,56L42,59L32,57L22,59L22,56L29,50L29,44L10,49L10,44L29,28L29,18Z"),
    BALLOON(0.7f, "M32,6C42,6 50,14 50,24C50,34 40,42 35,48L35,54L29,54L29,48C24,42 14,34 14,24C14,14 22,6 32,6Z"),
    GROUND(0.5f, "M32,18L46,32L32,46L18,32Z");

    companion object {
        private val HEAVY_TYPES = setOf("A388", "B748", "B744", "B74S", "A346", "A345", "A343", "A342", "B77W", "B77L", "B772", "B773", "B779", "B778", "A359", "A35K", "B788", "B789", "B78X", "A332", "A333", "A338", "A339", "B763", "B764", "MD11", "C5M", "AN25", "A124", "IL96")
        private val FIGHTERS = setOf("F16", "F18", "F18S", "F35", "F15", "F22", "EUFI", "RFAL", "GRIF", "HAWK", "PC21", "A4", "MIRA", "TORN", "SU27", "SU30", "MG29", "T38", "F5")

        fun of(a: Aircraft): Shape {
            val t = a.typeCode
            if (t != null) {
                if (t in HEAVY_TYPES) return HEAVY
                if (t in FIGHTERS) return FIGHTER
            }
            return when (a.category) {
                "A1" -> LIGHT
                "A5" -> HEAVY
                "A6" -> FIGHTER
                "A7" -> HELICOPTER
                "B1", "B4" -> GLIDER
                "B2" -> BALLOON
                "C0", "C1", "C2", "C3", "C4", "C5" -> GROUND
                else -> AIRLINER
            }
        }
    }
}

/**
 * Altitude colour ramp, tar1090-style: warm near the ground, through green, to violet at cruise.
 * Bucketed into bands so each (shape, band) pair is one pre-rendered sprite: crisp, no SDF blur.
 */
object AltitudeColors {
    private val BAND_TOPS = intArrayOf(500, 1000, 2000, 4000, 6000, 8000, 10000, 14000, 18000, 22000, 26000, 30000, 34000, 38000, 42000, Int.MAX_VALUE)
    const val GROUND_BAND = -1
    const val UNKNOWN_BAND = -2
    const val SELECTED_BAND = -3

    val bands: List<Int> = BAND_TOPS.indices.toList() + listOf(GROUND_BAND, UNKNOWN_BAND, SELECTED_BAND)

    fun band(a: Aircraft): Int = when {
        a.onGround -> GROUND_BAND
        a.altBaro == null -> UNKNOWN_BAND
        else -> bandFor(a.altBaro)
    }

    fun bandFor(altFt: Int): Int = BAND_TOPS.indexOfFirst { altFt < it }

    fun colorForBand(band: Int): Int = when (band) {
        GROUND_BAND -> 0xFF8C93A1.toInt()
        UNKNOWN_BAND -> 0xFFD6DAE3.toInt()
        SELECTED_BAND -> 0xFFFFFFFF.toInt()
        else -> {
            val lo = if (band == 0) 0 else BAND_TOPS[band - 1]
            val hi = if (BAND_TOPS[band] == Int.MAX_VALUE) 46000 else BAND_TOPS[band]
            colorForAltitude((lo + hi) / 2)
        }
    }

    fun colorForAltitude(altFt: Int): Int = hslToArgb(hue(altFt), 0.85, 0.6)

    /** Plain-Kotlin HSL → opaque ARGB (Android's ColorUtils can't run in JVM unit tests). */
    private fun hslToArgb(h: Double, s: Double, l: Double): Int {
        val c = (1 - abs(2 * l - 1)) * s
        val x = c * (1 - abs((h / 60) % 2 - 1))
        val m = l - c / 2
        val (r, g, b) = when ((h / 60).toInt().coerceIn(0, 5)) {
            0 -> Triple(c, x, 0.0); 1 -> Triple(x, c, 0.0); 2 -> Triple(0.0, c, x)
            3 -> Triple(0.0, x, c); 4 -> Triple(x, 0.0, c); else -> Triple(c, 0.0, x)
        }
        fun ch(v: Double) = ((v + m) * 255).roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
    }

    private val HUE_STOPS = listOf(0 to 20.0, 2000 to 32.0, 4000 to 43.0, 6000 to 54.0, 8000 to 72.0, 9000 to 85.0, 11000 to 140.0, 40000 to 300.0)

    fun hue(altFt: Int): Double {
        if (altFt <= HUE_STOPS.first().first) return HUE_STOPS.first().second
        if (altFt >= HUE_STOPS.last().first) return HUE_STOPS.last().second
        val i = HUE_STOPS.indexOfFirst { it.first > altFt }
        val (a0, h0) = HUE_STOPS[i - 1]
        val (a1, h1) = HUE_STOPS[i]
        return h0 + (h1 - h0) * (altFt - a0).toDouble() / (a1 - a0)
    }

    fun hex(color: Int) = String.format(Locale.US, "#%06X", color and 0xFFFFFF)

    private val bandHex = bands.associateWith { hex(colorForBand(it)) }

    /** Trail colour per band, precomputed: trails are rebuilt every frame for the selected plane. */
    fun hexForBand(band: Int): String = bandHex.getValue(band)
}

fun iconName(shape: Shape, band: Int) = "ac_${shape.name}_$band"

/** Renders every silhouette in every band colour, outlined in near-black so it reads on any map. */
fun buildAircraftSprites(context: Context): Map<String, Bitmap> {
    val density = context.resources.displayMetrics.density
    val px = (36 * density).toInt()
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        color = 0xE6000000.toInt()
        strokeWidth = 3.5f
    }
    val out = HashMap<String, Bitmap>()
    for (shape in Shape.entries) {
        val path: Path = PathParser.createPathFromPathData(shape.path)
        for (band in AltitudeColors.bands) {
            val bmp = createBitmap(px, px)
            val c = Canvas(bmp)
            c.scale(px / 64f, px / 64f)
            c.drawPath(path, stroke)
            fill.color = AltitudeColors.colorForBand(band)
            c.drawPath(path, fill)
            out[iconName(shape, band)] = bmp
        }
    }
    return out
}
