package de.heineke.myvrnlite.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import android.widget.TextView
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import de.heineke.myvrnlite.R
import de.heineke.myvrnlite.data.Shop

/**
 * Hochschul-Login in a WebView: the VRN shop's login (Dfn/Federate needs the shop's cookie
 * session, which our bearer token doesn't give), then the university's SAML login. The shop
 * comes back to its entitlements page with dfn_approved=true: that ends this screen with
 * RESULT_OK. Leaving earlier (back, close) is RESULT_CANCELED.
 *
 * The WebView keeps the shop's cookies, so a second run skips the shop login.
 */
class DfnActivity : BaseActivity() {
    private lateinit var web: WebView
    private lateinit var host: TextView
    private lateinit var progress: ProgressBar

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent(R.layout.activity_dfn)
        titleView.setText(R.string.dfn_title)
        navButton.setImageResource(R.drawable.ic_close)
        navButton.contentDescription = getString(R.string.fp_close)
        navButton.setOnClickListener { finish() }
        action.setOnClickListener { web.reload() }
        host = findViewById(R.id.dfn_host)
        progress = findViewById(R.id.dfn_progress)
        web = findViewById(R.id.dfn_web)
        web.settings.javaScriptEnabled = true  // the shop's login is a single-page app
        web.settings.domStorageEnabled = true
        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, p: Int) {
                progress.progress = p
                progress.visibility = if (p < 100) View.VISIBLE else View.INVISIBLE
            }
        }
        web.webViewClient = object : WebViewClient() {
            @Deprecated("Android 6 and older")
            override fun shouldOverrideUrlLoading(view: WebView, url: String) = intercept(url)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) =
                request.isForMainFrame && intercept(request.url.toString())

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                showHost(url)
                if (Shop.APPROVED_MARK in url) done()
            }
        }
        val start = intent.getStringExtra(EXTRA_URL) ?: return finish()
        if (savedInstanceState?.let(web::restoreState) == null) web.loadUrl(start)
        setupBack()
    }

    /** Only web pages load here (no app links, intent: or vrn: schemes); the shop's return ends the screen. */
    private fun intercept(url: String): Boolean {
        if (Shop.APPROVED_MARK in url) {
            done()
            return true
        }
        val scheme = Uri.parse(url).scheme
        return scheme != "https" && scheme != "http"
    }

    private fun done() {
        if (isFinishing) return
        setResult(Activity.RESULT_OK)
        finish()
    }

    private fun showHost(url: String) {
        val uri = Uri.parse(url)
        host.text = uri.host ?: url
        val icon = resources.getDrawable(if (uri.scheme == "https") R.drawable.ic_lock else R.drawable.ic_warning, theme).mutate()
        icon.setBounds(0, 0, dp(14), dp(14))
        icon.setTint(color(R.color.text_muted))
        host.setCompoundDrawables(icon, null, null, null)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }

    // ---- Back: within the login pages first, then leave ----------------------------

    // Android 12 and older only: with enableOnBackInvokedCallback, 13+ uses the callback below.
    @SuppressLint("GestureBackNavigation")
    @Deprecated("Back on Android 12 and older")
    override fun onBackPressed() {
        @Suppress("DEPRECATION")
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    private fun setupBack() {
        if (Build.VERSION.SDK_INT < 33) return
        onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT,
            OnBackInvokedCallback { if (web.canGoBack()) web.goBack() else finish() })
    }

    companion object {
        private const val EXTRA_URL = "url"

        fun intent(context: Context, university: Shop.University): Intent =
            Intent(context, DfnActivity::class.java).putExtra(EXTRA_URL, Shop.verifyUrl(university.id))
    }
}
