package app.contrail.domain

enum class AircraftKind(val label: String) {
    AIRLINER("Airliners"), LIGHT("Light & GA"), HELICOPTER("Helicopters"), MILITARY("Military"), GLIDER("Gliders & balloons"), GROUND("Ground vehicles");

    companion object {
        fun of(a: Aircraft): AircraftKind = when {
            a.military -> MILITARY
            else -> when (a.category) {
                "A1", "B4" -> LIGHT
                "A7" -> HELICOPTER
                "B1", "B2", "B3" -> GLIDER
                "C1", "C2", "C3", "C4", "C5" -> GROUND
                else -> AIRLINER
            }
        }
    }
}

data class Filters(
    val altMinFt: Int = 0,
    val altMaxFt: Int = MAX_ALT,
    val hideGround: Boolean = false,
    val kinds: Set<AircraftKind> = AircraftKind.entries.toSet(),
    /** Only military, emergency or "interesting" (database-flagged) aircraft. */
    val specialOnly: Boolean = false,
) {
    val active get() = this != Filters()

    fun matches(a: Aircraft): Boolean {
        if (a.isEmergency) return true // never hide an emergency
        if (hideGround && a.onGround) return false
        if (AircraftKind.of(a) !in kinds) return false
        if (specialOnly && !(a.military || a.interesting)) return false
        if (!a.onGround) {
            val alt = a.altBaro ?: return altMinFt == 0 && altMaxFt == MAX_ALT
            if (alt < altMinFt) return false
            if (altMaxFt < MAX_ALT && alt > altMaxFt) return false
        }
        return true
    }

    companion object {
        const val MAX_ALT = 50_000
    }
}
