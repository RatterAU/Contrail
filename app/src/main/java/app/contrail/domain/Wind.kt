package app.contrail.domain

import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

/** Open-Meteo levels: 250 hPa is about FL340 (airliner cruise), 500 hPa about FL180. */
enum class WindLevel(val label: String, val speedVar: String, val dirVar: String) {
    SURFACE("Surface", "wind_speed_10m", "wind_direction_10m"),
    FL180("FL180", "wind_speed_500hPa", "wind_direction_500hPa"),
    FL340("FL340", "wind_speed_250hPa", "wind_direction_250hPa"),
}

/** Wind at one grid point for every [WindLevel], as speed (kt) and the direction it blows from (°). */
class WindSample(val speedKt: FloatArray, val fromDeg: FloatArray)

/**
 * Wind on a regular lat/lon grid with [step]° spacing, keyed by grid index. Points are fetched from Open-Meteo,
 * which counts every point as a call (10,000 a day per IP), so the grid is coarse and aligned to fixed
 * multiples of [step]: panning reuses points already fetched.
 */
class WindGrid(val step: Double, val samples: Map<Long, WindSample>) {
    /** Wind vector (kt) at a position, bilinear between the four grid points around it; null if any is missing. */
    fun sample(lat: Double, lon: Double, level: WindLevel): Pair<Double, Double>? {
        val y = lat / step
        val x = lon / step
        val i = floor(y).toInt()
        val j = floor(x).toInt()
        val fy = y - i
        val fx = x - j
        var u = 0.0
        var v = 0.0
        for ((di, dj, w) in arrayOf(Triple(0, 0, (1 - fy) * (1 - fx)), Triple(0, 1, (1 - fy) * fx), Triple(1, 0, fy * (1 - fx)), Triple(1, 1, fy * fx))) {
            val s = samples[key(i + di, j + dj, step)] ?: return null
            val (pu, pv) = toVector(s.speedKt[level.ordinal].toDouble(), s.fromDeg[level.ordinal].toDouble())
            u += pu * w
            v += pv * w
        }
        return u to v
    }

    companion object {
        private val STEPS = doubleArrayOf(0.25, 0.5, 1.0, 2.0, 3.0, 5.0, 10.0, 15.0, 20.0, 30.0)
        const val MAX_POINTS = 200

        /** Grid spacing for a view: about ten cells across, coarser if that would need more than [MAX_POINTS] points. */
        fun stepFor(latSpan: Double, lonSpan: Double): Double {
            val want = maxOf(latSpan, lonSpan) / 10
            return STEPS.firstOrNull { it >= want && pointsFor(latSpan, lonSpan, it) <= MAX_POINTS } ?: STEPS.last()
        }

        private fun pointsFor(latSpan: Double, lonSpan: Double, step: Double) = (latSpan / step + 3) * (minOf(lonSpan, 360.0) / step + 3)

        /** Grid indices (lat, lon) covering the box plus one cell all round. */
        fun points(south: Double, west: Double, north: Double, east: Double, step: Double): List<Pair<Int, Int>> {
            val i0 = floor(maxOf(south, -89.0) / step).toInt() - 1
            val i1 = ceil(minOf(north, 89.0) / step).toInt() + 1
            val j0 = floor(west / step).toInt() - 1
            val j1 = ceil((if (east < west) east + 360 else east) / step).toInt() + 1
            return (i0..i1).flatMap { i -> (j0..j1).map { j -> i to j } }.distinctBy { key(it.first, it.second, step) }
        }

        /** Index to coordinates, longitude wrapped into -180..180. */
        fun latLon(i: Int, j: Int, step: Double): Pair<Double, Double> = i * step to wrapLon(j * step)

        /** One key per point on the globe, so 190° and -170° are the same point. */
        fun key(i: Int, j: Int, step: Double): Long {
            val perTurn = (360 / step).roundToInt()
            val jj = Math.floorMod(j + perTurn / 2, perTurn)
            return i.toLong() shl 32 or jj.toLong()
        }

        private fun wrapLon(lon: Double) = ((lon + 180) % 360 + 360) % 360 - 180

        /** Speed and direction it blows *from* (meteorological) to an (east, north) vector of where the air goes. */
        fun toVector(speed: Double, fromDeg: Double): Pair<Double, Double> {
            val r = Math.toRadians(fromDeg)
            return -speed * sin(r) to -speed * cos(r)
        }
    }
}
