package app.contrail.ui.aircraft

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Flight
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.contrail.data.enrich.Airport
import app.contrail.data.enrich.Route
import app.contrail.domain.FlightProgress
import app.contrail.domain.Fmt
import app.contrail.ui.theme.Numeric
import app.contrail.ui.theme.Readout
import java.time.Instant
import java.time.ZoneId

/**
 * Time at [tz] (an airport's zone), named only when it differs from the phone's.
 * [place] (the airport's city) names it; the bundled tz ids can name a neighbouring city (MEL is "Australia/Hobart").
 */
fun clock(ms: Long, tz: String? = null, place: String? = null): String =
    clockParts(ms, tz, place).let { (time, zone) -> if (zone == null) time else "$time $zone" }

/** The time, plus "Melbourne time" when that zone's offset differs from the phone's (else null). */
fun clockParts(ms: Long, tz: String?, place: String?): Pair<String, String?> {
    val zone = tz?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()
    val t = Instant.ofEpochMilli(ms).atZone(zone)
    val local = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
    // "Melbourne time" reads better than the locale's "GMT+11:00".
    return Fmt.time(t) to if (t.offset == local.offset) null else "${place ?: zone.id.substringAfterLast('/').replace('_', ' ')} time"
}

@Composable
fun RouteCard(route: Route, p: FlightProgress, now: Long) {
    val cs = MaterialTheme.colorScheme
    Surface(shape = MaterialTheme.shapes.medium, color = cs.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Endpoint(route.origin, Alignment.Start, Modifier.weight(1f))
                ProgressLine(if (p.verified) p.fraction else null, Modifier.weight(1.3f).padding(horizontal = 8.dp))
                Endpoint(route.destination, Alignment.End, Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
            Row {
                // Relative time large ("2h 10m ago", "in 35m"), the clock time and its zone small underneath.
                TimeBlock(
                    label = if (p.departedEstimated) "Took off (est.)" else "Took off",
                    big = p.departedAt?.let { "${Fmt.duration(now - it)} ago" } ?: Fmt.NONE,
                    value = p.departedAt?.let { clockParts(it, route.origin.tz, route.origin.city) },
                    align = Alignment.Start,
                    modifier = Modifier.weight(1f),
                )
                val landed = p.landedAt
                TimeBlock(
                    label = when {
                        landed != null -> "Landed"
                        p.eta != null -> "Lands (est.)"
                        else -> "Lands"
                    },
                    big = when {
                        landed != null -> "${Fmt.duration(now - landed)} ago"
                        p.eta != null -> if (p.eta - now < 60_000) "any moment" else "in ${Fmt.duration(p.eta - now)}"
                        else -> Fmt.NONE
                    },
                    value = (landed ?: p.eta)?.let { clockParts(it, route.destination.tz, route.destination.city) },
                    align = Alignment.End,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(10.dp))
            if (p.verified) {
                Text(
                    "${Fmt.nm(p.flownNm)} flown · ${Fmt.nm(p.remainingNm)} to go · ${(p.fraction * 100).toInt()}%",
                    style = MaterialTheme.typography.bodySmall.merge(Numeric),
                    color = cs.onSurfaceVariant,
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Rounded.HelpOutline, null, Modifier.size(16.dp), tint = cs.tertiary)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "Route unconfirmed: the plane's position doesn't match this route, so times aren't shown.",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun Endpoint(a: Airport, align: Alignment.Horizontal, modifier: Modifier) {
    val ta = if (align == Alignment.End) TextAlign.End else TextAlign.Start
    Column(modifier, horizontalAlignment = align) {
        Text(a.code, style = MaterialTheme.typography.headlineMedium, textAlign = ta)
        Text(
            a.shortName,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = ta,
        )
    }
}

@Composable
private fun TimeBlock(label: String, big: String, value: Pair<String, String?>?, align: Alignment.Horizontal, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    Column(modifier, horizontalAlignment = align) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
        Text(big, style = MaterialTheme.typography.titleLarge.merge(Readout), maxLines = 1)
        value?.let { (time, zone) ->
            Text(time, style = MaterialTheme.typography.bodySmall.merge(Numeric), color = cs.onSurfaceVariant, maxLines = 1)
            // The zone goes on its own small line so a long city name can't wrap the time.
            zone?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, maxLines = 1) }
        }
    }
}

/** Dashed track with a solid flown part and a plane icon riding the progress. Null = unknown. */
@Composable
private fun ProgressLine(fraction: Float?, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val f by animateFloatAsState(fraction ?: 0.5f, label = "progress")
    val desc = fraction?.let { "${(it * 100).toInt()} percent of the way" } ?: "Progress unknown"
    Box(modifier.height(28.dp).semantics { contentDescription = desc }, contentAlignment = Alignment.CenterStart) {
        Canvas(Modifier.fillMaxWidth().height(28.dp)) {
            val y = size.height / 2
            drawLine(cs.outline, Offset(0f, y), Offset(size.width, y), 3.dp.toPx(), StrokeCap.Round, PathEffect.dashPathEffect(floatArrayOf(6f, 10f)))
            if (fraction != null) drawLine(cs.primary, Offset(0f, y), Offset(size.width * f, y), 3.dp.toPx(), StrokeCap.Round)
        }
        PlaneAt(if (fraction == null) 0.5f else f) {
            Icon(Icons.Rounded.Flight, null, Modifier.size(22.dp).rotate(90f), tint = if (fraction == null) cs.outline else cs.primary)
        }
    }
}

@Composable
private fun PlaneAt(fraction: Float, content: @Composable () -> Unit) {
    Layout(content, Modifier.fillMaxWidth()) { measurables, constraints ->
        val p = measurables.first().measure(constraints.copy(minWidth = 0))
        layout(constraints.maxWidth, p.height) {
            p.place(((constraints.maxWidth - p.width) * fraction).toInt(), 0)
        }
    }
}

@Composable
fun RouteSkeleton() {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth().height(132.dp)) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Text("Looking up route…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
