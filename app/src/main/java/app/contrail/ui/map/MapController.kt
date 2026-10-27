package app.contrail.ui.map

import org.maplibre.android.style.layers.PropertyFactory.textAllowOverlap

import org.maplibre.android.geometry.LatLngBounds

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import app.contrail.data.enrich.Airport
import app.contrail.data.feed.TrafficRepository
import app.contrail.domain.Filters
import app.contrail.domain.Geo
import app.contrail.domain.Track
import app.contrail.domain.TrailPoint
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.LocationComponentOptions
import org.maplibre.android.location.engine.LocationEngineRequest
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression.eq
import org.maplibre.android.style.expressions.Expression.product
import org.maplibre.android.style.expressions.Expression.linear
import org.maplibre.android.style.expressions.Expression.interpolate
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.expressions.Expression.literal
import org.maplibre.android.style.expressions.Expression.step
import org.maplibre.android.style.expressions.Expression.stop
import org.maplibre.android.style.expressions.Expression.toColor
import org.maplibre.android.style.expressions.Expression.zoom
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.PropertyFactory.fillColor
import org.maplibre.android.style.layers.PropertyFactory.fillOpacity
import org.maplibre.android.style.expressions.Expression.geometryType
import kotlinx.coroutines.flow.MutableStateFlow
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.iconImage
import org.maplibre.android.style.layers.PropertyFactory.iconOpacity
import org.maplibre.android.style.layers.PropertyFactory.iconRotate
import org.maplibre.android.style.layers.PropertyFactory.iconRotationAlignment
import org.maplibre.android.style.layers.PropertyFactory.iconSize
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineDasharray
import org.maplibre.android.style.layers.PropertyFactory.lineJoin
import org.maplibre.android.style.layers.PropertyFactory.lineOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.PropertyFactory.rasterOpacity
import org.maplibre.android.style.layers.PropertyFactory.symbolSortKey
import org.maplibre.android.style.layers.PropertyFactory.textAnchor
import org.maplibre.android.style.layers.PropertyFactory.textColor
import org.maplibre.android.style.layers.PropertyFactory.textField
import org.maplibre.android.style.layers.PropertyFactory.textFont
import org.maplibre.android.style.layers.PropertyFactory.textHaloColor
import org.maplibre.android.style.layers.PropertyFactory.textHaloWidth
import org.maplibre.android.style.layers.PropertyFactory.textOffset
import org.maplibre.android.style.layers.PropertyFactory.textOptional
import org.maplibre.android.style.layers.PropertyFactory.textSize
import org.maplibre.android.style.layers.PropertyFactory.visibility
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.layers.TransitionOptions
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.sources.TileSet
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon
import app.contrail.data.weather.Sigmet
import kotlin.math.abs
import app.contrail.domain.Aircraft
import android.graphics.Paint
import android.graphics.Canvas
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import kotlin.math.sin
import org.maplibre.android.style.layers.PropertyFactory.iconOffset
import org.maplibre.android.style.layers.PropertyFactory.iconAnchor
import org.maplibre.android.style.expressions.Expression.has
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt

enum class MapStyle(val label: String, val uri: String?, val dark: Boolean) {
    DARK("Dark", "https://tiles.openfreemap.org/styles/dark", true),
    LIGHT("Light", "https://tiles.openfreemap.org/styles/positron", false),
    STREETS("Streets", "https://tiles.openfreemap.org/styles/liberty", false),
    SATELLITE("Satellite", null, true),
}

// Sentinel-2 cloudless mosaic by EOX (keyless; Copernicus Sentinel data, CC BY-NC-SA for personal use).
private const val SATELLITE_STYLE = """
{"version":8,
 "glyphs":"https://tiles.openfreemap.org/fonts/{fontstack}/{range}.pbf",
 "sources":{"s2":{"type":"raster","tileSize":256,"maxzoom":13,
   "tiles":["https://tiles.maps.eox.at/wmts/1.0.0/s2cloudless-2024_3857/default/g/{z}/{y}/{x}.jpg"],
   "attribution":"Sentinel-2 cloudless by EOX IT Services GmbH (contains modified Copernicus Sentinel data 2024)"}},
 "layers":[{"id":"bg","type":"background","paint":{"background-color":"#0B0F1A"}},{"id":"s2","type":"raster","source":"s2"}]}
"""

data class Viewport(val lat: Double, val lon: Double, val radiusNm: Int, val zoom: Double)

/** Owns the MapLibre map: style, sprites, sources and layers. All calls on the main thread. */
class MapController(
    private val context: Context,
    val map: MapLibreMap,
    private val onViewport: (Viewport) -> Unit,
    private val onAircraftTap: (String?) -> Unit,
    private val onAirportTap: (String) -> Unit,
    private val onUserGesture: () -> Unit,
) {
    private val sprites: Map<String, Bitmap> by lazy { buildAircraftSprites(context) }
    private var style: Style? = null
    private var airports: FeatureCollection? = null
    private var radarTiles: List<String> = emptyList()
    private var cloudTiles: List<String> = emptyList()
    private var hazards: FeatureCollection = EMPTY

    /** True from the start of any camera move until it settles; the wind particles hide meanwhile. */
    val moving = MutableStateFlow(false)
    private var showAirports = true
    private var showLabels = true
    var currentStyle: MapStyle? = null
        private set

    init {
        map.uiSettings.apply {
            isLogoEnabled = false
            isCompassEnabled = false
            isRotateGesturesEnabled = true
            isTiltGesturesEnabled = false
        }
        map.addOnCameraIdleListener { moving.value = false; viewport()?.let(onViewport) }
        map.addOnCameraMoveStartedListener { reason ->
            moving.value = true
            if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) onUserGesture()
        }
        map.addOnMapClickListener { latLng ->
            val p = map.projection.toScreenLocation(latLng)
            val r = 22 * context.resources.displayMetrics.density
            val box = RectF(p.x - r, p.y - r, p.x + r, p.y + r)
            val hit = map.queryRenderedFeatures(box, AIRCRAFT_LAYER).minByOrNull { f ->
                val pt = f.geometry() as? Point ?: return@minByOrNull Double.MAX_VALUE
                val s = map.projection.toScreenLocation(LatLng(pt.latitude(), pt.longitude()))
                ((s.x - p.x) * (s.x - p.x) + (s.y - p.y) * (s.y - p.y)).toDouble()
            }
            if (hit != null) {
                onAircraftTap(hit.getStringProperty("hex"))
            } else {
                val ap = map.queryRenderedFeatures(box, AIRPORT_LAYER_L, AIRPORT_LAYER_M, AIRPORT_LAYER_S).firstOrNull()
                if (ap != null) onAirportTap(ap.getStringProperty("icao")) else onAircraftTap(null)
            }
            true
        }
    }

    fun showAirports(fc: FeatureCollection) {
        airports = fc
        loaded()?.getSourceAs<GeoJsonSource>(AIRPORT_SOURCE)?.setGeoJson(fc)
    }

    private fun loaded() = style?.takeIf { it.isFullyLoaded }

    fun setStyle(s: MapStyle, locationAllowed: Boolean, onReady: () -> Unit = {}) {
        if (s == currentStyle) return
        currentStyle = s
        val builder = if (s.uri != null) Style.Builder().fromUri(s.uri) else Style.Builder().fromJson(SATELLITE_STYLE)
        map.setStyle(builder) { st ->
            style = st
            pushed.clear()
            st.transition = TransitionOptions(0, 0, false)
            sprites.forEach { (name, bmp) -> st.addImage(name, bmp) }
            addRadarLayers(st)
            addCloudLayers(st)
            addHazardLayers(st, s.dark)
            st.addSource(airports?.let { GeoJsonSource(AIRPORT_SOURCE, it) } ?: GeoJsonSource(AIRPORT_SOURCE))
            st.addSource(GeoJsonSource(ROUTE_SOURCE))
            st.addSource(GeoJsonSource(TRAIL_SOURCE))
            st.addSource(GeoJsonSource(SCRUB_SOURCE))
            st.addSource(GeoJsonSource(AIRCRAFT_SOURCE))
            val accent = if (s.dark) "#0A84FF" else "#007AFF"
            // Large airports from zoom 4, medium from 7, small (GA fields like Parafield) from 9: a dot plus the code.
            for ((id, size, minZ) in listOf(Triple(AIRPORT_LAYER_L, "L", 4f), Triple(AIRPORT_LAYER_M, "M", 7f), Triple(AIRPORT_LAYER_S, "S", 9f))) {
                st.addLayer(
                    CircleLayer("$id-dot", AIRPORT_SOURCE).withProperties(
                        circleRadius(when (size) { "L" -> 4.5f; "M" -> 3.5f; else -> 2.5f }),
                        circleColor(accent),
                        circleStrokeColor(if (s.dark) "#0B0F1A" else "#FFFFFF"),
                        circleStrokeWidth(1.5f),
                    ).withFilter(eq(get("s"), literal(size))).apply { minZoom = minZ },
                )
                st.addLayer(
                    SymbolLayer(id, AIRPORT_SOURCE).withProperties(
                        textField(get("code")),
                        textFont(arrayOf("Noto Sans Bold")),
                        textSize(when (size) { "L" -> 12f; "M" -> 11f; else -> 10f }),
                        textColor(accent),
                        textHaloColor(if (s.dark) "#E60B0F1A" else "#E6FFFFFF"),
                        textHaloWidth(1.5f),
                        textAnchor(Property.TEXT_ANCHOR_TOP),
                        textOffset(arrayOf(0f, 0.6f)),
                    ).withFilter(eq(get("s"), literal(size))).apply { minZoom = minZ },
                )
            }
            val routeColor = if (s.dark) "#E3E7F2" else "#303A4F"
            // Where it came from before we started recording: faint, because it's the straight-line estimate.
            st.addLayer(
                LineLayer(ROUTE_BEHIND_LAYER, ROUTE_SOURCE).withProperties(
                    lineColor(routeColor), lineOpacity(0.3f), lineWidth(2f), lineCap(Property.LINE_CAP_ROUND),
                ).withFilter(eq(get("kind"), literal(KIND_BEHIND))),
            )
            // Where it's going: dashed to the destination.
            st.addLayer(
                LineLayer(ROUTE_LAYER, ROUTE_SOURCE).withProperties(
                    lineColor(routeColor), lineOpacity(0.9f), lineWidth(3f),
                    lineDasharray(arrayOf(1.5f, 1.5f)), lineCap(Property.LINE_CAP_ROUND),
                ).withFilter(eq(get("kind"), literal(KIND_AHEAD))),
            )
            // No confirmed route: dotted line along its current track and turn.
            st.addLayer(
                LineLayer(ROUTE_PROJECTED_LAYER, ROUTE_SOURCE).withProperties(
                    lineColor(routeColor), lineOpacity(0.6f), lineWidth(2.5f),
                    lineDasharray(arrayOf(0.1f, 2f)), lineCap(Property.LINE_CAP_ROUND),
                ).withFilter(eq(get("kind"), literal(KIND_PROJECTED))),
            )
            // Both ends of a confirmed route: a ringed dot with the airport code, visible at any zoom.
            st.addLayer(
                CircleLayer(ROUTE_END_LAYER, ROUTE_SOURCE).withProperties(
                    circleRadius(6f),
                    circleColor(if (s.dark) "#0B0F1A" else "#FFFFFF"),
                    circleStrokeColor(routeColor),
                    circleStrokeWidth(3f),
                ).withFilter(eq(get("kind"), literal(KIND_END))),
            )
            st.addLayer(
                SymbolLayer(ROUTE_END_LABEL_LAYER, ROUTE_SOURCE).withProperties(
                    textField(get("code")),
                    textFont(arrayOf("Noto Sans Bold")),
                    textSize(14f),
                    textColor(routeColor),
                    textHaloColor(if (s.dark) "#E60B0F1A" else "#E6FFFFFF"),
                    textHaloWidth(2f),
                    textAnchor(Property.TEXT_ANCHOR_TOP),
                    textOffset(arrayOf(0f, 0.8f)),
                    textAllowOverlap(true),
                ).withFilter(eq(get("kind"), literal(KIND_END))),
            )
            st.addLayer(
                LineLayer(TRAIL_LAYER, TRAIL_SOURCE).withProperties(
                    lineColor(toColor(get("color"))),
                    lineWidth(get("w")),
                    lineOpacity(0.95f),
                    lineCap(Property.LINE_CAP_ROUND),
                    lineJoin(Property.LINE_JOIN_ROUND),
                ),
            )
            st.addLayer(
                CircleLayer(SCRUB_LAYER, SCRUB_SOURCE).withProperties(
                    circleRadius(7f),
                    circleColor("#FFFFFF"),
                    circleStrokeColor(accent),
                    circleStrokeWidth(3f),
                ),
            )
            st.addLayer(
                SymbolLayer(AIRCRAFT_LAYER, AIRCRAFT_SOURCE).withProperties(
                    iconImage(get("icon")),
                    iconRotate(get("rot")),
                    // Smaller when zoomed out, so a busy country view isn't a pile of overlapping planes.
                    iconSize(interpolate(linear(), zoom(), stop(3, product(get("size"), literal(0.5))), stop(8, get("size")))),
                    iconOpacity(get("op")),
                    iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                    iconAllowOverlap(true),
                    iconIgnorePlacement(true),
                    symbolSortKey(get("z")),
                    textFont(arrayOf("Noto Sans Bold")),
                    textSize(11f),
                    textColor(if (s.dark) "#E3E7F2" else "#1B2232"),
                    textHaloColor(if (s.dark) "#CC0B0F1A" else "#CCFFFFFF"),
                    textHaloWidth(1.4f),
                    textAnchor(Property.TEXT_ANCHOR_TOP),
                    textOffset(arrayOf(0f, 1.5f)),
                    textOptional(true),
                ),
            )
            st.addLayer(
                SymbolLayer(AIRCRAFT_LOGO_LAYER, AIRCRAFT_SOURCE).withProperties(
                    iconImage(get("logo")),
                    iconAnchor(Property.ICON_ANCHOR_BOTTOM),
                    iconOffset(arrayOf(0f, -16f)),
                    // Busy airspace: logos give way to each other rather than piling up.
                    iconAllowOverlap(false),
                    iconOpacity(get("op")),
                    symbolSortKey(get("z")),
                ).withFilter(has("logo")).apply { minZoom = LOGO_MIN_ZOOM },
            )
            logoImages.forEach { (id, bmp) -> st.addImage(id, bmp) }
            applyToggles(st)
            if (locationAllowed) enableLocation()
            onReady()
        }
    }

    fun setToggles(airports: Boolean, labels: Boolean) {
        showAirports = airports
        showLabels = labels
        loaded()?.let(::applyToggles)
    }

    private fun applyToggles(st: Style) {
        val vis = visibility(if (showAirports) Property.VISIBLE else Property.NONE)
        listOf(AIRPORT_LAYER_L, AIRPORT_LAYER_M, AIRPORT_LAYER_S).flatMap { listOf(it, "$it-dot") }.forEach { st.getLayer(it)?.setProperties(vis) }
        st.getLayer(AIRCRAFT_LAYER)?.setProperties(
            textField(if (showLabels) step(zoom(), literal(""), stop(7.5, get("label"))) else literal("")),
        )
        st.getLayer(AIRCRAFT_LOGO_LAYER)?.setProperties(visibility(if (showLabels) Property.VISIBLE else Property.NONE))
    }

    /** Radar frames as raster layers, all loaded; [showRadarFrame] flips opacity so animation doesn't refetch tiles. */
    fun setRadar(tiles: List<String>) {
        if (tiles == radarTiles) return
        val st = loaded()
        st?.let { s -> radarTiles.indices.forEach { i -> s.removeLayer("radar-$i"); s.removeSource("radar-$i") } }
        radarTiles = tiles
        st?.let(::addRadarLayers)
    }

    private fun addRadarLayers(st: Style) {
        // Radar sits on the base map and clouds, below hazards and our airport and aircraft layers.
        val ours = if (st.getLayer("hazard-fill") != null) "hazard-fill" else "$AIRPORT_LAYER_L-dot"
        radarTiles.forEachIndexed { i, url ->
            st.addSource(RasterSource("radar-$i", TileSet("2.2.0", url).apply { maxZoom = 7f }, 256))
            val layer = RasterLayer("radar-$i", "radar-$i").withProperties(rasterOpacity(if (i == radarTiles.lastIndex) RADAR_OPACITY else 0f))
            if (st.getLayer(ours) != null) st.addLayerBelow(layer, ours) else st.addLayer(layer)
        }
    }

    /** Satellite cloud layers (see [CloudTiles]), under the radar; an empty list removes them. */
    fun setClouds(tiles: List<String>) {
        if (tiles == cloudTiles) return
        val st = loaded()
        st?.let { s -> cloudTiles.indices.forEach { i -> s.removeLayer("clouds-$i"); s.removeSource("clouds-$i") } }
        cloudTiles = tiles
        st?.let(::addCloudLayers)
    }

    private fun addCloudLayers(st: Style) {
        val above = listOf("radar-0", "hazard-fill").firstOrNull { st.getLayer(it) != null } ?: "$AIRPORT_LAYER_L-dot"
        cloudTiles.forEachIndexed { i, url ->
            val (west, east) = CloudTiles.bounds[i]
            st.addSource(RasterSource("clouds-$i", TileSet("2.2.0", url).apply { maxZoom = CloudTiles.MAX_ZOOM; setBounds(west, -85f, east, 85f) }, 256))
            val layer = RasterLayer("clouds-$i", "clouds-$i").withProperties(rasterOpacity(0.85f))
            if (st.getLayer(above) != null) st.addLayerBelow(layer, above) else st.addLayer(layer)
        }
    }

    fun setHazards(fc: FeatureCollection) {
        hazards = fc
        loaded()?.getSourceAs<GeoJsonSource>(HAZARD_SOURCE)?.setGeoJson(fc)
    }

    /** SIGMET areas: a tinted fill, an outline, and the hazard and levels written in the middle. */
    private fun addHazardLayers(st: Style, dark: Boolean) {
        st.addSource(GeoJsonSource(HAZARD_SOURCE, hazards))
        val below = "$AIRPORT_LAYER_L-dot"
        fun add(layer: org.maplibre.android.style.layers.Layer) = if (st.getLayer(below) != null) st.addLayerBelow(layer, below) else st.addLayer(layer)
        add(FillLayer("hazard-fill", HAZARD_SOURCE).withProperties(fillColor(toColor(get("color"))), fillOpacity(0.16f)).withFilter(eq(geometryType(), literal("Polygon"))))
        add(LineLayer("hazard-line", HAZARD_SOURCE).withProperties(lineColor(toColor(get("color"))), lineWidth(1.5f), lineOpacity(0.9f)).withFilter(eq(geometryType(), literal("Polygon"))))
        add(
            SymbolLayer("hazard-label", HAZARD_SOURCE).withProperties(
                textField(get("label")),
                textFont(arrayOf("Noto Sans Bold")),
                textSize(11f),
                textColor(toColor(get("color"))),
                textHaloColor(if (dark) "#E60B0F1A" else "#E6FFFFFF"),
                textHaloWidth(1.5f),
            ).withFilter(eq(geometryType(), literal("Point"))).apply { minZoom = 3f },
        )
    }

    /** The visible map as a box; the whole world when zoomed far out. */
    fun visibleArea(): Area {
        val b = map.projection.visibleRegion.latLngBounds
        return if (b.longitudeSpan >= 180) Area(maxOf(b.latitudeSouth, -80.0), -180.0, minOf(b.latitudeNorth, 80.0), 180.0)
        else Area(b.latitudeSouth, b.longitudeWest, b.latitudeNorth, b.longitudeEast)
    }

    /** Map position under a screen point (px), for sampling the wind field. */
    fun latLngAt(x: Float, y: Float): LatLng = map.projection.fromScreenLocation(android.graphics.PointF(x, y))

    fun showRadarFrame(index: Int) {
        val st = loaded() ?: return
        radarTiles.indices.forEach { i -> st.getLayer("radar-$i")?.setProperties(rasterOpacity(if (i == index) RADAR_OPACITY else 0f)) }
    }

    @SuppressLint("MissingPermission")
    fun enableLocation() {
        val st = style ?: return
        val lc = map.locationComponent
        if (!lc.isLocationComponentActivated) {
            // The dot is for orientation, not navigation: a fix every 10 s or 25 m, no accuracy-circle animation.
            // The defaults (GPS every second, each fix animated) keep the GPU and GPS busy. Still high accuracy:
            // balanced never turns GPS on, so outdoors without Wi-Fi the dot would be a cell-tower guess.
            val request = LocationEngineRequest.Builder(10_000)
                .setPriority(LocationEngineRequest.PRIORITY_HIGH_ACCURACY)
                .setFastestInterval(5_000)
                .setDisplacement(25f)
                .build()
            val options = LocationComponentOptions.builder(context).accuracyAnimationEnabled(false).compassAnimationEnabled(false)
                .layerBelow(AIRCRAFT_LAYER) // planes stay tappable over the dot
                .build()
            lc.activateLocationComponent(
                LocationComponentActivationOptions.builder(context, st)
                    .useDefaultLocationEngine(true)
                    .locationEngineRequest(request)
                    .locationComponentOptions(options)
                    .build(),
            )
        }
        lc.isLocationComponentEnabled = true
        // Not COMPASS: the heading cone redraws the whole map on every compass sensor tick (~45 fps, constant CPU/GPU).
        lc.renderMode = RenderMode.NORMAL
        lc.setMaxAnimationFps(15)
    }

    @SuppressLint("MissingPermission")
    fun lastLocation(): LatLng? = runCatching {
        map.locationComponent.takeIf { it.isLocationComponentActivated }?.lastKnownLocation?.let { LatLng(it.latitude, it.longitude) }
    }.getOrNull()

    fun moveTo(lat: Double, lon: Double, zoom: Double? = null, animate: Boolean = true) {
        val pos = CameraPosition.Builder().target(LatLng(lat, lon)).apply { zoom?.let { zoom(it) } }.build()
        val u = CameraUpdateFactory.newCameraPosition(pos)
        if (animate) map.animateCamera(u, 700) else map.moveCamera(u)
    }

    /** Frame all [points], leaving [padding] (left, top, right, bottom px) clear for the sheet and controls. */
    fun fit(points: List<LatLng>, padding: IntArray) {
        if (points.size < 2) return
        val b = LatLngBounds.Builder().includes(points).build()
        map.animateCamera(CameraUpdateFactory.newLatLngBounds(b, padding[0], padding[1], padding[2], padding[3]), 900)
    }

    fun viewport(): Viewport? {
        val b = map.projection.visibleRegion.latLngBounds
        val c = map.cameraPosition.target ?: return null
        val r = Geo.distanceNm(c.latitude, c.longitude, b.latitudeNorth, b.longitudeEast)
        return Viewport(c.latitude, c.longitude, r.roundToInt().coerceIn(5, 250), map.cameraPosition.zoom)
    }

    /** Airline badges added to the style, by image id ("logo-QFA"); re-added whenever the style changes. */
    private val logoImages = HashMap<String, Bitmap>()

    /** Image ids of airlines whose badge is ready, for [aircraftFeatures]. */
    val logoIds: Set<String> get() = logoImages.keys

    fun addLogo(icao: String, logo: Bitmap) {
        val id = logoId(icao)
        if (id in logoImages) return
        // Wide wordmarks ("rex Regional Express") are unreadable at 22 dp; only near-square marks go on the map.
        if (logo.width > logo.height * 1.6f) return
        val badge = logoBadge(logo, context.resources.displayMetrics.density)
        logoImages[id] = badge
        loaded()?.addImage(id, badge)
    }

    /** Last collection pushed to each source; pushing the same object again would only make MapLibre re-tile and redraw. */
    private val pushed = HashMap<String, FeatureCollection>()

    fun render(aircraft: FeatureCollection, trail: FeatureCollection, route: FeatureCollection, scrub: FeatureCollection) {
        val st = loaded() ?: return
        for ((id, fc) in listOf(AIRCRAFT_SOURCE to aircraft, TRAIL_SOURCE to trail, ROUTE_SOURCE to route, SCRUB_SOURCE to scrub)) {
            if (pushed[id] === fc) continue
            pushed[id] = fc
            st.getSourceAs<GeoJsonSource>(id)?.setGeoJson(fc)
        }
    }

    /** The visible map plus half a screen each way, or null when it can't be a simple box (antimeridian, whole world). */
    fun cullArea(): Area? {
        val b = map.projection.visibleRegion.latLngBounds
        if (b.longitudeWest >= b.longitudeEast || b.longitudeSpan >= 180) return null
        val dLat = b.latitudeSpan / 2
        val dLon = b.longitudeSpan / 2
        return Area(b.latitudeSouth - dLat, b.longitudeWest - dLon, b.latitudeNorth + dLat, b.longitudeEast + dLon)
    }

    companion object {
        const val AIRCRAFT_SOURCE = "aircraft"
        const val AIRCRAFT_LAYER = "aircraft-layer"
        const val TRAIL_SOURCE = "trail"
        const val TRAIL_LAYER = "trail-layer"
        const val ROUTE_SOURCE = "route"
        const val ROUTE_LAYER = "route-layer"
        const val ROUTE_BEHIND_LAYER = "route-behind-layer"
        const val ROUTE_PROJECTED_LAYER = "route-projected-layer"
        const val ROUTE_END_LAYER = "route-end-layer"
        const val ROUTE_END_LABEL_LAYER = "route-end-label-layer"
        const val AIRCRAFT_LOGO_LAYER = "aircraft-logo-layer"
        const val SCRUB_SOURCE = "scrub"
        const val SCRUB_LAYER = "scrub-layer"
        const val AIRPORT_SOURCE = "airports"
        const val AIRPORT_LAYER_L = "airports-large"
        const val AIRPORT_LAYER_M = "airports-medium"
        const val AIRPORT_LAYER_S = "airports-small"
        private const val RADAR_OPACITY = 0.7f
        const val HAZARD_SOURCE = "hazards"

        /** Shared so an empty source isn't re-pushed every frame (see [render]). */
        private val EMPTY = FeatureCollection.fromFeatures(emptyList())

        /**
         * Milliseconds between map frames, aiming for about 1 dp of movement per frame for a fast jet (250 m/s).
         * Zoomed out a plane crawls under a pixel a second, so redrawing at 10 Hz there only burns battery.
         */
        fun frameIntervalMs(zoom: Double, lat: Double): Long {
            val metresPerDp = 78_271.5 * cos(Math.toRadians(lat)) / 2.0.pow(zoom) // MapLibre: the world is 512 dp wide at zoom 0
            return (metresPerDp / 250 * 1000).toLong().coerceIn(100, 2_000)
        }

        fun airportFeatures(list: List<Airport>): FeatureCollection = FeatureCollection.fromFeatures(
            list.filter { it.icao != null }.map { a ->
                Feature.fromGeometry(Point.fromLngLat(a.lon, a.lat)).apply {
                    addStringProperty("icao", a.icao)
                    addStringProperty("code", a.code)
                    addStringProperty("s", a.size.toString())
                }
            },
        )

        /** Pure: builds the aircraft layer data. Safe to call off the main thread. */
        const val LOGO_MIN_ZOOM = 7.5f
        const val KIND_AHEAD = "ahead"
        const val KIND_BEHIND = "behind"
        const val KIND_PROJECTED = "projected"
        const val KIND_END = "end"

        fun logoId(icao: String) = "logo-$icao"

        /**
         * The airline mark above an aircraft: the logo scaled into a 22 dp box with a thin white outline,
         * so dark marks still read on the dark map. Built once per airline.
         */
        fun logoBadge(logo: Bitmap, density: Float): Bitmap {
            val box = 22 * density
            val scale = box / maxOf(logo.width, logo.height)
            val w = (logo.width * scale).roundToInt().coerceAtLeast(1)
            val h = (logo.height * scale).roundToInt().coerceAtLeast(1)
            val halo = 1.5f * density
            val pad = halo.roundToInt() + 1
            val scaled = logo.scale(w, h)
            val mask = scaled.extractAlpha()
            val out = createBitmap(w + 2 * pad, h + 2 * pad)
            val c = Canvas(out)
            val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE }
            for (i in 0 until 12) {
                val ang = i * Math.PI / 6
                c.drawBitmap(mask, pad + (cos(ang) * halo).toFloat(), pad + (sin(ang) * halo).toFloat(), haloPaint)
            }
            c.drawBitmap(scaled, pad.toFloat(), pad.toFloat(), null)
            return out
        }

        fun aircraftFeatures(
            tracks: Collection<Track>,
            selected: String?,
            filters: Filters,
            now: Long,
            area: Area? = null,
            logoFor: (Aircraft) -> String? = { null },
        ): FeatureCollection =
            FeatureCollection.fromFeatures(tracks.mapNotNull { t ->
                val a = t.aircraft
                val isSel = a.hex == selected
                if (!isSel && !filters.matches(a)) return@mapNotNull null
                val (lat, lon) = t.displayPosition(now)
                if (!isSel && area != null && !area.contains(lat, lon)) return@mapNotNull null
                val shape = Shape.of(a)
                val stale = t.staleness(now) > TrafficRepository.FADE_AFTER_MS
                Feature.fromGeometry(Point.fromLngLat(lon, lat)).apply {
                    addStringProperty("hex", a.hex)
                    addStringProperty("icon", iconName(shape, if (isSel) AltitudeColors.SELECTED_BAND else AltitudeColors.band(a)))
                    addNumberProperty("rot", a.track ?: 0.0)
                    addNumberProperty("size", shape.size * if (isSel) 1.3f else 1f)
                    addNumberProperty("op", if (stale) 0.35 else 1.0)
                    // Selected on top, then higher aircraft over lower ones.
                    addNumberProperty("z", if (isSel) 1_000_000 else (a.altBaro ?: 0))
                    addStringProperty("label", a.callsign ?: a.registration ?: "")
                    logoFor(a)?.let { addStringProperty("logo", it) }
                }
            })

        /** Trail as short segments, each coloured by the altitude at its start. Unselected trails are thinner. */
        fun trailFeatures(tracks: Collection<Track>, selected: Track?, now: Long): FeatureCollection {
            val features = ArrayList<Feature>()
            for (track in tracks) addTrail(features, track, now, if (track === selected) 3.5f else 1.5f)
            if (selected != null && tracks.none { it === selected }) addTrail(features, selected, now, 3.5f)
            return if (features.isEmpty()) EMPTY else FeatureCollection.fromFeatures(features)
        }

        /** A past flight's path, coloured by altitude like a selected plane's trail. */
        fun pastTrailFeatures(pts: List<TrailPoint>): FeatureCollection {
            val out = ArrayList<Feature>()
            if (pts.size >= 2) addRuns(out, pts.dropLast(1), pts.last().lat, pts.last().lon, 3.5f)
            return if (out.isEmpty()) EMPTY else FeatureCollection.fromFeatures(out)
        }

        /** Labelled points at a past flight's known ends. */
        fun endFeatures(vararg airports: Airport?): FeatureCollection = FeatureCollection.fromFeatures(airports.filterNotNull().map { ap ->
            Feature.fromGeometry(Point.fromLngLat(ap.lon, ap.lat)).apply {
                addStringProperty("kind", KIND_END)
                addStringProperty("code", ap.iata ?: ap.icao)
            }
        })

        /** One line per run of same-coloured segments: a cruising plane's trail is a handful of features, not thousands. */
        private fun addTrail(out: MutableList<Feature>, track: Track, now: Long, width: Float) {
            if (track.trail.isEmpty()) return
            val (curLat, curLon) = track.displayPosition(now)
            addRuns(out, track.trail, curLat, curLon, width)
        }

        /** [pts] then a final segment to ([curLat], [curLon]). */
        private fun addRuns(out: MutableList<Feature>, pts: List<TrailPoint>, curLat: Double, curLon: Double, width: Float) {
            if (pts.isEmpty()) return
            var run = arrayListOf(Point.fromLngLat(pts[0].lon, pts[0].lat))
            var runBand = Int.MIN_VALUE
            fun flush() {
                if (run.size < 2) return
                out += Feature.fromGeometry(LineString.fromLngLats(unwrapped(run))).apply {
                    addStringProperty("color", AltitudeColors.hexForBand(runBand))
                    addNumberProperty("w", width)
                }
            }
            for (i in pts.indices) {
                val a = pts[i]
                val bLat = if (i + 1 < pts.size) pts[i + 1].lat else curLat
                val bLon = if (i + 1 < pts.size) pts[i + 1].lon else curLon
                val b = Point.fromLngLat(bLon, bLat)
                // A gap in coverage (~50 nm): don't draw a false straight line across it.
                if (abs(bLat - a.lat) > 0.8 || abs((bLon - a.lon + 540) % 360 - 180) * cos(Math.toRadians(a.lat)) > 0.8) {
                    flush(); run = arrayListOf(b); runBand = Int.MIN_VALUE; continue
                }
                val band = if (a.onGround) AltitudeColors.GROUND_BAND else a.alt?.let { AltitudeColors.bandFor(it) } ?: AltitudeColors.UNKNOWN_BAND
                if (band != runBand && run.size >= 2) { flush(); run = arrayListOf(run.last()) }
                runBand = band
                run += b
            }
            flush()
        }

        /** SIGMETs still in force as polygons plus a label point at each one's centre. */
        fun hazardFeatures(list: List<Sigmet>, nowSec: Long): FeatureCollection {
            val out = ArrayList<Feature>()
            for (h in list) for (area in h.areas) {
                if (area.size < 3 || (h.validTimeTo ?: Long.MAX_VALUE) < nowSec) continue
                val ring = unwrapped(area.map { Point.fromLngLat(it.lon, it.lat) }.let { if (it.first() == it.last()) it else it + it.first() })
                val color = hazardColor(h.hazard)
                out += Feature.fromGeometry(Polygon.fromLngLats(listOf(ring))).apply { addStringProperty("color", color) }
                out += Feature.fromGeometry(Point.fromLngLat(ring.map { it.longitude() }.average(), ring.map { it.latitude() }.average())).apply {
                    addStringProperty("color", color)
                    addStringProperty("label", hazardLabel(h))
                }
            }
            return if (out.isEmpty()) EMPTY else FeatureCollection.fromFeatures(out)
        }

        fun hazardColor(hazard: String) = when (hazard) {
            "TURB" -> "#FF9F0A"
            "ICE" -> "#64D2FF"
            "TS" -> "#FF453A"
            "VA" -> "#BF5AF2"
            "TC" -> "#FF375F"
            "MTW" -> "#FFD60A"
            else -> "#8E8E93"
        }

        /** "Severe turbulence" over "FL250–FL380". Flight levels stay in feet whatever the units, as pilots use them. */
        fun hazardLabel(h: Sigmet): String {
            val sev = if (h.qualifier == "SEV") "Severe " else ""
            val name = when (h.hazard) {
                "TURB" -> "${sev}turbulence"
                "ICE" -> "${sev}icing"
                "TS" -> "Thunderstorms"
                "VA" -> "Volcanic ash"
                "TC" -> "Cyclone ${h.qualifier.orEmpty().lowercase().replaceFirstChar { it.uppercase() }}".trim()
                "MTW" -> "${sev}mountain waves"
                else -> h.hazard
            }.replaceFirstChar { it.uppercase() }
            fun fl(ft: Int) = if (ft <= 0) "SFC" else "FL%03d".format(ft / 100)
            val levels = when {
                h.base != null && h.top != null -> "${fl(h.base)}–${fl(h.top)}"
                h.top != null -> "Up to ${fl(h.top)}"
                h.base != null -> "Above ${fl(h.base)}"
                else -> null
            }
            return listOfNotNull(name, levels).joinToString("\n")
        }

        fun scrubFeatures(p: TrailPoint?): FeatureCollection =
            p?.let { FeatureCollection.fromFeature(Feature.fromGeometry(Point.fromLngLat(it.lon, it.lat))) } ?: EMPTY

        /**
         * The selected plane's path, as up to two lines:
         * - [KIND_BEHIND]: origin to where the trail starts, when the route checks out and the flown path
         *   (FlightHistory) didn't reach back to the origin.
         * - [KIND_AHEAD]: from the plane, curving from its current heading onto the great circle to the destination.
         * - or [KIND_PROJECTED] without a confirmed route: the next 8 minutes at its current speed and turn rate.
         * - [KIND_END]: a point at each end of a confirmed route, labelled with its airport code.
         */
        fun routeFeatures(track: Track?, originAirport: Airport?, destAirport: Airport?, now: Long): FeatureCollection {
            if (track == null) return EMPTY
            val a = track.aircraft
            val origin = originAirport?.let { it.lat to it.lon }
            val dest = destAirport?.let { it.lat to it.lon }
            val out = ArrayList<Feature>(4)
            fun line(kind: String, pts: List<Point>) {
                if (pts.size >= 2) out += Feature.fromGeometry(LineString.fromLngLats(unwrapped(pts))).apply { addStringProperty("kind", kind) }
            }
            for (ap in listOfNotNull(originAirport, destAirport)) {
                out += Feature.fromGeometry(Point.fromLngLat(ap.lon, ap.lat)).apply {
                    addStringProperty("kind", KIND_END)
                    addStringProperty("code", ap.iata ?: ap.icao)
                }
            }
            if (origin != null) {
                track.trail.firstOrNull()?.let { first ->
                    if (Geo.distanceNm(origin.first, origin.second, first.lat, first.lon) > 15) {
                        line(KIND_BEHIND, greatCircle(origin.first, origin.second, first.lat, first.lon))
                    }
                }
            }
            if (!a.onGround) {
                val (lat, lon) = track.displayPosition(now)
                when {
                    dest != null -> line(KIND_AHEAD, pathAhead(lat, lon, a.track, dest))
                    (a.groundSpeed ?: 0.0) > 60 && a.track != null -> line(KIND_PROJECTED, projected(lat, lon, a.track, a.groundSpeed!!, a.trackRate))
                }
            }
            return if (out.isEmpty()) EMPTY else FeatureCollection.fromFeatures(out)
        }

        /** Longitudes made continuous (179 then 181, not -179) so MapLibre draws across the antimeridian the short way. */
        fun unwrapped(pts: List<Point>): List<Point> {
            var prev = pts.firstOrNull()?.longitude() ?: return pts
            return pts.map { p ->
                var lon = p.longitude()
                while (lon - prev > 180) lon -= 360
                while (lon - prev < -180) lon += 360
                prev = lon
                if (lon == p.longitude()) p else Point.fromLngLat(lon, p.latitude())
            }
        }

        /** Great circle as ~20 nm segments (max 64), including both ends. */
        fun greatCircle(lat0: Double, lon0: Double, lat1: Double, lon1: Double): List<Point> {
            var lat = lat0; var lon = lon0
            val steps = (Geo.distanceNm(lat0, lon0, lat1, lon1) / 20).toInt().coerceIn(1, 64)
            val pts = ArrayList<Point>(steps + 1).apply { add(Point.fromLngLat(lon, lat)) }
            for (i in 1..steps) {
                val remaining = Geo.distanceNm(lat, lon, lat1, lon1)
                val next = Geo.destination(lat, lon, Geo.bearing(lat, lon, lat1, lon1), remaining / (steps - i + 1))
                lat = next.first; lon = next.second
                pts += Point.fromLngLat(lon, lat)
            }
            return pts
        }

        /**
         * Turns from [heading] towards the destination at about a standard-rate turn's radius (5° per nm),
         * then follows the great circle, so a plane pointing away turns round instead of the line jumping sideways.
         */
        fun pathAhead(lat0: Double, lon0: Double, heading: Double?, dest: Pair<Double, Double>): List<Point> {
            var lat = lat0; var lon = lon0
            val direct = greatCircle(lat0, lon0, dest.first, dest.second)
            // Near the airport planes fly downwind legs and vectors we can't predict, and the turn radius
            // would orbit the field; a straight line is the honest picture there.
            if (heading == null || Geo.distanceNm(lat0, lon0, dest.first, dest.second) < 40) return direct
            var hdg: Double = heading
            val pts = arrayListOf(Point.fromLngLat(lon, lat))
            repeat(60) {
                val diff = (Geo.bearing(lat, lon, dest.first, dest.second) - hdg + 540) % 360 - 180
                if (abs(diff) < 3) return pts.apply { addAll(greatCircle(lat, lon, dest.first, dest.second).drop(1)) }
                hdg += diff.coerceIn(-5.0, 5.0)
                val next = Geo.destination(lat, lon, hdg, 1.0)
                lat = next.first; lon = next.second
                pts += Point.fromLngLat(lon, lat)
            }
            return direct // never settled on course: don't draw a loop
        }

        /** Dead-reckoned 8 minutes ahead, following the current turn (capped at standard rate, 3°/s). */
        fun projected(lat0: Double, lon0: Double, heading: Double, gs: Double, turnRate: Double?, minutes: Int = 8): List<Point> {
            var lat = lat0; var lon = lon0; var hdg = heading
            val rate = (turnRate ?: 0.0).coerceIn(-3.0, 3.0).takeIf { abs(it) > 0.1 } ?: 0.0
            val stepS = 15.0
            val pts = arrayListOf(Point.fromLngLat(lon, lat))
            repeat((minutes * 60 / stepS).toInt()) { i ->
                // A turn doesn't go on forever: stop turning after ~90°, the usual size of a course change.
                if (abs(rate) * stepS * i < 90) hdg += rate * stepS
                val next = Geo.destination(lat, lon, hdg, gs * stepS / 3600)
                lat = next.first; lon = next.second
                pts += Point.fromLngLat(lon, lat)
            }
            return pts
        }
    }
}

/** A lat/lon box for culling off-screen aircraft. */
data class Area(val south: Double, val west: Double, val north: Double, val east: Double) {
    fun contains(lat: Double, lon: Double) = lat in south..north && lon in west..east
}
