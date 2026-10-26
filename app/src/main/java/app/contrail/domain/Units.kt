package app.contrail.domain

import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.TemporalAccessor
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

enum class AltitudeUnit(val label: String) { FEET("Feet"), METRES("Metres") }
enum class SpeedUnit(val label: String) { KNOTS("Knots"), KMH("km/h"), MPH("mph") }
enum class DistanceUnit(val label: String) { NM("Nautical miles"), KM("Kilometres"), MI("Miles") }
enum class ClockStyle(val label: String) { SYSTEM("Automatic"), H12("12-hour"), H24("24-hour") }

data class Units(
    val altitude: AltitudeUnit = AltitudeUnit.FEET,
    val speed: SpeedUnit = SpeedUnit.KNOTS,
    val distance: DistanceUnit = DistanceUnit.KM,
    val clock: ClockStyle = ClockStyle.SYSTEM,
)

/** Display formatting in the user's chosen units. Inputs are always aviation units (ft, kt, nm, ft/min). */
object Fmt {
    const val NONE = "—"

    /** Set from Settings at startup and on change. */
    @Volatile var units = Units()

    fun altitude(a: Aircraft): String = when {
        a.onGround -> "Ground"
        a.altBaro != null -> feet(a.altBaro)
        else -> NONE
    }

    fun feet(ft: Int?) = ft?.let {
        if (units.altitude == AltitudeUnit.METRES) String.format(Locale.US, "%,d m", (it * 0.3048).roundToInt())
        else String.format(Locale.US, "%,d ft", it)
    } ?: NONE

    fun knots(kt: Double?) = kt?.let {
        when (units.speed) {
            SpeedUnit.KNOTS -> "${it.roundToInt()} kt"
            SpeedUnit.KMH -> "${(it * 1.852).roundToInt()} km/h"
            SpeedUnit.MPH -> "${(it * 1.15078).roundToInt()} mph"
        }
    } ?: NONE

    fun nm(d: Double?) = d?.let {
        val (v, u) = when (units.distance) {
            DistanceUnit.NM -> it to "nm"
            DistanceUnit.KM -> it * 1.852 to "km"
            DistanceUnit.MI -> it * 1.15078 to "mi"
        }
        if (v < 10) String.format(Locale.US, "%.1f %s", v, u) else "${v.roundToInt()} $u"
    } ?: NONE

    fun mach(m: Double?) = m?.let { String.format(Locale.US, "M%.2f", it) } ?: NONE
    fun degrees(d: Double?) = d?.let { "${(it.roundToInt() + 360) % 360}°" } ?: NONE
    fun celsius(c: Double?) = c?.let { "${it.roundToInt()} °C" } ?: NONE
    fun hpa(h: Double?) = h?.let { String.format(Locale.US, "%.1f hPa", it) } ?: NONE

    fun verticalRate(fpm: Double?): String {
        fpm ?: return NONE
        if (abs(fpm) < 64) return "Level"
        val sign = if (fpm > 0) "+" else "−"
        if (units.altitude == AltitudeUnit.METRES) return String.format(Locale.US, "%s%.1f m/s", sign, abs(fpm) * 0.00508)
        val r = (fpm / 64).roundToInt() * 64
        return String.format(Locale.US, "%s%,d ft/min", sign, abs(r))
    }

    fun time(t: TemporalAccessor): String = when (units.clock) {
        ClockStyle.SYSTEM -> DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
        ClockStyle.H12 -> DateTimeFormatter.ofPattern("h:mm a")
        ClockStyle.H24 -> DateTimeFormatter.ofPattern("HH:mm")
    }.format(t)

    fun ago(ms: Long): String = when {
        ms < 2_000 -> "now"
        ms < 60_000 -> "${ms / 1000}s ago"
        ms < 3_600_000 -> "${ms / 60_000}m ago"
        else -> "${ms / 3_600_000}h ago"
    }

    fun duration(ms: Long): String {
        val m = (ms / 60_000).coerceAtLeast(0)
        return if (m < 60) "${m}m" else "${m / 60}h ${m % 60}m"
    }
}
