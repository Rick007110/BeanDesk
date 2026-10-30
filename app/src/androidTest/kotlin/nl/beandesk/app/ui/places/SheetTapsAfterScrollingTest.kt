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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ModalBottomSheet
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
import nl.beandesk.app.ui.theme.BeanDeskTheme
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith

/**
 * A long form in a sheet, scrolled and flicked through like a thumb and then tapped, in
 * Material's ModalBottomSheet as app 0.2.1 had it (opening half way) and in the app's own
 * [BottomSheet]. The screen behind has a button too, for after the sheet is swiped away.
 */
@RunWith(AndroidJUnit4::class)
class SheetTapsAfterScrollingTest {
    @get:Rule
    val testName = TestName()

    private val finger by lazy { Finger(testName.methodName) }
    private var scenario: ActivityScenario<ComponentActivity>? = null

    @After
    fun tearDown() {
        finger.stopRecording()
        scenario?.close()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun materialSheetAsInVersion021() = scrollTapAndSwipeAway { onDismiss, content ->
        ModalBottomSheet(onDismissRequest = onDismiss) { content() }
    }

    @Test
    fun ownSheet() = scrollTapAndSwipeAway { onDismiss, content ->
        BottomSheet(onDismissRequest = onDismiss) { content() }
    }

    private fun scrollTapAndSwipeAway(sheet: @Composable (onDismiss: () -> Unit, content: @Composable () -> Unit) -> Unit) {
        finger.startRecording()
        scenario = ActivityScenario.launch(ComponentActivity::class.java).also {
            it.onActivity { activity ->
                activity.enableEdgeToEdge()
                activity.setContent { BeanDeskTheme { Screen(sheet) } }
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

    /** The first question row whose chip [option] is fully on screen, like "B4". */
    private fun visible(option: String): String =
        (1..QUESTIONS).map { "$option$it" }.firstOrNull { text ->
            val node = finger.device.findObject(By.text(text))
            node != null && node.visibleBounds.top > finger.height * 0.15 && node.visibleBounds.bottom < finger.height * 0.9
        } ?: finger.fail("No $option chip on screen")

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun Screen(sheet: @Composable (onDismiss: () -> Unit, content: @Composable () -> Unit) -> Unit) {
        var behind by remember { mutableIntStateOf(0) }
        var open by remember { mutableStateOf(true) }
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.safeDrawingPadding().padding(24.dp)) {
                Button(onClick = { behind++ }) { Text("Achter de sheet: $behind") }
            }
        }
        if (open) {
            sheet({ open = false }) {
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
