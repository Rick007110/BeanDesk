package nl.beandesk.app.data.remote

import nl.beandesk.core.export.CityExport

/** Where the app gets a city export: Supabase Storage, or the bundled sample in debug builds. */
fun interface CityExportSource {
    suspend fun fetch(city: String): CityExport
}
