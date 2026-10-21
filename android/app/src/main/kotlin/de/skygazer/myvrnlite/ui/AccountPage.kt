package de.skygazer.myvrnlite.ui

import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import de.skygazer.myvrnlite.R
import de.skygazer.myvrnlite.data.Dates
import de.skygazer.myvrnlite.net.VrnException
import java.io.IOException

/** Konto: login (e-mail + password, e-mail code) and the profile. */
class AccountPage(activity: MainActivity) : Page(activity, R.layout.page_account) {
    private val alerts: LinearLayout = view.findViewById(R.id.account_alerts)
    private val loginForm: View = view.findViewById(R.id.login_form)
    private val email: EditText = view.findViewById(R.id.email)
    private val password: EditText = view.findViewById(R.id.password)
    private val remember: CheckBox = view.findViewById(R.id.remember)
    private val loginButton: Button = view.findViewById(R.id.login_button)
    private val codeForm: View = view.findViewById(R.id.code_form)
    private val code: EditText = view.findViewById(R.id.code)
    private val codeButton: Button = view.findViewById(R.id.code_button)
    private val profileView: View = view.findViewById(R.id.profile_view)
    private val debug: LinearLayout = view.findViewById(R.id.account_debug)

    /** Error of the last login step, shown until the next attempt. */
    private var error: String? = null
    private var busy = false

    init {
        val a = activity
        remember.isChecked = repo.prefs.remember
        view.findViewById<ImageButton>(R.id.password_toggle).apply {
            tint(a.attrColor(R.attr.cTextMuted))
            setOnClickListener {
                // Compare the whole variation: VISIBLE_PASSWORD (0x90) contains the PASSWORD bit (0x80).
                val hidden = password.inputType and InputType.TYPE_MASK_VARIATION == InputType.TYPE_TEXT_VARIATION_PASSWORD
                val cursor = password.selectionEnd
                val typeface = password.typeface  // setInputType switches password fields to monospace
                password.inputType = InputType.TYPE_CLASS_TEXT or
                    if (hidden) InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD else InputType.TYPE_TEXT_VARIATION_PASSWORD
                password.typeface = typeface
                password.setSelection(cursor)
            }
        }
        password.setOnEditorActionListener { _, id, _ -> (id == EditorInfo.IME_ACTION_DONE).also { if (it) login() } }
        loginButton.setOnClickListener { login() }
        view.findViewById<View>(R.id.forgot_password).setOnClickListener { a.openUrl(URL_FORGOT_PASSWORD) }
        code.setOnEditorActionListener { _, id, _ -> (id == EditorInfo.IME_ACTION_DONE).also { if (it) submitCode() } }
        codeButton.setOnClickListener { submitCode() }
        view.findViewById<View>(R.id.code_cancel).setOnClickListener {
            error = null
            repo.cancelLogin()
        }
        view.findViewById<View>(R.id.account_verify).setOnClickListener { a.select(MainActivity.Tab.SHOP) }
        view.findViewById<View>(R.id.logout).setOnClickListener {
            error = null
            repo.logout()
            // The Hochschul-Login's shop session (WebView cookies) belongs to this login too.
            CookieManager.getInstance().removeAllCookies(null)
        }
        view.findViewById<TextView>(R.id.change_login).apply {
            val icon = a.resources.getDrawable(R.drawable.ic_open_in_new, a.theme).mutate()
            icon.setBounds(0, 0, a.dp(14), a.dp(14))
            icon.setTint(a.attrColor(R.attr.brandInk))
            setCompoundDrawables(null, null, icon, null)
            setOnClickListener { a.openUrl(URL_CHANGE_LOGIN) }
        }
        view.findViewById<ImageView>(R.id.avatar).tint(a.attrColor(R.attr.brandInk))
    }

    private fun login() {
        val mail = email.text.toString().trim()
        val pw = password.text.toString()
        if (mail.isEmpty() || pw.isEmpty()) {
            error = activity.getString(R.string.err_fill_in)
            return render()
        }
        start()
        repo.login(mail, pw, remember.isChecked) { e -> finish(e, R.string.err_login) }
    }

    private fun submitCode() {
        start()
        repo.submitCode(code.text.toString()) { e -> finish(e, R.string.err_code) }
    }

    private fun start() {
        busy = true
        error = null
        render()
    }

    private fun finish(e: Throwable?, failure: Int) {
        busy = false
        if (e == null) {
            password.text.clear()
            code.text.clear()
            if (repo.loggedIn) activity.select(MainActivity.Tab.TICKETS)
        } else {
            val known = e is IOException || (e is VrnException && e.reason in KNOWN_FAILURES)
            error = if (known) activity.errorText(e) else activity.getString(failure, e.message)
        }
    }

    override fun render() {
        val a = activity
        val pending = repo.pendingLogin != null
        alerts.removeAllViews()
        val message = error ?: if (repo.sessionExpired && !repo.loggedIn) a.getString(R.string.err_session_expired) else null
        message?.let { (alerts.add(a.alert(AlertKind.BAD, it)).layoutParams as LinearLayout.LayoutParams).bottomMargin = a.dp(12) }

        loginForm.visibility = if (!repo.loggedIn && !pending) View.VISIBLE else View.GONE
        codeForm.visibility = if (!repo.loggedIn && pending) View.VISIBLE else View.GONE
        profileView.visibility = if (repo.loggedIn) View.VISIBLE else View.GONE
        for ((button, label) in listOf(loginButton to R.string.login, codeButton to R.string.confirm)) {
            button.isEnabled = !busy
            button.alpha = if (busy) 0.6f else 1f
            button.setText(if (busy) R.string.logging_in else label)
        }
        view.findViewById<ImageView>(R.id.login_logo).setImageResource(when {
            a.isNight -> R.drawable.vrn_logo_white
            repo.prefs.palette == "vrn" -> R.drawable.vrn_logo_vrn
            else -> R.drawable.vrn_logo_lite
        })
        if (pending) view.findViewById<TextView>(R.id.code_sent).text = a.getString(R.string.code_sent, repo.pendingEmail)
        if (repo.loggedIn) renderProfile()

        debug.removeAllViews()
        if (repo.prefs.debug && repo.loggedIn) {
            debug.add(tokenPanel(), top = 12)
            debug.add(profilePanel(), top = 12)
        }
    }

    private fun renderProfile() {
        val a = activity
        val p = repo.profile
        val mail = p?.email ?: repo.tokens?.claims?.optString("email")?.ifEmpty { null }
        view.findViewById<TextView>(R.id.hello).text = p?.first?.let { a.getString(R.string.hello_name, it) } ?: a.getString(R.string.hello)
        val fullName = listOfNotNull(p?.title, p?.first, p?.last).joinToString(" ").trim()
        view.findViewById<TextView>(R.id.profile_name).text = fullName.ifEmpty { mail ?: a.getString(R.string.my_account) }
        view.findViewById<TextView>(R.id.profile_email).apply {
            text = mail
            visibility = if (mail != null) View.VISIBLE else View.GONE
        }
        val rows = buildList {
            p?.dob?.let { add(a.getString(R.string.kv_dob) to Dates.formatRaw(it, false)) }
            if (p?.street != null || p?.city != null) {
                add(a.getString(R.string.kv_address) to "${p.street.orEmpty()}\n${p.zip.orEmpty()} ${p.city.orEmpty()}".trim())
            }
            p?.phone?.let { add(a.getString(R.string.kv_phone) to it) }
        }
        view.findViewById<FrameLayout>(R.id.profile_details).apply {
            removeAllViews()
            if (rows.isNotEmpty()) addView(a.kvTable(rows))
            visibility = if (rows.isNotEmpty()) View.VISIBLE else View.GONE
        }
        view.findViewById<LinearLayout>(R.id.profile_alerts).apply {
            removeAllViews()
            val result = repo.profileResult
            if (p == null && result != null && !repo.loadingProfile) {
                val status = result.calls.lastOrNull()?.code
                add(a.alert(AlertKind.INFO, if (status != null) a.getString(R.string.profile_failed_status, status.toString())
                else a.getString(R.string.profile_failed)))
            }
        }
        view.findViewById<TextView>(R.id.remember_state)
            .setText(if (repo.prefs.remember) R.string.remember_active else R.string.remember_inactive)
    }

    private fun tokenPanel(): View {
        val a = activity
        val t = repo.tokens!!
        val remaining = if (t.expiresAt == Long.MAX_VALUE) null else (t.expiresAt - System.currentTimeMillis()) / 1000
        return a.debugPanel("Token", open = true) {
            add(a.kvTable(listOfNotNull(
                a.getString(R.string.dbg_token_status) to a.getString(if (remaining != null && remaining <= 0) R.string.dbg_token_expired else R.string.dbg_token_valid),
                a.getString(R.string.dbg_token_expires) to (remaining?.let { a.getString(R.string.dbg_seconds, it.toInt()) } ?: "—"),
                a.getString(R.string.dbg_scope) to t.scope.ifEmpty { "—" },
                a.getString(R.string.dbg_refresh_token) to a.getString(if (t.refreshToken != null) R.string.dbg_yes else R.string.dbg_no),
                t.claims?.optString("sub")?.ifEmpty { null }?.let { "sub" to it },
            ), padV = 6))
            add(Button(a, null, 0, R.style.Button_Small).apply {
                setText(R.string.dbg_token_refresh)
                setOnClickListener {
                    repo.refreshToken { e -> if (e != null) Toast.makeText(a, a.errorText(e), Toast.LENGTH_LONG).show() }
                }
            }, top = 8, width = LinearLayout.LayoutParams.WRAP_CONTENT)
            add(a.styled(R.style.FieldLabel, a.getString(R.string.dbg_claims_access)), top = 10)
            add(a.pre(pretty(t.claims)))
            t.idClaims?.let {
                add(a.styled(R.style.FieldLabel, a.getString(R.string.dbg_claims_id)), top = 10)
                add(a.pre(pretty(it)))
            }
        }
    }

    private fun profilePanel(): View {
        val a = activity
        val result = repo.profileResult
        return a.debugPanel("Profil") {
            add(a.hint(a.getString(R.string.dbg_source, result?.source ?: "—")))
            if (result == null) add(a.hint(a.getString(R.string.dbg_not_loaded)))
            result?.calls?.forEach { c ->
                add(a.hint(c.code.toString()), top = 6)
                add(a.pre(c.request))
                add(a.pre(pretty(c.json, c.body)), top = 6)
            }
        }
    }

    private companion object {
        const val URL_FORGOT_PASSWORD = "https://shop.myvrn.de/authentication/forgot-password?organization=vrn&client=vrn"
        const val URL_CHANGE_LOGIN = "https://shop.myvrn.de/authentication/private?organization=vrn&client=vrn"
        val KNOWN_FAILURES = setOf("CredentialValidationFailed", "UserLocked", "UserNotActivated")
    }
}
