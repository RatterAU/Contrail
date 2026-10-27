package app.contrail.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.contrail.AppContainer
import app.contrail.BuildInfo
import app.contrail.data.feed.SourceHealth
import app.contrail.domain.AltitudeUnit
import app.contrail.domain.ClockStyle
import app.contrail.domain.DistanceUnit
import app.contrail.domain.Fmt
import app.contrail.domain.SpeedUnit
import app.contrail.domain.Units
import app.contrail.ui.theme.InsetGroup
import app.contrail.ui.theme.LargeTitle
import app.contrail.ui.theme.Segmented
import app.contrail.ui.theme.ValueRow
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(container: AppContainer, onClose: () -> Unit) {
    val units by container.settings.units.collectAsState(Fmt.units)
    val scope = rememberCoroutineScope()
    fun set(f: (Units) -> Units) { scope.launch { container.settings.updateUnits(f) } }

    Page("Settings", onClose) {
        InsetGroup(header = "Units") {
            row { Choice("Altitude", AltitudeUnit.entries.map { it.label }, units.altitude.ordinal) { i -> set { it.copy(altitude = AltitudeUnit.entries[i]) } } }
            row { Choice("Speed", SpeedUnit.entries.map { it.label }, units.speed.ordinal) { i -> set { it.copy(speed = SpeedUnit.entries[i]) } } }
            row { Choice("Distance", listOf("nm", "km", "mi"), units.distance.ordinal) { i -> set { it.copy(distance = DistanceUnit.entries[i]) } } }
        }
        InsetGroup(header = "Time", footer = "Flight times always show in the airport's own time zone.") {
            row { Choice("Clock", ClockStyle.entries.map { it.label }, units.clock.ordinal) { i -> set { it.copy(clock = ClockStyle.entries[i]) } } }
        }
    }
}

@Composable
fun AboutScreen(container: AppContainer, onClose: () -> Unit) {
    val health by container.scheduler.health.collectAsState()
    Page("About", onClose) {
        InsetGroup {
            row { ValueRow("Version", BuildInfo.VERSION) }
            row { ValueRow("Accounts or tracking", "None") }
        }
        InsetGroup(header = "Live feeds", footer = "Aircraft positions come from volunteer receiver networks. If one feed fails, the other takes over.") {
            container.scheduler.sourceNames.forEach { name -> row { ValueRow(name, describe(health[name])) } }
        }
        InsetGroup(header = "Data sources") {
            CREDITS.forEach { (name, what) -> row { Credit(name, what) } }
        }
    }
}

private fun describe(h: SourceHealth?): String = when {
    h == null -> "Not used yet"
    h.ok -> "Working · ${h.latencyMs ?: "?"} ms"
    else -> "Failing · ${h.lastError ?: "unknown error"}"
}

private val CREDITS = listOf(
    "adsb.lol" to "Live aircraft positions (ODbL)",
    "adsb.fi" to "Live aircraft positions",
    "adsbdb" to "Aircraft details and routes",
    "adsb.lol routes" to "Route legs for multi-stop flights (ODbL)",
    "adsb.lol history" to "Past flights, from its daily open data (ODbL)",
    "OpenSky Network" to "The path flown before the app saw the plane",
    "airport-data.com" to "Aircraft photos, credited to each photographer",
    "aviationweather.gov" to "Airport weather (METAR and TAF)",
    "RainViewer" to "Rain radar",
    "Open-Meteo" to "Wind (CC BY 4.0)",
    "NASA GIBS" to "Satellite clouds from Himawari and GOES (public domain)",
    "aviationweather.gov SIGMETs" to "Turbulence, icing, storm, ash and cyclone areas",
    "OurAirports" to "Airports and runways (public domain)",
    "OpenFlights" to "Airlines (ODbL)",
    "OpenFreeMap and OpenStreetMap" to "Map tiles (© OpenStreetMap contributors, ODbL)",
    "EOX Sentinel-2 cloudless" to "Satellite imagery (CC BY-NC-SA 4.0, Copernicus Sentinel data)",
    "MapLibre" to "Map engine (BSD)",
    "Inter" to "Typeface (SIL Open Font License)",
    "Haze" to "Glass effects (Apache 2.0)",
)

@Composable
private fun Credit(name: String, what: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(name, style = MaterialTheme.typography.bodyLarge)
        Text(what, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Choice(label: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(8.dp))
        Segmented(options, selected, onSelect)
    }
}

@Composable
private fun Page(title: String, onClose: () -> Unit, content: @Composable () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            LargeTitle(title, onClose)
            Column(Modifier.padding(horizontal = 16.dp).padding(top = 8.dp).navigationBarsPadding()) { content() }
        }
    }
}
