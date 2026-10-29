package nl.beandesk.app.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import nl.beandesk.core.export.CityExport
import nl.beandesk.core.export.CityExportParser

/**
 * Downloads the city export from the database function `city_export`
 * (supabase/migrations), so it always includes the latest reports.
 */
class CityExportClient(
    private val http: HttpClient,
    private val supabaseUrl: String,
    private val anonKey: String,
) : CityExportSource {
    override suspend fun fetch(city: String): CityExport {
        val body = http.get("$supabaseUrl/rest/v1/rpc/city_export") {
            parameter("p_city", city)
            header("apikey", anonKey)
            header(HttpHeaders.Authorization, "Bearer $anonKey")
        }.bodyAsText()
        return CityExportParser.parse(body)
    }
}
