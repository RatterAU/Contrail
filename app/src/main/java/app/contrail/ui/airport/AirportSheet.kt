package app.contrail.ui.airport

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.contrail.data.weather.Metar
import app.contrail.domain.BoardRow
import app.contrail.domain.BoardStatus
import app.contrail.domain.Fmt
import app.contrail.ui.aircraft.clock
import app.contrail.ui.map.AirportUi
import app.contrail.ui.theme.Numeric
import app.contrail.ui.theme.CloseButton
import app.contrail.ui.theme.InsetGroup
import app.contrail.ui.theme.Segmented
import app.contrail.ui.theme.ValueRow
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt

@Composable
fun AirportSheet(ui: AirportUi, onAircraft: (String) -> Unit, onClose: () -> Unit) {
    val ap = ui.airport
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    // Everything here shows minutes, so tick on the minute rather than every second.
    LaunchedEffect(Unit) { while (true) { delay(60_000 - System.currentTimeMillis() % 60_000); now = System.currentTimeMillis() } }
    val zone = ap.tz?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding().padding(bottom = 24.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(ap.code, style = MaterialTheme.typography.headlineMedium)
                    if (ap.icao != null && ap.icao != ap.code) {
                        Spacer(Modifier.width(8.dp))
                        Text(ap.icao, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 3.dp))
                    }
                }
                Text(ap.name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    Fmt.time(Instant.ofEpochMilli(now).atZone(zone)),
                    style = MaterialTheme.typography.titleLarge.merge(Numeric),
                )
                Text("Local time", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(14.dp))
            CloseButton(onClose)
        }
        Spacer(Modifier.height(14.dp))
        WeatherCard(ui.metar, ui.weatherLoaded, now)
        Spacer(Modifier.height(20.dp))
        RunwaysCard(ui)
        Boards(ui, onAircraft, ap.tz, ap.city)
    }
}

@Composable
private fun WeatherCard(m: Metar?, loaded: Boolean, now: Long) {
    val cs = MaterialTheme.colorScheme
    Surface(shape = MaterialTheme.shapes.medium, color = cs.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            if (m == null) {
                Text(if (loaded) "No weather report from this airport" else "Loading weather…", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                return@Column
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                m.fltCat?.let { FlightCategoryChip(it) }
                Spacer(Modifier.width(10.dp))
                Text(
                    "Observed ${m.obsTime?.let { Fmt.ago(now - it * 1000) } ?: ""}",
                    style = MaterialTheme.typography.labelMedium,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                m.temp?.let { Text("${it.roundToInt()}°", style = MaterialTheme.typography.headlineMedium.merge(Numeric)) }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Label("Wind")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Arrow points where the wind blows to.
                        m.windDir?.let { Icon(Icons.Rounded.Navigation, null, Modifier.size(18.dp).rotate((it + 180).toFloat()), tint = cs.primary) }
                        Text(
                            when {
                                (m.wspd ?: 0.0) < 1 -> "Calm"
                                m.windVariable -> "Variable ${Fmt.knots(m.wspd)}"
                                else -> "${Fmt.degrees(m.windDir)} ${Fmt.knots(m.wspd)}" + (m.wgst?.let { " G${it.roundToInt()}" } ?: "")
                            },
                            style = MaterialTheme.typography.bodyLarge.merge(Numeric),
                        )
                    }
                }
                Column(Modifier.weight(1f)) {
                    Label("Visibility")
                    Text(m.visibilityText, style = MaterialTheme.typography.bodyLarge.merge(Numeric))
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Label("Cloud")
                    Text(m.cloudText, style = MaterialTheme.typography.bodyLarge)
                }
                Column(Modifier.weight(1f)) {
                    Label("Dew point · QNH")
                    Text("${Fmt.celsius(m.dewp)} · ${m.altim?.roundToInt() ?: "—"} hPa", style = MaterialTheme.typography.bodyLarge.merge(Numeric))
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(m.rawOb, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), color = cs.onSurfaceVariant)
        }
    }
}

@Composable
private fun FlightCategoryChip(cat: String) {
    val (bg, desc) = when (cat) {
        "VFR" -> Color(0xFF22C55E) to "Good"
        "MVFR" -> Color(0xFF3B82F6) to "Marginal"
        "IFR" -> Color(0xFFEF4444) to "Poor"
        "LIFR" -> Color(0xFFD946EF) to "Very poor"
        else -> Color.Gray to cat
    }
    Row(
        Modifier.background(bg.copy(alpha = 0.18f), CircleShape).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).background(bg, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text("$cat · $desc", style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun RunwaysCard(ui: AirportUi) {
    if (ui.runways.isEmpty()) return
    InsetGroup(header = "Runways", cell = MaterialTheme.colorScheme.surfaceContainerHigh) {
        ui.activeRunway?.let { row { ValueRow("In use", it.ident + if (it.fromTraffic) "" else " (likely, from wind)") } }
        ui.runways.sortedByDescending { it.lengthFt ?: 0 }.forEach { r ->
            row { ValueRow("${r.leIdent}/${r.heIdent}", listOfNotNull(r.lengthFt?.let { Fmt.feet(it) }, surface(r.surface)).joinToString(", ")) }
        }
    }
}

private fun surface(code: String) = when (code.uppercase().take(3)) {
    "ASP", "ASH" -> "Asphalt"
    "CON" -> "Concrete"
    "GRS", "GRA", "TUR" -> "Grass"
    "GRV", "GVL" -> "Gravel"
    "DIR" -> "Dirt"
    "WAT" -> "Water"
    else -> code.ifEmpty { null }
}

@Composable
private fun Boards(ui: AirportUi, onAircraft: (String) -> Unit, tz: String?, city: String?) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val boards = ui.boards
    Segmented(
        listOf("Arrivals" to boards?.arrivals?.size, "Departures" to boards?.departures?.size).map { (label, n) -> if (n != null) "$label ($n)" else label },
        tab,
        { tab = it },
    )
    Spacer(Modifier.height(12.dp))
    if (boards == null) {
        Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) }
        return
    }
    val rows = if (tab == 0) boards.arrivals else boards.departures
    InsetGroup(
        cell = MaterialTheme.colorScheme.surfaceContainerHigh,
        footer = "Built live from aircraft within 250 nm. There's no published schedule, so flights not yet airborne or out of range don't appear.",
    ) {
        if (rows.isEmpty()) row {
            Text(
                if (tab == 0) "No arrivals tracked right now" else "No departures tracked right now",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
        rows.forEach { r -> row { BoardRowItem(r, arrival = tab == 0, tz = tz, city = city, onClick = { onAircraft(r.track.hex) }) } }
    }
}

@Composable
private fun BoardRowItem(r: BoardRow, arrival: Boolean, tz: String?, city: String?, onClick: () -> Unit) {
    val a = r.track.aircraft
    val now = System.currentTimeMillis()
    val other = r.route?.let { if (arrival) it.origin else it.destination }
    val (status, highlight) = when (r.status) {
        BoardStatus.LANDED -> (r.time?.let { "Landed ${clock(it, tz, city)}" } ?: "Landed") to false
        BoardStatus.APPROACH -> (r.time?.let { "Landing ~${Fmt.duration(it - now)}" } ?: "On approach") to true
        BoardStatus.EN_ROUTE -> (r.time?.let { "Est. ${clock(it, tz, city)}" } ?: "En route") to false
        BoardStatus.TAXIING -> "Taxiing" to true
        BoardStatus.ON_GROUND -> "At airport" to false
        BoardStatus.DEPARTED -> (r.time?.let { (if (r.timeEstimated) "Departed ≈" else "Departed ") + clock(it, tz, city) } ?: "Departed") to false
        BoardStatus.JUST_DEPARTED -> "Just departed" to true
    }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(r.route?.flightNumber ?: a.label, style = MaterialTheme.typography.titleMedium)
            Text(
                listOfNotNull(other?.let { (if (arrival) "from " else "to ") + it.shortName }, a.typeCode).joinToString(" · ").ifEmpty { a.registration ?: "" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            status,
            style = MaterialTheme.typography.labelLarge.merge(Numeric),
            color = if (highlight) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun Label(text: String) =
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
