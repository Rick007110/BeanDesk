package nl.beandesk.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import nl.beandesk.app.data.local.BeanDeskDatabase
import nl.beandesk.app.data.remote.CityExportSource
import nl.beandesk.app.data.remote.NoAreaSearch
import nl.beandesk.app.data.remote.SampleCityExportSource
import nl.beandesk.core.export.CityExport
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.time.OffsetDateTime

/** Downloading a city: whole the first time and once a week, otherwise only what changed. */
@RunWith(AndroidJUnit4::class)
class PlaceRepositoryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var database: BeanDeskDatabase
    private lateinit var sample: CityExport

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, BeanDeskDatabase::class.java).build()
        sample = runBlocking { SampleCityExportSource(context.assets).fetch("utrecht") }
    }

    @After
    fun tearDown() {
        database.close()
    }

    /** The sample as the database would give it; records what was asked. */
    private inner class FakeSource(var generatedAt: String = now()) : CityExportSource {
        val asked = mutableListOf<String>()
        var changes: CityExport? = null
        var changesFail = false

        override suspend fun fetch(city: String): CityExport {
            asked += "all"
            return sample.copy(generatedAt = generatedAt)
        }

        override suspend fun fetchChanges(city: String, since: String): CityExport? {
            asked += "changes since $since"
            if (changesFail) throw IOException("No city_changes in this database")
            return changes
        }
    }

    private fun now(minus: Long = 0) = OffsetDateTime.now().minusDays(minus).toString()

    private fun repository(source: CityExportSource) =
        PlaceRepository(database.placeDao(), source, NoAreaSearch, backend = null)

    private fun names(repository: PlaceRepository) = runBlocking { repository.places("utrecht").first().map { it.name }.toSet() }

    @Test
    fun theFirstDownloadIsWholeAndTheNextOnlyWhatChanged() = runBlocking {
        val source = FakeSource()
        val repository = repository(source)
        repository.refresh("utrecht")
        assertEquals(listOf("all"), source.asked)
        assertEquals(12, names(repository).size)

        // One place renamed, one gone.
        val renamed = sample.places.first { it.place.name == "Koffie & Werk" }
        val gone = sample.places.first { it.place.name == "Werkplaats Lombok" }
        val changedAt = now()
        source.changes = sample.copy(
            generatedAt = changedAt,
            center = null,
            places = listOf(renamed.copy(place = renamed.place.copy(name = "Koffie & Werk Oudegracht"))),
            since = source.generatedAt,
            removed = listOf(gone.place.id),
        )
        repository.refresh("utrecht")
        assertEquals(listOf("all", "changes since ${source.generatedAt}"), source.asked)
        val after = names(repository)
        assertEquals(11, after.size)
        assert("Koffie & Werk Oudegracht" in after)
        assert("Werkplaats Lombok" !in after)
        // The city keeps its centre, which changes do not carry.
        assertEquals(sample.center?.lat, repository.cityInfo("utrecht").first()?.center?.lat)

        // The next time, changes since this download.
        repository.refresh("utrecht")
        assertEquals("changes since $changedAt", source.asked.last())
    }

    @Test
    fun whenChangesFailTheWholeCityComes() = runBlocking {
        val source = FakeSource()
        val repository = repository(source)
        repository.refresh("utrecht")
        source.changesFail = true

        repository.refresh("utrecht")

        assertEquals(listOf("all", "changes since ${source.generatedAt}", "all"), source.asked)
        assertEquals(12, names(repository).size)
    }

    @Test
    fun afterAWeekTheWholeCityComesAgain() = runBlocking {
        val source = FakeSource(generatedAt = now(minus = 8))
        val repository = repository(source)
        repository.refresh("utrecht")

        repository.refresh("utrecht")

        assertEquals(listOf("all", "all"), source.asked)
    }
}
