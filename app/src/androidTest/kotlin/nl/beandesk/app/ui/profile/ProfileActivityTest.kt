package nl.beandesk.app.ui.profile

import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import nl.beandesk.app.BeanDeskApplication
import nl.beandesk.app.DutchRule
import nl.beandesk.app.settings.LanguageChoice
import nl.beandesk.app.settings.ThemeChoice
import nl.beandesk.app.ui.places.Finger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith

/** The profile as the app opens it, used with a finger (see [Finger]). */
@RunWith(AndroidJUnit4::class)
class ProfileActivityTest {
    @get:Rule(order = 0)
    val dutch = DutchRule()

    @get:Rule(order = 1)
    val testName = TestName()

    private val finger by lazy { Finger(testName.methodName) }
    private val settings = ApplicationProvider.getApplicationContext<BeanDeskApplication>().container.settings
    private var scenario: ActivityScenario<ProfileActivity>? = null

    @After
    fun tearDown() {
        finger.stopRecording()
        scenario?.close()
        settings.setTheme(ThemeChoice.SYSTEM)
    }

    private fun launch() {
        finger.startRecording()
        scenario = ActivityScenario.launch(ProfileActivity::class.java)
        finger.await(By.text("Profiel en instellingen"), timeout = 10_000)
        finger.shot("open")
    }

    @Test
    fun theAppSwitchesToEnglishAndBack() {
        launch()
        finger.tap(By.text("English"), expect = By.text("Profile and settings"))
        assertEquals(LanguageChoice.ENGLISH, settings.language())
        finger.tap(By.text("Nederlands"), expect = By.text("Profiel en instellingen"))
        assertEquals(LanguageChoice.DUTCH, settings.language())
    }

    @Test
    fun theDarkLookStaysChosen() {
        launch()
        finger.tapUntilSelected(By.text("Donker"))
        assertEquals(ThemeChoice.DARK, settings.theme.value)
        finger.shot("dark")
        finger.tapUntilSelected(By.text("Licht"))
        assertEquals(ThemeChoice.LIGHT, settings.theme.value)
    }

    @Test
    fun thePrivacyStatementSlidesUpAndAway() {
        launch()
        finger.tap(By.text("Privacyverklaring"), expect = By.text("Je locatie"))
        finger.pullDown(finger.await(By.text("Je locatie")))
        finger.awaitGone(By.text("Je locatie"))
        // The screen behind still reacts.
        finger.tap(By.text("App-icoon"), expect = By.text("Terracotta"))
    }
}
