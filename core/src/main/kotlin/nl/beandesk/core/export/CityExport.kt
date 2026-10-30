package nl.beandesk.core.export

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import nl.beandesk.core.model.Category
import nl.beandesk.core.model.CommunityFacts
import nl.beandesk.core.model.LaptopPolicy
import nl.beandesk.core.model.LatLng
import nl.beandesk.core.model.OsmFacts
import nl.beandesk.core.model.Place
import nl.beandesk.core.model.PowerLevel
import nl.beandesk.core.model.Rating
import nl.beandesk.core.model.WifiStatus

/** All places of one city, as written by the import script (`importer/beandesk_import/export.py`). */
data class CityExport(
    val city: String,
    val timezone: String,
    val generatedAt: String,
    val attribution: String,
    val center: LatLng?,
    val places: List<ExportedPlace>,
)

data class ExportedPlace(val place: Place, val updatedAt: String)

class UnsupportedExportException(message: String) : Exception(message)

object CityExportParser {
    const val SUPPORTED_VERSION = 1

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): CityExport {
        val dto = json.decodeFromString<CityExportDto>(text)
        if (dto.version != SUPPORTED_VERSION) {
            throw UnsupportedExportException("City export version ${dto.version} is not supported")
        }
        return CityExport(
            city = dto.city,
            timezone = dto.timezone,
            generatedAt = dto.generatedAt,
            attribution = dto.attribution,
            center = dto.center?.let { LatLng(it.lat, it.lng) },
            // Categories from a newer export are skipped instead of failing the whole sync.
            places = dto.places.mapNotNull { it.toExportedPlace() },
        )
    }

    private fun PlaceDto.toExportedPlace(): ExportedPlace? {
        val category = enumOrNull<Category>(category) ?: return null
        val place = Place(
            id = id,
            category = category,
            name = name,
            address = address,
            location = LatLng(lat, lng),
            openingHours = openingHours,
            osm = OsmFacts(wifi = osm.wifi, wifiFree = osm.wifiFree, power = osm.power),
            community = community?.let {
                CommunityFacts(
                    wifi = enumOrNull<WifiStatus>(it.wifi),
                    power = enumOrNull<PowerLevel>(it.power),
                    quiet = it.quiet,
                    laptopPolicy = enumOrNull<LaptopPolicy>(it.laptopPolicy),
                    closedReports = it.closedReports,
                )
            },
            rating = rating?.takeIf { it.count > 0 }?.let { Rating(it.average, it.count) },
        )
        return ExportedPlace(place, updatedAt)
    }

    /** Export values are the lowercase enum names, e.g. "not_on_weekends". */
    private inline fun <reified T : Enum<T>> enumOrNull(value: String?): T? =
        value?.let { v -> enumValues<T>().firstOrNull { it.name.equals(v, ignoreCase = true) } }
}

@Serializable
private data class CityExportDto(
    val version: Int,
    val city: String,
    val timezone: String,
    @SerialName("generated_at") val generatedAt: String,
    val attribution: String,
    val center: LatLngDto? = null,
    val places: List<PlaceDto>,
)

@Serializable
private data class LatLngDto(val lat: Double, val lng: Double)

@Serializable
private data class PlaceDto(
    val id: Long,
    val category: String,
    val name: String,
    val address: String? = null,
    val lat: Double,
    val lng: Double,
    @SerialName("opening_hours") val openingHours: String? = null,
    val osm: OsmFactsDto = OsmFactsDto(),
    val community: CommunityDto? = null,
    val rating: RatingDto? = null,
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
private data class RatingDto(val average: Double, val count: Int)

@Serializable
private data class OsmFactsDto(
    val wifi: Boolean? = null,
    @SerialName("wifi_free") val wifiFree: Boolean? = null,
    val power: Boolean? = null,
)

@Serializable
private data class CommunityDto(
    val wifi: String? = null,
    val power: String? = null,
    val quiet: Boolean? = null,
    @SerialName("laptop_policy") val laptopPolicy: String? = null,
    @SerialName("closed_reports") val closedReports: Int = 0,
)
