package app.contrail.data.user

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.contrail.domain.AircraftKind
import app.contrail.domain.Filters
import app.contrail.domain.AltitudeUnit
import app.contrail.domain.ClockStyle
import app.contrail.domain.DistanceUnit
import app.contrail.domain.SpeedUnit
import app.contrail.domain.Units
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class MapPrefs(
    /** Null = follow the system theme. */
    val style: String? = null,
    val radar: Boolean = false,
    val airports: Boolean = true,
    val labels: Boolean = true,
    val allTrails: Boolean = false,
    val filters: Filters = Filters(),
)

private val Context.store by preferencesDataStore("settings")

class SettingsRepository(private val context: Context) {
    private object K {
        val style = stringPreferencesKey("map_style")
        val radar = booleanPreferencesKey("radar")
        val airports = booleanPreferencesKey("airports")
        val labels = booleanPreferencesKey("labels")
        val allTrails = booleanPreferencesKey("all_trails")
        val altMin = intPreferencesKey("f_alt_min")
        val altMax = intPreferencesKey("f_alt_max")
        val hideGround = booleanPreferencesKey("f_hide_ground")
        val kinds = stringSetPreferencesKey("f_kinds")
        val special = booleanPreferencesKey("f_special")
        val altUnit = stringPreferencesKey("unit_alt")
        val speedUnit = stringPreferencesKey("unit_speed")
        val distUnit = stringPreferencesKey("unit_dist")
        val clock = stringPreferencesKey("clock")
    }

    val map: Flow<MapPrefs> = context.store.data.map { p -> p.toMapPrefs() }

    val units: Flow<Units> = context.store.data.map { p -> p.toUnits() }

    private fun Preferences.toUnits() = Units(
        altitude = AltitudeUnit.entries.find { it.name == this[K.altUnit] } ?: AltitudeUnit.FEET,
        speed = SpeedUnit.entries.find { it.name == this[K.speedUnit] } ?: SpeedUnit.KNOTS,
        distance = DistanceUnit.entries.find { it.name == this[K.distUnit] } ?: DistanceUnit.KM,
        clock = ClockStyle.entries.find { it.name == this[K.clock] } ?: ClockStyle.SYSTEM,
    )

    suspend fun updateUnits(f: (Units) -> Units) {
        context.store.edit { p ->
            val n = f(p.toUnits())
            p[K.altUnit] = n.altitude.name
            p[K.speedUnit] = n.speed.name
            p[K.distUnit] = n.distance.name
            p[K.clock] = n.clock.name
        }
    }

    private fun Preferences.toMapPrefs() = MapPrefs(
        style = this[K.style],
        radar = this[K.radar] ?: false,
        airports = this[K.airports] ?: true,
        labels = this[K.labels] ?: true,
        allTrails = this[K.allTrails] ?: false,
        filters = Filters(
            altMinFt = this[K.altMin] ?: 0,
            altMaxFt = this[K.altMax] ?: Filters.MAX_ALT,
            hideGround = this[K.hideGround] ?: false,
            kinds = this[K.kinds]?.mapNotNull { n -> AircraftKind.entries.find { it.name == n } }?.toSet() ?: AircraftKind.entries.toSet(),
            specialOnly = this[K.special] ?: false,
        ),
    )

    suspend fun update(f: (MapPrefs) -> MapPrefs) {
        context.store.edit { p ->
            val n = f(p.toMapPrefs())
            if (n.style == null) p.remove(K.style) else p[K.style] = n.style
            p[K.radar] = n.radar
            p[K.airports] = n.airports
            p[K.labels] = n.labels
            p[K.allTrails] = n.allTrails
            p[K.altMin] = n.filters.altMinFt
            p[K.altMax] = n.filters.altMaxFt
            p[K.hideGround] = n.filters.hideGround
            p[K.kinds] = n.filters.kinds.map { it.name }.toSet()
            p[K.special] = n.filters.specialOnly
        }
    }
}
