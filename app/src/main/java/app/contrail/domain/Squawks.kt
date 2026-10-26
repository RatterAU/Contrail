package app.contrail.domain

/** Plain-English meanings for transponder codes and emitter categories. */
object Squawks {
    fun meaning(sq: String): String? = when (sq) {
        "7500" -> "Hijack"
        "7600" -> "Radio failure"
        "7700" -> "General emergency"
        "7000" -> "VFR (Europe/Australia)"
        "1200" -> "VFR (US/Canada)"
        "2000" -> "Entering controlled airspace"
        "7400" -> "Lost datalink (drone)"
        "1000" -> "IFR, Mode S conspicuity"
        else -> null
    }

    fun category(c: String?): String = when (c) {
        "A1" -> "Light (< 7 t)"
        "A2" -> "Small (7–34 t)"
        "A3" -> "Large (34–136 t)"
        "A4" -> "High-vortex large (B757)"
        "A5" -> "Heavy (> 136 t)"
        "A6" -> "High performance"
        "A7" -> "Rotorcraft"
        "B1" -> "Glider"
        "B2" -> "Lighter than air"
        "B3" -> "Parachutist"
        "B4" -> "Ultralight"
        "B6" -> "Drone (UAV)"
        "B7" -> "Space vehicle"
        "C1" -> "Emergency vehicle"
        "C2" -> "Service vehicle"
        "C3" -> "Ground obstacle"
        null, "A0", "B0", "C0", "D0" -> "Not reported"
        else -> c
    }
}
