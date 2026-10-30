package nl.beandesk.app.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import nl.beandesk.core.model.Report
import nl.beandesk.core.model.WifiStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The app's side of the server's API (backend/src/api.ts), against a fake server. */
class ApiBackendTest {
    private class MemoryTokens(override var token: String? = null) : TokenStore

    /** Answers by method and path; records "METHOD path token body" for each call. */
    private class FakeServer(val answer: (method: HttpMethod, path: String, token: String?) -> Pair<HttpStatusCode, String>) {
        val calls = mutableListOf<String>()
        var accounts = 0

        val client = HttpClient(MockEngine { request ->
            val token = request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer ")
            val body = String(request.body.toByteArray())
            calls += listOfNotNull(request.method.value, request.url.encodedPath, token, body.ifEmpty { null }).joinToString(" ")
            val (status, text) = if (request.url.encodedPath == "/v1/accounts") {
                HttpStatusCode.Created to """{"token":"token-${++accounts}"}"""
            } else {
                answer(request.method, request.url.encodedPath, token)
            }
            respond(text, status, headersOf(HttpHeaders.ContentType, "application/json"))
        }) {
            expectSuccess = true
        }
    }

    private val report = Report(placeId = 7, wifi = WifiStatus.FAST, stars = 4)

    @Test
    fun `the first report makes an account and keeps its token`() = runBlocking {
        val server = FakeServer { _, _, _ -> HttpStatusCode.Created to """{"ok":true}""" }
        val tokens = MemoryTokens()
        val backend = ApiBackend(server.client, "https://beandesk.example/", tokens)
        assertFalse(backend.hasAccount())

        backend.sendReport(report)
        backend.sendReport(report.copy(stars = null))

        assertEquals("token-1", tokens.token)
        assertTrue(backend.hasAccount())
        assertEquals(
            listOf(
                "POST /v1/accounts",
                """POST /v1/reports token-1 {"place_id":7,"wifi":"fast","power":null,"quiet":null,"laptop_policy":null,"permanently_closed":false,"stars":4}""",
                """POST /v1/reports token-1 {"place_id":7,"wifi":"fast","power":null,"quiet":null,"laptop_policy":null,"permanently_closed":false,"stars":null}""",
            ),
            server.calls,
        )
    }

    @Test
    fun `a report from an account that is gone starts a new one`() = runBlocking {
        val server = FakeServer { _, _, token ->
            if (token == "old") HttpStatusCode.Unauthorized to """{"error":"account_gone"}""" else HttpStatusCode.Created to "{}"
        }
        val tokens = MemoryTokens("old")
        ApiBackend(server.client, "https://beandesk.example", tokens).sendReport(report)
        assertEquals("token-1", tokens.token)
        assertEquals(listOf("POST /v1/reports old", "POST /v1/accounts", "POST /v1/reports token-1"), server.calls.map { it.substringBefore(" {") })
    }

    @Test
    fun `past the daily limit the report says so`() = runBlocking {
        val server = FakeServer { _, _, _ -> HttpStatusCode.TooManyRequests to """{"error":"rate_limit"}""" }
        val error = assertFailsWith<RateLimitedException> {
            ApiBackend(server.client, "https://beandesk.example", MemoryTokens("t")).sendReport(report)
        }
        // PlacesViewModel shows its own message for this.
        assertTrue(error.message!!.contains("rate_limit"))
    }

    @Test
    fun `own reports and stars, newest first`() = runBlocking {
        val server = FakeServer { _, _, _ ->
            HttpStatusCode.OK to """
                {"reports": [{"place_id": 7, "reported_at": "2026-10-30T10:00:00.000Z", "wifi": "fast", "power": null,
                              "quiet": true, "laptop_policy": "not_on_weekends", "permanently_closed": false, "place_name": "Koffie & Werk"}],
                 "ratings": [{"place_id": 8, "rating": 4, "rated_at": "2026-10-30T11:00:00.000Z", "place_name": null}]}
            """.trimIndent()
        }
        val mine = ApiBackend(server.client, "https://beandesk.example", MemoryTokens("t")).myContributions()
        assertEquals(listOf(null, "Koffie & Werk"), mine.map { it.placeName })
        assertEquals(4, mine[0].report.stars)
        assertEquals(WifiStatus.FAST, mine[1].report.wifi)
        assertEquals(true, mine[1].report.quiet)
        assertEquals("GET /v1/me/contributions t", server.calls.single())
    }

    @Test
    fun `without an account there is nothing to show or delete`() = runBlocking {
        val server = FakeServer { _, _, _ -> HttpStatusCode.OK to "{}" }
        val backend = ApiBackend(server.client, "https://beandesk.example", MemoryTokens())
        assertEquals(emptyList(), backend.myContributions())
        backend.deleteAccount()
        assertEquals(emptyList(), server.calls)
    }

    @Test
    fun `deleting forgets the token, also when the server already forgot the account`() = runBlocking {
        for (status in listOf(HttpStatusCode.NoContent, HttpStatusCode.Unauthorized)) {
            val server = FakeServer { _, _, _ -> status to "" }
            val tokens = MemoryTokens("t")
            ApiBackend(server.client, "https://beandesk.example", tokens).deleteAccount()
            assertNull(tokens.token)
            assertEquals("DELETE /v1/me t", server.calls.single())
        }
    }

    @Test
    fun `contributions of a gone account are empty and the token goes`() = runBlocking {
        val server = FakeServer { _, _, _ -> HttpStatusCode.Unauthorized to """{"error":"account_gone"}""" }
        val tokens = MemoryTokens("t")
        assertEquals(emptyList(), ApiBackend(server.client, "https://beandesk.example", tokens).myContributions())
        assertNull(tokens.token)
    }
}
