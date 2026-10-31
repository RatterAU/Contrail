package app.contrail.ui.nearby

import android.location.Location
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.ViewInAr
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.contrail.domain.Aircraft
import app.contrail.domain.Fmt
import app.contrail.domain.Geo
import app.contrail.domain.SkyGeometry
import app.contrail.domain.SkyPosition
import app.contrail.domain.Track
import app.contrail.ui.theme.Numeric
import app.contrail.ui.theme.CapsuleButton
import app.contrail.ui.theme.InsetGroup
import app.contrail.ui.theme.LargeTitle
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.graphics.Color
import kotlin.math.roundToInt

data class NearbyItem(val track: Track, val sky: SkyPosition)

fun nearbyItems(tracks: Collection<Track>, loc: Location, now: Long, maxNm: Double = 60.0): List<NearbyItem> {
    val obsAltFt = if (loc.hasAltitude()) loc.altitude * 3.281 else 0.0
    return tracks.mapNotNull { t ->
        val (lat, lon) = Track.extrapolate(t.aircraft, now)
        val sky = SkyGeometry.locate(loc.latitude, loc.longitude, obsAltFt, t.aircraft, lat, lon)
        if (sky.groundNm > maxNm) null else NearbyItem(t, sky)
    }.sortedWith(compareBy({ it.track.aircraft.onGround }, { it.sky.slantNm })) // airborne first
}

@Composable
fun NearbyScreen(
    items: List<NearbyItem>?,
    phoneHeading: () -> Double?,
    onAircraft: (String) -> Unit,
    onAr: () -> Unit,
    onClose: () -> Unit,
) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                LargeTitle("Near you", onClose)
                when {
                    items == null -> Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.height(12.dp))
                        Text("Finding your location…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    items.isEmpty() -> Text(
                        "No aircraft within 60 nm right now.",
                        modifier = Modifier.padding(24.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 112.dp)) {
                        item { OverheadCard(items.first(), phoneHeading) { onAircraft(items.first().track.hex) } }
                        item {
                            InsetGroup(header = "Also nearby", dividerInset = 72.dp) {
                                items.drop(1).forEach { n -> row { NearbyRow(n, phoneHeading) { onAircraft(n.track.hex) } } }
                            }
                        }
                    }
                }
            }
            CapsuleButton(
                "Sky view", Icons.Rounded.ViewInAr, onAr,
                filled = true,
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 20.dp),
            )
        }
    }
}

fun describeSky(s: SkyPosition): String {
    val dir = Geo.compass(s.bearing)
    val up = if (s.elevation < 1) "near the horizon" else "${s.elevation.roundToInt()}° up"
    return "$dir, $up"
}

@Composable
private fun OverheadCard(item: NearbyItem, heading: () -> Double?, onClick: () -> Unit) {
    val a = item.track.aircraft
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
    ) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Closest now", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onPrimary)
                Text(a.label, style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.onPrimary)
                Text(
                    listOfNotNull(a.typeCode, Fmt.altitude(a), Fmt.nm(item.sky.slantNm)).joinToString(" · "),
                    style = MaterialTheme.typography.bodyLarge.merge(Numeric),
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(Modifier.height(4.dp))
                Text("Look ${describeSky(item.sky)}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimary)
            }
            Arrow(item.sky.bearing, heading, 64, onCard = true)
        }
    }
}

@Composable
private fun NearbyRow(item: NearbyItem, heading: () -> Double?, onClick: () -> Unit) {
    val a: Aircraft = item.track.aircraft
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Arrow(item.sky.bearing, heading, 40)
        Column(Modifier.weight(1f)) {
            Text(a.label, style = MaterialTheme.typography.titleMedium)
            Text(
                listOfNotNull(describeSky(item.sky), a.typeCode).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(Fmt.nm(item.sky.slantNm), style = MaterialTheme.typography.labelLarge.merge(Numeric))
            Text(Fmt.altitude(a), style = MaterialTheme.typography.bodySmall.merge(Numeric), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Arrow pointing at the aircraft relative to where the phone faces (or to north-up if no compass). */
@Composable
private fun Arrow(bearing: Double, heading: () -> Double?, sizeDp: Int, onCard: Boolean = false) {
    // 2° steps: sensor noise would otherwise restart the animation every tick and never let the screen idle.
    val target = ((((bearing - (heading() ?: 0.0)) % 360 + 360) % 360) / 2).roundToInt() * 2f
    val angle = remember { Animatable(target) }
    LaunchedEffect(target) {
        // Turn the short way: 359° to 1° is a 2° nudge, not a full spin.
        val delta = ((target - angle.value) % 360 + 540) % 360 - 180
        angle.animateTo(angle.value + delta)
    }
    Box(
        Modifier.size(sizeDp.dp).background(if (onCard) Color.White.copy(alpha = 0.2f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.Navigation, "Direction", Modifier.size((sizeDp * 0.55).dp).graphicsLayer { rotationZ = angle.value }, tint = if (onCard) Color.White else MaterialTheme.colorScheme.primary)
    }
}
