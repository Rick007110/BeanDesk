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

    // A place in several areas has a copy in each; they are all the same.
    @Query("SELECT * FROM places WHERE id = :id LIMIT 1")
    abstract suspend fun place(id: Long): PlaceEntity?

    /** Replaces what users say about a place, in every area it is in; enum values are stored by name. */
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

    /** Keeps the [keep] most recent searches, so the cache does not grow with every search. */
    @Transaction
    open suspend fun pruneSearches(keep: Int) {
        deleteSearchPlacesBeyond(keep)
        deleteSearchesBeyond(keep)
    }

    @Query(
        """
        DELETE FROM places WHERE city IN (
            SELECT city FROM cities WHERE city LIKE 'zoek:%' ORDER BY generatedAt DESC LIMIT -1 OFFSET :keep
        )
        """,
    )
    protected abstract suspend fun deleteSearchPlacesBeyond(keep: Int)

    @Query(
        """
        DELETE FROM cities WHERE city IN (
            SELECT city FROM cities WHERE city LIKE 'zoek:%' ORDER BY generatedAt DESC LIMIT -1 OFFSET :keep
        )
        """,
    )
    protected abstract suspend fun deleteSearchesBeyond(keep: Int)

    @Query("DELETE FROM places WHERE city = :city")
    protected abstract suspend fun deletePlaces(city: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertPlaces(places: List<PlaceEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun upsertCity(city: CityEntity)
}
