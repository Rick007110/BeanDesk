package nl.beandesk.app.data.remote

import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import nl.beandesk.core.model.LaptopPolicy
import nl.beandesk.core.model.PowerLevel
import nl.beandesk.core.model.Report
import nl.beandesk.core.model.WifiStatus
import java.time.Instant
import java.time.OffsetDateTime

/**
 * Reports go straight into the database as an anonymous user; nobody needs an account.
 * Row level security and column grants (supabase/migrations) decide what is allowed.
 */
class SupabaseBackend(url: String, anonKey: String) {
    private val client = createSupabaseClient(supabaseUrl = url, supabaseKey = anonKey) {
        install(Auth)
        install(Postgrest)
    }

    suspend fun sendReport(report: Report) {
        signInIfNeeded()
        if (report.hasFacts) client.from("place_reports").insert(ReportRow.from(report))
        // One rating per user per place: a new one replaces the old.
        report.stars?.let { stars ->
            client.from("place_ratings").upsert(RatingRow(report.placeId, stars)) { onConflict = "place_id,user_id" }
        }
    }

    /** The first report creates an anonymous account; supabase-kt keeps its session on the phone. */
    private suspend fun signInIfNeeded() {
        client.auth.awaitInitialization()
        if (client.auth.currentSessionOrNull() == null) client.auth.signInAnonymously()
    }

    /** Whether this phone has an account, which only a first report creates. */
    suspend fun hasAccount(): Boolean {
        client.auth.awaitInitialization()
        return client.auth.currentSessionOrNull() != null
    }

    /**
     * What this phone's account reported and rated, newest first, at most [limit] of each; empty
     * without an account. Row level security only lets the account read its own rows.
     */
    suspend fun myContributions(limit: Long = 100): List<Contribution> {
        if (!hasAccount()) return emptyList()
        val reports = client.from("place_reports")
            .select(Columns.raw("place_id, reported_at, wifi, power, quiet, laptop_policy, permanently_closed, places(name)")) {
                order("reported_at", Order.DESCENDING)
                limit(limit)
            }
            .decodeList<MyReportRow>()
        val ratings = client.from("place_ratings")
            .select(Columns.raw("place_id, rating, rated_at, places(name)")) {
                order("rated_at", Order.DESCENDING)
                limit(limit)
            }
            .decodeList<MyRatingRow>()
        return (reports.map { it.toContribution() } + ratings.map { it.toContribution() }).sortedByDescending { it.at }
    }

    /**
     * Deletes the account with everything it reported and rated (the database cascades and
     * recounts), and forgets the session on the phone. A next report starts a new account.
     */
    suspend fun deleteAccount() {
        if (!hasAccount()) return
        client.postgrest.rpc("delete_my_account")
        client.auth.clearSession()
    }
}

/** Something the user sent: facts about a place, or stars ([Report.stars]), at [at]. */
data class Contribution(val placeName: String?, val at: Instant, val report: Report)

@Serializable
private data class PlaceNameRow(val name: String)

@Serializable
private data class MyReportRow(
    @SerialName("place_id") val placeId: Long,
    @SerialName("reported_at") val reportedAt: String,
    val wifi: String? = null,
    val power: String? = null,
    val quiet: Boolean? = null,
    @SerialName("laptop_policy") val laptopPolicy: String? = null,
    @SerialName("permanently_closed") val permanentlyClosed: Boolean = false,
    val places: PlaceNameRow? = null,
) {
    fun toContribution() = Contribution(
        placeName = places?.name,
        at = OffsetDateTime.parse(reportedAt).toInstant(),
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
private data class MyRatingRow(
    @SerialName("place_id") val placeId: Long,
    val rating: Int,
    @SerialName("rated_at") val ratedAt: String,
    val places: PlaceNameRow? = null,
) {
    fun toContribution() = Contribution(
        placeName = places?.name,
        at = OffsetDateTime.parse(ratedAt).toInstant(),
        report = Report(placeId = placeId, stars = rating),
    )
}

/** user_id comes from the database (auth.uid()). */
@Serializable
private data class RatingRow(
    @SerialName("place_id") val placeId: Long,
    val rating: Int,
)

/** Only the columns users may set; user_id and reported_at come from the database. */
@Serializable
private data class ReportRow(
    @SerialName("place_id") val placeId: Long,
    val wifi: String?,
    val power: String?,
    val quiet: Boolean?,
    @SerialName("laptop_policy") val laptopPolicy: String?,
    @SerialName("permanently_closed") val permanentlyClosed: Boolean,
) {
    companion object {
        // Database enum values are the lowercase Kotlin names, such as "not_on_weekends".
        fun from(report: Report) = ReportRow(
            placeId = report.placeId,
            wifi = report.wifi?.name?.lowercase(),
            power = report.power?.name?.lowercase(),
            quiet = report.quiet,
            laptopPolicy = report.laptopPolicy?.name?.lowercase(),
            permanentlyClosed = report.permanentlyClosed,
        )
    }
}
