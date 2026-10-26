package app.contrail.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.Flight
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.contrail.data.SearchResults
import app.contrail.data.enrich.Airport
import app.contrail.domain.Aircraft
import app.contrail.domain.Fmt
import app.contrail.ui.theme.IconTile
import app.contrail.ui.theme.InsetGroup
import app.contrail.ui.theme.Numeric
import app.contrail.ui.theme.TextAction
import app.contrail.ui.theme.ValueRow


@Composable
fun SearchOverlay(
    results: SearchResults?,
    searching: Boolean,
    onQuery: (String) -> Unit,
    onAircraft: (Aircraft) -> Unit,
    onAirport: (Airport) -> Unit,
    onClose: () -> Unit,
) {
    var q by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
            Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.weight(1f).height(40.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(12.dp)).padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Search, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(6.dp))
                    Box(Modifier.weight(1f)) {
                        if (q.isEmpty()) Text("Flight, registration, airport, type", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        BasicTextField(
                            value = q,
                            onValueChange = { q = it; onQuery(it) },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Search),
                            modifier = Modifier.fillMaxWidth().focusRequester(focus),
                        )
                    }
                    if (q.isNotEmpty()) {
                        Icon(
                            Icons.Rounded.Cancel, "Clear",
                            Modifier.size(18.dp).clickable { q = ""; onQuery("") },
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                TextAction("Cancel", onClose)
            }
            if (searching && results == null) {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) }
            }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
                if (results == null && !searching) {
                    item { Hint() }
                }
                results?.let { r ->
                    if (r.aircraft.isEmpty() && r.airports.isEmpty() && !searching) {
                        item {
                            Text(
                                if (r.failed) "Couldn't reach the flight data servers. Check your connection." else "Nothing found. Flights only show while they're in the air or broadcasting on the ground.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 24.dp, horizontal = 8.dp),
                            )
                        }
                    }
                    item {
                        InsetGroup(header = "Live aircraft", dividerInset = 72.dp) { r.aircraft.forEach { a -> row { AircraftRow(a) { onAircraft(a) } } } }
                    }
                    item {
                        InsetGroup(header = "Airports", dividerInset = 84.dp) { r.airports.forEach { ap -> row { AirportRow(ap) { onAirport(ap) } } } }
                    }
                }
            }
        }
    }
}

@Composable
private fun Hint() {
    InsetGroup(header = "Try searching for") {
        listOf("QF1" to "Flight number", "VH-OQA" to "Registration", "A388" to "Aircraft type", "Adelaide" to "Airport name or code", "7700" to "Emergency squawk").forEach { (ex, what) ->
            row { ValueRow(ex, what) }
        }
    }
}

@Composable
private fun AircraftRow(a: Aircraft, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        IconTile(Icons.Rounded.Flight, MaterialTheme.colorScheme.primary, size = 40.dp)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(a.label, style = MaterialTheme.typography.titleMedium)
            Text(
                listOfNotNull(a.registration, a.typeCode, a.operator).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(Fmt.altitude(a), style = MaterialTheme.typography.bodyMedium.merge(Numeric), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AirportRow(ap: Airport, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.width(52.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(10.dp)).padding(vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(ap.code, style = MaterialTheme.typography.labelLarge)
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(ap.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(ap.city, ap.country).joinToString(", "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
