package nl.beandesk.app.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.encodeURLPathPart
import nl.beandesk.core.export.CityExport
import nl.beandesk.core.export.CityExportParser

/**
 * Downloads the city export from BeanDesk's server (backend/), which the database builds live
 * (city_export), so it always has the latest reports; or only what changed since (city_changes).
 */
class CityExportClient(private val http: HttpClient, apiUrl: String) : CityExportSource {
    private val base = apiUrl.trimEnd('/')

    override suspend fun fetch(city: String): CityExport =
        CityExportParser.parse(http.get("$base/v1/cities/${city.encodeURLPathPart()}").bodyAsText())

    override suspend fun fetchChanges(city: String, since: String): CityExport =
        CityExportParser.parse(
            http.get("$base/v1/cities/${city.encodeURLPathPart()}/changes") { parameter("since", since) }.bodyAsText(),
        )
}
