package nl.beandesk.app.icon

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.edit
import nl.beandesk.app.MainActivity
import nl.beandesk.app.R

/**
 * The launcher icons the user can choose from. Each one is an <activity-alias> in the
 * manifest with the same name; keep both in sync (AppIconManifestTest checks this).
 */
enum class AppIcon(val aliasName: String, val labelRes: Int, val badgeRes: Int) {
    ESPRESSO("LauncherEspresso", R.string.app_icon_espresso, R.drawable.logo_badge_espresso),
    TERRACOTTA("LauncherTerracotta", R.string.app_icon_terracotta, R.drawable.logo_badge_terracotta),
    CREMA("LauncherCrema", R.string.app_icon_crema, R.drawable.logo_badge_crema),
    TEAL("LauncherTeal", R.string.app_icon_teal, R.drawable.logo_badge_teal),
    NIGHT("LauncherNight", R.string.app_icon_night, R.drawable.logo_badge_night),
    ;

    companion object {
        /** Enabled in the manifest, so this is the icon until the user picks another. */
        val DEFAULT = ESPRESSO
    }
}

/**
 * Switches the launcher icon by enabling one activity-alias and disabling the others.
 *
 * Disabling the alias the app was started from can close the app, so a choice is only stored
 * here and applied by [applyPending] once the app is in the background.
 */
class AppIconManager(context: Context) {
    private val appContext = context.applicationContext
    private val packageManager = appContext.packageManager
    private val prefs = appContext.getSharedPreferences("app_icon", Context.MODE_PRIVATE)

    /** The icon the user picked, applied or not. */
    fun selected(): AppIcon = pending() ?: current()

    fun select(icon: AppIcon) = prefs.edit { putString(KEY_PENDING, icon.name) }

    fun applyPending() {
        val icon = pending() ?: return
        if (icon != current()) {
            // Enable the new entry first, so the app never has zero launcher entries.
            setEnabled(icon, true)
            AppIcon.entries.filter { it != icon }.forEach { setEnabled(it, false) }
        }
        prefs.edit { remove(KEY_PENDING) }
    }

    private fun pending(): AppIcon? =
        prefs.getString(KEY_PENDING, null)?.let { name -> AppIcon.entries.firstOrNull { it.name == name } }

    /** The icon the launcher shows now: the one enabled alias. */
    private fun current(): AppIcon = AppIcon.entries.firstOrNull { isEnabled(it) } ?: AppIcon.DEFAULT

    private fun isEnabled(icon: AppIcon): Boolean =
        when (packageManager.getComponentEnabledSetting(component(icon))) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            // Never changed: the manifest decides, which enables only the default.
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> icon == AppIcon.DEFAULT
            else -> false
        }

    private fun setEnabled(icon: AppIcon, enabled: Boolean) {
        val state = if (enabled) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        packageManager.setComponentEnabledSetting(component(icon), state, PackageManager.DONT_KILL_APP)
    }

    // Aliases live in the code namespace, which can differ from the applicationId.
    private fun component(icon: AppIcon) =
        ComponentName(appContext, "${MainActivity::class.java.name.substringBeforeLast('.')}.${icon.aliasName}")

    private companion object {
        const val KEY_PENDING = "pending"
    }
}
