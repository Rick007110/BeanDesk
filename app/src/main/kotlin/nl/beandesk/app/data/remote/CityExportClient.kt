package nl.beandesk.app.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import nl.beandesk.core.export.CityExport
import nl.beandesk.core.export.CityExportParser

/** Downloads `<base>/<city>.json`, the file the import script uploads to Supabase Storage. */
class CityExportClient(
    private val http: HttpClient,
    private val baseUrl: String,
) : CityExportSource {
    override suspend fun fetch(city: String): CityExport {
        val body = http.get("${baseUrl.trimEnd('/')}/$city.json").bodyAsText()
        return CityExportParser.parse(body)
    }
}
