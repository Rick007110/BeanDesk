package nl.beandesk.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

// No schema export yet: the database only caches the city export and is rebuilt on schema changes.
@Database(entities = [PlaceEntity::class, CityEntity::class], version = 2, exportSchema = false)
abstract class BeanDeskDatabase : RoomDatabase() {
    abstract fun placeDao(): PlaceDao
}
