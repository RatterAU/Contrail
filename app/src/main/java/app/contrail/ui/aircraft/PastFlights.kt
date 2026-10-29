package app.contrail.ui.aircraft

import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Flight
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.contrail.ContrailApp
import app.contrail.data.enrich.Airport
import app.contrail.data.feed.FlightHistory
import app.contrail.data.feed.PastFlight
import app.contrail.data.static.StaticData
import app.contrail.domain.Fmt
import app.contrail.domain.Replay
import app.contrail.domain.TrailPoint
import app.contrail.ui.theme.CapsuleButton
import app.contrail.ui.theme.Numeric
import app.contrail.ui.theme.Segmented
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Earlier flights from the daily history (last 14 days, up to yesterday), newest first, grouped by day, under a
 * short summary. Each end shows only what was seen: a scheduled airport says so, and an unseen takeoff or landing
 * gives the time it must have been before (or after).
 */
@Composable
fun PastFlights(hex: String, onOpen: (PastFlight, List<PastFlight>) -> Unit) {
    val history = (LocalContext.current.applicationContext as ContrailApp).container.history
    // null while loading; a failed download says so rather than hiding the card.
    val flights by produceState<Result<List<PastFlight>>?>(null, hex) {
        value = history.past(hex)?.let { Result.success(it) } ?: Result.failure(Exception())
    }
    val list = flights?.getOrNull() ?: emptyList()
    if (flights == null) return
    var all by rememberSaveable(hex) { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme
    Surface(shape = MaterialTheme.shapes.medium, color = cs.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Past flights", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text("Last 14 days", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
            }
            when {
                flights!!.isFailure -> Note("Couldn't load past flights. Check your connection.")
                list.isEmpty() -> Note("Not seen flying in the last 14 days.")
                else -> {
                    Spacer(Modifier.height(12.dp))
                    Summary(list)
                }
            }
            val shown = if (all) list else list.take(PREVIEW)
            var lastDay: LocalDate? = null
            shown.forEach { f ->
                val day = Instant.ofEpochMilli(f.departed).atZone(zoneOf(airport(f.from ?: f.scheduledFrom))).toLocalDate()
                if (day != lastDay) {
                    lastDay = day
                    Text(
                        dayName(day).uppercase(),
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = cs.onSurfaceVariant,
                        modifier = Modifier.padding(top = 16.dp, bottom = 2.dp),
                    )
                } else {
                    HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.4f))
                }
                PastRow(f) { onOpen(f, list) }
            }
            if (list.size > PREVIEW && !all) {
                Text(
                    "Show all ${list.size} flights",
                    color = cs.primary,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(top = 12.dp).clickable(role = Role.Button) { all = true },
                )
            }
            Text(
                "From adsb.lol's open data. Times are local to each airport.",
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
    Spacer(Modifier.height(12.dp))
}

private const val PREVIEW = 6

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
}

/** Flights, airports and the most-flown route over the fortnight, from seen ends only. */
@Composable
private fun Summary(list: List<PastFlight>) {
    val airports = list.flatMap { listOfNotNull(it.from, it.to) }.toSet()
    val top = list.mapNotNull { f -> if (f.from != null && f.to != null && f.from != f.to) setOf(f.from, f.to) else null }
        .groupingBy { it }.eachCount().maxByOrNull { it.value }?.takeIf { it.value >= 2 }
    val facts = buildList {
        add("Flights" to "${list.size}")
        add("Airports" to if (airports.isEmpty()) Fmt.NONE else "${airports.size}")
        top?.let { (pair, n) -> add("Most flown" to "${pair.joinToString("–") { code(it) }} ×$n") }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        facts.forEach { (label, value) ->
            Column(
                Modifier.weight(if (label == "Most flown") 1.6f else 1f)
                    .clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                Text(value, style = MaterialTheme.typography.titleMedium.merge(Numeric), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}

@Composable
private fun PastRow(f: PastFlight, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EndColumn(f, departing = true, Modifier.weight(1f))
        Column(Modifier.weight(0.8f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(f.callsign.orEmpty(), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            FlightLine()
            Text(duration(f).orEmpty(), style = MaterialTheme.typography.labelSmall.merge(Numeric), color = cs.onSurfaceVariant, maxLines = 1)
        }
        EndColumn(f, departing = false, Modifier.weight(1f))
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, Modifier.padding(start = 4.dp).size(20.dp), tint = cs.onSurfaceVariant.copy(alpha = 0.6f))
    }
}

/** Code, city and time for one end of a past flight, the way the route card shows a live one. */
@Composable
private fun EndColumn(f: PastFlight, departing: Boolean, modifier: Modifier, large: Boolean = false) {
    val cs = MaterialTheme.colorScheme
    val seen = if (departing) f.from else f.to
    val scheduled = if (departing) f.scheduledFrom else f.scheduledTo
    val ap = airport(seen ?: scheduled)
    val align = if (departing) Alignment.Start else Alignment.End
    Column(modifier, horizontalAlignment = align) {
        Text(
            (seen ?: scheduled)?.let(::code) ?: "—",
            style = if (large) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleLarge,
            color = if (seen != null) cs.onSurface else cs.onSurfaceVariant,
            maxLines = 1,
        )
        Text(
            ap?.shortName ?: seen ?: scheduled ?: "Not seen",
            style = MaterialTheme.typography.bodySmall,
            color = cs.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            endTime(f, departing, large),
            style = MaterialTheme.typography.labelSmall.merge(Numeric),
            color = if (seen == null && scheduled != null) cs.primary else cs.onSurfaceVariant,
            maxLines = if (large) 2 else 1,
            textAlign = if (departing) TextAlign.Start else TextAlign.End,
        )
    }
}

/** A short line with a plane on it, between the two ends. */
@Composable
private fun FlightLine() {
    val line = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 6.dp)) {
        Box(Modifier.weight(1f).height(1.5.dp).background(line))
        Icon(Icons.Rounded.Flight, null, Modifier.padding(horizontal = 4.dp).size(16.dp).rotate(90f), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(Modifier.weight(1f).height(1.5.dp).background(line))
    }
}

private fun airport(code: String?): Airport? = code?.let { StaticData.now()?.airport(it) }

private fun code(icao: String) = airport(icao)?.iata ?: icao

private fun zoneOf(ap: Airport?): ZoneId = ap?.tz?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()

private val DAY = DateTimeFormatter.ofPattern("EEE d MMM")

private fun dayName(day: LocalDate) = when (day) {
    LocalDate.now().minusDays(1) -> "Yesterday"
    LocalDate.now() -> "Today"
    else -> day.format(DAY)
}

/** Each clock is in its own airport's zone, so the duration says how long it really took. */
private fun duration(f: PastFlight) = if (f.departedSeen && f.arrivedSeen) Fmt.duration(f.arrived - f.departed) else null

/**
 * "10:05", or for an unseen takeoff the time it must have been before (first seen), and for an unseen landing the
 * time it must have been after (last seen). A scheduled end says so instead.
 */
private fun endTime(f: PastFlight, departing: Boolean, long: Boolean): String {
    val seen = if (departing) f.departedSeen else f.arrivedSeen
    val ms = if (departing) f.departed else f.arrived
    val t = Fmt.time(Instant.ofEpochMilli(ms).atZone(zoneOf(airport(if (departing) f.from ?: f.scheduledFrom else f.to ?: f.scheduledTo))))
    val scheduled = (if (departing) f.from else f.to) == null && (if (departing) f.scheduledFrom else f.scheduledTo) != null
    return when {
        seen -> if (long) "${if (departing) "Took off" else "Landed"} $t" else t
        scheduled -> if (long) "Scheduled, not seen" else "Scheduled"
        departing -> if (long) "Took off before $t" else "before $t"
        else -> if (long) "Landed after $t" else "after $t"
    }
}

/**
 * One earlier flight, opened from Past flights. MapScreen draws its path in place of the live trail and a plane
 * where the replay is; this shows the two ends in their own local times, the replay, and what the path says.
 * [flights] is the whole list, for stepping to the flight before or after.
 */
@Composable
fun PastFlightSheet(
    flight: PastFlight,
    path: List<TrailPoint>?,
    failed: Boolean,
    flights: List<PastFlight>,
    onOpen: (PastFlight) -> Unit,
    onBack: () -> Unit,
    onFrame: (Replay.Frame?) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val data = StaticData.now()
    val from = airport(flight.from ?: flight.scheduledFrom)
    val airlineIcao = data?.airlineCode(flight.callsign)
    val airline = data?.airlineForCallsign(flight.callsign)?.name
    val logo = rememberAirlineLogo(airlineIcao)
    val index = flights.indexOf(flight)
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding().padding(bottom = 24.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            logo?.let {
                val wide = it.width > it.height * 1.4f
                val size = if (wide) Modifier.height(28.dp).width((28 * it.width.toFloat() / it.height).coerceAtMost(96f).dp) else Modifier.size(44.dp)
                Image(it.asImageBitmap(), airline?.let { n -> "$n logo" }, Modifier.padding(top = if (wide) 12.dp else 6.dp, end = 12.dp).then(size), contentScale = ContentScale.Fit)
            }
            Column(Modifier.weight(1f).padding(top = 4.dp)) {
                Text(flight.callsign ?: "Past flight", style = MaterialTheme.typography.headlineMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val day = Instant.ofEpochMilli(flight.departed).atZone(zoneOf(from)).toLocalDate()
                Text(listOfNotNull(airline, dayName(day)).joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            CapsuleButton(text = "Back", icon = Icons.AutoMirrored.Rounded.KeyboardArrowLeft, onClick = onBack)
        }
        Spacer(Modifier.height(16.dp))
        Surface(shape = MaterialTheme.shapes.medium, color = cs.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    EndColumn(flight, departing = true, Modifier.weight(1f), large = true)
                    Column(Modifier.weight(0.7f), horizontalAlignment = Alignment.CenterHorizontally) {
                        FlightLine()
                        duration(flight)?.let { Text(it, style = MaterialTheme.typography.labelMedium.merge(Numeric), color = cs.onSurfaceVariant) }
                    }
                    EndColumn(flight, departing = false, Modifier.weight(1f), large = true)
                }
                if (flight.scheduledFrom != null && flight.from == null || flight.scheduledTo != null && flight.to == null) {
                    Text(
                        "Scheduled: from the flight's published route, which its path matches. It wasn't seen there.",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        when {
            path == null -> PastNote("Loading the flown path…")
            failed -> PastNote("Couldn't load the flown path. Check your connection.")
            path.size < 2 -> PastNote("The path for this flight wasn't recorded.")
            else -> {
                if (path.count { it.alt != null || it.onGround } >= 3) {
                    ReplayCard(path, from, onFrame)
                    Spacer(Modifier.height(12.dp))
                }
                val st = remember(path) { FlightHistory.stats(path) }
                Surface(shape = MaterialTheme.shapes.medium, color = cs.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                    Box(Modifier.padding(16.dp)) {
                        FactGrid(
                            listOf(
                                "Distance flown" to Fmt.nm(st.distanceNm),
                                "Highest" to Fmt.feet(st.topAltFt),
                                "Top speed" to Fmt.knots(st.topSpeedKt),
                                "Tracked for" to Fmt.duration(path.last().time - path.first().time),
                            ),
                        )
                    }
                }
            }
        }
        if (flights.size > 1 && index >= 0) {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                // The list is newest first: "Earlier" steps down it.
                Pager("Earlier", Icons.AutoMirrored.Rounded.KeyboardArrowLeft, flights.getOrNull(index + 1), onOpen, Modifier.weight(1f))
                Text("${flights.size - index} of ${flights.size}", style = MaterialTheme.typography.labelMedium.merge(Numeric), color = cs.onSurfaceVariant)
                Pager("Later", Icons.AutoMirrored.Rounded.KeyboardArrowRight, flights.getOrNull(index - 1), onOpen, Modifier.weight(1f), end = true)
            }
        }
        Spacer(Modifier.height(12.dp))
        Text("Path from adsb.lol's open data, as its receivers heard it: gaps are where none did.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
    }
}

@Composable
private fun Pager(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, target: PastFlight?, onOpen: (PastFlight) -> Unit, modifier: Modifier, end: Boolean = false) {
    Box(modifier, contentAlignment = if (end) Alignment.CenterEnd else Alignment.CenterStart) {
        if (target != null) CapsuleButton(text = label, icon = if (end) null else icon, onClick = { onOpen(target) })
    }
}

@Composable
private fun PastNote(text: String) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
    }
}

/** Replay speeds: a one-hour flight takes a minute at 60×, a long-haul about a minute at 1000×. */
private val SPEEDS = listOf(10, 60, 300, 1000)

/**
 * Plays the flight back: the plane moves along its path on the map ([onFrame]), the clock shows the moment in the
 * departure airport's time, and the profile underneath is the timeline, dragged to jump anywhere.
 */
@Composable
private fun ReplayCard(path: List<TrailPoint>, from: Airport?, onFrame: (Replay.Frame?) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val pts = remember(path) { path.filter { it.alt != null || it.onGround } }
    val start = path.first().time
    val end = path.last().time
    var t by rememberSaveable(path) { mutableLongStateOf(start) }
    var playing by remember(path) { mutableStateOf(false) }
    var speed by rememberSaveable { mutableIntStateOf(1) }
    val frame = onFrame.let { rememberUpdatedState(it) }
    LaunchedEffect(playing, speed) {
        if (!playing) return@LaunchedEffect
        if (t >= end) t = start
        var last = withFrameMillis { it }
        while (playing) {
            val now = withFrameMillis { it }
            t = (t + (now - last) * SPEEDS[speed]).coerceAtMost(end)
            last = now
            if (t >= end) playing = false
        }
    }
    // The map follows at up to 30 frames a second; each one redraws every plane, so faster would only cost battery.
    LaunchedEffect(path) {
        snapshotFlow { t }.conflate().collect {
            frame.value(Replay.at(path, it))
            delay(33)
        }
    }
    DisposableEffect(path) { onDispose { frame.value(null) } }

    val now = Replay.at(path, t)
    Surface(shape = MaterialTheme.shapes.medium, color = cs.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text("Replay", style = MaterialTheme.typography.titleSmall)
                    val (time, zone) = clockParts(t, from?.tz, from?.shortName)
                    Text(time, style = MaterialTheme.typography.headlineMedium.merge(Numeric))
                    Text(
                        listOfNotNull(zone, "+${Fmt.duration(t - start)}").joinToString(" · "),
                        style = MaterialTheme.typography.labelMedium.merge(Numeric),
                        color = cs.onSurfaceVariant,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        when {
                            now == null -> Fmt.NONE
                            now.onGround -> "On the ground"
                            else -> Fmt.feet(now.alt)
                        },
                        style = MaterialTheme.typography.titleMedium.merge(Numeric),
                    )
                    Text(Fmt.knots(now?.gs), style = MaterialTheme.typography.labelMedium.merge(Numeric), color = cs.onSurfaceVariant)
                    if (now?.gap == true) Text("No coverage here", style = MaterialTheme.typography.labelSmall, color = cs.primary)
                }
            }
            Spacer(Modifier.height(10.dp))
            ProfileCanvas(pts, { t }) { picked -> if (picked != null) { playing = false; t = picked.coerceIn(start, end) } }
            Spacer(Modifier.height(6.dp))
            ProfileLegend(pts)
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(44.dp).clip(CircleShape).background(cs.primary).clickable(role = Role.Button) { playing = !playing },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        when {
                            playing -> Icons.Rounded.Pause
                            t >= end -> Icons.Rounded.Replay
                            else -> Icons.Rounded.PlayArrow
                        },
                        if (playing) "Pause" else "Play",
                        tint = cs.onPrimary,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Segmented(SPEEDS.map { "$it×" }, speed, { speed = it }, Modifier.weight(1f))
            }
            Text(
                "Drag along the profile to jump to any moment.",
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp).alpha(if (playing) 0f else 1f),
            )
        }
    }
}
