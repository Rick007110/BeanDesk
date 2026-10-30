package nl.beandesk.app.ui.places

import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.delay
import nl.beandesk.app.data.PlaceRepository
import nl.beandesk.app.data.local.BeanDeskDatabase
import nl.beandesk.app.data.location.LocationRepository
import nl.beandesk.app.data.remote.AreaSearchSource
import nl.beandesk.app.data.remote.NoAreaSearch
import nl.beandesk.app.data.remote.SampleCityExportSource
import nl.beandesk.app.icon.AppIconManager
import nl.beandesk.app.ui.theme.BeanDeskTheme
import nl.beandesk.core.export.CityExport
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The places screen on a real device, with the sample places (src/debug/assets) and no network. */
@RunWith(AndroidJUnit4::class)
class PlacesScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var database: BeanDeskDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, BeanDeskDatabase::class.java).build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun showScreen(search: AreaSearchSource = NoAreaSearch) {
        val repository = PlaceRepository(database.placeDao(), SampleCityExportSource(context.assets), search, backend = null)
        val viewModel = PlacesViewModel(
            repository = repository,
            locationRepository = LocationRepository(context),
            city = "utrecht",
            canSearchAreas = search !== NoAreaSearch,
        )
        compose.setContent {
            BeanDeskTheme { PlacesScreen(viewModel, AppIconManager(context)) }
        }
        // The count above the list shows once the places are in (12 in the sample, one reported
        // closed); a given card may be further down, depending on which places are open right now.
        compose.waitUntil(10_000) { compose.onAllNodesWithText("11 plekken").fetchSemanticsNodes().isNotEmpty() }
    }

    private val searchField get() = compose.onNode(hasSetTextAction())

    @Test
    fun typingKeepsEveryLetterInOrder() {
        showScreen()
        // One key at a time, the way a keyboard sends them, while the list keeps filtering.
        "koffiehuis science".forEach { searchField.performTextInput(it.toString()) }
        searchField.assertTextEquals("koffiehuis science")
    }

    @Test
    fun typingNarrowsTheListToMatchingNames() {
        showScreen()
        searchField.performTextInput("koffiehuis")
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("1 van 11 plekken").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Koffiehuis Science Park").assertExists()
    }

    @Test
    fun searchingAPlaceShowsItsSurroundings() {
        val search = FakeSearch(context)
        showScreen(search)

        searchField.performTextInput("Amersfoort")
        searchField.performImeAction()

        // The list switches to the two places of the fake search, the field empties and "Amersfoort"
        // shows as the area.
        compose.waitUntil(10_000) { compose.onAllNodesWithText("2 plekken").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Koffie & Werk").assertExists()
        compose.onNodeWithText("Stadsbibliotheek Centrum").assertExists()
        compose.onAllNodes(hasSetTextAction() and hasText("Amersfoort")).assertCountEquals(0)
        assertEquals(true, compose.onAllNodesWithText("Amersfoort").fetchSemanticsNodes().isNotEmpty())
        assertEquals(listOf("Amersfoort"), search.queries)
    }

    /** Answers every search with the first two sample places, labelled "Amersfoort". */
    private class FakeSearch(private val context: Context) : AreaSearchSource {
        val queries = mutableListOf<String>()

        override suspend fun search(query: String): CityExport {
            queries += query
            delay(300)
            val sample = SampleCityExportSource(context.assets).fetch("utrecht")
            return sample.copy(city = "Amersfoort", places = sample.places.take(2))
        }

        override suspend fun refreshAround(placeId: Long) = Unit
    }
}
