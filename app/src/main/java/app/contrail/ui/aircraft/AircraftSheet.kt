package app.contrail.ui.aircraft

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.rounded.Air
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FlightLand
import androidx.compose.material.icons.rounded.FlightTakeoff
import androidx.compose.material.icons.rounded.LocalParking
import androidx.compose.material.icons.rounded.NearMe
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Straight
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material.icons.rounded.TrendingFlat
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.NorthEast
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.contrail.ContrailApp
import app.contrail.data.enrich.AircraftInfo
import app.contrail.data.enrich.Enrichment
import app.contrail.data.enrich.Route
import app.contrail.data.feed.PastFlight
import app.contrail.data.feed.FlightHistory
import app.contrail.data.enrich.Airport
import androidx.compose.ui.semantics.Role
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import app.contrail.data.static.StaticData
import app.contrail.domain.Aircraft
import app.contrail.domain.FlightInsights
import app.contrail.domain.FlightProgressCalc
import app.contrail.domain.Fmt
import app.contrail.domain.Insight
import app.contrail.domain.Phase
import app.contrail.domain.Squawks
import app.contrail.domain.Track
import app.contrail.domain.TrailPoint
import app.contrail.ui.map.AltitudeColors
import app.contrail.ui.theme.CapsuleButton
import app.contrail.ui.theme.CloseButton
import app.contrail.ui.theme.Numeric
import app.contrail.ui.theme.Readout
import app.contrail.ui.theme.SystemGreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.Year
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

@Composable
fun AircraftSheet(
    track: Track,
    enrichment: Enrichment?,
    following: Boolean,
    onFollow: () -> Unit,
    onClose: () -> Unit,
    onScrub: (TrailPoint?) -> Unit,
    onPast: (PastFlight) -> Unit = {},
) {
    val a = track.aircraft
    // Read only inside the parts that show time, so the 1 s tick doesn't recompose the whole sheet.
    val clock = remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); clock.longValue = System.currentTimeMillis() } }
    val info = enrichment?.info
    val route = FlightProgressCalc.shownRoute(track, enrichment?.route, System.currentTimeMillis(), enrichment?.legs.orEmpty())
    val airlineIcao = StaticData.now()?.airlineCode(a.callsign) ?: route?.airline?.icao // the operator flying it (QantasLink, not Qantas)
    val airlineName = route?.airline?.name ?: StaticData.now()?.airlineForCallsign(a.callsign)?.name

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .navigationBarsPadding()
            .padding(bottom = 24.dp),
    ) {
        Header(a, route?.flightNumber, airlineName, info?.fullName, airlineIcao, onClose)
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            CapsuleButton(
                text = if (following) "Following" else "Follow",
                icon = Icons.Rounded.NearMe,
                onClick = onFollow,
                filled = following,
            )
            Spacer(Modifier.width(8.dp))
            PhasePill(track, route) { clock.longValue }
        }
        Spacer(Modifier.height(16.dp))
        // Until adsb.lol's legs arrive, an adsbdb route that doesn't check out isn't called wrong yet.
        val waiting = enrichment?.legsPending == true && route?.let { FlightProgressCalc.compute(track, it, clock.longValue).verified } != true
        when {
            waiting -> RouteSkeleton()
            route != null -> LiveRouteCard(track, route) { clock.longValue }
            enrichment == null && a.callsign != null -> RouteSkeleton()
        }
        Spacer(Modifier.height(12.dp))
        Instruments(a)
        Spacer(Modifier.height(12.dp))
        RightNow(track, route) { clock.longValue }
        Spacer(Modifier.height(12.dp))
        if (track.trail.size >= 3) {
            ProfileChart(track.trail, onScrub)
            Spacer(Modifier.height(12.dp))
        }
        enrichment?.photos?.takeIf { it.isNotEmpty() }?.let {
            PhotoPager(it, a.label)
            Spacer(Modifier.height(12.dp))
        }
        AboutAircraft(a, info, airlineName)
        Spacer(Modifier.height(12.dp))
        PastFlights(a.hex, onPast)
        TechnicalDetails(track) { clock.longValue }
    }
}

@Composable
private fun LiveRouteCard(track: Track, route: Route, now: () -> Long) {
    val t = now()
    RouteCard(route, FlightProgressCalc.compute(track, route, t), t)
}

/** Flight number (or callsign) large with the airline's mark beside it; airline and type underneath. */
@Composable
private fun Header(a: Aircraft, flightNumber: String?, airline: String?, typeName: String?, airlineIcao: String?, onClose: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val logo = rememberAirlineLogo(airlineIcao)
    Row(verticalAlignment = Alignment.Top) {
        logo?.let {
            // Square marks at 44 dp; wordmarks keep their shape at a lower height so they stay legible.
            val wide = it.width > it.height * 1.4f
            val size = if (wide) Modifier.height(28.dp).width((28 * it.width.toFloat() / it.height).coerceAtMost(96f).dp) else Modifier.size(44.dp)
            Image(it.asImageBitmap(), airline?.let { n -> "$n logo" }, Modifier.padding(top = if (wide) 12.dp else 6.dp, end = 12.dp).then(size), contentScale = ContentScale.Fit)
        }
        Column(Modifier.weight(1f).padding(top = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(flightNumber ?: a.label, style = MaterialTheme.typography.headlineMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (a.isEmergency) Tag("Emergency", cs.error)
                if (a.military) Tag("Military", cs.tertiary)
            }
            val sub = listOfNotNull(airline, a.callsign?.takeIf { flightNumber != null && it != flightNumber }).joinToString(" · ")
            if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                typeName ?: a.description ?: a.typeCode ?: a.registration ?: a.hex.uppercase(),
                style = MaterialTheme.typography.bodyMedium,
                color = cs.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        CloseButton(onClose)
    }
}

@Composable
private fun rememberAirlineLogo(icao: String?): Bitmap? {
    val logos = (LocalContext.current.applicationContext as ContrailApp).container.logos
    return produceState(icao?.let(logos::peek), icao) { value = icao?.let { logos.get(it) } }.value
}

@Composable
private fun Tag(text: String, color: Color) {
    Spacer(Modifier.width(8.dp))
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = Color.White,
        modifier = Modifier.background(color, CircleShape).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

private fun Phase.icon(): ImageVector = when (this) {
    Phase.CLIMBING, Phase.TAKEOFF -> Icons.Rounded.FlightTakeoff
    Phase.DESCENDING, Phase.APPROACH, Phase.LANDED -> Icons.Rounded.FlightLand
    Phase.CRUISING, Phase.LEVEL -> Icons.Rounded.TrendingFlat
    Phase.TAXIING -> Icons.Rounded.Straight
    Phase.PARKED -> Icons.Rounded.LocalParking
}

private fun Phase.tint(): Color = when (this) {
    Phase.CLIMBING, Phase.TAKEOFF -> SystemGreen
    Phase.CRUISING, Phase.LEVEL -> Color(0xFF0A84FF)
    Phase.DESCENDING, Phase.APPROACH -> Color(0xFFFF9F0A)
    Phase.LANDED, Phase.TAXIING, Phase.PARKED -> Color(0xFF8E8E93)
}

/** What the flight is doing right now, as a coloured pill beside Follow. */
@Composable
private fun PhasePill(track: Track, route: Route?, now: () -> Long) {
    val t = now()
    val phase = FlightInsights.phase(track, route?.let { FlightProgressCalc.compute(track, it, t) }, t)
    val tint = phase.tint()
    Row(
        Modifier.height(36.dp).clip(CircleShape).background(tint.copy(alpha = 0.15f)).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(phase.icon(), null, Modifier.size(16.dp), tint = tint)
        Spacer(Modifier.width(6.dp))
        Text(phase.label, style = MaterialTheme.typography.labelLarge, color = tint)
    }
}

/** Four live readouts in one cell, divided by hairlines; altitude carries the map's altitude colour. */
@Composable
private fun Instruments(a: Aircraft) {
    val cs = MaterialTheme.colorScheme
    val altColour = Color(if (a.onGround) AltitudeColors.colorForBand(AltitudeColors.GROUND_BAND) else AltitudeColors.colorForAltitude(a.altBaro ?: 0))
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min).clip(MaterialTheme.shapes.medium).background(cs.surfaceContainerHigh).padding(vertical = 12.dp),
    ) {
        Readout("Altitude", Fmt.altitude(a), altColour, weight = 1.3f)
        Divider()
        Readout("Speed", Fmt.knots(a.groundSpeed))
        Divider()
        Readout("Climb", Fmt.verticalRate(a.verticalRate).replace("ft/min", "fpm")) // "ft/min" clips in a quarter-width cell
        Divider()
        Readout("Heading", Fmt.degrees(a.track))
    }
}

@Composable
private fun RowScope.Readout(label: String, value: String, dot: Color? = null, weight: Float = 1f) {
    Column(Modifier.weight(weight).padding(horizontal = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            dot?.let {
                Box(Modifier.size(7.dp).background(it, CircleShape))
                Spacer(Modifier.width(5.dp))
            }
            // The unit sits with the label ("Climb · fpm") so the number gets the column's full width.
            val unit = value.substringAfter(' ', "")
            Text(if (unit.isEmpty()) label else "$label · $unit", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        Spacer(Modifier.height(2.dp))
        Text(value.substringBefore(' '), style = Readout.copy(fontSize = 20.sp), maxLines = 1)
    }
}

@Composable
private fun Divider() {
    Box(Modifier.width(0.5.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant))
}

private fun Insight.Kind.icon(): ImageVector = when (this) {
    Insight.Kind.PHASE -> Icons.Rounded.NorthEast
    Insight.Kind.TARGET -> Icons.Rounded.UnfoldMore
    Insight.Kind.WIND -> Icons.Rounded.Air
    Insight.Kind.TEMPERATURE -> Icons.Rounded.Thermostat
    Insight.Kind.PLACE -> Icons.Rounded.Place
    Insight.Kind.EMERGENCY -> Icons.Rounded.Warning
}

/** Plain-language observations, worked out from the raw fields so nobody has to read a table. */
@Composable
private fun RightNow(track: Track, route: Route?, now: () -> Long) {
    val a = track.aircraft
    // Nearest named place, from ~18k airports: worked out off the main thread, and only when the plane moves ~5 km.
    val near by produceState<app.contrail.data.enrich.Airport?>(null, (a.lat * 20).roundToInt(), (a.lon * 20).roundToInt()) {
        value = withContext(Dispatchers.Default) { StaticData.get().nearestPlace(a.lat, a.lon) }
    }
    val t = now()
    val progress = route?.let { FlightProgressCalc.compute(track, it, t) }
    val phase = FlightInsights.phase(track, progress, t)
    val items = FlightInsights.insights(track, route, progress, near, t)
    if (items.isEmpty()) return
    val cs = MaterialTheme.colorScheme
    Surface(shape = MaterialTheme.shapes.medium, color = cs.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Right now", style = MaterialTheme.typography.titleSmall)
            items.forEach { item ->
                val tint = when (item.kind) {
                    Insight.Kind.EMERGENCY -> cs.error
                    Insight.Kind.PHASE -> phase.tint()
                    else -> cs.primary
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(30.dp).background(tint.copy(alpha = 0.14f), CircleShape), contentAlignment = Alignment.Center) {
                        Icon(if (item.kind == Insight.Kind.PHASE) phase.icon() else item.kind.icon(), null, Modifier.size(17.dp), tint = tint)
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(item.text, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

/** A spec card for the airframe: type as the headline, then the facts that identify it. */
@Composable
private fun AboutAircraft(a: Aircraft, info: AircraftInfo?, airline: String?) {
    val cs = MaterialTheme.colorScheme
    val age = FlightInsights.ageYears(a.year, Year.now().value)
    Surface(shape = MaterialTheme.shapes.medium, color = cs.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("About this aircraft", style = MaterialTheme.typography.titleSmall, color = cs.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            Text(info?.fullName ?: a.description ?: a.typeCode ?: "Unknown type", style = MaterialTheme.typography.titleLarge)
            (a.operator ?: info?.owner ?: airline)?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
            }
            Spacer(Modifier.height(16.dp))
            FactGrid(
                listOfNotNull(
                    "Registration" to (a.registration ?: info?.registration ?: Fmt.NONE),
                    "Age" to (age?.let { if (it == 0) "New (${a.year})" else "$it ${if (it == 1) "year" else "years"} · ${a.year}" } ?: Fmt.NONE),
                    "Registered in" to (info?.ownerCountry ?: Fmt.NONE),
                    "Type code" to (a.typeCode ?: info?.icaoType ?: Fmt.NONE),
                    "Size" to Squawks.category(a.category),
                    "Transponder" to a.hex.uppercase(),
                ).filter { (_, v) -> v != Fmt.NONE && v != "Not reported" }, // a spec card shows what's known, not blanks
            )
        }
    }
}

/** Earlier flights from the daily history (last 14 days, up to yesterday), newest first. Hidden when none are known. */
@Composable
private fun PastFlights(hex: String, onOpen: (PastFlight) -> Unit) {
    val history = (LocalContext.current.applicationContext as ContrailApp).container.history
    // null while loading; a failed download says so rather than hiding the card.
    val flights by produceState<Result<List<PastFlight>>?>(null, hex) {
        value = history.past(hex)?.let { Result.success(it) } ?: Result.failure(Exception())
    }
    val list = flights?.getOrNull() ?: emptyList()
    if (flights == null) return
    var all by rememberSaveable(hex) { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme
    val data = StaticData.now()
    Surface(shape = MaterialTheme.shapes.medium, color = cs.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Past flights", style = MaterialTheme.typography.titleSmall)
            when {
                flights!!.isFailure -> Text("Couldn't load past flights. Check your connection.", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                list.isEmpty() -> Text("No flights seen in the last 14 days.", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
            }
            (if (all) list else list.take(5)).forEach { f ->
                val from = f.from?.let { data?.airport(it) }
                val to = f.to?.let { data?.airport(it) }
                // Each end in its airport's local time, and only when the takeoff or landing was seen.
                val dep = Instant.ofEpochMilli(f.departed).atZone(from?.tz?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault())
                val arr = Instant.ofEpochMilli(f.arrived).atZone(to?.tz?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault())
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable(role = Role.Button) { onOpen(f) }) {
                    Column(Modifier.weight(1f)) {
                        Text("${from?.shortName ?: f.from ?: "Unknown"} → ${to?.shortName ?: f.to ?: "Unknown"}",
                            style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(listOfNotNull(dep.format(DAY), f.callsign).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    }
                    val times = when {
                        f.departedSeen && f.arrivedSeen -> "${Fmt.time(dep)}–${Fmt.time(arr)}"
                        f.departedSeen -> "Dep ${Fmt.time(dep)}"
                        f.arrivedSeen -> "Arr ${Fmt.time(arr)}"
                        else -> null
                    }
                    times?.let {
                        Spacer(Modifier.width(12.dp))
                        // Each clock is in its own airport's zone, so the duration says how long it really took.
                        Column(horizontalAlignment = Alignment.End) {
                            Text(it, style = MaterialTheme.typography.bodyMedium.merge(Numeric), color = cs.onSurfaceVariant)
                            if (f.departedSeen && f.arrivedSeen) {
                                Text(Fmt.duration(f.arrived - f.departed), style = MaterialTheme.typography.bodySmall.merge(Numeric), color = cs.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            if (list.size > 5 && !all) {
                Text("Show all ${list.size}", color = cs.primary, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.clickable { all = true })
            }
            Text("Last 14 days, up to yesterday · adsb.lol", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
    }
    Spacer(Modifier.height(12.dp))
}

private val DAY = DateTimeFormatter.ofPattern("EEE d MMM")

private fun zoneOf(ap: Airport?): ZoneId = ap?.tz?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()

/**
 * One earlier flight, opened from Past flights. MapScreen draws its path in place of the live trail; this shows
 * the two ends in their own local times, what the path says (distance, height, speed) and its profile.
 */
@Composable
fun PastFlightSheet(flight: PastFlight, path: List<TrailPoint>?, failed: Boolean, onBack: () -> Unit, onScrub: (TrailPoint?) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val data = StaticData.now()
    val from = flight.from?.let { data?.airport(it) }
    val to = flight.to?.let { data?.airport(it) }
    val dep = Instant.ofEpochMilli(flight.departed).atZone(zoneOf(from))
    val arr = Instant.ofEpochMilli(flight.arrived).atZone(zoneOf(to))
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding().padding(bottom = 24.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(flight.callsign ?: "Past flight", style = MaterialTheme.typography.headlineMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${dep.format(DAY)} · Past flight", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
            }
            CapsuleButton(text = "Back", icon = Icons.AutoMirrored.Rounded.KeyboardArrowLeft, onClick = onBack)
        }
        Spacer(Modifier.height(16.dp))
        Surface(shape = MaterialTheme.shapes.medium, color = cs.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row {
                    PastEnd(flight.from, from, if (flight.departedSeen) "Took off ${Fmt.time(dep)}" else "Takeoff not seen", Alignment.Start, Modifier.weight(1f))
                    PastEnd(flight.to, to, if (flight.arrivedSeen) "Landed ${Fmt.time(arr)}" else "Landing not seen", Alignment.End, Modifier.weight(1f))
                }
                // Each clock is in its own airport's zone, so the duration says how long it really took.
                if (flight.departedSeen && flight.arrivedSeen) {
                    Text("${Fmt.duration(flight.arrived - flight.departed)} in the air", style = MaterialTheme.typography.bodyMedium.merge(Numeric), color = cs.onSurfaceVariant)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        when {
            path == null -> PastNote("Loading the flown path…")
            failed -> PastNote("Couldn't load the flown path. Check your connection.")
            path.size < 2 -> PastNote("The path for this flight wasn't recorded.")
            else -> {
                val st = remember(path) { FlightHistory.stats(path) }
                Surface(shape = MaterialTheme.shapes.medium, color = cs.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                    Box(Modifier.padding(16.dp)) {
                        FactGrid(listOf("Distance flown" to Fmt.nm(st.distanceNm), "Highest" to Fmt.feet(st.topAltFt), "Top speed" to Fmt.knots(st.topSpeedKt)))
                    }
                }
                Spacer(Modifier.height(12.dp))
                if (path.size >= 3) ProfileChart(path, onScrub)
            }
        }
        Spacer(Modifier.height(12.dp))
        Text("Path from adsb.lol's open data, as its receivers heard it: gaps are where none did.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
    }
}

@Composable
private fun PastEnd(code: String?, ap: Airport?, time: String, align: Alignment.Horizontal, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    Column(modifier, horizontalAlignment = align) {
        Text(ap?.iata ?: code ?: "—", style = MaterialTheme.typography.headlineMedium, color = if (code == null) cs.onSurfaceVariant else cs.onSurface)
        Text(ap?.shortName ?: if (code == null) "Unknown" else code, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(time, style = MaterialTheme.typography.bodySmall.merge(Numeric), color = cs.onSurfaceVariant)
    }
}

@Composable
private fun PastNote(text: String) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
    }
}

/** Label-over-value pairs in two columns. */
@Composable
private fun FactGrid(facts: List<Pair<String, String>>) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        facts.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                pair.forEach { (label, value) -> Fact(label, value, Modifier.weight(1f)) }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun Fact(label: String, value: String, modifier: Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        Spacer(Modifier.height(2.dp))
        Text(value, style = MaterialTheme.typography.bodyLarge.merge(Numeric), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** Everything the feed reports, for the curious; folded away by default. */
@Composable
private fun TechnicalDetails(track: Track, now: () -> Long) {
    val a = track.aircraft
    val cs = MaterialTheme.colorScheme
    var open by rememberSaveable { mutableStateOf(false) }
    val chevron by animateFloatAsState(if (open) 180f else 0f, label = "chevron")
    Surface(shape = MaterialTheme.shapes.medium, color = cs.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                Modifier.fillMaxWidth().clickable { open = !open }.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Technical details", style = MaterialTheme.typography.titleSmall)
                    Text("Autopilot, transponder, signal and more", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
                Icon(Icons.Rounded.ExpandMore, if (open) "Collapse" else "Expand", Modifier.rotate(chevron), tint = cs.onSurfaceVariant)
            }
            AnimatedVisibility(open, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    TechSection("Speed and altitude", listOf(
                        "Baro altitude" to Fmt.altitude(a), "GPS altitude" to Fmt.feet(a.altGeom),
                        "Indicated airspeed" to Fmt.knots(a.ias), "True airspeed" to Fmt.knots(a.tas),
                        "Mach" to Fmt.mach(a.mach), "Vertical rate" to Fmt.verticalRate(a.verticalRate),
                    ))
                    TechSection("Direction", listOf(
                        "Track" to Fmt.degrees(a.track), "True heading" to Fmt.degrees(a.trueHeading),
                        "Magnetic heading" to Fmt.degrees(a.magHeading),
                        "Turn rate" to (a.trackRate?.let { String.format("%.2f°/s", it) } ?: Fmt.NONE),
                        "Roll" to (a.roll?.let { String.format("%.1f°", it) } ?: Fmt.NONE),
                    ))
                    TechSection("Autopilot", listOf(
                        "Selected altitude" to Fmt.feet(a.navAltMcp), "FMS altitude" to Fmt.feet(a.navAltFms),
                        "Selected heading" to Fmt.degrees(a.navHeading), "QNH set" to Fmt.hpa(a.navQnh),
                        "Modes" to a.navModes.joinToString(" ") { it.uppercase() }.ifEmpty { Fmt.NONE },
                    ))
                    TechSection("Air outside", listOf(
                        "Wind" to (if (a.windDir != null && a.windSpeed != null) "${Fmt.degrees(a.windDir)} at ${Fmt.knots(a.windSpeed)}" else Fmt.NONE),
                        "Outside air" to Fmt.celsius(a.oat), "Total air" to Fmt.celsius(a.tat),
                    ))
                    val t = now()
                    TechSection("Transponder and signal", listOf(
                        "Squawk" to (a.squawk?.let { sq -> Squawks.meaning(sq)?.let { "$sq, $it" } ?: sq } ?: Fmt.NONE),
                        "Position source" to a.source.label,
                        "ADS-B version" to (a.adsbVersion?.toString() ?: Fmt.NONE),
                        "NIC / NACp / SIL" to listOf(a.nic, a.nacP, a.sil).joinToString(" / ") { it?.toString() ?: "–" },
                        "Signal" to (a.rssi?.let { String.format("%.1f dBFS", it) } ?: Fmt.NONE),
                        "Messages" to (a.messages?.let { String.format("%,d", it) } ?: Fmt.NONE),
                        "Position age" to Fmt.ago(t - a.positionTime),
                        "Tracked for" to Fmt.duration(t - track.firstSeen),
                    ))
                }
            }
        }
    }
}

@Composable
private fun TechSection(title: String, facts: List<Pair<String, String>>) {
    Column {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(10.dp))
        FactGrid(facts)
    }
}
