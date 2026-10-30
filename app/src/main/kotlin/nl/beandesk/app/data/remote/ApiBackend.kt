package nl.beandesk.app.data.remote

import android.content.Context
import androidx.core.content.edit
import io.ktor.client.HttpClient
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import nl.beandesk.core.model.LaptopPolicy
import nl.beandesk.core.model.PowerLevel
import nl.beandesk.core.model.Report
import nl.beandesk.core.model.WifiStatus
import java.time.Instant

/** Where the phone keeps its account's token; null without an account. */
interface TokenStore {
    var token: String?
}

class PrefsTokenStore(context: Context) : TokenStore {
    private val prefs = context.applicationContext.getSharedPreferences("account", Context.MODE_PRIVATE)

    override var token: String?
        get() = prefs.getString(KEY_TOKEN, null)
        set(value) = prefs.edit { if (value == null) remove(KEY_TOKEN) else putString(KEY_TOKEN, value) }

    private companion object {
        const val KEY_TOKEN = "token"
    }
}

/** Past the daily report limit of the account (enforce_report_rate_limit in the database). */
class RateLimitedException(cause: Throwable) : Exception("rate_limit: too many reports today", cause)

/**
 * The phone's account on BeanDesk's own server (backend/): made anonymously at the first report
 * and kept on the phone as a token. Who may do what is decided by the database.
 */
class ApiBackend(private val http: HttpClient, apiUrl: String, private val tokens: TokenStore) {
    private val base = apiUrl.trimEnd('/')

    suspend fun sendReport(report: Report) {
        val body = json.encodeToString(ReportBody.serializer(), ReportBody.from(report))
        try {
            withAccount { token -> http.post("$base/v1/reports") { asJson(body, token) } }
        } catch (e: ResponseException) {
            if (e.response.status == HttpStatusCode.TooManyRequests) throw RateLimitedException(e)
            throw e
        }
    }

    /** Whether this phone has an account, which only a first report creates. */
    fun hasAccount(): Boolean = tokens.token != null

    /** What this phone's account reported and rated, newest first; empty without an account. */
    suspend fun myContributions(): List<Contribution> {
        val token = tokens.token ?: return emptyList()
        val text = try {
            http.get("$base/v1/me/contributions") { bearer(token) }.bodyAsText()
        } catch (e: ResponseException) {
            if (e.response.status != HttpStatusCode.Unauthorized) throw e
            // The account is gone; the next report makes a new one.
            tokens.token = null
            return emptyList()
        }
        val mine = json.decodeFromString(ContributionsBody.serializer(), text)
        return (mine.reports.map { it.toContribution() } + mine.ratings.map { it.toContribution() }).sortedByDescending { it.at }
    }

    /** Deletes the account with everything it reported and rated; a next report starts a new one. */
    suspend fun deleteAccount() {
        val token = tokens.token ?: return
        try {
            http.delete("$base/v1/me") { bearer(token) }
        } catch (e: ResponseException) {
            // Already gone is gone.
            if (e.response.status != HttpStatusCode.Unauthorized) throw e
        }
        tokens.token = null
    }

    /**
     * Runs [call] with the account's token, making the account first when there is none. When
     * the server no longer knows it (deleted, or removed after 30 days without reports), once
     * more with a new account.
     */
    private suspend fun <T> withAccount(call: suspend (String) -> T): T {
        val token = tokens.token ?: createAccount()
        return try {
            call(token)
        } catch (e: ResponseException) {
            if (e.response.status != HttpStatusCode.Unauthorized) throw e
            tokens.token = null
            call(createAccount())
        }
    }

    private suspend fun createAccount(): String {
        val text = http.post("$base/v1/accounts").bodyAsText()
        val token = json.parseToJsonElement(text).jsonObject.getValue("token").jsonPrimitive.content
        tokens.token = token
        return token
    }

    private fun HttpRequestBuilder.bearer(token: String) = header(HttpHeaders.Authorization, "Bearer $token")

    private fun HttpRequestBuilder.asJson(body: String, token: String) {
        bearer(token)
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}

/** Something the user sent: facts about a place, or stars ([Report.stars]), at [at]. */
data class Contribution(val placeName: String?, val at: Instant, val report: Report)

/** Database enum values are the lowercase Kotlin names, such as "not_on_weekends". */
@Serializable
private data class ReportBody(
    @SerialName("place_id") val placeId: Long,
    val wifi: String?,
    val power: String?,
    val quiet: Boolean?,
    @SerialName("laptop_policy") val laptopPolicy: String?,
    @SerialName("permanently_closed") val permanentlyClosed: Boolean,
    val stars: Int?,
) {
    companion object {
        fun from(report: Report) = ReportBody(
            placeId = report.placeId,
            wifi = report.wifi?.name?.lowercase(),
            power = report.power?.name?.lowercase(),
            quiet = report.quiet,
            laptopPolicy = report.laptopPolicy?.name?.lowercase(),
            permanentlyClosed = report.permanentlyClosed,
            stars = report.stars,
        )
    }
}

@Serializable
private data class ContributionsBody(val reports: List<MyReport> = emptyList(), val ratings: List<MyRating> = emptyList())

@Serializable
private data class MyReport(
    @SerialName("place_id") val placeId: Long,
    @SerialName("reported_at") val reportedAt: String,
    val wifi: String? = null,
    val power: String? = null,
    val quiet: Boolean? = null,
    @SerialName("laptop_policy") val laptopPolicy: String? = null,
    @SerialName("permanently_closed") val permanentlyClosed: Boolean = false,
    @SerialName("place_name") val placeName: String? = null,
) {
    fun toContribution() = Contribution(
        placeName = placeName,
        at = Instant.parse(reportedAt),
        report = Report(
            placeId = placeId,
            wifi = WifiStatus.entries.firstOrNull { it.name.equals(wifi, ignoreCase = true) },
            power = PowerLevel.entries.firstOrNull { it.name.equals(power, ignoreCase = true) },
            quiet = quiet,
            laptopPolicy = LaptopPolicy.entries.firstOrNull { it.name.equals(laptopPolicy, ignoreCase = true) },
            permanentlyClosed = permanentlyClosed,
        ),
    )
}

@Serializable
private data class MyRating(
    @SerialName("place_id") val placeId: Long,
    val rating: Int,
    @SerialName("rated_at") val ratedAt: String,
    @SerialName("place_name") val placeName: String? = null,
) {
    fun toContribution() = Contribution(placeName, Instant.parse(ratedAt), Report(placeId = placeId, stars = rating))
}
