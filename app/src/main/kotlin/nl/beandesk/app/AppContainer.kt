package nl.beandesk.app

import android.content.Context
import androidx.room.Room
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import nl.beandesk.app.data.PlaceRepository
import nl.beandesk.app.data.local.BeanDeskDatabase
import nl.beandesk.app.data.location.LocationRepository
import nl.beandesk.app.data.remote.AreaSearchClient
import nl.beandesk.app.data.remote.AreaSearchSource
import nl.beandesk.app.data.remote.CityExportClient
import nl.beandesk.app.data.remote.CityExportSource
import nl.beandesk.app.data.remote.NoAreaSearch
import nl.beandesk.app.data.remote.SampleCityExportSource
import nl.beandesk.app.data.remote.ApiBackend
import nl.beandesk.app.data.remote.PrefsTokenStore
import nl.beandesk.app.icon.AppIconManager
import nl.beandesk.app.settings.AppSettings

/** Manual dependency injection; small enough that a DI framework is not worth it yet. */
class AppContainer(context: Context) {
    private val database = Room.databaseBuilder(context, BeanDeskDatabase::class.java, "beandesk.db")
        // The local database is a cache of the city export, so it can be rebuilt at any time.
        .fallbackToDestructiveMigration(dropAllTables = true)
        .build()

    private val http by lazy {
        HttpClient(OkHttp) {
            expectSuccess = true
            install(HttpTimeout)
        }
    }

    // USE_SAMPLE_DATA is always false in release builds (see app/build.gradle.kts).
    private val exportSource: CityExportSource =
        if (BuildConfig.USE_SAMPLE_DATA) {
            SampleCityExportSource(context.assets)
        } else {
            CityExportClient(http, BuildConfig.API_URL)
        }

    private val searchSource: AreaSearchSource =
        if (BuildConfig.USE_SAMPLE_DATA) {
            NoAreaSearch
        } else {
            AreaSearchClient(http, BuildConfig.API_URL)
        }

    // Sample places do not exist on the server, so reports about them stay on the phone.
    val backend: ApiBackend? =
        if (!BuildConfig.USE_SAMPLE_DATA && BuildConfig.API_URL.isNotBlank()) {
            ApiBackend(http, BuildConfig.API_URL, PrefsTokenStore(context))
        } else {
            null
        }

    val placeRepository = PlaceRepository(
        dao = database.placeDao(),
        source = exportSource,
        searchSource = searchSource,
        backend = backend,
    )

    val locationRepository = LocationRepository(context)

    val appIconManager = AppIconManager(context)

    val settings = AppSettings(context)
}
