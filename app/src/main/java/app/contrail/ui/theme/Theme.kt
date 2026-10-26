package app.contrail.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.contrail.R

// Apple system colours. Surface roles follow iOS grouped lists:
// surface = page, surfaceContainerLow = sheet, surfaceContainer = cell on a page,
// surfaceContainerHigh = cell on a sheet, surfaceContainerHighest = fills (fields, segmented tracks).
private val Night = darkColorScheme(
    primary = Color(0xFF0A84FF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF0E2A4D),
    onPrimaryContainer = Color(0xFF64B0FF),
    secondary = Color(0xFF8E8E93),
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFF3A3A3C),
    onSecondaryContainer = Color.White,
    tertiary = Color(0xFFFF9F0A),
    onTertiary = Color.Black,
    tertiaryContainer = Color(0xFF3D2A0A),
    onTertiaryContainer = Color(0xFFFFD08A),
    error = Color(0xFFFF453A),
    onError = Color.White,
    errorContainer = Color(0xFF4A1512),
    onErrorContainer = Color(0xFFFFB4AE),
    background = Color.Black,
    onBackground = Color.White,
    surface = Color.Black,
    onSurface = Color.White,
    surfaceVariant = Color(0xFF2C2C2E),
    onSurfaceVariant = Color(0xFF8E8E93),
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF1C1C1E),
    surfaceContainer = Color(0xFF1C1C1E),
    surfaceContainerHigh = Color(0xFF2C2C2E),
    surfaceContainerHighest = Color(0xFF3A3A3C),
    outline = Color(0xFF545458),
    outlineVariant = Color(0xFF38383A),
)

private val Day = lightColorScheme(
    primary = Color(0xFF007AFF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE3EEFF),
    onPrimaryContainer = Color(0xFF0062D1),
    secondary = Color(0xFF8E8E93),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE5E5EA),
    onSecondaryContainer = Color.Black,
    tertiary = Color(0xFFC77700),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFEBCC),
    onTertiaryContainer = Color(0xFF5C3400),
    error = Color(0xFFFF3B30),
    onError = Color.White,
    errorContainer = Color(0xFFFFE1DE),
    onErrorContainer = Color(0xFF8A0F07),
    background = Color(0xFFF2F2F7),
    onBackground = Color.Black,
    surface = Color(0xFFF2F2F7),
    onSurface = Color.Black,
    surfaceVariant = Color(0xFFE5E5EA),
    onSurfaceVariant = Color(0xFF6E6E73),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF2F2F7),
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color.White,
    surfaceContainerHighest = Color(0xFFE3E3E8),
    outline = Color(0xFFC7C7CC),
    outlineVariant = Color(0xFFD8D8DC),
)

/** iOS system green, for on-states (switches, live dot). */
val SystemGreen = Color(0xFF34C759)

// Inter is the closest freely licensed match to SF Pro. Its optical-size axis gives
// the "Display" cut for large text, like SF Pro Display vs Text.
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun inter(opsz: Float) = FontFamily(
    listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold).map { w ->
        Font(R.font.inter_variable, w, variationSettings = FontVariation.Settings(FontVariation.weight(w.weight), FontVariation.Setting("opsz", opsz)))
    },
)
private val Inter = inter(14f)
val InterDisplay = inter(32f)

/** Tabular figures so changing numbers don't shuffle their neighbours. */
val Numeric = TextStyle(fontFeatureSettings = "tnum")

/** Large numeric readout. */
val Readout = TextStyle(fontFamily = InterDisplay, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum", letterSpacing = (-0.4).sp)

private fun style(size: Int, line: Int, weight: FontWeight, track: Double, display: Boolean = false) = TextStyle(
    fontFamily = if (display) InterDisplay else Inter,
    fontSize = size.sp,
    lineHeight = line.sp,
    fontWeight = weight,
    letterSpacing = track.sp,
)

// The iOS Dynamic Type scale: large title 34, title 28/22/20, headline/body 17, subheadline 15, footnote 13, caption 12.
private val ContrailType = Typography(
    displayLarge = style(48, 54, FontWeight.Bold, -1.0, true),
    displayMedium = style(40, 46, FontWeight.Bold, -0.8, true),
    displaySmall = style(34, 41, FontWeight.Bold, -0.6, true),
    headlineLarge = style(34, 41, FontWeight.Bold, -0.6, true),
    headlineMedium = style(28, 34, FontWeight.Bold, -0.5, true),
    headlineSmall = style(22, 28, FontWeight.Bold, -0.35, true),
    titleLarge = style(20, 25, FontWeight.SemiBold, -0.3, true),
    titleMedium = style(17, 22, FontWeight.SemiBold, -0.3),
    titleSmall = style(15, 20, FontWeight.SemiBold, -0.15),
    bodyLarge = style(17, 22, FontWeight.Normal, -0.3),
    bodyMedium = style(15, 20, FontWeight.Normal, -0.15),
    bodySmall = style(13, 18, FontWeight.Normal, -0.05),
    labelLarge = style(15, 20, FontWeight.SemiBold, -0.15),
    labelMedium = style(13, 18, FontWeight.Medium, -0.05),
    labelSmall = style(12, 16, FontWeight.Medium, 0.0),
)

private val ContrailShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ContrailTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialExpressiveTheme(
        colorScheme = if (dark) Night else Day,
        // iOS motion is soft springs without overshoot; the standard scheme is closer than expressive.
        motionScheme = MotionScheme.standard(),
        typography = ContrailType,
        shapes = ContrailShapes,
        content = content,
    )
}
