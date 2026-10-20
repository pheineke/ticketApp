package de.heineke.myvrnlite.ui

import android.view.View
import android.widget.FrameLayout
import android.widget.Switch
import android.widget.TextView
import de.heineke.myvrnlite.BuildConfig
import de.heineke.myvrnlite.R
import de.heineke.myvrnlite.data.Dates

/** Einstellungen: theme, colour scheme, offline copy, debug mode. */
class SettingsPage(activity: MainActivity) : Page(activity, R.layout.page_settings) {
    private val offlineSwitch: Switch = view.findViewById(R.id.offline_switch)
    private val debugSwitch: Switch = view.findViewById(R.id.debug_switch)

    init {
        val a = activity
        offlineSwitch.setOnCheckedChangeListener { _, on -> if (on != repo.prefs.offline) repo.setOfflineEnabled(on) }
        debugSwitch.setOnCheckedChangeListener { _, on ->
            if (on != repo.prefs.debug) {
                repo.prefs.debug = on
                a.render()
            }
        }
        view.findViewById<View>(R.id.offline_row).setOnClickListener { offlineSwitch.toggle() }
        view.findViewById<View>(R.id.debug_row).setOnClickListener { debugSwitch.toggle() }
        view.findViewById<View>(R.id.offline_clear).setOnClickListener { repo.clearOffline() }
        view.findViewById<TextView>(R.id.footer).text = a.getString(R.string.footer, BuildConfig.VERSION_NAME)
    }

    override fun render() {
        val a = activity
        val prefs = repo.prefs
        view.findViewById<FrameLayout>(R.id.theme_seg).apply {
            removeAllViews()
            addView(a.segmented(listOf("system" to a.getString(R.string.theme_system), "light" to a.getString(R.string.theme_light),
                "dark" to a.getString(R.string.theme_dark)), prefs.theme) { prefs.theme = it; a.recreate() })
        }
        view.findViewById<FrameLayout>(R.id.palette_seg).apply {
            removeAllViews()
            addView(a.segmented(listOf("lite" to a.getString(R.string.palette_lite), "vrn" to a.getString(R.string.palette_vrn)),
                prefs.palette) { prefs.palette = it; a.recreate() })
        }
        offlineSwitch.isChecked = prefs.offline
        debugSwitch.isChecked = prefs.debug
        view.findViewById<TextView>(R.id.offline_state).text = repo.offlineState?.let { (count, at) ->
            a.resources.getQuantityString(R.plurals.offline_state, count, count, Dates.format(at))
        } ?: a.getString(R.string.offline_none)
    }
}
