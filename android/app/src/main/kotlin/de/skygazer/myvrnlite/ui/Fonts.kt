package de.skygazer.myvrnlite.ui

import android.content.Context
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import de.skygazer.myvrnlite.R

/**
 * The Modern design's type: DM Sans and DM Mono (assets/fonts, SIL OFL). Android 5 has no
 * variable fonts and the app no AndroidX, so the text views get them in code: [apply] maps the
 * family a view asked for (regular, sans-serif-medium, bold, monospace) to the DM face.
 */
object Fonts {
    private var faces: Map<String, Typeface>? = null
    private val systemMedium: Typeface by lazy { Typeface.create("sans-serif-medium", Typeface.NORMAL) }

    private fun faces(context: Context): Map<String, Typeface> = faces ?: listOf(
        "DMSans-Regular", "DMSans-Medium", "DMSans-Bold", "DMMono-Regular", "DMMono-Medium",
    ).associateWith { Typeface.createFromAsset(context.assets, "fonts/$it.ttf") }.also { faces = it }

    /** DM Sans/Mono for the text views under [root]; cheap to call again (each view is done once). */
    fun apply(root: View) {
        if (!root.context.modern) return
        val f = faces(root.context.applicationContext)
        walk(root, f)
    }

    private fun walk(v: View, f: Map<String, Typeface>) {
        if (v is TextView) {
            val current = v.typeface
            // Done already, unless the code set another typeface since (e.g. the tab labels).
            if (current == null || current !== v.getTag(R.id.font_applied)) {
                val bold = current?.isBold == true
                val face = when {
                    current == Typeface.MONOSPACE || current?.let { it == Typeface.create(Typeface.MONOSPACE, it.style) } == true ->
                        if (bold) "DMMono-Medium" else "DMMono-Regular"
                    bold -> "DMSans-Bold"
                    current == systemMedium -> "DMSans-Medium"
                    else -> "DMSans-Regular"
                }
                val tf = f.getValue(face)
                v.setTag(R.id.font_applied, tf)
                v.typeface = tf
            }
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i), f)
    }
}
