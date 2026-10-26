package app.contrail.ui.aircraft

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.contrail.domain.Fmt
import app.contrail.domain.TrailPoint
import app.contrail.ui.map.AltitudeColors
import app.contrail.ui.theme.Numeric
import kotlin.math.roundToInt

/**
 * Altitude (filled, coloured like the map trail) and ground speed (line) over the recorded part
 * of the flight. Drag across it to inspect any moment; the map marks that point.
 */
@Composable
fun ProfileChart(trail: List<TrailPoint>, onScrub: (TrailPoint?) -> Unit) {
    val pts = remember(trail) { trail.filter { it.alt != null || it.onGround } }
    if (pts.size < 3) return
    val cs = MaterialTheme.colorScheme
    var cursor by remember { mutableStateOf<Int?>(null) }
    val t0 = pts.first().time
    val t1 = pts.last().time.coerceAtLeast(t0 + 1)
    val maxAlt = remember(pts) { (pts.maxOf { it.alt ?: 0 }.coerceAtLeast(1000) * 1.1).toFloat() }
    val maxGs = remember(pts) { (pts.maxOf { it.gs ?: 0.0 }.coerceAtLeast(100.0) * 1.1).toFloat() }
    // New trail points arrive every few seconds; the gesture handlers read the latest list instead of
    // restarting (a restart would cancel a drag in progress).
    val latest by rememberUpdatedState(pts)
    val scrub by rememberUpdatedState(onScrub)
    val shown = cursor?.let { pts.getOrNull(it) } ?: pts.last()

    Surface(shape = MaterialTheme.shapes.medium, color = cs.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row {
                Text("Flight profile", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(
                    "${clock(shown.time)} · ${if (shown.onGround) "Ground" else Fmt.feet(shown.alt)} · ${Fmt.knots(shown.gs)}",
                    style = MaterialTheme.typography.labelMedium.merge(Numeric),
                    color = cs.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(10.dp))
            fun indexAt(x: Float, width: Float): Int {
                val p = latest
                val a = p.first().time
                val t = a + ((x / width).coerceIn(0f, 1f) * (p.last().time - a)).toLong()
                val i = p.binarySearchBy(t) { it.time }.let { if (it >= 0) it else -it - 1 }.coerceIn(0, p.lastIndex)
                return if (i > 0 && t - p[i - 1].time < p[i].time - t) i - 1 else i
            }
            fun pick(x: Float, width: Int) { cursor = indexAt(x, width.toFloat()).also { scrub(latest[it]) } }
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .semantics { contentDescription = "Altitude and speed profile, ${pts.size} points. Drag to inspect." }
                    .pointerInput(Unit) {
                        // Horizontal only, so vertical drags still move the sheet.
                        detectHorizontalDragGestures(
                            onDragStart = { o -> pick(o.x, size.width) },
                            onDragEnd = { cursor = null; scrub(null) },
                            onDragCancel = { cursor = null; scrub(null) },
                        ) { change, _ -> pick(change.position.x, size.width) }
                    }
                    .pointerInput(Unit) { detectTapGestures { o -> pick(o.x, size.width) } }
                    // Paths are rebuilt only when the trail or size changes; dragging the cursor just redraws it.
                    .drawWithCache {
                        fun x(t: Long) = (t - t0).toFloat() / (t1 - t0) * size.width
                        fun yAlt(a: Int?) = size.height - (a ?: 0) / maxAlt * size.height
                        fun yGs(g: Double?) = size.height - ((g ?: 0.0) / maxGs * size.height).toFloat()
                        // At most ~one point per pixel column: a long trail has far more points than the chart has pixels.
                        val step = (pts.size / size.width.coerceAtLeast(1f)).toInt().coerceAtLeast(1)
                        val drawn = pts.filterIndexed { i, _ -> i % step == 0 || i == pts.lastIndex }
                        val area = Path().apply {
                            moveTo(0f, size.height)
                            drawn.forEach { lineTo(x(it.time), yAlt(if (it.onGround) 0 else it.alt)) }
                            lineTo(size.width, size.height)
                            close()
                        }
                        val top = top(maxAlt)
                        val low = Color(AltitudeColors.colorForAltitude(0))
                        val fill = Brush.verticalGradient(listOf(top.copy(alpha = 0.55f), low.copy(alpha = 0.15f)))
                        val altLine = Path().apply { drawn.forEachIndexed { i, p -> val px = x(p.time); val py = yAlt(if (p.onGround) 0 else p.alt); if (i == 0) moveTo(px, py) else lineTo(px, py) } }
                        val gsLine = Path().apply { drawn.forEachIndexed { i, p -> val px = x(p.time); val py = yGs(p.gs); if (i == 0) moveTo(px, py) else lineTo(px, py) } }
                        val altStroke = Stroke(2.dp.toPx())
                        val gsStroke = Stroke(1.5.dp.toPx())
                        onDrawBehind {
                            drawPath(area, fill)
                            drawPath(altLine, top, style = altStroke)
                            drawPath(gsLine, cs.onSurfaceVariant.copy(alpha = 0.7f), style = gsStroke)
                            cursor?.let { pts.getOrNull(it) }?.let { p ->
                                val cx = x(p.time)
                                drawLine(cs.onSurface, Offset(cx, 0f), Offset(cx, size.height), 1.5.dp.toPx())
                                drawCircle(cs.onSurface, 4.dp.toPx(), Offset(cx, yAlt(if (p.onGround) 0 else p.alt)))
                            }
                        }
                    },
            ) {}
            Spacer(Modifier.height(6.dp))
            Row {
                Text("— Altitude", style = MaterialTheme.typography.labelSmall, color = top(maxAlt), modifier = Modifier.weight(1f))
                Text("— Ground speed", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
            }
            Text(
                "Since ${clock(t0)}.",
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

private fun top(maxAlt: Float) = Color(AltitudeColors.colorForAltitude((maxAlt / 1.1f).roundToInt()))
