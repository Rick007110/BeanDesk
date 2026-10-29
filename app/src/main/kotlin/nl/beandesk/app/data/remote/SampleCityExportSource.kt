package nl.beandesk.app.data.remote

import android.content.res.AssetManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nl.beandesk.core.export.CityExport
import nl.beandesk.core.export.CityExportParser

/**
 * Reads `assets/sample/<city>.json`, which only debug builds contain (app/src/debug/assets).
 * Lets the app be tried on an emulator before there is a Supabase project.
 */
class SampleCityExportSource(private val assets: AssetManager) : CityExportSource {
    override suspend fun fetch(city: String): CityExport = withContext(Dispatchers.IO) {
        val text = assets.open("sample/$city.json").bufferedReader().use { it.readText() }
        CityExportParser.parse(text)
    }
}
