package nl.beandesk.app

import android.app.LocaleManager
import android.content.Context
import android.os.LocaleList
import androidx.test.core.app.ApplicationProvider
import org.junit.rules.ExternalResource

/**
 * Shows the app in Dutch, the language the tests read, whatever the emulator's own language
 * (English in CI). Sets the app's language the way the profile does on Android 13 and later, so
 * it must run before an activity starts: give it the lowest rule order.
 *
 * It is not reset afterwards: the change reaches the app a moment later, and a reset still on
 * its way could switch the next test to English halfway.
 */
class DutchRule : ExternalResource() {
    private val context: Context = ApplicationProvider.getApplicationContext()

    override fun before() {
        context.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags("nl-NL")
        // Waits for the new configuration; a test that still sees English fails on its texts.
        val deadline = System.currentTimeMillis() + 5_000
        while (context.resources.configuration.locales[0].language != "nl" && System.currentTimeMillis() < deadline) {
            Thread.sleep(100)
        }
    }
}
