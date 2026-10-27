package app.contrail

import android.app.Application
import app.contrail.data.SearchRepository
import app.contrail.data.enrich.AirlineLogos
import app.contrail.data.enrich.EnrichmentRepository
import app.contrail.data.static.StaticData
import app.contrail.data.user.SettingsRepository
import app.contrail.data.weather.WeatherRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import app.contrail.domain.Fmt
import app.contrail.data.feed.ADSB_FI
import app.contrail.data.feed.ADSB_LOL
import app.contrail.data.feed.AdsbSource
import app.contrail.data.feed.FeedScheduler
import app.contrail.data.feed.FlightHistory
import app.contrail.data.feed.TrafficRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import app.contrail.ui.map.CloudTiles
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import org.maplibre.android.MapLibre
import org.maplibre.android.module.http.HttpRequestUtil

class ContrailApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        MapLibre.getInstance(this)
        // MapLibre's own client (20 requests per host, like its default) plus the satellite cloud recolouring.
        HttpRequestUtil.setOkHttpClient(
            OkHttpClient.Builder()
                .dispatcher(Dispatcher().apply { maxRequestsPerHost = 20 })
                .addInterceptor(CloudTiles.interceptor)
                .build(),
        )
        container = AppContainer(this)
        StaticData.preload(this, appScope)
        appScope.launch { container.settings.units.collect { Fmt.units = it } }
    }

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}

/** Manual dependency wiring; one instance per process. */
class AppContainer(val app: Application) {
    val clock: () -> Long = System::currentTimeMillis

    val http = HttpClient(OkHttp) {
        install(UserAgent) { agent = "Contrail/${BuildInfo.VERSION} (Android; personal flight tracker)" }
        install(HttpTimeout) {
            connectTimeoutMillis = 6_000
            requestTimeoutMillis = 10_000
        }
    }

    val scheduler = FeedScheduler(listOf(AdsbSource(ADSB_LOL, http, clock), AdsbSource(ADSB_FI, http, clock)), clock)
    val traffic = TrafficRepository(scheduler, clock)
    val enrichment = EnrichmentRepository(http)
    val logos = AirlineLogos(http, app.cacheDir)
    val history = FlightHistory(http, clock)
    val weather = WeatherRepository(http)
    val search = SearchRepository(traffic)
    val settings = SettingsRepository(app)
}

object BuildInfo {
    const val VERSION = "0.1"
}
