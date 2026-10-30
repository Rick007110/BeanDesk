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
        device.wait(Until.findObject(expect), 3_000)
            ?: fail("Tapped $selector at ${bounds.toShortString()}, but $expect did not show")
        shot("tapped")
    }

    /**
     * Taps the middle of [selector] and waits until [landed] says the app handled it. For a change
     * inside a dialog window: what UiAutomator reads of the screen can lag seconds behind there.
     */
    fun tap(selector: BySelector, what: String, landed: () -> Boolean) {
        val bounds = reveal(selector).visibleBounds
        device.click(bounds.centerX(), bounds.centerY())
        val deadline = System.currentTimeMillis() + 3_000
        while (!landed()) {
            if (System.currentTimeMillis() > deadline) fail("Tapped $selector at ${bounds.toShortString()}, but $what did not happen")
            Thread.sleep(100)
        }
        shot("tapped")
    }

    /** Taps [selector] and checks it is selected afterwards, which proves the tap landed. */
    fun tapUntilSelected(selector: BySelector) {
        val bounds = reveal(selector).visibleBounds
        device.click(bounds.centerX(), bounds.centerY())
        val deadline = System.currentTimeMillis() + 2_000
        while (System.currentTimeMillis() < deadline) {
            if (isSelected(selector)) {
                shot("selected")
                return
            }
            Thread.sleep(250)
        }
        fail("Tapped $selector at ${bounds.toShortString()}, but it is not selected")
    }

    fun isSelected(selector: BySelector): Boolean = isOn(device.findObject(selector))

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

    /**
     * Waits until nothing on screen moves any more: no changes reported for half a second, then
     * a moment for the stretch at the end of a list to relax.
     *
     * The test only listens here. Reading the whole screen over and over runs on the app's main
     * thread, and with a fast loop of that the app handled a tap seconds late.
     */
    private fun awaitStill() {
        device.waitForIdle(5_000)
        Thread.sleep(500)
    }

    fun await(selector: BySelector, timeout: Long = 3_000): UiObject2 =
        find(selector, timeout) ?: fail("Not on screen after $timeout ms: $selector")

    /** Waits up to [timeout] ms for [selector]; looks about once a second. */
    fun find(selector: BySelector, timeout: Long): UiObject2? {
        val deadline = System.currentTimeMillis() + timeout
        do {
            val left = (deadline - System.currentTimeMillis()).coerceIn(1, 1_000)
            device.wait(Until.findObject(selector), left)?.let { return it }
            dismissSystemDialog()
        } while (System.currentTimeMillis() < deadline)
        return null
    }

    /**
     * Closes an "isn't responding" dialog about another app, which a busy emulator sometimes shows
     * over everything (the launcher, for one).
     */
    private fun dismissSystemDialog() {
        if (device.hasObject(By.textContains("isn't responding"))) device.findObject(By.text("Wait"))?.click()
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
