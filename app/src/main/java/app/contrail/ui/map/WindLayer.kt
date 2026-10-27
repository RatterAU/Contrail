package app.contrail.ui.map

import android.os.PowerManager
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.contrail.domain.WindGrid
import app.contrail.domain.WindLevel
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Wind as drifting streaks, Windy-style. The field is sampled once per camera stop on a coarse screen grid
 * (one projection call per cell), and particles move in screen space, so a frame costs no map calls.
 * Particles hide while the camera moves, while [covered] (a page covers the map, or following a plane moves the
 * map every frame), and while the phone is running hot.
 */
@Composable
fun WindLayer(controller: MapController, grid: WindGrid?, level: WindLevel, dark: Boolean, covered: Boolean, modifier: Modifier = Modifier) {
    val density = LocalDensity.current.density
    val context = LocalContext.current
    var hot by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        val pm = context.getSystemService(PowerManager::class.java)
        val listener = PowerManager.OnThermalStatusChangedListener { hot = it >= PowerManager.THERMAL_STATUS_SEVERE }
        pm.addThermalStatusListener(context.mainExecutor, listener)
        onDispose { pm.removeThermalStatusListener(listener) }
    }
    val paused = covered || hot
    val moving by controller.moving.collectAsStateWithLifecycle()
    var size by remember { mutableStateOf(IntSize.Zero) }
    var field by remember { mutableStateOf<ScreenWind?>(null) }
    LaunchedEffect(grid, level, moving, size) {
        field = if (grid == null || moving || size == IntSize.Zero) null else ScreenWind.sample(controller, grid, level, size, density)
    }
    val particles = remember { Particles() }
    val frame = remember { mutableLongStateOf(0L) }
    LaunchedEffect(field, paused) {
        val f = field ?: return@LaunchedEffect
        if (paused) return@LaunchedEffect
        particles.reset(f, density)
        var last = 0L
        while (true) {
            withFrameNanos { t ->
                // 20 fps is smooth enough for drifting streaks, at a third of the cost of 60.
                if (t - last >= 50_000_000L) {
                    particles.step(f, if (last == 0L) 0f else ((t - last) / 1e9f).coerceAtMost(0.1f))
                    last = t
                    frame.longValue = t
                }
            }
        }
    }
    val colour = if (dark) Color.White else Color(0xFF1B2232)
    Canvas(modifier.onSizeChanged { size = it }) {
        if (field == null || paused) return@Canvas
        frame.longValue // redraw on every step, without recomposing
        for (bin in 0 until Particles.BINS) {
            val pts = particles.segments(bin)
            if (pts.isNotEmpty()) drawPoints(pts, PointMode.Lines, colour.copy(alpha = (bin + 1f) / Particles.BINS * 0.8f), 1.6f * density, StrokeCap.Round)
        }
    }
}

/** Screen-space wind velocity (px/s) on a grid of [cell] px, bilinear between cells. Null where there's no data. */
class ScreenWind(private val cols: Int, private val rows: Int, private val cell: Float, private val vx: FloatArray, private val vy: FloatArray, val width: Int, val height: Int) {
    fun at(x: Float, y: Float): Pair<Float, Float>? {
        val gx = (x / cell).coerceIn(0f, cols - 1.001f)
        val gy = (y / cell).coerceIn(0f, rows - 1.001f)
        val i = gx.toInt()
        val j = gy.toInt()
        val fx = gx - i
        val fy = gy - j
        var sx = 0f
        var sy = 0f
        for ((di, dj, w) in arrayOf(Triple(0, 0, (1 - fx) * (1 - fy)), Triple(1, 0, fx * (1 - fy)), Triple(0, 1, (1 - fx) * fy), Triple(1, 1, fx * fy))) {
            val k = (j + dj) * cols + i + di
            if (vx[k].isNaN()) return null
            sx += vx[k] * w
            sy += vy[k] * w
        }
        return sx to sy
    }

    companion object {
        fun sample(c: MapController, grid: WindGrid, level: WindLevel, size: IntSize, density: Float): ScreenWind {
            val cell = 40 * density
            val cols = (size.width / cell).toInt() + 2
            val rows = (size.height / cell).toInt() + 2
            val bearing = Math.toRadians(c.map.cameraPosition.bearing)
            val vx = FloatArray(cols * rows)
            val vy = FloatArray(cols * rows)
            for (j in 0 until rows) for (i in 0 until cols) {
                val ll = c.latLngAt(i * cell, j * cell)
                val k = j * cols + i
                val w = grid.sample(ll.latitude, ll.longitude, level)
                if (w == null) { vx[k] = Float.NaN; vy[k] = Float.NaN; continue }
                val (u, v) = w
                val kt = sqrt(u * u + v * v)
                // Screen speed grows with the square root of wind speed: a 10 kt breeze still drifts, a 150 kt jet doesn't streak off screen.
                val scale = if (kt < 0.1) 0.0 else 8 * density * sqrt(kt) / kt
                vx[k] = ((u * cos(bearing) - v * sin(bearing)) * scale).toFloat()
                vy[k] = (-(u * sin(bearing) + v * cos(bearing)) * scale).toFloat()
            }
            return ScreenWind(cols, rows, cell, vx, vy, size.width, size.height)
        }
    }
}

private class Particles {
    private var n = 0
    private var x = FloatArray(0)
    private var y = FloatArray(0)
    private var age = FloatArray(0)
    private var life = FloatArray(0)
    private val out = Array(BINS) { ArrayList<Offset>() }

    fun reset(f: ScreenWind, density: Float) {
        n = (f.width * f.height / (34 * density * 34 * density)).toInt().coerceIn(50, 1000)
        x = FloatArray(n); y = FloatArray(n); age = FloatArray(n); life = FloatArray(n)
        for (i in 0 until n) spawn(f, i, Random.nextFloat())
    }

    private fun spawn(f: ScreenWind, i: Int, startAge: Float) {
        x[i] = Random.nextFloat() * f.width
        y[i] = Random.nextFloat() * f.height
        life[i] = 2f + Random.nextFloat() * 2f
        age[i] = startAge * life[i]
    }

    fun step(f: ScreenWind, dt: Float) {
        for (b in out) b.clear()
        for (i in 0 until n) {
            age[i] += dt
            val v = f.at(x[i], y[i])
            if (v == null || age[i] > life[i] || x[i] !in 0f..f.width.toFloat() || y[i] !in 0f..f.height.toFloat()) { spawn(f, i, 0f); continue }
            x[i] += v.first * dt
            y[i] += v.second * dt
            // Fade in and out over each particle's life, so they don't pop.
            val bin = (sin(PI * age[i] / life[i]) * BINS).toInt().coerceIn(0, BINS - 1)
            out[bin] += Offset(x[i], y[i])
            out[bin] += Offset(x[i] - v.first * TAIL_S, y[i] - v.second * TAIL_S)
        }
    }

    fun segments(bin: Int): List<Offset> = out[bin]

    companion object {
        const val BINS = 4
        const val TAIL_S = 0.35f
    }
}
