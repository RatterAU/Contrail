package app.contrail.ui.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.contrail.AppContainer
import app.contrail.data.SearchResults
import app.contrail.data.enrich.Airport
import app.contrail.data.enrich.Enrichment
import app.contrail.data.feed.FeedsUnavailableException
import app.contrail.data.feed.PastFlight
import app.contrail.domain.Replay
import app.contrail.data.static.Runway
import app.contrail.data.static.StaticData
import app.contrail.data.weather.Metar
import app.contrail.data.weather.Taf
import app.contrail.domain.AirportBoards
import app.contrail.domain.Boards
import app.contrail.domain.Geo
import app.contrail.data.user.MapPrefs
import app.contrail.domain.Track
import app.contrail.domain.Aircraft
import app.contrail.domain.TrailPoint
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOn
import app.contrail.domain.WindGrid
import org.maplibre.geojson.FeatureCollection
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

enum class FeedStatus { CONNECTING, LIVE, OFFLINE }

data class AirportUi(
    val airport: Airport,
    val runways: List<Runway>,
    val metar: Metar? = null,
    val taf: Taf? = null,
    val weatherLoaded: Boolean = false,
    val boards: Boards? = null,
    val activeRunway: AirportBoards.ActiveRunway? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class MapViewModel(private val c: AppContainer) : ViewModel() {
    val tracks: StateFlow<Map<String, Track>> = c.traffic.tracks
    val health = c.scheduler.health

    private val _status = MutableStateFlow(FeedStatus.CONNECTING)
    val status: StateFlow<FeedStatus> = _status

    private val _selected = MutableStateFlow<String?>(null)
    val selected: StateFlow<String?> = _selected

    private val _following = MutableStateFlow(false)
    val following: StateFlow<Boolean> = _following

    /** Narrow views of [tracks] so the map screen doesn't recompose on every poll. */
    val selectedTrack: StateFlow<Track?> = combine(_selected, tracks) { hex, all -> hex?.let { all[it] } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val trackCount: StateFlow<Int> = tracks.map { it.size }.stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    private val _selectedAirport = MutableStateFlow<Airport?>(null)
    private val _airportUi = MutableStateFlow<AirportUi?>(null)
    val airportUi: StateFlow<AirportUi?> = _airportUi

    private val viewport = MutableStateFlow<Viewport?>(null)

    val prefs: StateFlow<MapPrefs> = c.settings.map.stateIn(viewModelScope, SharingStarted.Eagerly, MapPrefs())
    fun updatePrefs(f: (MapPrefs) -> MapPrefs) {
        viewModelScope.launch { c.settings.update(f) }
    }

    /** Last ~hour of radar frames, refreshed every 5 min while radar is on. */
    val radarFrames: StateFlow<List<Pair<Long, String>>> = prefs.map { it.radar }.distinctUntilChanged()
        .transformLatest { on ->
            if (!on) { emit(emptyList()); return@transformLatest }
            while (true) {
                // A failed refresh keeps the frames already showing.
                runCatching { c.weather.radarFrames().takeLast(8) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { emit(it) }
                delay(5 * 60_000)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Satellite cloud tile URLs, new every 10 min while clouds are on. */
    val cloudTiles: StateFlow<List<String>> = prefs.map { it.clouds }.distinctUntilChanged()
        .transformLatest { on ->
            if (!on) { emit(emptyList()); return@transformLatest }
            while (true) {
                emit(CloudTiles.urls(System.currentTimeMillis() / 600_000))
                delay(10 * 60_000)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** SIGMET areas as map features, refreshed every 10 min while hazards are on. */
    val hazards: StateFlow<FeatureCollection?> = prefs.map { it.hazards }.distinctUntilChanged()
        .transformLatest { on ->
            if (!on) { emit(null); return@transformLatest }
            while (true) {
                runCatching { c.weather.sigmets() }.getOrNull()?.let { emit(MapController.hazardFeatures(it, System.currentTimeMillis() / 1000)) }
                delay(10 * 60_000)
            }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The visible map box, set when the camera stops; drives which wind points are fetched. */
    val windArea = MutableStateFlow<Area?>(null)

    /** Wind for the visible map while the wind layer is on; refetched as the view moves (points are cached an hour). */
    @OptIn(FlowPreview::class)
    val wind: StateFlow<WindGrid?> = combine(prefs.map { it.wind != null }.distinctUntilChanged(), windArea.debounce(600)) { on, area -> area.takeIf { on } }
        .distinctUntilChanged()
        .transformLatest { area ->
            if (area == null) { emit(null); return@transformLatest }
            val step = WindGrid.stepFor(area.north - area.south, if (area.east < area.west) area.east + 360 - area.west else area.east - area.west)
            val points = WindGrid.points(area.south, area.west, area.north, area.east, step)
            while (true) {
                val grid = c.weather.wind(points, step)
                emit(grid)
                // A failed or rate-limited fetch comes back short: try again soon rather than in an hour.
                delay(if (points.all { WindGrid.key(it.first, it.second, step) in grid.samples }) 60 * 60_000 else 2 * 60_000)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Point on the selected plane's trail being inspected in the profile chart. */
    val scrub = MutableStateFlow<TrailPoint?>(null)

    /** Where a past flight's replay has the plane, drawn on the map as the plane itself. */
    val replay = MutableStateFlow<Replay.Frame?>(null)

    /** Emits whenever the map needs redrawing before its next paced frame: new data, a selection, settings or a camera stop. */
    val redraw: Flow<Any?> get() = merge(tracks.drop(1), _selected.drop(1), prefs.drop(1), scrub.drop(1), enrichment.drop(1), viewport.drop(1), past.drop(1), replay.drop(1))

    /** Route, photos and registry info for the selected plane; re-fetched if its callsign changes. */
    val enrichment: StateFlow<Enrichment?> = combine(_selected, tracks) { hex, all -> hex?.let { it to all[it]?.aircraft?.callsign } }
        .distinctUntilChanged()
        .transformLatest { key ->
            if (key == null) { emit(null); return@transformLatest }
            val (hex, cs) = key
            emit(c.enrichment.cached(hex, cs))
            emit(c.enrichment.lookup(hex, cs) { emit(it) })
            // A source was down (the answer wasn't cached): ask again while the plane stays selected.
            for (wait in longArrayOf(30_000, 90_000)) {
                if (c.enrichment.cached(hex, cs) != null) break
                delay(wait)
                emit(c.enrichment.lookup(hex, cs))
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val searchQuery = MutableStateFlow("")
    private val _searchResults = MutableStateFlow<SearchResults?>(null)
    val searchResults: StateFlow<SearchResults?> = _searchResults
    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching

    init {
        viewModelScope.launch {
            @OptIn(FlowPreview::class)
            searchQuery.debounce(350).distinctUntilChanged().collectLatest { q ->
                if (q.trim().length < 2) { _searchResults.value = null; return@collectLatest }
                _searching.value = true
                try {
                    _searchResults.value = c.search.search(q)
                } finally {
                    _searching.value = false
                }
            }
        }
    }

    fun search(q: String) {
        searchQuery.value = q
    }

    fun onViewport(v: Viewport) {
        viewport.value = v
    }

    /**
     * A past flight opened from the sheet; [path] is null while downloading and empty if it wasn't recorded. [all] is
     * the plane's whole list, for stepping to the flight before or after.
     */
    data class PastView(val hex: String, val flight: PastFlight, val path: List<TrailPoint>?, val failed: Boolean = false, val all: List<PastFlight> = emptyList())

    /** Shown on the map in place of the live trail, framed once when its path arrives. */
    val past = MutableStateFlow<PastView?>(null)
    private var pastJob: kotlinx.coroutines.Job? = null

    fun openPast(hex: String, f: PastFlight, all: List<PastFlight> = past.value?.all.orEmpty()) {
        pastJob?.cancel()
        _following.value = false
        scrub.value = null
        replay.value = null
        past.value = PastView(hex, f, null, all = all)
        pastJob = viewModelScope.launch {
            val path = c.history.path(f)
            past.update { v -> if (v?.flight == f) v.copy(path = path.orEmpty(), failed = path == null) else v }
        }
    }

    fun closePast() {
        pastJob?.cancel()
        past.value = null
        scrub.value = null
        replay.value = null
    }

    fun select(hex: String?) {
        closePast()
        // An open airport sheet gives way to the plane (it used to stay up until closed by hand).
        if (hex != null) selectAirport(null)
        _selected.value = hex
        scrub.value = null
        c.traffic.pinned = setOfNotNull(hex)
        if (hex == null) _following.value = false
    }

    fun selectAirport(a: Airport?) {
        if (a != null) select(null)
        _selectedAirport.value = a
        _airportUi.value = a?.let { AirportUi(it, StaticData.now()?.runways(it.icao.orEmpty()).orEmpty()) }
    }

    fun setFollowing(on: Boolean) {
        _following.value = on && _selected.value != null
    }

    /** Polls while the screen is visible: viewport, the selected plane if it's off-area, and the open airport. */
    @OptIn(FlowPreview::class)
    suspend fun pollWhileVisible() = coroutineScope {
        launch {
            combine(viewport.filterNotNull().debounce(350), mapVisible) { v, visible -> v.takeIf { visible } }.collectLatest { v ->
                v ?: return@collectLatest // a full-screen page covers the map; it polls what it needs itself
                while (true) {
                    refresh { c.traffic.refreshArea(v.lat, v.lon, v.radiusNm) }
                    delay(pollMs(v.radiusNm))
                }
            }
        }
        launch {
            while (true) {
                delay(POLL_MS)
                val hex = _selected.value ?: continue
                val t = tracks.value[hex]
                if (t == null || t.staleness(c.clock()) > 8_000) refresh { c.traffic.refreshHex(hex) }
            }
        }
        // The path flown before we saw it: fetched once per selection, merged into the trail.
        launch {
            _selected.collectLatest { hex ->
                hex ?: return@collectLatest
                if (tracks.value[hex]?.aircraft?.onGround != false) return@collectLatest
                val pts = c.history.fetch(hex) ?: return@collectLatest
                withContext(Dispatchers.Default) { c.traffic.addHistory(hex, pts) }
            }
        }
        launch { _selectedAirport.collectLatest { ap -> if (ap != null) pollAirport(ap) } }
        launch {
            nearbyCentre.collectLatest { c0 ->
                c0 ?: return@collectLatest
                while (true) {
                    refresh { c.traffic.refreshArea(c0.first, c0.second, 60) }
                    delay(5_000)
                }
            }
        }
    }

    /** False while a full-screen page hides the map, so the map area isn't polled for nothing. */
    val mapVisible = MutableStateFlow(true)

    /** Set while Nearby / Sky view is open so the area around the user stays fresh. */
    val nearbyCentre = MutableStateFlow<Pair<Double, Double>?>(null)

    private suspend fun pollAirport(ap: Airport) = coroutineScope {
        launch {
            val icao = ap.icao ?: return@launch
            // One retry: a cold first request sometimes fails, and that would read as "no weather report".
            val metar = runCatching { c.weather.metar(icao) }.recoverCatching { c.weather.metar(icao) }.getOrNull()
            ensureActive() // runCatching swallows cancellation; this airport's answer mustn't land on the next one
            _airportUi.update { it?.copy(metar = metar, weatherLoaded = true) }
            val taf = runCatching { c.weather.taf(icao) }.getOrNull()
            ensureActive()
            _airportUi.update { it?.copy(taf = taf) }
        }
        val limit = Semaphore(4)
        while (true) {
            refresh { c.traffic.refreshArea(ap.lat, ap.lon, BOARD_RADIUS_NM) }
            val now = c.clock()
            val near = tracks.value.values
                .filter { it.aircraft.callsign != null && Geo.distanceNm(ap.lat, ap.lon, it.aircraft.lat, it.aircraft.lon) < BOARD_RADIUS_NM }
                .sortedBy { Geo.distanceNm(ap.lat, ap.lon, it.aircraft.lat, it.aircraft.lon) }
                .take(60)
            val routes = near.map { t ->
                async { limit.withPermit { t.aircraft.callsign!! to c.enrichment.routeFor(t.aircraft.callsign) } }
            }.awaitAll().toMap()
            val boards = AirportBoards.build(ap, near, routes, now, _airportUi.value?.runways.orEmpty())
            _airportUi.update { ui ->
                ui?.copy(
                    boards = boards,
                    // Calm reports direction 0, which says nothing about the runway.
                    activeRunway = AirportBoards.activeRunway(ap, ui.runways, tracks.value.values, ui.metar?.takeIf { (it.wspd ?: 0.0) >= 1 }?.windDir),
                )
            }
            delay(20_000)
        }
    }

    private suspend fun refresh(block: suspend () -> Unit) {
        try {
            block()
            _status.value = FeedStatus.LIVE
        } catch (e: CancellationException) {
            throw e
        } catch (e: FeedsUnavailableException) {
            _status.value = FeedStatus.OFFLINE
            withContext(Dispatchers.Default) { c.traffic.merge(emptyList()) } // planes still age out with every feed down
        }
    }

    companion object {
        const val POLL_MS = 3_000L

        /** A wide view returns hundreds of aircraft that barely move on screen; poll it less often (data, CPU, rate limits). */
        fun pollMs(radiusNm: Int) = if (radiusNm > 120) 6_000L else POLL_MS
        const val BOARD_RADIUS_NM = 250
    }
}
