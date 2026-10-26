package app.contrail.ui.map

import android.content.Context

import android.Manifest
import android.app.ActivityManager
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import app.contrail.ContrailApp
import androidx.compose.material.icons.rounded.NearMe
import androidx.compose.material.icons.rounded.ViewInAr
import app.contrail.data.device.DeviceSensors
import app.contrail.data.device.Orientation
import app.contrail.domain.SkyGeometry
import app.contrail.ui.ar.SkyViewScreen
import app.contrail.ui.nearby.NearbyItem
import app.contrail.ui.nearby.NearbyScreen
import app.contrail.ui.nearby.nearbyItems
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material3.ModalBottomSheet
import app.contrail.ui.aircraft.clock
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.rounded.Search
import app.contrail.data.static.StaticData
import org.maplibre.android.geometry.LatLng
import app.contrail.domain.Aircraft
import app.contrail.domain.FlightProgressCalc
import app.contrail.ui.airport.AirportSheet
import app.contrail.ui.search.SearchOverlay
import app.contrail.ui.aircraft.AircraftSheet
import app.contrail.ui.theme.Numeric
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import org.maplibre.android.maps.MapView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.statusBars
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.graphics.vector.ImageVector
import app.contrail.ui.settings.AboutScreen
import app.contrail.ui.settings.SettingsScreen
import app.contrail.ui.theme.Grabber
import app.contrail.ui.theme.GlassCircleButton
import app.contrail.ui.theme.InsetGroup
import app.contrail.ui.theme.LocalHaze
import app.contrail.ui.theme.NavRow
import app.contrail.ui.theme.SystemGreen
import app.contrail.ui.theme.glass
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import org.maplibre.android.maps.MapLibreMapOptions

private enum class MapSheet { LAYERS, FILTERS }


@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MapScreen(vm: MapViewModel, darkTheme: Boolean) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    // Android Go / low-RAM phones: skip live glass. It needs the map in a TextureView (an extra GPU copy per
    // frame) plus a blur pass; the controls fall back to a frosted solid fill that costs nothing.
    val lite = remember { context.getSystemService(ActivityManager::class.java).isLowRamDevice }
    val mapView = rememberMapView(textureMode = !lite)
    val haze = rememberHazeState()
    var controller by remember { mutableStateOf<MapController?>(null) }

    val selected by vm.selected.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val following by vm.following.collectAsStateWithLifecycle()
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val mapStyle = prefs.style?.let { n -> MapStyle.entries.find { it.name == n } } ?: if (darkTheme) MapStyle.DARK else MapStyle.LIGHT
    val radarFrames by vm.radarFrames.collectAsStateWithLifecycle()
    var radarIndex by remember { mutableStateOf(0) }
    /** True while a full-screen page hides the map, so the render and radar loops can idle. */
    val mapCovered = remember { mutableStateOf(false) }

    fun hasLocation() = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    var centredOnUser by rememberSaveable { mutableStateOf(false) }
    var locationGranted by remember { mutableStateOf(hasLocation()) }
    /** Set once the user pans or zooms, so a late GPS fix doesn't yank the map away from what they're looking at. */
    var userMoved by remember { mutableStateOf(false) }
    val cameraPrefs = remember { context.getSharedPreferences("camera", Context.MODE_PRIVATE) }
    fun centreOnUser() {
        val c = controller ?: return
        c.enableLocation()
        c.lastLocation()?.let {
            c.moveTo(it.latitude, it.longitude, zoom = maxOf(c.map.cameraPosition.zoom, 8.5))
            centredOnUser = true
        } ?: scope.launch {
            // The map's puck has no fix yet: wait (up to 30 s) for the platform's.
            withTimeoutOrNull(30_000) { DeviceSensors.location(context).first() }?.let {
                c.moveTo(it.latitude, it.longitude, zoom = maxOf(c.map.cameraPosition.zoom, 8.5))
                centredOnUser = true
            }
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.any { it }) {
            locationGranted = true
            controller?.enableLocation()
        }
    }
    var askedPermission by rememberSaveable { mutableStateOf(false) }

    // Map setup, once.
    LaunchedEffect(mapView) {
        mapView.getMapAsync { map ->
            val c = MapController(
                context, map,
                { v ->
                    vm.onViewport(v)
                    cameraPrefs.edit().putFloat("lat", v.lat.toFloat()).putFloat("lon", v.lon.toFloat()).putFloat("zoom", v.zoom.toFloat()).apply()
                },
                onAircraftTap = { hex -> vm.select(hex) },
                onAirportTap = { icao -> StaticData.now()?.airport(icao)?.let(vm::selectAirport) },
                onUserGesture = { userMoved = true; vm.setFollowing(false) },
            )
            // Open where the map was last left (the whole world on first launch); the GPS fix below then takes over.
            if (map.cameraPosition.target == null || map.cameraPosition.zoom < 1) {
                if (cameraPrefs.contains("lat")) {
                    c.moveTo(cameraPrefs.getFloat("lat", 0f).toDouble(), cameraPrefs.getFloat("lon", 0f).toDouble(), cameraPrefs.getFloat("zoom", 8f).toDouble(), animate = false)
                } else {
                    c.moveTo(20.0, 0.0, 1.5, animate = false)
                }
            }
            controller = c
        }
    }
    LaunchedEffect(controller) {
        val c = controller ?: return@LaunchedEffect
        val static = StaticData.get()
        c.showAirports(withContext(Dispatchers.Default) { MapController.airportFeatures(static.airports) })
    }
    LaunchedEffect(controller, mapStyle) {
        val c = controller ?: return@LaunchedEffect
        c.setStyle(mapStyle, hasLocation()) {
            c.viewport()?.let(vm::onViewport)
        }
        if (!hasLocation() && !askedPermission) {
            askedPermission = true
            permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }

    // Fly to the user on the first fix of this launch, however long a cold GPS takes, unless they've already moved the map.
    LaunchedEffect(controller, locationGranted) {
        val c = controller ?: return@LaunchedEffect
        if (centredOnUser || !locationGranted) return@LaunchedEffect
        val fix = DeviceSensors.location(context).first()
        if (!userMoved && !centredOnUser) c.moveTo(fix.latitude, fix.longitude, zoom = maxOf(c.map.cameraPosition.zoom, 8.5))
        centredOnUser = true
    }

    LaunchedEffect(controller, prefs.airports, prefs.labels, mapStyle) {
        controller?.setToggles(prefs.airports, prefs.labels)
    }
    // Radar: load all frames, then loop through them; pause on the newest.
    LaunchedEffect(controller, radarFrames, mapStyle) {
        val c = controller ?: return@LaunchedEffect
        c.setRadar(radarFrames.map { it.second })
        if (radarFrames.isEmpty()) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                for (i in radarFrames.indices) {
                    while (mapCovered.value) delay(500)
                    radarIndex = i
                    c.showRadarFrame(i)
                    delay(if (i == radarFrames.lastIndex) 2_000 else 500)
                }
            }
        }
    }

    // Network polling only while visible.
    LaunchedEffect(Unit) { lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { vm.pollWhileVisible() } }

    // Render loop: dead-reckoned positions so planes glide between reports. The frame rate follows the zoom,
    // and nothing is drawn while a full-screen page covers the map.
    LaunchedEffect(controller) {
        val c = controller ?: return@LaunchedEffect
        var trail = MapController.trailFeatures(emptyList(), null, 0)
        var trailAt = 0L
        val logoRequested = HashSet<String>()
        var fittedFor: String? = null
        val logos = (context.applicationContext as ContrailApp).container.logos
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                if (mapCovered.value) { delay(500); continue }
                val now = System.currentTimeMillis()
                val sel = vm.selected.value
                val all = vm.tracks.value
                val selTrack = sel?.let { all[it] }
                // Only draw the route when it checks out against the live position.
                val checked = vm.enrichment.value?.route?.takeIf { r ->
                    selTrack != null && FlightProgressCalc.compute(selTrack, r, now).verified
                }
                val origin = checked?.origin
                val dest = checked?.destination
                val p = vm.prefs.value
                val static = StaticData.now()
                val cam = c.map.cameraPosition
                val logosShown = p.labels && cam.zoom >= MapController.LOGO_MIN_ZOOM && static != null
                val logoIds = c.logoIds
                val scrubPoint = vm.scrub.value
                val area = c.cullArea()
                // Every plane's trail is costly to rebuild, so with "all trails" on it refreshes once a second.
                val rebuildTrail = !p.allTrails || now - trailAt >= 1_000
                val (ac, route) = withContext(Dispatchers.Default) {
                    if (rebuildTrail) {
                        val visible = if (p.allTrails) all.values.filter { p.filters.matches(it.aircraft) } else emptyList()
                        trail = MapController.trailFeatures(visible, selTrack, now)
                        trailAt = now
                    }
                    val logoFor: (Aircraft) -> String? = { a ->
                        static?.airlineCode(a.callsign)?.let(MapController::logoId)?.takeIf { it in logoIds }
                    }
                    MapController.aircraftFeatures(all.values, sel, p.filters, now, area, if (logosShown) logoFor else { _ -> null }) to
                        MapController.routeFeatures(selTrack, origin, dest, now)
                }
                // Fetch badges for airlines on screen (each once; AirlineLogos caches them on disk).
                if (logosShown) {
                    for (t in all.values) {
                        if (area != null && !area.contains(t.aircraft.lat, t.aircraft.lon)) continue
                        val icao = static.airlineCode(t.aircraft.callsign) ?: continue
                        if (logoRequested.add(icao)) launch {
                            try {
                                logos.get(icao)?.let { bmp -> c.addLogo(icao, bmp) }
                            } catch (e: CancellationException) {
                                logoRequested.remove(icao) // app went to the background mid-download
                                throw e
                            }
                        }
                    }
                }
                c.render(ac, trail, route, MapController.scrubFeatures(scrubPoint))
                // Once per selection, frame the whole flight (origin, plane, destination) above the sheet.
                if (sel == null) fittedFor = null
                if (origin != null && dest != null && selTrack != null && sel != fittedFor && !vm.following.value) {
                    fittedFor = sel
                    val dm = context.resources.displayMetrics
                    val (lat, lon) = selTrack.displayPosition(now)
                    val pad = (56 * dm.density).toInt()
                    c.fit(
                        listOf(LatLng(origin.lat, origin.lon), LatLng(lat, lon), LatLng(dest.lat, dest.lon)),
                        intArrayOf(pad, (110 * dm.density).toInt(), pad, (dm.heightPixels * 0.5).toInt()),
                    )
                }
                if (vm.following.value && sel != null) {
                    all[sel]?.displayPosition(now)?.let { (lat, lon) -> c.moveTo(lat, lon, animate = false) }
                }
                withTimeoutOrNull(MapController.frameIntervalMs(cam.zoom, cam.target?.latitude ?: 0.0)) { vm.redraw.first() }
            }
        }
    }

    val sheetState = rememberBottomSheetState(SheetValue.Hidden, setOf(SheetValue.Hidden, SheetValue.PartiallyExpanded, SheetValue.Expanded))
    val scaffoldState = rememberBottomSheetScaffoldState(bottomSheetState = sheetState)
    val airportUi by vm.airportUi.collectAsStateWithLifecycle()
    val anySelection = selected != null || airportUi != null
    LaunchedEffect(selected, airportUi?.airport) {
        if (selected != null || airportUi != null) sheetState.partialExpand() else sheetState.hide()
    }
    LaunchedEffect(sheetState) {
        snapshotFlow { sheetState.currentValue }.collect {
            if (it == SheetValue.Hidden) {
                if (vm.selected.value != null) vm.select(null)
                if (vm.airportUi.value != null) vm.selectAirport(null)
            }
        }
    }
    val selectedTrack by vm.selectedTrack.collectAsStateWithLifecycle()
    val enrichment by vm.enrichment.collectAsStateWithLifecycle()

    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var openSheet by remember { mutableStateOf<MapSheet?>(null) }
    var nearbyOpen by rememberSaveable { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var page by rememberSaveable { mutableStateOf<String?>(null) }
    val container = (context.applicationContext as ContrailApp).container
    var skyOpen by rememberSaveable { mutableStateOf(false) }

    // Location + compass only while Nearby or Sky view is showing.
    var userLocation by remember { mutableStateOf<android.location.Location?>(null) }
    // 30 Hz: read only by the arrows and sky labels (via lambdas), never here, or the whole screen would recompose.
    val orientation = remember { mutableStateOf<Orientation?>(null) }
    LaunchedEffect(nearbyOpen || skyOpen) {
        if (!(nearbyOpen || skyOpen)) { vm.nearbyCentre.value = null; return@LaunchedEffect }
        if (!hasLocation()) {
            permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            return@LaunchedEffect
        }
        launch {
            DeviceSensors.location(context).collect { l ->
                userLocation = l
                // Rounded so small GPS wobble doesn't restart the area polling.
                vm.nearbyCentre.value = (Math.round(l.latitude * 20) / 20.0) to (Math.round(l.longitude * 20) / 20.0)
            }
        }
        DeviceSensors.orientation(context).collect { orientation.value = it }
    }
    var nearbyItems by remember { mutableStateOf<List<NearbyItem>?>(null) }
    LaunchedEffect(nearbyOpen || skyOpen, userLocation) {
        val loc = userLocation ?: return@LaunchedEffect
        while (isActive && (nearbyOpen || skyOpen)) {
            nearbyItems = withContext(Dispatchers.Default) { nearbyItems(vm.tracks.value.values, loc, System.currentTimeMillis()) }
            delay(if (skyOpen) 200 else 1_000)
        }
    }
    val searchResults by vm.searchResults.collectAsStateWithLifecycle()
    val searching by vm.searching.collectAsStateWithLifecycle()
    BackHandler(enabled = anySelection && !searchOpen) {
        vm.select(null)
        vm.selectAirport(null)
    }
    fun flyToAircraft(hex: String) {
        vm.select(hex)
        vm.tracks.value[hex]?.aircraft?.let { a -> controller?.moveTo(a.lat, a.lon, zoom = maxOf(controller?.map?.cameraPosition?.zoom ?: 0.0, 8.0)) }
    }

    SideEffect {
        mapCovered.value = searchOpen || nearbyOpen || skyOpen || page != null
        vm.mapVisible.value = !mapCovered.value
    }
    val windowHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
    val maxSheetHeight = windowHeight - WindowInsets.statusBars.asPaddingValues().calculateTopPadding() - 40.dp
    BottomSheetScaffold(
        scaffoldState = scaffoldState,
        sheetPeekHeight = 300.dp,
        sheetContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        sheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        sheetShadowElevation = 12.dp,
        sheetTonalElevation = 0.dp,
        sheetDragHandle = { Grabber() },
        sheetContent = {
            // Fully expanded, the sheet stops short of the status bar, iOS-style, leaving a strip of map.
            Box(Modifier.heightIn(max = maxSheetHeight)) {
                val ap = airportUi
                val track = selectedTrack
                if (ap != null) {
                    AirportSheet(ap, onAircraft = ::flyToAircraft, onClose = { vm.selectAirport(null) })
                } else if (track != null) {
                    AircraftSheet(
                        track = track,
                        enrichment = enrichment,
                        following = following,
                        onFollow = { vm.setFollowing(!following) },
                        onClose = { vm.select(null) },
                        onScrub = { vm.scrub.value = it },
                    )
                } else {
                    Spacer(Modifier.size(1.dp))
                }
            }
        },
    ) {
        Box(Modifier.fillMaxSize()) {
            AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize().hazeSource(haze))

            CompositionLocalProvider(LocalHaze provides haze.takeUnless { lite }) {
                Column(Modifier.align(Alignment.TopStart).statusBarsPadding().padding(start = 16.dp, top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val count by vm.trackCount.collectAsStateWithLifecycle()
                    LiveChip(status, count)
                    radarFrames.getOrNull(radarIndex)?.let { (time, _) ->
                        Text(
                            "Radar ${clock(time)}",
                            style = MaterialTheme.typography.labelMedium.merge(Numeric),
                            modifier = Modifier.glass(RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 7.dp),
                        )
                    }
                }

                Column(
                    Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(end = 16.dp, top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ControlStack {
                        StackButton(Icons.Rounded.Layers, "Map layers") { openSheet = MapSheet.LAYERS }
                        StackButton(Icons.Rounded.FilterList, "Filters", badge = prefs.filters.active) { openSheet = MapSheet.FILTERS }
                        StackButton(Icons.Rounded.NearMe, "My location") {
                            if (hasLocation()) centreOnUser()
                            else permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                        }
                    }
                    GlassCircleButton(Icons.Rounded.MoreHoriz, "More", { menuOpen = true })
                }

                AnimatedVisibility(
                    visible = !anySelection,
                    enter = slideInVertically { it } + fadeIn(),
                    exit = slideOutVertically { it } + fadeOut(),
                    modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SearchBar(onClick = { searchOpen = true }, modifier = Modifier.weight(1f))
                        GlassCircleButton(Icons.Rounded.Explore, "Planes near me", { nearbyOpen = true }, size = 52.dp)
                    }
                }
            }
        }
    }

    if (openSheet != null) {
        ModalBottomSheet(onDismissRequest = { openSheet = null }, containerColor = MaterialTheme.colorScheme.surfaceContainerLow, dragHandle = { Grabber() }) {
            when (openSheet) {
                MapSheet.LAYERS -> LayersSheet(prefs, vm::updatePrefs)
                MapSheet.FILTERS -> FiltersSheet(prefs.filters) { f -> vm.updatePrefs { it.copy(filters = f) } }
                null -> Unit
            }
        }
    }

    if (menuOpen) {
        ModalBottomSheet(onDismissRequest = { menuOpen = false }, containerColor = MaterialTheme.colorScheme.surfaceContainerLow, dragHandle = { Grabber() }) {
            Column(Modifier.padding(horizontal = 16.dp).navigationBarsPadding()) {
                InsetGroup(cell = MaterialTheme.colorScheme.surfaceContainerHigh, dividerInset = 60.dp) {
                    row { NavRow(Icons.Rounded.Explore, Color(0xFF0A84FF), "Planes near me", "What's overhead, and where to look") { menuOpen = false; nearbyOpen = true } }
                    row { NavRow(Icons.Rounded.ViewInAr, Color(0xFF5E5CE6), "Sky view", "Point your camera at the sky") { menuOpen = false; skyOpen = true } }
                }
                InsetGroup(cell = MaterialTheme.colorScheme.surfaceContainerHigh, dividerInset = 60.dp) {
                    row { NavRow(Icons.Rounded.Settings, Color(0xFF8E8E93), "Settings", "Units and time") { menuOpen = false; page = "settings" } }
                    row { NavRow(Icons.Rounded.Info, Color(0xFF8E8E93), "About", "Data sources, feed status and credits") { menuOpen = false; page = "about" } }
                }
            }
        }
    }
    AnimatedVisibility(visible = page != null, enter = slideInVertically { it / 4 } + fadeIn(), exit = fadeOut()) {
        BackHandler { page = null }
        when (page) {
            "settings" -> SettingsScreen(container, onClose = { page = null })
            "about" -> AboutScreen(container, onClose = { page = null })
        }
    }

    AnimatedVisibility(visible = nearbyOpen && !skyOpen, enter = slideInVertically { it / 4 } + fadeIn(), exit = fadeOut()) {
        BackHandler { nearbyOpen = false }
        NearbyScreen(
            items = nearbyItems,
            phoneHeading = { orientation.value?.matrix?.let { SkyGeometry.phoneHeading(it) } },
            onAircraft = { hex -> nearbyOpen = false; flyToAircraft(hex) },
            onAr = { skyOpen = true },
            onClose = { nearbyOpen = false },
        )
    }
    AnimatedVisibility(visible = skyOpen, enter = fadeIn(), exit = fadeOut()) {
        BackHandler { skyOpen = false }
        SkyViewScreen(
            items = nearbyItems,
            orientation = { orientation.value },
            onAircraft = { hex -> skyOpen = false; nearbyOpen = false; flyToAircraft(hex) },
            onClose = { skyOpen = false },
        )
    }

    AnimatedVisibility(visible = searchOpen, enter = fadeIn(), exit = fadeOut()) {
        BackHandler { searchOpen = false }
        SearchOverlay(
            results = searchResults,
            searching = searching,
            onQuery = vm::search,
            onAircraft = { a -> searchOpen = false; flyToAircraft(a.hex) },
            onAirport = { ap ->
                searchOpen = false
                vm.selectAirport(ap)
                controller?.moveTo(ap.lat, ap.lon, zoom = 9.0)
            },
            onClose = { searchOpen = false },
        )
    }
}

@Composable
private fun LiveChip(status: FeedStatus, count: Int) {
    val (dot, text) = when (status) {
        FeedStatus.CONNECTING -> Color(0xFFFF9F0A) to "Connecting"
        FeedStatus.LIVE -> SystemGreen to if (count == 1) "1 aircraft" else "$count aircraft"
        FeedStatus.OFFLINE -> Color(0xFFFF453A) to "Offline"
    }
    Row(
        Modifier.glass(RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).background(dot, CircleShape))
        Spacer(Modifier.width(7.dp))
        Text(text, style = MaterialTheme.typography.labelMedium.merge(Numeric))
    }
}

@Composable
private fun ControlStack(content: @Composable () -> Unit) {
    Column(Modifier.width(44.dp).glass(RoundedCornerShape(22.dp)).padding(vertical = 2.dp)) { content() }
}

@Composable
private fun StackButton(icon: ImageVector, description: String, badge: Boolean = false, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, description, Modifier.size(21.dp), tint = MaterialTheme.colorScheme.onSurface)
        if (badge) Box(Modifier.align(Alignment.TopEnd).padding(top = 9.dp, end = 9.dp).size(8.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
    }
}

@Composable
private fun SearchBar(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.height(52.dp).glass(RoundedCornerShape(26.dp)).clickable(onClick = onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Search, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(10.dp))
        Text("Search flights and airports", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

@Composable
private fun rememberMapView(textureMode: Boolean): MapView {
    val context = LocalContext.current
    // Texture mode so the map draws inside the view hierarchy, where the glass controls can sample it.
    val mapView = remember { MapView(context, MapLibreMapOptions.createFromAttributes(context).textureMode(textureMode)).apply { onCreate(null) } }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return mapView
}
