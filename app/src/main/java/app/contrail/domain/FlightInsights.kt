package app.contrail.domain

import app.contrail.data.enrich.Airport
import app.contrail.data.enrich.Route
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

enum class Phase(val label: String) {
    PARKED("Parked"), TAXIING("Taxiing"), TAKEOFF("Taking off"), CLIMBING("Climbing"), CRUISING("Cruising"),
    LEVEL("Level flight"), DESCENDING("Descending"), APPROACH("On approach"), LANDED("Landed"),
}

/** A short, plain-language observation for the aircraft sheet. */
data class Insight(val kind: Kind, val text: String) {
    enum class Kind { PHASE, TARGET, WIND, TEMPERATURE, PLACE, EMERGENCY }
}

/** Turns the raw feed fields into what a person actually wants to know. Pure; unit-tested. */
object FlightInsights {
    private const val CLIMB_FPM = 400.0

    fun phase(t: Track, p: FlightProgress?, now: Long): Phase {
        val a = t.aircraft
        val gs = a.groundSpeed ?: 0.0
        if (a.onGround) return when {
            t.landedAt != null && now - t.landedAt < 15 * 60_000 -> if (gs > 40) Phase.LANDED else if (gs > 3) Phase.TAXIING else Phase.LANDED
            gs > 40 -> Phase.TAKEOFF
            gs > 3 -> Phase.TAXIING
            else -> Phase.PARKED
        }
        val vr = a.verticalRate ?: 0.0
        val alt = a.altBaro ?: 0
        return when {
            vr > CLIMB_FPM -> Phase.CLIMBING
            vr < -CLIMB_FPM -> if (alt < 3000 || (p?.verified == true && p.remainingNm < 30 && alt < 8000)) Phase.APPROACH else Phase.DESCENDING
            alt >= 18_000 -> Phase.CRUISING
            else -> Phase.LEVEL
        }
    }

    /** Headwind (+) / tailwind (−) and crosswind (+ from the right) components, in knots. */
    fun windComponents(windFrom: Double, windKt: Double, track: Double): Pair<Double, Double> {
        val rel = Math.toRadians(windFrom - track)
        return windKt * cos(rel) to windKt * sin(rel)
    }

    /** One line describing what the flight is doing, naming the airports when the route checks out. */
    fun phaseLine(phase: Phase, a: Aircraft, route: Route?, p: FlightProgress?): String {
        val r = route?.takeIf { p?.verified == true }
        return when (phase) {
            // Not on a step climb hours later, and "landed" only at the destination: it may be the origin of its next flight.
            Phase.CLIMBING -> r?.takeIf { (p?.flownNm ?: 0.0) < 100 }?.let { "Climbing out of ${it.origin.shortName}" } ?: "Climbing through ${Fmt.altitude(a)}"
            Phase.CRUISING -> "Cruising at ${Fmt.altitude(a)}"
            Phase.LEVEL -> "Level at ${Fmt.altitude(a)}"
            Phase.DESCENDING -> r?.let { "Descending towards ${it.destination.shortName}" } ?: "Descending through ${Fmt.altitude(a)}"
            Phase.APPROACH -> r?.let { "On final approach into ${it.destination.shortName}" } ?: "Low and descending, likely landing"
            Phase.TAKEOFF -> "Accelerating on the runway"
            Phase.LANDED -> r?.takeIf { (p?.remainingNm ?: 0.0) < 5 }?.let { "Landed in ${it.destination.shortName}" } ?: "Just landed"
            Phase.TAXIING -> "Taxiing"
            Phase.PARKED -> "Parked"
        }
    }

    fun insights(t: Track, route: Route?, p: FlightProgress?, near: Airport?, now: Long): List<Insight> {
        val a = t.aircraft
        val out = ArrayList<Insight>()
        if (a.isEmergency) {
            val sq = a.squawk
            val what = sq?.let { Squawks.meaning(it) } ?: a.emergency?.replaceFirstChar { it.uppercase() } ?: "Emergency"
            out += Insight(Insight.Kind.EMERGENCY, if (sq != null) "Squawking $sq: $what" else what)
        }
        val phase = phase(t, p, now)
        out += Insight(Insight.Kind.PHASE, phaseLine(phase, a, route, p))
        if (!a.onGround) {
            val alt = a.altBaro
            // Only when the plane is actually heading there: on approach the selected altitude is the
            // missed-approach altitude, above the plane, and "climbing to 3,000 ft" would contradict "landing".
            val target = (a.navAltMcp ?: a.navAltFms)?.let { (it + 50) / 100 * 100 } // feed values are in 4 ft steps (3008)
            val vr = a.verticalRate ?: 0.0
            if (alt != null && target != null && target > 0 && abs(target - alt) > 400 && phase != Phase.APPROACH) {
                if (target > alt && vr > CLIMB_FPM) out += Insight(Insight.Kind.TARGET, "Climbing to ${Fmt.feet(target)} on autopilot")
                if (target < alt && vr < -CLIMB_FPM) out += Insight(Insight.Kind.TARGET, "Descending to ${Fmt.feet(target)} on autopilot")
            }
            val trk = a.track
            if (a.windDir != null && a.windSpeed != null && trk != null && a.windSpeed >= 5) {
                val (head, cross) = windComponents(a.windDir, a.windSpeed, trk)
                out += Insight(
                    Insight.Kind.WIND,
                    when {
                        head <= -8 -> "${Fmt.knots(-head)} tailwind, so it's moving faster over the ground"
                        head >= 8 -> "${Fmt.knots(head)} headwind slowing it down"
                        else -> "${Fmt.knots(abs(cross))} crosswind from the ${if (cross > 0) "right" else "left"}"
                    },
                )
            }
            a.oat?.let { out += Insight(Insight.Kind.TEMPERATURE, "${Fmt.celsius(it)} outside the cabin") }
        }
        near?.let { ap ->
            val (lat, lon) = Track.extrapolate(a, now)
            val nm = Geo.distanceNm(ap.lat, ap.lon, lat, lon)
            out += Insight(
                Insight.Kind.PLACE,
                if (nm < 3) "${if (a.onGround) "At" else "Over"} ${ap.name}" else "${Fmt.nm(nm)} ${Geo.compass(Geo.bearing(ap.lat, ap.lon, lat, lon))} of ${ap.shortName}",
            )
        }
        return out
    }

    /** Whole years since the build year the feed reports, or null if it's not a plausible year. */
    fun ageYears(year: String?, nowYear: Int): Int? = year?.take(4)?.toIntOrNull()?.takeIf { it in 1900..nowYear }?.let { nowYear - it }
}
