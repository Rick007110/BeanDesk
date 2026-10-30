package nl.beandesk.app.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import nl.beandesk.core.export.CityExport
import nl.beandesk.core.export.CityExportParser

/** Calls the edge function `search` with the anon key, like the city export. */
class AreaSearchClient(
    private val http: HttpClient,
    supabaseUrl: String,
    private val anonKey: String,
) : AreaSearchSource {
    private val url = "$supabaseUrl/functions/v1/search"

    override suspend fun search(query: String): CityExport {
        val body = try {
            post(buildJsonObject { put("q", JsonPrimitive(query)) }.toString())
        } catch (e: CancellationException) {
            throw e
        } catch (e: ResponseException) {
            throw SearchException(e.response.status.toFailure(), e)
        } catch (e: Exception) {
            throw SearchException(SearchFailure.FAILED, e)
        }
        return CityExportParser.parse(body)
    }

    override suspend fun refreshAround(placeId: Long) {
        post(buildJsonObject { put("refresh_place_id", JsonPrimitive(placeId)) }.toString())
    }

    private suspend fun post(json: String): String =
        http.post(url) {
            // The first search of an area waits for OpenStreetMap, which can take a while.
            timeout {
                requestTimeoutMillis = 150_000
                socketTimeoutMillis = 150_000
            }
            header("apikey", anonKey)
            header(HttpHeaders.Authorization, "Bearer $anonKey")
            contentType(ContentType.Application.Json)
            setBody(json)
        }.bodyAsText()

    private fun HttpStatusCode.toFailure(): SearchFailure = when (this) {
        HttpStatusCode.NotFound -> SearchFailure.NOT_FOUND
        // 429: everyone together asked OpenStreetMap too much; 502: it could not be reached.
        HttpStatusCode.TooManyRequests, HttpStatusCode.BadGateway -> SearchFailure.UNAVAILABLE
        else -> SearchFailure.FAILED
    }
}
