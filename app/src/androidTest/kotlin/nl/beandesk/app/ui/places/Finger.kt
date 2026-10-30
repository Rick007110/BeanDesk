package nl.beandesk.app.ui.places

import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until

/**
 * Uses the screen like a finger: real touches through the system (UiAutomator), on the app's own
 * frame clock with animations on, not the Compose test clock that waits for every animation to
 * end. Leaves a screen recording and a screenshot per step in /sdcard/Download/beandesk-ui, which
 * CI keeps as an artifact; [name] prefixes the files.
 */
class Finger(private val name: String) {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    val device: UiDevice = UiDevice.getInstance(instrumentation)
    private val packageName = instrumentation.targetContext.packageName
    private var recording: ParcelFileDescriptor? = null
    private var step = 0

    val width get() = device.displayWidth
    val height get() = device.displayHeight

    /** Taps the middle of [selector], scrolled into view if needed, and waits for [expect]. */
    fun tap(selector: BySelector, expect: BySelector) {
        val bounds = reveal(selector).visibleBounds
        device.click(bounds.centerX(), bounds.centerY())
        find(expect, 3_000) ?: fail("Tapped $selector at ${bounds.toShortString()}, but $expect did not show")
        shot("tapped")
    }

    /** Taps [selector] and checks it is selected afterwards, which proves the tap landed. */
    fun tapUntilSelected(selector: BySelector) {
        val bounds = reveal(selector).visibleBounds
        device.click(bounds.centerX(), bounds.centerY())
        val deadline = System.currentTimeMillis() + 2_000
        while (System.currentTimeMillis() < deadline) {
            if (isOn(device.findObject(selector))) {
                shot("selected")
                return
            }
            Thread.sleep(50)
        }
        fail("Tapped $selector at ${bounds.toShortString()}, but it is not selected")
    }

    /**
     * Whether the chip or button around [node] is selected. A text or icon is found, not the chip
     * itself, so this looks a few levels up.
     */
    private fun isOn(node: UiObject2?): Boolean = try {
        generateSequence(node) { it.parent }.take(4).any { it.isChecked || it.isSelected }
    } catch (e: StaleObjectException) {
        false
    }

    /** Finds [selector]; when it is below the screen edge, scrolls down a little at a time. */
    fun reveal(selector: BySelector): UiObject2 {
        repeat(5) {
            val node = find(selector, 1_000)
            if (node != null && node.visibleBounds.height() > 20) return node
            drag(0.7f, 0.5f)
        }
        return await(selector)
    }

    /** Grabs [node] and pulls it to the bottom of the screen, as when swiping a sheet away. */
    fun pullDown(node: UiObject2) {
        val bounds = node.visibleBounds
        device.swipe(bounds.centerX(), bounds.centerY(), bounds.centerX(), height - 20, 40)
    }

    /**
     * A finger moving at a steady pace, about 5 ms per step; fractions of the screen height. Then
     * waits, as a person does, until the content has stopped moving: a tap on content that still
     * scrolls only stops it, on any Android list.
     */
    fun drag(fromY: Float, toY: Float, steps: Int = 40) {
        device.swipe(width / 2, (height * fromY).toInt(), width / 2, (height * toY).toInt(), steps)
        awaitStill()
    }

    fun fling(fromY: Float, toY: Float) = drag(fromY, toY, steps = 6)

    /** Waits until nothing on screen moves any more (at most five seconds). */
    private fun awaitStill() {
        var before = layout()
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(300)
            val now = layout()
            if (now == before) return
            before = now
        }
    }

    private fun layout(): List<String> = device.findObjects(By.pkg(packageName)).mapNotNull { node ->
        try {
            "${node.text ?: node.contentDescription} ${node.visibleBounds.toShortString()}"
        } catch (e: StaleObjectException) {
            null
        }
    }

    fun await(selector: BySelector, timeout: Long = 3_000): UiObject2 =
        find(selector, timeout) ?: fail("Not on screen after $timeout ms: $selector")

    /** Waits up to [timeout] ms for [selector]. */
    fun find(selector: BySelector, timeout: Long): UiObject2? {
        val deadline = System.currentTimeMillis() + timeout
        do {
            device.findObject(selector)?.let { return it }
            dismissSystemDialog()
            Thread.sleep(100)
        } while (System.currentTimeMillis() < deadline)
        return null
    }

    /**
     * Closes an "isn't responding" dialog about another app, which a busy emulator sometimes shows
     * over everything (the launcher, for one).
     */
    private fun dismissSystemDialog() {
        if (device.findObject(By.textContains("isn't responding")) != null) {
            device.findObject(By.text("Wait"))?.click()
        }
    }

    fun awaitGone(selector: BySelector, timeout: Long = 3_000) {
        if (!device.wait(Until.gone(selector), timeout)) fail("Still on screen after $timeout ms: $selector")
    }

    fun fail(message: String): Nothing {
        shot("failed")
        throw AssertionError("$message\n\nOn screen:\n${screenText()}")
    }

    /** What the screen shows, for a failure message: per element its text, place and state. */
    private fun screenText(): String = device.findObjects(By.pkg(packageName)).mapNotNull { node ->
        try {
            val label = node.text ?: node.contentDescription ?: return@mapNotNull null
            val state = listOfNotNull("checked".takeIf { isOn(node) }, "disabled".takeIf { !node.isEnabled })
            "$label ${node.visibleBounds.toShortString()} ${state.joinToString(" ")}".trim()
        } catch (e: StaleObjectException) {
            null
        }
    }.take(80).joinToString("\n")

    fun shot(label: String) {
        step++
        device.executeShellCommand("mkdir -p $SCREENS")
        device.executeShellCommand("screencap -p $SCREENS/$name-%02d-$label.png".format(step))
    }

    /** Records the screen in the background until [stopRecording] (at most three minutes). */
    fun startRecording() {
        device.executeShellCommand("mkdir -p $SCREENS")
        recording = instrumentation.uiAutomation.executeShellCommand(
            "screenrecord --bit-rate 4000000 $SCREENS/$name.mp4",
        )
    }

    fun stopRecording() {
        val running = recording ?: return
        device.executeShellCommand("pkill -2 screenrecord")
        // Gives screenrecord the time to finish the file.
        Thread.sleep(1_000)
        running.close()
        recording = null
    }

    /** Sets a phone setting through the shell and gives the app time to pick it up. */
    fun setting(command: String) {
        device.executeShellCommand(command)
        Thread.sleep(1_500)
    }

    private companion object {
        const val SCREENS = "/sdcard/Download/beandesk-ui"
    }
}
