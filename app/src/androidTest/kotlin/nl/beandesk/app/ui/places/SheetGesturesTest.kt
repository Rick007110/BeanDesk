package nl.beandesk.app.ui.places

import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
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

/**
 * The sheets the way a finger uses them: real touches through the system (UiAutomator), on the
 * app's own frame clock with animations on, not the Compose test clock that waits for every
 * animation to end. Each test leaves a screen recording and a screenshot per step in
 * /sdcard/Download/beandesk-ui, which CI keeps as an artifact.
 */
@RunWith(AndroidJUnit4::class)
class SheetGesturesTest {
    @get:Rule
    val testName = TestName()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val context: Context = ApplicationProvider.getApplicationContext()
    private var database: BeanDeskDatabase? = null
    private var scenario: ActivityScenario<ComponentActivity>? = null
    private var recording: ParcelFileDescriptor? = null
    private var fontScaled = false
    private var step = 0

    private val width get() = device.displayWidth
    private val height get() = device.displayHeight

    @After
    fun tearDown() {
        stopRecording()
        scenario?.close()
        database?.close()
        if (fontScaled) {
            device.executeShellCommand("settings put system font_scale 1.0")
            Thread.sleep(1_500)
        }
    }

    @Test
    fun afterScrollingTheDetailSheetItsButtonsStillWork() {
        launch()
        openPlace()
        // A thumb looking through the sheet: up slowly, then a flick up.
        drag(0.85f, 0.5f)
        fling(0.85f, 0.4f)
        shot("scrolled")
        tap(By.text("Melden"), expect = By.text("Wat klopt er nu?"))
    }

    @Test
    fun afterScrollingTheReportSheetEveryTapLandsAndItSends() {
        launch()
        openReportSheet()
        drag(0.85f, 0.5f)
        fling(0.85f, 0.4f)
        shot("scrolled")
        tapUntilSelected(By.desc("4 van 5 sterren"))
        tapUntilSelected(By.text("Druk"))
        tap(By.text("Versturen"), expect = By.text("Opgeslagen op je telefoon (voorbeeldmodus)."))
        awaitGone(By.text("Wat klopt er nu?"))
        awaitGone(By.text("Route"))
    }

    @Test
    fun withLargeTextTheReportSheetScrollsAndStillSends() {
        // Larger text makes the questions taller than the screen, so they scroll inside the sheet.
        launch(fontScale = 1.3f)
        openReportSheet()
        tapUntilSelected(By.desc("4 van 5 sterren"))
        fling(0.85f, 0.35f)
        drag(0.45f, 0.55f)
        shot("scrolled")
        tapUntilSelected(By.text("Druk"))
        tap(By.text("Versturen"), expect = By.text("Opgeslagen op je telefoon (voorbeeldmodus)."))
        awaitGone(By.text("Wat klopt er nu?"))
    }

    @Test
    fun swipingTheSheetAwayLeavesTheScreenWorking() {
        launch()
        openPlace()
        pullDown(await(By.text("Route")))
        awaitGone(By.text("Route"))
        shot("swiped-away")
        // The screen behind still reacts: a filter, and the next place.
        tapUntilSelected(By.text("Laptop ok"))
        openPlace()
    }

    @Test
    fun scrollingAndThenSwipingTheReportSheetAwayLeavesTheScreenWorking() {
        launch()
        openReportSheet()
        drag(0.85f, 0.5f)
        fling(0.85f, 0.4f)
        shot("scrolled")
        pullDown(await(By.text("Wat klopt er nu?")))
        awaitGone(By.text("Wat klopt er nu?"))
        // Back on the place itself, which goes the same way.
        pullDown(await(By.text("Route")))
        awaitGone(By.text("Route"))
        shot("swiped-away")
        tapUntilSelected(By.text("Laptop ok"))
        openPlace()
        tap(By.text("Melden"), expect = By.text("Wat klopt er nu?"))
    }

    /** The places screen with the sample places, as in the app, at the phone's [fontScale]. */
    private fun launch(fontScale: Float = 1f) {
        if (fontScale != 1f) {
            fontScaled = true
            device.executeShellCommand("settings put system font_scale $fontScale")
            // The new text size reaches the app before the activity starts, so it is not recreated.
            Thread.sleep(1_500)
        }
        startRecording()
        val db = Room.inMemoryDatabaseBuilder(context, BeanDeskDatabase::class.java).build().also { database = it }
        val repository = PlaceRepository(db.placeDao(), SampleCityExportSource(context.assets), NoAreaSearch, backend = null)
        val viewModel = PlacesViewModel(repository, LocationRepository(context), city = "utrecht", canSearchAreas = false)
        scenario = ActivityScenario.launch(ComponentActivity::class.java).also {
            it.onActivity { activity ->
                activity.enableEdgeToEdge()
                activity.setContent { BeanDeskTheme { PlacesScreen(viewModel, AppIconManager(activity)) } }
            }
        }
        await(By.text("11 plekken"), timeout = 10_000)
        shot("start")
    }

    /** Scrolls the list until the card is clear of the button at the bottom, then taps it. */
    private fun openPlace(name: String = "Koffiehuis Science Park") {
        var card = device.wait(Until.findObject(By.text(name)), 1_000)
        var tries = 0
        while ((card == null || card.visibleBounds.centerY() > height * 0.7) && tries++ < 8) {
            drag(0.7f, 0.45f)
            card = device.wait(Until.findObject(By.text(name)), 1_000)
        }
        val bounds = (card ?: fail("No card for $name")).visibleBounds
        // The left of the name: the map button sits in the middle at the bottom.
        device.click(bounds.left + 20, bounds.centerY())
        await(By.text("Route"))
        shot("place")
    }

    private fun openReportSheet() {
        openPlace()
        tap(By.text("Melden"), expect = By.text("Wat klopt er nu?"))
    }

    /** Taps the middle of [selector], scrolled into view if needed, and waits for [expect]. */
    private fun tap(selector: BySelector, expect: BySelector) {
        val bounds = reveal(selector).visibleBounds
        device.click(bounds.centerX(), bounds.centerY())
        device.wait(Until.findObject(expect), 3_000)
            ?: fail("Tapped $selector at ${bounds.toShortString()}, but $expect did not show")
        shot("tapped")
    }

    /** Taps [selector] and checks it is selected afterwards, which proves the tap landed. */
    private fun tapUntilSelected(selector: BySelector) {
        val bounds = reveal(selector).visibleBounds
        device.click(bounds.centerX(), bounds.centerY())
        val deadline = System.currentTimeMillis() + 2_000
        while (System.currentTimeMillis() < deadline) {
            val node = device.findObject(selector)
            if (node != null && isOn(node)) {
                shot("selected")
                return
            }
            Thread.sleep(50)
        }
        fail("Tapped $selector at ${bounds.toShortString()}, but it is not selected")
    }

    private fun isOn(node: UiObject2): Boolean = try {
        node.isChecked || node.isSelected
    } catch (e: StaleObjectException) {
        false
    }

    /** Finds [selector]; when it is below the screen edge, scrolls the sheet down a little at a time. */
    private fun reveal(selector: BySelector): UiObject2 {
        repeat(4) {
            val node = device.wait(Until.findObject(selector), 1_000)
            if (node != null && node.visibleBounds.height() > 20) return node
            drag(0.7f, 0.5f)
        }
        return await(selector)
    }

    /** Grabs [node] and pulls it to the bottom of the screen, as when swiping a sheet away. */
    private fun pullDown(node: UiObject2) {
        val bounds = node.visibleBounds
        device.swipe(bounds.centerX(), bounds.centerY(), bounds.centerX(), height - 20, 40)
    }

    /** A finger moving at a steady pace, about 5 ms per step; fractions of the screen height. */
    private fun drag(fromY: Float, toY: Float, steps: Int = 40) {
        device.swipe(width / 2, (height * fromY).toInt(), width / 2, (height * toY).toInt(), steps)
    }

    private fun fling(fromY: Float, toY: Float) = drag(fromY, toY, steps = 6)

    private fun await(selector: BySelector, timeout: Long = 3_000): UiObject2 =
        device.wait(Until.findObject(selector), timeout) ?: fail("Not on screen after $timeout ms: $selector")

    private fun awaitGone(selector: BySelector, timeout: Long = 3_000) {
        if (!device.wait(Until.gone(selector), timeout)) fail("Still on screen after $timeout ms: $selector")
    }

    private fun fail(message: String): Nothing {
        shot("failed")
        throw AssertionError("$message\n\nOn screen:\n${screenText()}")
    }

    /** What the screen shows, for a failure message: per element its text, place and state. */
    private fun screenText(): String = device.findObjects(By.pkg(context.packageName)).mapNotNull { node ->
        try {
            val label = node.text ?: node.contentDescription ?: return@mapNotNull null
            val state = listOfNotNull("checked".takeIf { node.isChecked }, "disabled".takeIf { !node.isEnabled })
            "$label ${node.visibleBounds.toShortString()} ${state.joinToString(" ")}".trim()
        } catch (e: StaleObjectException) {
            null
        }
    }.take(80).joinToString("\n")

    private fun shot(label: String) {
        step++
        device.executeShellCommand("mkdir -p $SCREENS")
        device.executeShellCommand("screencap -p $SCREENS/${testName.methodName}-%02d-$label.png".format(step))
    }

    /** Records the screen in the background until [stopRecording] (at most three minutes). */
    private fun startRecording() {
        device.executeShellCommand("mkdir -p $SCREENS")
        recording = instrumentation.uiAutomation.executeShellCommand(
            "screenrecord --bit-rate 4000000 $SCREENS/${testName.methodName}.mp4",
        )
    }

    private fun stopRecording() {
        val running = recording ?: return
        device.executeShellCommand("pkill -2 screenrecord")
        // Gives screenrecord the time to finish the file.
        Thread.sleep(1_000)
        running.close()
        recording = null
    }

    private companion object {
        const val SCREENS = "/sdcard/Download/beandesk-ui"
    }
}
