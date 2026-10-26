package app.contrail.domain

/** One aircraft as last reported by a feed. Units are aviation-native: ft, kt, ft/min, degrees. */
data class Aircraft(
    val hex: String,
    val callsign: String?,
    val registration: String?,
    val typeCode: String?,
    val description: String?,
    val operator: String?,
    val year: String?,
    val lat: Double,
    val lon: Double,
    val altBaro: Int?,
    val onGround: Boolean,
    val altGeom: Int?,
    val groundSpeed: Double?,
    val ias: Double?,
    val tas: Double?,
    val mach: Double?,
    val track: Double?,
    val trackRate: Double?,
    val roll: Double?,
    val magHeading: Double?,
    val trueHeading: Double?,
    val baroRate: Double?,
    val geomRate: Double?,
    val squawk: String?,
    val emergency: String?,
    val category: String?,
    val navQnh: Double?,
    val navAltMcp: Int?,
    val navAltFms: Int?,
    val navHeading: Double?,
    val navModes: List<String>,
    val windDir: Double?,
    val windSpeed: Double?,
    val oat: Double?,
    val tat: Double?,
    val source: PositionSource,
    val nic: Int?,
    val nacP: Int?,
    val sil: Int?,
    val adsbVersion: Int?,
    val rssi: Double?,
    val messages: Long?,
    val dbFlags: Int,
    /** Epoch ms when lat/lon was measured. */
    val positionTime: Long,
    /** Epoch ms of the last message of any kind. */
    val seenTime: Long,
) {
    val military get() = dbFlags and 1 != 0
    val interesting get() = dbFlags and 2 != 0
    val pia get() = dbFlags and 4 != 0
    val ladd get() = dbFlags and 8 != 0
    val verticalRate get() = baroRate ?: geomRate
    val label get() = callsign ?: registration ?: hex.uppercase()
    val isEmergency get() = emergency != null || squawk in EMERGENCY_SQUAWKS
}

val EMERGENCY_SQUAWKS = setOf("7500", "7600", "7700")

enum class PositionSource(val label: String) {
    ADSB("ADS-B"), ADSR("ADS-R"), MLAT("MLAT"), TISB("TIS-B"), MODE_S("Mode S"), ADSC("ADS-C"), ROUGH("Approximate"), OTHER("Other");

    companion object {
        fun from(type: String?, rough: Boolean): PositionSource = when {
            rough -> ROUGH
            type == null -> OTHER
            type.startsWith("adsb") -> ADSB
            type.startsWith("adsr") -> ADSR
            type.startsWith("tisb") -> TISB
            type == "mlat" -> MLAT
            type == "mode_s" -> MODE_S
            type == "adsc" -> ADSC
            else -> OTHER
        }
    }
}
