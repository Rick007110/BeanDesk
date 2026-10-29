package nl.beandesk.app

import android.content.Context
import androidx.room.Room
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import nl.beandesk.app.data.PlaceRepository
import nl.beandesk.app.data.local.BeanDeskDatabase
import nl.beandesk.app.data.remote.CityExportClient
import nl.beandesk.app.data.remote.CityExportSource
import nl.beandesk.app.data.remote.SampleCityExportSource

/** Manual dependency injection; small enough that a DI framework is not worth it yet. */
class AppContainer(context: Context) {
    private val database = Room.databaseBuilder(context, BeanDeskDatabase::class.java, "beandesk.db")
        // The local database is a cache of the city export, so it can be rebuilt at any time.
        .fallbackToDestructiveMigration(dropAllTables = true)
        .build()

    // USE_SAMPLE_DATA is always false in release builds (see app/build.gradle.kts).
    private val exportSource: CityExportSource =
        if (BuildConfig.USE_SAMPLE_DATA) {
            SampleCityExportSource(context.assets)
        } else {
            CityExportClient(HttpClient(OkHttp) { expectSuccess = true }, BuildConfig.CITY_EXPORT_BASE_URL)
        }

    val placeRepository = PlaceRepository(
        dao = database.placeDao(),
        source = exportSource,
    )
}
