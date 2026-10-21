package de.skygazer.myvrnlite.ui

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.animation.LinearInterpolator
import android.widget.ImageButton
import android.widget.TextView
import de.skygazer.myvrnlite.R
import de.skygazer.myvrnlite.data.Prefs
import de.skygazer.myvrnlite.data.Repo
import de.skygazer.myvrnlite.repo

/**
 * Theme handling shared by the screens. Plain framework [Activity] (no AppCompat), so the
 * app runs on Android 5 without AndroidX: light/dark is a uiMode override, the palette a theme.
 */
abstract class BaseActivity : Activity() {
    val repo: Repo get() = applicationContext.repo
    protected lateinit var titleView: TextView
    protected lateinit var navButton: ImageButton
    protected lateinit var action: ImageButton

    val isNight: Boolean
        get() = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    /** The theme of the chosen palette (Settings > Farben). */
    protected val paletteTheme: Int
        get() = when (repo.prefs.palette) {
            "vrn" -> R.style.Theme_MyVrn_Vrn
            "modern" -> R.style.Theme_MyVrn_Modern
            else -> R.style.Theme_MyVrn_Lite
        }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase)
        // "System" follows the device (dark mode exists from Android 10); otherwise force it.
        val night = when (Prefs(newBase).theme) {
            "light" -> Configuration.UI_MODE_NIGHT_NO
            "dark" -> Configuration.UI_MODE_NIGHT_YES
            else -> return
        }
        applyOverrideConfiguration(Configuration(newBase.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
        })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(paletteTheme)
        super.onCreate(savedInstanceState)
    }

    protected fun setContent(layout: Int) {
        setContentView(layout)
        titleView = findViewById(R.id.title)
        navButton = findViewById(R.id.nav_button)
        action = findViewById(R.id.action)
        val ink = attrColor(R.attr.cText)
        navButton.tint(ink)
        action.tint(ink)
        styleSystemBars()
        fitSystemBars(findViewById(R.id.root))
        if (modern) {
            // DM Sans/Mono for whatever the screen shows, also views added later.
            val decor = window.decorView
            decor.viewTreeObserver.addOnGlobalLayoutListener { Fonts.apply(decor) }
        }
    }

    /**
     * The bar of a detail screen: back arrow and [title]; Modern shows "‹ Zurück" there instead
     * (the content starts right below, as in the web client).
     */
    protected fun backBar(title: Int) {
        navButton.contentDescription = getString(R.string.back)
        navButton.setOnClickListener { finish() }
        if (modern) {
            navButton.setImageResource(R.drawable.mi_back)
            titleView.setText(R.string.back)
            titleView.textSize = 15f
            titleView.setTextColor(attrColor(R.attr.cTextHeading))
            titleView.setOnClickListener { finish() }
        } else {
            navButton.setImageResource(R.drawable.ic_arrow_back)
            titleView.setText(title)
        }
    }

    /** Status/navigation bars in the page colour where the platform allows dark icons. */
    @Suppress("DEPRECATION")
    private fun styleSystemBars() {
        if (Build.VERSION.SDK_INT < 23) return  // 5.x: the theme's dark status bar
        val page = attrColor(R.attr.cBg)
        window.statusBarColor = page
        if (Build.VERSION.SDK_INT >= 27) window.navigationBarColor = page
        if (Build.VERSION.SDK_INT >= 30) {
            val light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(if (isNight) 0 else light, light)
        } else {
            var flags = window.decorView.systemUiVisibility
            val light = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or (if (Build.VERSION.SDK_INT >= 27) View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0)
            flags = if (isNight) flags and light.inv() else flags or light
            window.decorView.systemUiVisibility = flags
        }
    }

    /** Android 15+ draws apps edge to edge; keep the content clear of the bars and the keyboard. */
    private fun fitSystemBars(root: View) {
        if (Build.VERSION.SDK_INT < 35) return
        root.setOnApplyWindowInsetsListener { v, insets ->
            val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
            v.setPadding(i.left, i.top, i.right, i.bottom)
            WindowInsets.CONSUMED
        }
    }

    /** Spins [view] while [on] (loading indicator on the refresh button). */
    protected fun spin(view: View, on: Boolean) {
        val running = view.tag as? ObjectAnimator
        if (on && running == null) {
            view.tag = ObjectAnimator.ofFloat(view, View.ROTATION, 0f, 360f).apply {
                duration = 900
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                start()
            }
        } else if (!on && running != null) {
            running.cancel()
            view.rotation = 0f
            view.tag = null
        }
    }
}
