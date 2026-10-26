package app.contrail.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlin.math.abs
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.contrail.data.user.MapPrefs
import app.contrail.domain.AircraftKind
import app.contrail.domain.Filters
import app.contrail.domain.Fmt
import app.contrail.ui.theme.InsetGroup
import app.contrail.ui.theme.Numeric
import app.contrail.ui.theme.Segmented
import app.contrail.ui.theme.TextAction
import app.contrail.ui.theme.ToggleRow
import kotlin.math.roundToInt


@Composable
fun LayersSheet(prefs: MapPrefs, onChange: ((MapPrefs) -> MapPrefs) -> Unit) {
    val cell = MaterialTheme.colorScheme.surfaceContainerHigh
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding().padding(bottom = 16.dp)) {
        SheetTitle("Map")
        val styles = listOf<String?>(null) + MapStyle.entries.map { it.name }
        Segmented(
            listOf("Auto") + MapStyle.entries.map { it.label },
            styles.indexOf(prefs.style).coerceAtLeast(0),
            { i -> onChange { it.copy(style = styles[i]) } },
        )
        Spacer(Modifier.height(20.dp))
        InsetGroup(cell = cell) {
            row { ToggleRow("Rain radar", "Animated, last hour", prefs.radar) { v -> onChange { it.copy(radar = v) } } }
            row { ToggleRow("Airports", "Smaller airfields appear as you zoom in", prefs.airports) { v -> onChange { it.copy(airports = v) } } }
            row { ToggleRow("Callsign labels", "Shown when zoomed in", prefs.labels) { v -> onChange { it.copy(labels = v) } } }
            row { ToggleRow("Trails for every aircraft", "Paths recorded while the app is open", prefs.allTrails) { v -> onChange { it.copy(allTrails = v) } } }
        }
        InsetGroup(header = "Altitude colours", cell = cell) { row { AltitudeLegend() } }
    }
}

@Composable
private fun SheetTitle(text: String, action: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 4.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
        action()
    }
}

@Composable
private fun AltitudeLegend() {
    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        listOf(-1 to "Gnd", 0 to "0", 4000 to "4k", 8000 to "8k", 12000 to "12k", 20000 to "20k", 30000 to "30k", 40000 to "40k+").forEach { (alt, label) ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                androidx.compose.foundation.Canvas(Modifier.size(22.dp, 8.dp)) {
                    drawRoundRect(Color(if (alt < 0) AltitudeColors.colorForBand(AltitudeColors.GROUND_BAND) else AltitudeColors.colorForAltitude(alt)), cornerRadius = androidx.compose.ui.geometry.CornerRadius(8f))
                }
                Spacer(Modifier.height(4.dp))
                Text(label, style = MaterialTheme.typography.labelSmall.merge(Numeric), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun FiltersSheet(filters: Filters, onChange: (Filters) -> Unit) {
    var range by remember(filters) { mutableStateOf(filters.altMinFt.toFloat()..filters.altMaxFt.toFloat()) }
    val cs = MaterialTheme.colorScheme
    val cell = cs.surfaceContainerHigh
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding().padding(bottom = 16.dp)) {
        SheetTitle("Filters") { TextAction("Reset", { onChange(Filters()) }, enabled = filters.active) }
        InsetGroup(header = "Altitude", cell = cell) {
            row {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(
                        "${Fmt.feet(range.start.roundToInt())} to ${Fmt.feet(range.endInclusive.roundToInt())}${if (range.endInclusive >= Filters.MAX_ALT) "+" else ""}",
                        style = MaterialTheme.typography.bodyLarge.merge(Numeric),
                    )
                    Spacer(Modifier.height(8.dp))
                    IosRangeSlider(range, Filters.MAX_ALT.toFloat(), step = 1000f, onChange = { range = it }) {
                        onChange(filters.copy(altMinFt = range.start.roundToInt(), altMaxFt = range.endInclusive.roundToInt()))
                    }
                }
            }
        }
        InsetGroup(header = "Show", cell = cell) {
            AircraftKind.entries.forEach { k ->
                val on = k in filters.kinds
                row { CheckRow(k.label, on) { onChange(filters.copy(kinds = if (on) filters.kinds - k else filters.kinds + k)) } }
            }
        }
        InsetGroup(cell = cell, footer = "Emergencies (squawk 7500, 7600 or 7700) are always shown.") {
            row { ToggleRow("Hide aircraft on the ground", "Parked and taxiing", filters.hideGround) { onChange(filters.copy(hideGround = it)) } }
            row { ToggleRow("Only military and notable", "Database-flagged interesting aircraft", filters.specialOnly) { onChange(filters.copy(specialOnly = it)) } }
        }
    }
}

/** iOS-style two-thumb slider: thin track, white knobs. Drags the nearer knob, snapping to [step]. */
@Composable
private fun IosRangeSlider(value: ClosedFloatingPointRange<Float>, max: Float, step: Float, onChange: (ClosedFloatingPointRange<Float>) -> Unit, onDone: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    // The gesture outlives recompositions, so it reads the latest values and callbacks.
    val current by rememberUpdatedState(value)
    val change by rememberUpdatedState(onChange)
    val done by rememberUpdatedState(onDone)
    BoxWithConstraints(
        Modifier.fillMaxWidth().height(32.dp).pointerInput(max) {
            val knob = 28.dp.toPx()
            fun toValue(x: Float) = (((x - knob / 2) / (size.width - knob)).coerceIn(0f, 1f) * max / step).roundToInt() * step
            var dragStart = true
            detectHorizontalDragGestures(
                onDragStart = { o ->
                    val v = toValue(o.x)
                    dragStart = abs(v - current.start) < abs(v - current.endInclusive) || (v < current.start)
                },
                onDragEnd = { done() },
            ) { c, _ ->
                val v = toValue(c.position.x)
                change(if (dragStart) v.coerceAtMost(current.endInclusive)..current.endInclusive else current.start..v.coerceAtLeast(current.start))
            }
        }.semantics { contentDescription = "Altitude range" },
        contentAlignment = Alignment.CenterStart,
    ) {
        val travel = maxWidth - 28.dp
        val a = travel * (value.start / max)
        val b = travel * (value.endInclusive / max)
        Box(Modifier.padding(horizontal = 14.dp).fillMaxWidth().height(4.dp).background(cs.surfaceContainerHighest, CircleShape))
        Box(Modifier.offset(x = a + 14.dp).width(b - a).height(4.dp).background(cs.primary, CircleShape))
        listOf(a, b).forEach { x ->
            Box(Modifier.offset(x = x).size(28.dp).shadow(4.dp, CircleShape).background(Color.White, CircleShape))
        }
    }
}

/** List row with an iOS checkmark when selected. */
@Composable
private fun CheckRow(title: String, checked: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(checked, role = Role.Checkbox) { onClick() }.heightIn(min = 44.dp).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (checked) Icon(Icons.Rounded.Check, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
    }
}
