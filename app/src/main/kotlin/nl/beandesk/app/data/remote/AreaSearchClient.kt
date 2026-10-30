package nl.beandesk.app.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.timeout
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import nl.beandesk.core.export.CityExport
import nl.beandesk.core.export.CityExportParser

/** Searches through BeanDesk's server (backend/, POST /v1/search). */
class AreaSearchClient(private val http: HttpClient, apiUrl: String) : AreaSearchSource {
    private val url = "${apiUrl.trimEnd('/')}/v1/search"

    override suspend fun search(query: String): SearchResult {
        val body = try {
            post(buildJsonObject { put("q", JsonPrimitive(query)) }.toString())
        } catch (e: CancellationException) {
            throw e
        } catch (e: ResponseException) {
            throw SearchException(e.response.status.toFailure(), e)
        } catch (e: Exception) {
            throw SearchException(SearchFailure.FAILED, e)
        }
        // "search": {"kind": "naam" | "woonplaats" | ...} next to the export fields.
        val kind = Json.parseToJsonElement(body).jsonObject["search"]?.jsonObject?.get("kind")?.jsonPrimitive?.content
        return SearchResult(CityExportParser.parse(body), byName = kind == "naam")
    }

    private suspend fun post(json: String): String =
        http.post(url) {
            // A lookup at PDOK and a database query: seconds, but give a slow network some room.
            timeout {
                requestTimeoutMillis = 20_000
                socketTimeoutMillis = 20_000
            }
            contentType(ContentType.Application.Json)
            setBody(json)
        }.bodyAsText()

    private fun HttpStatusCode.toFailure(): SearchFailure = when (this) {
        HttpStatusCode.NotFound -> SearchFailure.NOT_FOUND
        // 429: too many searches from this address; 502: the address lookup (PDOK) could not be reached.
        HttpStatusCode.TooManyRequests, HttpStatusCode.BadGateway -> SearchFailure.UNAVAILABLE
        else -> SearchFailure.FAILED
    }
}
