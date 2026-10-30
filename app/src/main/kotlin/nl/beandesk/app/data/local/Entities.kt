package nl.beandesk.app.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One row per place per downloaded area. [city] is the key of that area: a city slug such as
 * "utrecht", or "zoek:<label>" for a search. Areas can overlap, so a place can be in several.
 */
@Entity(tableName = "places", primaryKeys = ["city", "id"], indices = [Index("id")])
data class PlaceEntity(
    val id: Long,
    val city: String,
    val category: String,
    val name: String,
    val address: String?,
    val lat: Double,
    val lng: Double,
    val openingHours: String?,
    val osmWifi: Boolean?,
    val osmWifiFree: Boolean?,
    val osmPower: Boolean?,
    // Community columns are all null when nobody has reported anything yet.
    val hasCommunity: Boolean,
    val communityWifi: String?,
    val communityPower: String?,
    val communityQuiet: Boolean?,
    val communityLaptopPolicy: String?,
    val closedReports: Int,
    // Milliseconds since 1970; null when unknown.
    val lastReportAt: Long?,
    // "MORNING:3:0,WEEKEND:0:2": quiet and busy reports per part of the day (see PlaceRepository).
    val communityDayparts: String?,
    // Stars from users; null average without ratings.
    val ratingAverage: Double?,
    val ratingCount: Int,
    val updatedAt: String,
)

/** One row per downloaded area (city or search): what the app needs besides the places themselves. */
@Entity(tableName = "cities")
data class CityEntity(
    @PrimaryKey val city: String,
    // What the app shows: "Utrecht", "Amersfoort" or "Oudegracht 10, 3511AM Utrecht".
    val label: String,
    val timezone: String,
    val generatedAt: String,
    val attribution: String,
    val centerLat: Double?,
    val centerLng: Double?,
)
