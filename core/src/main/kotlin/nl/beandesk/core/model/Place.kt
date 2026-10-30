package nl.beandesk.core.model

enum class Category { CAFE, LIBRARY, COWORKING }

enum class WifiStatus { NONE, YES, SLOW, FAST }

enum class PowerLevel { NONE, FEW, MANY }

enum class LaptopPolicy { ALLOWED, NOT_ON_WEEKENDS, NOT_ALLOWED }

data class LatLng(val lat: Double, val lng: Double)

/**
 * What OpenStreetMap says about a place. Kept apart from [CommunityFacts] so the app can
 * show "volgens OSM" and "volgens gebruikers" separately (ODbL, see the plan).
 */
data class OsmFacts(
    val wifi: Boolean? = null,
    val wifiFree: Boolean? = null,
    val power: Boolean? = null,
)

/** The latest reports from users, as aggregated by the backend. */
data class CommunityFacts(
    val wifi: WifiStatus? = null,
    val power: PowerLevel? = null,
    val quiet: Boolean? = null,
    val laptopPolicy: LaptopPolicy? = null,
    val closedReports: Int = 0,
)

/** Stars from users as a laptop spot: the average of 1 to 5 and how many gave one. */
data class Rating(val average: Double, val count: Int)

data class Place(
    val id: Long,
    val category: Category,
    val name: String,
    val address: String?,
    val location: LatLng,
    val openingHours: String?,
    val osm: OsmFacts = OsmFacts(),
    val community: CommunityFacts? = null,
    val rating: Rating? = null,
) {
    /** User reports win over OSM tags; null means nobody knows yet. */
    val hasWifi: Boolean?
        get() = when (community?.wifi) {
            null -> osm.wifi
            WifiStatus.NONE -> false
            else -> true
        }

    val hasPower: Boolean?
        get() = when (community?.power) {
            null -> osm.power
            PowerLevel.NONE -> false
            else -> true
        }

    /** Hidden in the app until a moderator confirms the closure or the import removes it. */
    val isReportedClosed: Boolean
        get() = (community?.closedReports ?: 0) >= CLOSED_REPORTS_TO_HIDE

    companion object {
        const val CLOSED_REPORTS_TO_HIDE = 3
    }
}
