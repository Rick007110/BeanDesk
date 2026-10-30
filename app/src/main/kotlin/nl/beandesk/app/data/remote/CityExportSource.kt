package nl.beandesk.app.data.remote

import nl.beandesk.core.export.CityExport

/** Where the app gets a city export: the database, or the bundled sample in debug builds. */
interface CityExportSource {
    suspend fun fetch(city: String): CityExport

    /**
     * What changed in [city] since the export generated at [since] (see `city_changes`), or null
     * when this source can only give whole cities.
     */
    suspend fun fetchChanges(city: String, since: String): CityExport? = null
}
