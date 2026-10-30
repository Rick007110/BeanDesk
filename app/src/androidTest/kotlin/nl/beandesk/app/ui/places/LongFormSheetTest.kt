package nl.beandesk.app.ui.places

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.StaleObjectException
import nl.beandesk.app.ui.theme.BeanDeskTheme
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith

/**
 * A long form in the app's [BottomSheet], scrolled and flicked through like a thumb, with a tap
 * after each move, then sent and swiped away; the screen behind must react afterwards.
 */
@RunWith(AndroidJUnit4::class)
class LongFormSheetTest {
    @get:Rule
    val testName = TestName()

    private val finger by lazy { Finger(testName.methodName) }
    private var scenario: ActivityScenario<ComponentActivity>? = null

    @After
    fun tearDown() {
        finger.stopRecording()
        scenario?.close()
    }

    @Test
    fun everyTapLandsAfterScrollingAndTheScreenWorksAfterSwipingAway() {
        finger.startRecording()
        scenario = ActivityScenario.launch(ComponentActivity::class.java).also {
            it.onActivity { activity ->
                activity.enableEdgeToEdge()
                activity.setContent { BeanDeskTheme { Screen() } }
            }
        }
        finger.await(By.text("Vraag 1"), timeout = 10_000)
        finger.shot("open")
        finger.tapUntilSelected(By.text("A1"))

        // Up through the form: slowly, flicks, a bit back; a tap after each.
        finger.drag(0.85f, 0.45f)
        finger.shot("dragged")
        finger.tapUntilSelected(By.text(visible("B")))
        finger.fling(0.85f, 0.35f)
        finger.shot("flicked")
        finger.tapUntilSelected(By.text(visible("C")))
        finger.drag(0.45f, 0.55f, steps = 100)
        finger.tapUntilSelected(By.text(visible("A")))
        finger.fling(0.35f, 0.6f)
        finger.tapUntilSelected(By.text(visible("B")))
        finger.fling(0.85f, 0.35f)
        finger.fling(0.85f, 0.35f)
        finger.shot("bottom")
        finger.tap(By.text("Versturen"), expect = By.text("Verstuurd"))

        // Back to the top and pull the sheet away; the screen behind must react.
        repeat(6) { finger.drag(0.35f, 0.5f, steps = 100) }
        finger.pullDown(finger.await(By.text("Vraag 1")))
        finger.awaitGone(By.text("Vraag 1"))
        finger.shot("swiped-away")
        finger.tap(By.text("Achter de sheet: 0"), expect = By.text("Achter de sheet: 1"))
    }

    /** The first question row whose chip [option] is well on screen and not chosen yet, like "B4". */
    private fun visible(option: String): String {
        repeat(5) {
            (1..QUESTIONS).map { "$option$it" }
                .firstOrNull { text -> isWellOnScreen(By.text(text)) && !finger.isSelected(By.text(text)) }
                ?.let { return it }
            Thread.sleep(300)
        }
        return finger.fail("No $option chip on screen")
    }

    private fun isWellOnScreen(selector: BySelector): Boolean = try {
        val bounds = finger.device.findObject(selector)?.visibleBounds
        bounds != null && bounds.top > finger.height * 0.15 && bounds.bottom < finger.height * 0.9
    } catch (e: StaleObjectException) {
        // It moved out of sight while looking.
        false
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun Screen() {
        var behind by remember { mutableIntStateOf(0) }
        var open by remember { mutableStateOf(true) }
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.safeDrawingPadding().padding(24.dp)) {
                Button(onClick = { behind++ }) { Text("Achter de sheet: $behind") }
            }
        }
        if (open) {
            BottomSheet(onDismissRequest = { open = false }) {
                var picked by remember { mutableStateOf(setOf<String>()) }
                var sent by remember { mutableStateOf(false) }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    for (question in 1..QUESTIONS) {
                        Text("Vraag $question")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (option in listOf("A", "B", "C")) {
                                val name = "$option$question"
                                FilterChip(
                                    selected = name in picked,
                                    onClick = { picked = if (name in picked) picked - name else picked + name },
                                    label = { Text(name) },
                                )
                            }
                        }
                    }
                    Button(onClick = { sent = true }) { Text(if (sent) "Verstuurd" else "Versturen") }
                }
            }
        }
    }

    private companion object {
        const val QUESTIONS = 14
    }
}
