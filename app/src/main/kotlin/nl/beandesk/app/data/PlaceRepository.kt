package nl.beandesk.app.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import nl.beandesk.app.data.local.CityEntity
import nl.beandesk.app.data.local.PlaceDao
import nl.beandesk.app.data.local.PlaceEntity
import nl.beandesk.app.data.remote.CityExportClient
import nl.beandesk.core.export.ExportedPlace
import nl.beandesk.core.model.Category
import nl.beandesk.core.model.CommunityFacts
import nl.beandesk.core.model.LaptopPolicy
import nl.beandesk.core.model.LatLng
import nl.beandesk.core.model.OsmFacts
import nl.beandesk.core.model.Place
import nl.beandesk.core.model.PowerLevel
import nl.beandesk.core.model.WifiStatus

data class CityInfo(val timezone: String, val attribution: String, val center: LatLng?)

/**
 * Places live in the local database; the network only refreshes it.
 * Searching and filtering never leave the phone, so the user's location never does either.
 */
class PlaceRepository(
    private val dao: PlaceDao,
    private val client: CityExportClient,
) {
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
     * Downloads the whole city again. The export is a few hundred KB, so this is fine for the
     * skeleton; the MVP adds delta sync on `updated_at` (see README).
     */
    suspend fun refresh(city: String) {
        val export = client.fetch(city)
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
    }
}

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
