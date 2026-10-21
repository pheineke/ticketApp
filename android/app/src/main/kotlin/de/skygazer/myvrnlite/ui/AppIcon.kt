package de.skygazer.myvrnlite.ui

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * The launcher icon follows the design (Settings > Farben): the manifest has one launcher
 * entry (activity-alias) per icon, and exactly one is enabled. Modern has its own logo; myVRN
 * Lite and VRN Original share the app's icon.
 *
 * The launcher picks the change up after a moment; some launchers drop a home-screen shortcut
 * to the entry that was turned off (the app drawer always shows the current one).
 */
object AppIcon {
    private const val DEFAULT = ".Launcher"
    private const val MODERN = ".LauncherModern"

    fun apply(context: Context, palette: String) {
        val wanted = if (palette == "modern") MODERN else DEFAULT
        // The new one first, so there is never a moment without a launcher entry.
        set(context, wanted, true)
        set(context, if (wanted == MODERN) DEFAULT else MODERN, false)
    }

    private fun set(context: Context, alias: String, on: Boolean) {
        val pm = context.packageManager
        // The alias names are relative to the namespace, which is this package (App lives there).
        val component = ComponentName(context.packageName, de.skygazer.myvrnlite.App::class.java.`package`!!.name + alias)
        val current = when (pm.getComponentEnabledSetting(component)) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED -> false
            else -> alias == DEFAULT  // as in the manifest
        }
        if (current == on) return
        pm.setComponentEnabledSetting(component,
            if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP)
    }
}
