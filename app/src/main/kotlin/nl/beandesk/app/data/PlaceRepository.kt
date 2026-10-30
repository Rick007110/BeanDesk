package nl.beandesk.app.data

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import nl.beandesk.app.data.local.CityEntity
import nl.beandesk.app.data.local.PlaceDao
import nl.beandesk.app.data.local.PlaceEntity
import nl.beandesk.app.data.remote.AreaSearchSource
import nl.beandesk.app.data.remote.CityExportSource
import nl.beandesk.app.data.remote.SupabaseBackend
import nl.beandesk.core.export.CityExport
import nl.beandesk.core.export.ExportedPlace
import nl.beandesk.core.model.Category
import nl.beandesk.core.model.CommunityFacts
import nl.beandesk.core.model.LaptopPolicy
import nl.beandesk.core.model.LatLng
import nl.beandesk.core.model.OsmFacts
import nl.beandesk.core.model.Place
import nl.beandesk.core.model.PowerLevel
import nl.beandesk.core.model.Rating
import nl.beandesk.core.model.Report
import nl.beandesk.core.model.WifiStatus
import nl.beandesk.core.model.withReport
import nl.beandesk.core.model.withStars

data class CityInfo(val label: String, val timezone: String, val attribution: String, val center: LatLng?)

/** A downloaded set of places: a city, or the surroundings of a searched town or address. */
data class Area(
    // Where its places are stored locally (PlaceEntity.city).
    val key: String,
    val label: String,
    // What was searched; null for a city.
    val query: String? = null,
)

/**
 * Places live in the local database; the network only refreshes it.
 * Filtering and sorting happen on the phone, so the user's location never leaves it. A search
 * sends only the typed town or address.
 *
 * [backend] is null without a Supabase project (sample data): reports then stay on the phone.
 */
class PlaceRepository(
    private val dao: PlaceDao,
    private val source: CityExportSource,
    private val searchSource: AreaSearchSource,
    private val backend: SupabaseBackend?,
) {
    val sendsReports: Boolean get() = backend != null

    fun places(city: String): Flow<List<Place>> =
        dao.observePlaces(city).map { rows -> rows.mapNotNull { it.toPlace() } }

    fun cityInfo(city: String): Flow<CityInfo?> =
        dao.observeCity(city).map { row ->
            row?.let {
                val center = if (it.centerLat != null && it.centerLng != null) LatLng(it.centerLat, it.centerLng) else null
                CityInfo(it.label, it.timezone, it.attribution, center)
            }
        }

    /**
     * Downloads the whole city again (some tens of KB). The export is built live by the
     * database, so it already includes everyone's latest reports.
     */
    suspend fun refresh(city: String) {
        val export = source.fetch(city)
        store(key = city, label = city.replaceFirstChar { it.titlecase() }, export = export)
    }

    /**
     * Finds the places around a town or address. The first search of an area makes the backend
     * fetch it from OpenStreetMap; after that it comes straight from the database.
     * Throws [nl.beandesk.app.data.remote.SearchException].
     */
    suspend fun search(query: String): Area {
        val export = searchSource.search(query)
        val area = Area(key = SEARCH_PREFIX + export.city.lowercase(), label = export.city, query = query)
        store(key = area.key, label = area.label, export = export)
        dao.pruneSearches(keep = KEPT_SEARCHES)
        return area
    }

    private suspend fun store(key: String, label: String, export: CityExport) {
        dao.replaceCity(
            city = CityEntity(
                city = key,
                label = label,
                timezone = export.timezone,
                generatedAt = export.generatedAt,
                attribution = export.attribution,
                centerLat = export.center?.lat,
                centerLng = export.center?.lng,
            ),
            places = export.places.map { it.toEntity(key) },
        )
    }

    /**
     * Sends the report (when there is a backend) and applies it on the phone straight away,
     * the way the database trigger does, so the user sees the effect at once.
     */
    suspend fun report(report: Report) {
        backend?.sendReport(report)
        val place = dao.place(report.placeId)?.toPlace() ?: return
        if (report.hasFacts) setCommunity(report.placeId, place.community.withReport(report))
        report.stars?.let { stars ->
            val rating = place.rating.withStars(stars)
            dao.setRating(report.placeId, rating.average, rating.count)
        }
    }

    /**
     * After a report, someone has just looked at the place: a good moment for the backend to
     * pick up changes in OpenStreetMap around it. Best effort; it can take a while.
     */
    suspend fun refreshAround(placeId: Long) {
        try {
            searchSource.refreshAround(placeId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.i(TAG, "Refreshing around place $placeId failed", e)
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
}

private const val TAG = "PlaceRepository"
private const val SEARCH_PREFIX = "zoek:"

// Searched areas stay on the phone for offline use; the oldest go once there are more.
private const val KEPT_SEARCHES = 10

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
        ratingAverage = place.rating?.average,
        ratingCount = place.rating?.count ?: 0,
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
        rating = ratingAverage?.takeIf { ratingCount > 0 }?.let { Rating(it, ratingCount) },
    )
}
