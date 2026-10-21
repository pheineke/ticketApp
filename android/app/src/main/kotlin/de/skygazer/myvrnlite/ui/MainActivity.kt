package de.skygazer.myvrnlite.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import de.skygazer.myvrnlite.R

/** A tab's content inside [MainActivity]. */
abstract class Page(protected val activity: MainActivity, layout: Int) {
    val view: View = activity.layoutInflater.inflate(layout, null)
    protected val repo get() = activity.repo

    abstract fun render()

    /** Activity lifecycle, for pages with a map or listeners of their own. */
    open fun onResume() = Unit

    open fun onPause() = Unit

    /** Answer to a runtime permission request the page made (location). */
    open fun onPermissionResult(granted: Boolean) = Unit
}

/**
 * The app shell (Gullivr layout): app bar, bottom tabs Tickets · Fahrplan · Konto · Einstellen,
 * and the "Home navigator" drawer for everything else. On wide screens (tablet landscape)
 * the drawer is a permanent sidebar and the tab bar goes, as in the web client.
 */
class MainActivity : BaseActivity() {
    enum class Tab(val title: Int) {
        TICKETS(R.string.title_tickets), FAHRPLAN(R.string.title_fahrplan), ACCOUNT(R.string.title_account),
        SETTINGS(R.string.title_settings),
        /** Drawer only (no bottom tab), as in the web client. */
        SHOP(R.string.title_shop)
    }

    private var tab = Tab.TICKETS
    private lateinit var pages: Map<Tab, Page>
    private lateinit var drawer: View
    private lateinit var scrim: View
    private var drawerOpen = false
    private var backCallback: Any? = null  // OnBackInvokedCallback, Android 13+
    private val onChange: () -> Unit = { render() }

    /** Tablet landscape and up: sidebar instead of drawer + tab bar, Fahrplan as a side panel. */
    val wide: Boolean get() = resources.configuration.screenWidthDp >= WIDE_DP

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent(R.layout.activity_main)
        pages = mapOf(Tab.TICKETS to TicketsPage(this), Tab.FAHRPLAN to FahrplanPage(this),
            Tab.ACCOUNT to AccountPage(this), Tab.SETTINGS to SettingsPage(this), Tab.SHOP to ShopPage(this))
        val container = findViewById<FrameLayout>(R.id.pages)
        for (page in pages.values) container.addView(page.view, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        navButton.setOnClickListener { openDrawer(true) }
        action.setOnClickListener {
            when (tab) {
                Tab.TICKETS -> repo.refreshTickets()
                Tab.ACCOUNT -> repo.refreshProfile()
                Tab.SHOP -> repo.refreshShop()
                Tab.FAHRPLAN, Tab.SETTINGS -> Unit
            }
        }
        setupTabs()
        setupDrawer()
        applyWide()
        select(savedInstanceState?.getString(STATE_TAB)?.let(Tab::valueOf) ?: Tab.TICKETS)
    }

    override fun onStart() {
        super.onStart()
        repo.addListener(onChange)
        repo.refreshIfStale()
        render()
    }

    override fun onStop() {
        repo.removeListener(onChange)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        pages.values.forEach { it.onResume() }
    }

    override fun onPause() {
        pages.values.forEach { it.onPause() }
        super.onPause()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_LOCATION) {
            pages.getValue(Tab.FAHRPLAN).onPermissionResult(grantResults.any { it == PackageManager.PERMISSION_GRANTED })
        }
    }

    @Deprecated("Framework Activity: no Activity Result API without AndroidX")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_DFN) (pages.getValue(Tab.SHOP) as ShopPage).onVerifyResult(resultCode)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_TAB, tab.name)
    }

    fun select(tab: Tab) {
        this.tab = tab
        for ((t, page) in pages) page.view.visibility = if (t == tab) View.VISIBLE else View.GONE
        if (tab == Tab.ACCOUNT && repo.loggedIn && repo.profileResult == null) repo.refreshProfile()
        if (tab == Tab.SHOP) (pages.getValue(Tab.SHOP) as ShopPage).onShown()
        if (drawerOpen) openDrawer(false)
        render()
    }

    fun render() {
        titleView.setText(tab.title)
        findViewById<View>(R.id.debug_flag).visibility = if (repo.prefs.debug) View.VISIBLE else View.GONE
        action.visibility = if ((tab == Tab.TICKETS || tab == Tab.ACCOUNT || tab == Tab.SHOP) && repo.loggedIn) View.VISIBLE else View.GONE
        spin(action, when (tab) {
            Tab.TICKETS -> repo.loadingTickets
            Tab.SHOP -> repo.loadingShop || repo.loadingTickets
            else -> repo.loadingProfile
        })
        styleTabs()
        findViewById<TextView>(R.id.drawer_state).setText(if (repo.loggedIn) R.string.drawer_logged_in else R.string.drawer_logged_out)
        for ((id, t) in NAV_TABS) findViewById<View>(id).isSelected = t == tab
        pages.getValue(tab).render()
        updateBackHandling()
    }

    // ---- Tabs --------------------------------------------------------------------

    private val tabViews = listOf(
        Triple(R.id.tab_tickets, Tab.TICKETS, R.drawable.ic_ticket to R.string.tab_tickets),
        Triple(R.id.tab_fahrplan, Tab.FAHRPLAN, R.drawable.ic_route to R.string.tab_fahrplan),
        Triple(R.id.tab_account, Tab.ACCOUNT, R.drawable.nav_account to R.string.tab_account),
        Triple(R.id.tab_settings, Tab.SETTINGS, R.drawable.ic_settings to R.string.tab_settings),
    )

    private fun setupTabs() {
        for ((id, t, res) in tabViews) {
            val v = findViewById<View>(id)
            v.findViewById<ImageView>(R.id.tab_icon).setImageResource(res.first)
            v.findViewById<TextView>(R.id.tab_label).setText(res.second)
            v.setOnClickListener { select(t) }
        }
    }

    private fun styleTabs() {
        for ((id, t, _) in tabViews) {
            val v = findViewById<View>(id)
            val on = t == tab
            val c = if (on) attrColor(R.attr.brandInk) else attrColor(R.attr.cTextMuted)
            v.findViewById<ImageView>(R.id.tab_icon).tint(c)
            v.findViewById<TextView>(R.id.tab_label).apply {
                setTextColor(c)
                typeface = if (on) Typeface.create("sans-serif-medium", Typeface.NORMAL) else Typeface.DEFAULT
            }
            v.isSelected = on
        }
    }

    // ---- Drawer (Home navigator) ---------------------------------------------------

    private fun setupDrawer() {
        drawer = findViewById(R.id.drawer)
        scrim = findViewById(R.id.scrim)
        drawer.layoutParams.width = minOf(dp(300), (resources.displayMetrics.widthPixels * 0.85f).toInt())
        findViewById<View>(R.id.drawer_brand).background =
            GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(attrColor(R.attr.brand), attrColor(R.attr.brandDark)))
        navRow(R.id.nav_tickets, R.drawable.nav_tickets, R.string.nav_tickets, R.string.nav_tickets_sub) { select(Tab.TICKETS) }
        navRow(R.id.nav_fahrplan, R.drawable.nav_fahrplan, R.string.nav_fahrplan, R.string.nav_fahrplan_sub) { select(Tab.FAHRPLAN) }
        navRow(R.id.nav_account, R.drawable.nav_account, R.string.nav_account, R.string.nav_account_sub) { select(Tab.ACCOUNT) }
        navRow(R.id.nav_settings, R.drawable.nav_settings, R.string.nav_settings, R.string.nav_settings_sub) { select(Tab.SETTINGS) }
        navRow(R.id.nav_shop, R.drawable.nav_shop, R.string.nav_shop, R.string.nav_shop_sub) { select(Tab.SHOP) }
        navRow(R.id.nav_help, R.drawable.nav_help, R.string.nav_help, external = true) { openUrl(URL_HELP) }
        navRow(R.id.nav_privacy, R.drawable.nav_privacy, R.string.nav_privacy, external = true) { openUrl(URL_PRIVACY) }
        navRow(R.id.nav_imprint, R.drawable.nav_imprint, R.string.nav_imprint, external = true) { openUrl(URL_IMPRINT) }
        scrim.setOnClickListener { openDrawer(false) }
    }

    private fun navRow(id: Int, icon: Int, title: Int, sub: Int = 0, external: Boolean = false, onClick: () -> Unit) {
        val row = findViewById<View>(id)
        row.findViewById<ImageView>(R.id.nav_icon).apply { setImageResource(icon); tint(attrColor(R.attr.accentText)) }
        row.findViewById<TextView>(R.id.nav_title).setText(title)
        if (sub != 0) row.findViewById<TextView>(R.id.nav_sub).apply { setText(sub); visibility = View.VISIBLE }
        if (external) row.findViewById<ImageView>(R.id.nav_external).apply { visibility = View.VISIBLE; tint(attrColor(R.attr.cTextMuted)) }
        row.setOnClickListener {
            if (external) openDrawer(false)
            onClick()
        }
    }

    /** Wide screens: the drawer stays open as a sidebar next to the content; no tab bar. */
    private fun applyWide() {
        if (!wide) return
        val side = dp(272)
        findViewById<View>(R.id.tab_bar).visibility = View.GONE
        navButton.visibility = View.GONE
        scrim.visibility = View.GONE
        drawer.layoutParams.width = side
        drawer.elevation = 0f
        drawer.translationX = 0f
        drawer.visibility = View.VISIBLE
        drawer.setBackgroundResource(R.drawable.bg_sidebar)
        (findViewById<View>(R.id.main_column).layoutParams as ViewGroup.MarginLayoutParams).marginStart = side
        findViewById<View>(R.id.app_bar).setPadding(dp(20), 0, dp(8), 0)
        // Pages other than the map: centred at a readable width (max. 880dp, as in the web client).
        val free = resources.configuration.screenWidthDp - 272
        val pad = dp(maxOf(0, (free - 880) / 2))
        for ((t, page) in pages) if (t != Tab.FAHRPLAN) page.view.setPadding(pad, 0, pad, 0)
    }

    private fun openDrawer(open: Boolean) {
        if (wide) return
        drawerOpen = open
        val hidden = -drawer.layoutParams.width.toFloat()
        scrim.animate().cancel()
        drawer.animate().cancel()
        if (open) {
            scrim.alpha = 0f
            scrim.visibility = View.VISIBLE
            scrim.animate().alpha(1f).setDuration(ANIM_MS)
            drawer.translationX = hidden
            drawer.visibility = View.VISIBLE
            drawer.animate().translationX(0f).setDuration(ANIM_MS)
        } else {
            scrim.animate().alpha(0f).setDuration(ANIM_MS).withEndAction { scrim.visibility = View.GONE }
            drawer.animate().translationX(hidden).setDuration(ANIM_MS).withEndAction { drawer.visibility = View.INVISIBLE }
        }
        updateBackHandling()
    }

    // ---- Back: close the drawer, then go back to the Tickets tab, then leave ---------

    private fun handleBack(): Boolean = when {
        drawerOpen -> { openDrawer(false); true }
        tab != Tab.TICKETS -> { select(Tab.TICKETS); true }
        else -> false
    }

    // Android 12 and older only: with enableOnBackInvokedCallback, 13+ uses the callback below.
    @SuppressLint("GestureBackNavigation")
    @Deprecated("Back on Android 12 and older")
    override fun onBackPressed() {
        @Suppress("DEPRECATION")
        if (!handleBack()) super.onBackPressed()
    }

    private fun updateBackHandling() {
        if (Build.VERSION.SDK_INT < 33) return
        val wanted = drawerOpen || tab != Tab.TICKETS
        val current = backCallback as OnBackInvokedCallback?
        if (wanted && current == null) {
            backCallback = OnBackInvokedCallback { handleBack() }.also {
                onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, it)
            }
        } else if (!wanted && current != null) {
            onBackInvokedDispatcher.unregisterOnBackInvokedCallback(current)
            backCallback = null
        }
    }

    companion object {
        const val REQ_LOCATION = 1
        const val REQ_DFN = 2
        private const val WIDE_DP = 900
        private const val STATE_TAB = "tab"
        private const val ANIM_MS = 200L
        private const val URL_HELP = "https://www.vrn.de/service/kontakt/feedback/index.html"
        private const val URL_PRIVACY = "https://www.vrn.de/_inc/app/agb/"
        private const val URL_IMPRINT = "https://www.vrn.de/_inc/app/impressum/index.html"
        private val NAV_TABS = listOf(R.id.nav_tickets to Tab.TICKETS, R.id.nav_fahrplan to Tab.FAHRPLAN,
            R.id.nav_account to Tab.ACCOUNT, R.id.nav_settings to Tab.SETTINGS, R.id.nav_shop to Tab.SHOP)
    }
}
