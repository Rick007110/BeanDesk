package nl.beandesk.app.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "places", indices = [Index("city")])
data class PlaceEntity(
    @PrimaryKey val id: Long,
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
    val updatedAt: String,
)

/** One row per downloaded city: what the app needs besides the places themselves. */
@Entity(tableName = "cities")
data class CityEntity(
    @PrimaryKey val city: String,
    val timezone: String,
    val generatedAt: String,
    val attribution: String,
    val centerLat: Double?,
    val centerLng: Double?,
)
