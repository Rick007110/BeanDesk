package nl.beandesk.app.data

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import nl.beandesk.app.data.local.CityEntity
import nl.beandesk.app.data.local.PlaceDao
import nl.beandesk.app.data.local.PlaceEntity
import nl.beandesk.app.data.remote.CityExportSource
import nl.beandesk.app.data.remote.SupabaseBackend
import nl.beandesk.core.export.ExportedPlace
import nl.beandesk.core.model.Category
import nl.beandesk.core.model.CommunityFacts
import nl.beandesk.core.model.LaptopPolicy
import nl.beandesk.core.model.LatLng
import nl.beandesk.core.model.OsmFacts
import nl.beandesk.core.model.Place
import nl.beandesk.core.model.PowerLevel
import nl.beandesk.core.model.Report
import nl.beandesk.core.model.WifiStatus
import nl.beandesk.core.model.withReport

data class CityInfo(val timezone: String, val attribution: String, val center: LatLng?)

/**
 * Places live in the local database; the network only refreshes it.
 * Searching and filtering never leave the phone, so the user's location never does either.
 *
 * [backend] is null without a Supabase project (sample data): reports then stay on the phone.
 */
class PlaceRepository(
    private val dao: PlaceDao,
    private val source: CityExportSource,
    private val backend: SupabaseBackend?,
) {
    val sendsReports: Boolean get() = backend != null

    fun places(city: String): Flow<List<Place>> =
        dao.observePlaces(city).map { rows -> rows.mapNotNull { it.toPlace() } }

    fun cityInfo(city: String): Flow<CityInfo?> =
        dao.observeCity(city).map { row ->
            row?.let {
                val center = if (it.centerLat != null && it.centerLng != null) LatLng(it.centerLat, it.centerLng) else null
                CityInfo(it.timezone, it.attribution, center)
            }
        }

    /**
     * Downloads the whole city again (a few hundred KB), then the reports made since the export
     * was written, so new reports show up within minutes instead of the next day.
     */
    suspend fun refresh(city: String) {
        val export = source.fetch(city)
        dao.replaceCity(
            city = CityEntity(
                city = export.city,
                timezone = export.timezone,
                generatedAt = export.generatedAt,
                attribution = export.attribution,
                centerLat = export.center?.lat,
                centerLng = export.center?.lng,
            ),
            places = export.places.map { it.toEntity(export.city) },
        )
        backend?.let { syncCommunity(it, since = export.generatedAt) }
    }

    /**
     * Sends the report (when there is a backend) and applies it on the phone straight away,
     * the way the database trigger does, so the user sees the effect at once.
     */
    suspend fun report(report: Report) {
        backend?.sendReport(report)
        val place = dao.place(report.placeId)?.toPlace() ?: return
        setCommunity(report.placeId, place.community.withReport(report))
    }

    private suspend fun syncCommunity(backend: SupabaseBackend, since: String) {
        // A "+" in a URL query reads as a space, so use the "Z" form of UTC.
        val rows = try {
            backend.communityChangesSince(since.replace("+00:00", "Z"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The export is already in place; recent reports can wait for the next refresh.
            Log.w(TAG, "Fetching recent reports failed", e)
            return
        }
        rows.forEach { row ->
            setCommunity(
                id = row.placeId,
                facts = CommunityFacts(
                    wifi = enumOrNull<WifiStatus>(row.wifi),
                    power = enumOrNull<PowerLevel>(row.power),
                    quiet = row.quiet,
                    laptopPolicy = enumOrNull<LaptopPolicy>(row.laptopPolicy),
                    closedReports = row.closedReports,
                ),
            )
        }
    }

    private suspend fun setCommunity(id: Long, facts: CommunityFacts) = dao.setCommunity(
        id = id,
        wifi = facts.wifi?.name,
        power = facts.power?.name,
        quiet = facts.quiet,
        laptopPolicy = facts.laptopPolicy?.name,
        closedReports = facts.closedReports,
    )

    private companion object {
        const val TAG = "PlaceRepository"
    }
}

private inline fun <reified T : Enum<T>> enumOrNull(value: String?): T? =
    value?.let { v -> enumValues<T>().firstOrNull { it.name.equals(v, ignoreCase = true) } }

private fun ExportedPlace.toEntity(city: String): PlaceEntity {
    val community = place.community
    return PlaceEntity(
        id = place.id,
        city = city,
        category = place.category.name,
        name = place.name,
        address = place.address,
        lat = place.location.lat,
        lng = place.location.lng,
        openingHours = place.openingHours,
        osmWifi = place.osm.wifi,
        osmWifiFree = place.osm.wifiFree,
        osmPower = place.osm.power,
        hasCommunity = community != null,
        communityWifi = community?.wifi?.name,
        communityPower = community?.power?.name,
        communityQuiet = community?.quiet,
        communityLaptopPolicy = community?.laptopPolicy?.name,
        closedReports = community?.closedReports ?: 0,
        updatedAt = updatedAt,
    )
}

private fun PlaceEntity.toPlace(): Place? {
    val category = Category.entries.firstOrNull { it.name == category } ?: return null
    return Place(
        id = id,
        category = category,
        name = name,
        address = address,
        location = LatLng(lat, lng),
        openingHours = openingHours,
        osm = OsmFacts(wifi = osmWifi, wifiFree = osmWifiFree, power = osmPower),
        community = if (!hasCommunity) {
            null
        } else {
            CommunityFacts(
                wifi = WifiStatus.entries.firstOrNull { it.name == communityWifi },
                power = PowerLevel.entries.firstOrNull { it.name == communityPower },
                quiet = communityQuiet,
                laptopPolicy = LaptopPolicy.entries.firstOrNull { it.name == communityLaptopPolicy },
                closedReports = closedReports,
            )
        },
    )
}
