package nl.beandesk.app.settings

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

enum class ThemeChoice { SYSTEM, LIGHT, DARK }

/** [tag] is null for the phone's own language. */
enum class LanguageChoice(val tag: String?) {
    SYSTEM(null),
    DUTCH("nl"),
    ENGLISH("en"),
    ;

    companion object {
        fun of(tag: String?): LanguageChoice = entries.firstOrNull { it.tag != null && it.tag == tag } ?: SYSTEM
    }
}

/** The light or dark look and the language, as chosen in the profile; kept on the phone. */
class AppSettings(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val themeState = MutableStateFlow(
        prefs.getString(KEY_THEME, null)?.let { name -> ThemeChoice.entries.firstOrNull { it.name == name } } ?: ThemeChoice.SYSTEM,
    )
    val theme: StateFlow<ThemeChoice> = themeState.asStateFlow()

    fun setTheme(choice: ThemeChoice) {
        prefs.edit { putString(KEY_THEME, choice.name) }
        themeState.value = choice
    }

    /**
     * From Android 13 the system keeps the app's language, which can then also be changed in the
     * phone's settings; before that the app keeps it itself.
     */
    fun language(): LanguageChoice =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val locales = appContext.getSystemService(LocaleManager::class.java).applicationLocales
            if (locales.isEmpty) LanguageChoice.SYSTEM else LanguageChoice.of(locales[0].language)
        } else {
            LanguageChoice.of(prefs.getString(KEY_LANGUAGE, null))
        }

    /** Shows [activity] again in the new language; from Android 13 the system does that itself. */
    fun setLanguage(choice: LanguageChoice, activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.getSystemService(LocaleManager::class.java).applicationLocales =
                choice.tag?.let { LocaleList.forLanguageTags(it) } ?: LocaleList.getEmptyLocaleList()
        } else {
            prefs.edit(commit = true) { putString(KEY_LANGUAGE, choice.tag) }
            activity.recreate()
        }
    }

    companion object {
        private const val PREFS = "settings"
        private const val KEY_THEME = "theme"
        private const val KEY_LANGUAGE = "language"

        /**
         * Before Android 13: the language chosen in the app, for an activity to apply over the
         * phone's (see LocalizedActivity). Null to keep the phone's, and always from Android 13.
         */
        fun legacyLanguageTag(context: Context): String? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                null
            } else {
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LANGUAGE, null)
            }

        fun configurationFor(tag: String): Configuration = Configuration().apply { setLocale(Locale.forLanguageTag(tag)) }
    }
}
