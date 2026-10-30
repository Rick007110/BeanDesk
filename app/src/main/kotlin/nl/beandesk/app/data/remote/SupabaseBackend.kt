package nl.beandesk.app.data.remote

import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.from
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import nl.beandesk.core.model.Report

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
