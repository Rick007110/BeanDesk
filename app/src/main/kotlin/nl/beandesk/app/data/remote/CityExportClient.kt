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
 * Downloads the city export from the database function `city_export` (supabase/migrations), so
 * it always includes the latest reports; or, with `city_changes`, only what changed since.
 */
class CityExportClient(
    private val http: HttpClient,
    private val supabaseUrl: String,
    private val anonKey: String,
) : CityExportSource {
    override suspend fun fetch(city: String): CityExport = call("city_export", "p_city" to city)

    override suspend fun fetchChanges(city: String, since: String): CityExport =
        call("city_changes", "p_city" to city, "p_since" to since)

    private suspend fun call(function: String, vararg parameters: Pair<String, String>): CityExport {
        val body = http.get("$supabaseUrl/rest/v1/rpc/$function") {
            parameters.forEach { (name, value) -> parameter(name, value) }
            header("apikey", anonKey)
            header(HttpHeaders.Authorization, "Bearer $anonKey")
        }.bodyAsText()
        return CityExportParser.parse(body)
    }
}
