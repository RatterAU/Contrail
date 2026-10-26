package app.contrail.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.ExperimentalHazeApi
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazePerformanceMode
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.glass.GlassStyle
import dev.chrisbanes.haze.glass.hazeGlass

/** Backdrop the map registers, so floating controls can render as glass over it. */
val LocalHaze = compositionLocalOf<HazeState?> { null }

private val isDark @Composable get() = MaterialTheme.colorScheme.surface.luminance() < 0.5f

/** Liquid-glass surface over the map; a frosted translucent fill where there's no backdrop. */
@OptIn(ExperimentalHazeApi::class)
@Composable
fun Modifier.glass(shape: RoundedCornerShape): Modifier {
    val haze = LocalHaze.current
    val dark = isDark
    val base = shadow(10.dp, shape, ambientColor = Color.Black.copy(alpha = 0.25f), spotColor = Color.Black.copy(alpha = 0.25f))
    return if (haze != null) {
        base.hazeGlass(
            input = HazeInput.Backdrop(haze),
            style = GlassStyle.regular.then {
                shape(shape)
                tint(if (dark) Color(0xFF1C1C1E).copy(alpha = 0.55f) else Color.White.copy(alpha = 0.6f))
            },
            // The backdrop is blurred anyway, so sampling it at low resolution looks the same and costs far less GPU,
            // which matters while the map is panned at 120 Hz on a budget phone.
            performanceMode = HazePerformanceMode.Performance,
        ).clip(shape)
    } else {
        base.clip(shape).background(if (dark) Color(0xEB1C1C1E) else Color(0xF0FFFFFF))
    }
}

/** iOS sheet grabber. */
@Composable
fun Grabber(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(top = 6.dp, bottom = 4.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(36.dp, 5.dp).background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f), CircleShape))
    }
}

/** Full-screen page header: a text button bar, then a large left-aligned title. */
@Composable
fun LargeTitle(title: String, onDone: () -> Unit, modifier: Modifier = Modifier, action: @Composable RowScope.() -> Unit = {}) {
    Column(modifier.statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            action()
            Spacer(Modifier.weight(1f))
            TextAction("Done", onDone, bold = true)
        }
        Text(title, style = MaterialTheme.typography.displaySmall, modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp))
    }
}

@Composable
fun TextAction(text: String, onClick: () -> Unit, bold: Boolean = false, enabled: Boolean = true) {
    Text(
        text,
        style = if (bold) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 1f else 0.4f),
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
    )
}

/** Inset grouped list section: optional header and footer, rows separated by inset hairlines. */
class GroupScope {
    internal val rows = mutableListOf<@Composable () -> Unit>()
    fun row(content: @Composable () -> Unit) { rows += content }
}

@Composable
fun InsetGroup(
    modifier: Modifier = Modifier,
    header: String? = null,
    footer: String? = null,
    cell: Color = MaterialTheme.colorScheme.surfaceContainer,
    dividerInset: Dp = 16.dp,
    build: GroupScope.() -> Unit,
) {
    val rows = GroupScope().apply(build).rows
    if (rows.isEmpty()) return
    Column(modifier.fillMaxWidth().padding(bottom = 20.dp)) {
        header?.let {
            Text(it, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
        }
        Column(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(cell)) {
            rows.forEachIndexed { i, r ->
                if (i > 0) HorizontalDivider(Modifier.padding(start = dividerInset), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                r()
            }
        }
        footer?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp))
        }
    }
}

/** Label on the left, value in secondary colour on the right. */
@Composable
fun ValueRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(end = 12.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodyLarge.merge(Numeric),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
}

/** iOS Settings-style row: coloured icon tile, title, optional subtitle, chevron. */
@Composable
fun NavRow(icon: ImageVector, tile: Color, title: String, subtitle: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(icon, tile)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.outline)
    }
}

@Composable
fun IconTile(icon: ImageVector, tile: Color, size: Dp = 30.dp) {
    Box(Modifier.size(size).background(tile, RoundedCornerShape(size * 0.24f)), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(size * 0.62f))
    }
}

@Composable
fun ToggleRow(title: String, subtitle: String? = null, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .heightIn(min = 44.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        IosSwitch(checked)
    }
}

/** Visual-only iOS switch; the row owns the toggle semantics. */
@Composable
fun IosSwitch(checked: Boolean) {
    val track by animateColorAsState(if (checked) SystemGreen else MaterialTheme.colorScheme.surfaceContainerHighest, label = "track")
    val x by animateDpAsState(if (checked) 22.dp else 2.dp, spring(dampingRatio = 0.8f, stiffness = 500f), label = "thumb")
    Box(Modifier.size(51.dp, 31.dp).background(track, CircleShape)) {
        Box(
            Modifier.offset(x = x, y = 2.dp).size(27.dp)
                .shadow(3.dp, CircleShape)
                .background(Color.White, CircleShape),
        )
    }
}

/** iOS segmented control. */
@Composable
fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val dark = isDark
    BoxWithConstraints(
        modifier.fillMaxWidth().height(34.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = if (dark) 0.7f else 1f), RoundedCornerShape(9.dp))
            .padding(2.dp),
    ) {
        val w = maxWidth / options.size
        val h = maxHeight
        val x by animateDpAsState(w * selected, spring(dampingRatio = 0.85f, stiffness = 600f), label = "seg")
        Box(
            Modifier.offset(x = x).size(w, h)
                .shadow(if (dark) 0.dp else 2.dp, RoundedCornerShape(7.dp))
                .background(if (dark) Color(0xFF636366) else Color.White, RoundedCornerShape(7.dp)),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            options.forEachIndexed { i, o ->
                Box(
                    Modifier.weight(1f).height(h).selectable(selected = i == selected, role = Role.Tab) { onSelect(i) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(o, style = MaterialTheme.typography.labelMedium.copy(fontWeight = if (i == selected) androidx.compose.ui.text.font.FontWeight.SemiBold else androidx.compose.ui.text.font.FontWeight.Medium), maxLines = 1)
                }
            }
        }
    }
}

/** Capsule button. Filled = primary action; otherwise tinted, like iOS's .bordered style. */
@Composable
fun CapsuleButton(text: String, icon: ImageVector?, onClick: () -> Unit, modifier: Modifier = Modifier, filled: Boolean = false) {
    val cs = MaterialTheme.colorScheme
    val bg by animateColorAsState(if (filled) cs.primary else cs.primary.copy(alpha = 0.15f), label = "bg")
    val fg by animateColorAsState(if (filled) cs.onPrimary else cs.primary, label = "fg")
    Row(
        modifier.clip(CircleShape).background(bg).clickable(role = Role.Button, onClick = onClick).height(40.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        icon?.let {
            Icon(it, null, Modifier.size(18.dp), tint = fg)
            Spacer(Modifier.width(6.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, color = fg)
    }
}

/** iOS sheet close: small filled circle with an xmark. */
@Composable
fun CloseButton(onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier.size(30.dp).clip(CircleShape).background(cs.surfaceContainerHighest).clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.Close, "Close", Modifier.size(18.dp), tint = cs.onSurfaceVariant)
    }
}

/** Round glass button for floating map controls. */
@Composable
fun GlassCircleButton(icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 44.dp) {
    Box(
        modifier.size(size).glass(RoundedCornerShape(size / 2)).clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurface)
    }
}
