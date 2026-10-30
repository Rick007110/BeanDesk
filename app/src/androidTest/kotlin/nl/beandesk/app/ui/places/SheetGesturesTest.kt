package nl.beandesk.app.ui.places

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import nl.beandesk.app.data.PlaceRepository
import nl.beandesk.app.data.local.BeanDeskDatabase
import nl.beandesk.app.data.location.LocationRepository
import nl.beandesk.app.data.remote.NoAreaSearch
import nl.beandesk.app.data.remote.SampleCityExportSource
import nl.beandesk.app.icon.AppIconManager
import nl.beandesk.app.ui.theme.BeanDeskTheme
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith

/** The place and report sheets on the places screen, used with a finger (see [Finger]). */
@RunWith(AndroidJUnit4::class)
class SheetGesturesTest {
    @get:Rule
    val testName = TestName()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val finger by lazy { Finger(testName.methodName) }
    private var database: BeanDeskDatabase? = null
    private var scenario: ActivityScenario<ComponentActivity>? = null
    private val resets = mutableListOf<String>()

    @After
    fun tearDown() {
        finger.stopRecording()
        scenario?.close()
        database?.close()
        resets.forEach { finger.setting(it) }
    }

    @Test
    fun afterScrollingTheDetailSheetItsButtonsStillWork() {
        launch()
        openPlace()
        // A thumb looking through the sheet: up slowly, then a flick up.
        finger.drag(0.85f, 0.5f)
        finger.fling(0.85f, 0.4f)
        finger.shot("scrolled")
        finger.tap(By.text("Melden"), expect = By.text("Wat klopt er nu?"))
    }

    @Test
    fun afterScrollingTheReportSheetEveryTapLandsAndItSends() {
        launch()
        openReportSheet()
        finger.drag(0.85f, 0.5f)
        finger.fling(0.85f, 0.4f)
        finger.shot("scrolled")
        finger.tapUntilSelected(By.desc("4 van 5 sterren"))
        finger.tapUntilSelected(By.text("Druk"))
        finger.tap(By.text("Versturen"), expect = By.text("Opgeslagen op je telefoon (voorbeeldmodus)."))
        finger.awaitGone(By.text("Wat klopt er nu?"))
        finger.awaitGone(By.text("Route"))
    }

    @Test
    fun onASmallScreenWithLargeTextTheQuestionsScrollAndEveryTapLands() {
        // A small phone with larger text: the questions are taller than the screen and scroll
        // inside the sheet, with the send button below them.
        launch(small = true)
        openReportSheet()
        finger.tapUntilSelected(By.desc("4 van 5 sterren"))
        finger.fling(0.8f, 0.3f)
        finger.drag(0.4f, 0.5f)
        finger.fling(0.8f, 0.3f)
        finger.shot("scrolled")
        finger.tapUntilSelected(By.text("Niet toegestaan"))
        // Slowly back up: a quick pull down at the top would close the sheet, as it should.
        repeat(3) { finger.drag(0.4f, 0.55f, steps = 100) }
        finger.shot("scrolled-back")
        finger.tapUntilSelected(By.text("Traag"))
        finger.tap(By.text("Versturen"), expect = By.text("Opgeslagen op je telefoon (voorbeeldmodus)."))
        finger.awaitGone(By.text("Wat klopt er nu?"))
    }

    @Test
    fun swipingTheSheetAwayLeavesTheScreenWorking() {
        launch()
        openPlace()
        finger.pullDown(finger.await(By.text("Route")))
        finger.awaitGone(By.text("Route"))
        finger.shot("swiped-away")
        // The screen behind still reacts: a filter, and the next place.
        finger.tapUntilSelected(By.text("Laptop ok"))
        openPlace()
    }

    @Test
    fun scrollingAndThenSwipingTheReportSheetAwayLeavesTheScreenWorking() {
        launch()
        openReportSheet()
        finger.drag(0.85f, 0.5f)
        finger.fling(0.85f, 0.4f)
        finger.shot("scrolled")
        finger.pullDown(finger.await(By.text("Wat klopt er nu?")))
        finger.awaitGone(By.text("Wat klopt er nu?"))
        // The place's sheet is still there underneath and goes the same way.
        finger.pullDown(finger.await(By.text("Route")))
        finger.awaitGone(By.text("Route"))
        finger.shot("swiped-away")
        finger.tapUntilSelected(By.text("Laptop ok"))
        openPlace()
        finger.tap(By.text("Melden"), expect = By.text("Wat klopt er nu?"))
    }

    /** The places screen with the sample places, as in the app; [small] as on a small phone. */
    private fun launch(small: Boolean = false) {
        if (small) {
            finger.setting("wm density 520")
            finger.setting("settings put system font_scale 1.3")
            resets += "wm density reset"
            resets += "settings put system font_scale 1.0"
        }
        finger.startRecording()
        val db = Room.inMemoryDatabaseBuilder(context, BeanDeskDatabase::class.java).build().also { database = it }
        val repository = PlaceRepository(db.placeDao(), SampleCityExportSource(context.assets), NoAreaSearch, backend = null)
        val viewModel = PlacesViewModel(repository, LocationRepository(context), city = "utrecht", canSearchAreas = false)
        scenario = ActivityScenario.launch(ComponentActivity::class.java).also {
            it.onActivity { activity ->
                activity.enableEdgeToEdge()
                activity.setContent { BeanDeskTheme { PlacesScreen(viewModel, AppIconManager(activity)) } }
            }
        }
        finger.await(By.text("11 plekken"), timeout = 10_000)
        finger.shot("start")
    }

    /** Scrolls the list until the card is clear of the button at the bottom, then taps it. */
    private fun openPlace(name: String = "Koffiehuis Science Park") {
        var card = finger.find(By.text(name), 1_000)
        var tries = 0
        while ((card == null || card.visibleBounds.centerY() > finger.height * 0.7) && tries++ < 8) {
            finger.drag(0.7f, 0.45f)
            card = finger.find(By.text(name), 1_000)
        }
        val bounds = (card ?: finger.fail("No card for $name")).visibleBounds
        // The left of the name: the map button sits in the middle at the bottom.
        finger.device.click(bounds.left + 20, bounds.centerY())
        finger.await(By.text("Route"))
        finger.shot("place")
    }

    private fun openReportSheet() {
        openPlace()
        finger.tap(By.text("Melden"), expect = By.text("Wat klopt er nu?"))
    }
}
