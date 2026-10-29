package nl.beandesk.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
abstract class PlaceDao {
    @Query("SELECT * FROM places WHERE city = :city")
    abstract fun observePlaces(city: String): Flow<List<PlaceEntity>>

    @Query("SELECT * FROM cities WHERE city = :city")
    abstract fun observeCity(city: String): Flow<CityEntity?>

    @Query("SELECT * FROM places WHERE id = :id")
    abstract suspend fun place(id: Long): PlaceEntity?

    /** Replaces what users say about a place; enum values are stored by name. */
    @Query(
        """
        UPDATE places SET hasCommunity = 1, communityWifi = :wifi, communityPower = :power,
            communityQuiet = :quiet, communityLaptopPolicy = :laptopPolicy, closedReports = :closedReports
        WHERE id = :id
        """,
    )
    abstract suspend fun setCommunity(
        id: Long,
        wifi: String?,
        power: String?,
        quiet: Boolean?,
        laptopPolicy: String?,
        closedReports: Int,
    )

    /** Swaps in a complete city export in one transaction, so the UI never sees half a city. */
    @Transaction
    open suspend fun replaceCity(city: CityEntity, places: List<PlaceEntity>) {
        deletePlaces(city.city)
        insertPlaces(places)
        upsertCity(city)
    }

    @Query("DELETE FROM places WHERE city = :city")
    protected abstract suspend fun deletePlaces(city: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertPlaces(places: List<PlaceEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun upsertCity(city: CityEntity)
}
