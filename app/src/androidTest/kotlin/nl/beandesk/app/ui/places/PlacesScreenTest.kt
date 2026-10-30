package nl.beandesk.app.ui.places

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
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
import nl.beandesk.app.data.remote.SearchResult
import nl.beandesk.app.icon.AppIconManager
import nl.beandesk.app.ui.theme.BeanDeskTheme
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
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Koffiehuis Science Park"))
    }

    @Test
    fun aPauseInTypingSearchesAndAnEmptyFieldGoesBack() {
        val search = FakeSearch(context)
        showScreen(search)

        // No search key: the search starts by itself two seconds after the last letter.
        searchField.performTextInput("Amersfoort")
        compose.waitUntil(10_000) { compose.onAllNodesWithText("2 plekken").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(listOf("Amersfoort"), search.queries)
        // The text stays, and does not filter its own results.
        searchField.assertTextEquals("Amersfoort")
        val list = compose.onNode(hasScrollToNodeAction())
        list.performScrollToNode(hasText("Koffie & Werk"))
        list.performScrollToNode(hasText("Stadsbibliotheek Centrum"))

        // Clearing the field shows the city again.
        searchField.performTextClearance()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("11 plekken").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun theSearchKeySearchesAtOnce() {
        val search = FakeSearch(context)
        showScreen(search)

        searchField.performTextInput("Amersfoort")
        searchField.performImeAction()
        // Well within the two-second pause.
        compose.waitUntil(1_500) { compose.onAllNodesWithText("2 plekken").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(listOf("Amersfoort"), search.queries)
    }

    @Test
    fun aReportWithStarsAndAnswersIsSentAndClosesTheSheets() {
        showScreen()
        val list = compose.onNode(hasScrollToNodeAction())
        list.performScrollToNode(hasText("Koffiehuis Science Park"))
        compose.onNodeWithText("Koffiehuis Science Park").performClick()
        compose.onNodeWithText("Melden").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("Wat klopt er nu?").assertIsDisplayed()

        // Every tap lands: a star, an answer further down and the send button, which is in view.
        compose.onNodeWithContentDescription("4 van 5 sterren").performClick().assertIsSelected()
        compose.onNodeWithText("Druk").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithText("Versturen").assertIsDisplayed().assertIsEnabled().performClick()

        // Sample mode: the report stays on the phone, both sheets close and the card shows the stars.
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Opgeslagen op je telefoon (voorbeeldmodus).").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Wat klopt er nu?").assertDoesNotExist()
        compose.onNodeWithText("Melden").assertDoesNotExist()
        list.performScrollToNode(hasContentDescription("4,0 van 5 sterren, 1 beoordelingen"))
    }

    /** Answers every search with the first two sample places, labelled "Amersfoort". */
    private class FakeSearch(private val context: Context) : AreaSearchSource {
        val queries = mutableListOf<String>()

        override suspend fun search(query: String): SearchResult {
            queries += query
            delay(300)
            val sample = SampleCityExportSource(context.assets).fetch("utrecht")
            return SearchResult(sample.copy(city = "Amersfoort", places = sample.places.take(2)), byName = false)
        }

        override suspend fun refreshAround(placeId: Long) = Unit
    }
}
