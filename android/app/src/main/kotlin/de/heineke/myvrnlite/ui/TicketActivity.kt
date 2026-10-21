package de.heineke.myvrnlite.ui

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.drawable.BitmapDrawable
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import de.heineke.myvrnlite.R
import de.heineke.myvrnlite.data.Dates
import de.heineke.myvrnlite.data.Group
import de.heineke.myvrnlite.data.InvoiceProvider
import de.heineke.myvrnlite.data.Ticket
import java.util.Locale

/**
 * Ticket detail with the barcode — the screen shown at a ticket check, laid out as in the
 * official app: white with VRN navy ink, navy with white in dark mode. Full brightness
 * and screen on while visible; the count-up timer shows the ticket is live, not a screenshot.
 */
class TicketActivity : BaseActivity() {
    private var ticketId = 0L
    private var shownAt = 0L
    private var turned = false
    private var drawn: Pair<String, Barcode.Options>? = null
    private var codeSize = 0
    private val ticker = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            val s = (SystemClock.elapsedRealtime() - shownAt) / 1000
            counter.text = String.format(Locale.ROOT, "%02d:%02d", s / 60, s % 60)
            ticker.postDelayed(this, 1000)
        }
    }
    private val onChange: () -> Unit = { render() }

    private lateinit var codebox: LinearLayout
    private lateinit var codeImage: ImageView
    private lateinit var codeLabel: TextView
    private lateinit var counter: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent(R.layout.activity_ticket)
        ticketId = intent.getLongExtra(EXTRA_ID, -1)
        shownAt = savedInstanceState?.getLong(STATE_SHOWN_AT) ?: SystemClock.elapsedRealtime()
        codebox = findViewById(R.id.codebox)
        codeImage = findViewById(R.id.code_image)
        codeLabel = findViewById(R.id.code_label)
        counter = findViewById(R.id.counter)
        styleBars()

        titleView.setText(R.string.title_ticket)
        navButton.setImageResource(R.drawable.ic_arrow_back)
        navButton.contentDescription = getString(R.string.back)
        navButton.setOnClickListener { finish() }
        action.setOnClickListener { repo.loadQr(ticketId, force = true) }
        counter.setOnClickListener {
            AlertDialog.Builder(this).setTitle(R.string.counter_info).setMessage(R.string.counter_info_text)
                .setPositiveButton(R.string.understood, null).show()
        }
        // Tap spins the code card a full turn; the next tap spins it back the other way.
        codebox.setOnClickListener {
            turned = !turned
            codebox.animate().rotation(if (turned) 360f else 0f).setDuration(600)
                .setInterpolator(AccelerateDecelerateInterpolator()).start()
        }

        // As large as possible (scanners read big codes best), but with name and timer on screen;
        // the ticket data below may scroll.
        val dm = resources.displayMetrics
        val box = maxOf(dp(220), minOf(dm.widthPixels - dp(48), dp(400), dm.heightPixels - dp(220)))
        codebox.layoutParams.width = box
        codeSize = box - dp(24)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL }
        repo.loadQr(ticketId)
    }

    override fun onStart() {
        super.onStart()
        repo.addListener(onChange)
        render()
        ticker.post(tick)
    }

    override fun onStop() {
        ticker.removeCallbacks(tick)
        repo.removeListener(onChange)
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(STATE_SHOWN_AT, shownAt)
    }

    private fun render() {
        findViewById<View>(R.id.debug_flag).visibility = if (repo.prefs.debug) View.VISIBLE else View.GONE
        action.visibility = if (repo.loggedIn) View.VISIBLE else View.GONE
        spin(action, ticketId in repo.loadingQr)
        val alerts = findViewById<LinearLayout>(R.id.ticket_alerts).apply { removeAllViews() }
        val t = repo.findTicket(ticketId)
        if (t == null) {
            showCode(false)
            alerts.add(alert(AlertKind.BAD, getString(R.string.ticket_not_found)))
            return
        }
        findViewById<TextView>(R.id.ticket_name).text = t.name
        when (t.group) {
            Group.EXPIRED -> alerts.add(alert(AlertKind.BAD, getString(R.string.ticket_sub_expired, Dates.formatRaw(t.validTo))), top = 8)
            Group.FUTURE -> alerts.add(alert(AlertKind.INFO, getString(R.string.ticket_sub_future, Dates.formatRaw(t.validFrom))), top = 8)
            else -> Unit
        }

        val qr = repo.qr[ticketId]
        val code = qr?.code.orEmpty()
        showCode(code.isNotEmpty())
        when {
            code.isNotEmpty() -> {
                draw(code)
                if (qr?.call == null) alerts.add(note(getString(R.string.ticket_offline_copy, Dates.format(qr!!.savedAt))))
            }
            ticketId in repo.loadingQr -> alerts.add(note(getString(R.string.ticket_code_loading)))
            else -> {
                val reason = repo.qrError[ticketId]?.ifEmpty { null }
                alerts.add(alert(AlertKind.BAD, getString(R.string.no_qr) + (reason?.let { " $it" } ?: "")))
            }
        }

        findViewById<LinearLayout>(R.id.ticket_terms).apply {
            removeAllViews()
            if (t.dticket) {
                add(rule(), top = 20)
                add(ink(getString(R.string.ticket_terms_personal)), top = 14)
                add(ink(getString(R.string.ticket_terms_tariff)), top = 10)
            }
        }
        findViewById<LinearLayout>(R.id.ticket_kv).apply {
            removeAllViews()
            add(rule(), top = 20)
            listOfNotNull(
                t.holder?.let { R.string.kv_holder to it },
                t.dob?.let { R.string.kv_dob to Dates.formatRaw(it, false) },
                R.string.kv_valid to getString(R.string.validity_until, Dates.formatRaw(t.validFrom), Dates.formatRaw(t.validTo)),
                t.booked?.let { R.string.kv_booked to Dates.formatRaw(it) },
                t.area?.let { R.string.kv_area to it },
                t.priceLevel?.let { R.string.kv_price_level to it },
                t.number?.let { R.string.kv_number to it },
            ).forEachIndexed { i, (label, value) -> add(row(getString(label), value), top = if (i == 0) 14 else 8) }
        }
        renderInvoice(t)
        renderDebug(t, code)
    }

    // ---- Invoice: for showing what was bought when the ticket itself won't load ----------

    private fun renderInvoice(t: Ticket) {
        val link = findViewById<TextView>(R.id.ticket_invoice)
        val orderId = t.orderId
        val saved = orderId != null && repo.invoiceFile(orderId).length() > 0
        link.visibility = if (orderId != null && (repo.loggedIn || saved)) View.VISIBLE else View.GONE
        if (orderId == null) return
        val loading = orderId in repo.loadingInvoice
        link.setText(if (loading) R.string.invoice_loading else R.string.invoice_download)
        link.isEnabled = !loading
        link.setOnClickListener {
            repo.invoice(orderId) { r ->
                r.onSuccess { openInvoice(orderId, t.orderNumber ?: orderId.toString()) }
                    .onFailure { Toast.makeText(this, getString(R.string.invoice_failed, errorText(it)), Toast.LENGTH_LONG).show() }
            }
        }
    }

    /** In a PDF viewer; without one, offer to share (save, mail) it instead. */
    private fun openInvoice(orderId: Long, number: String) {
        val name = getString(R.string.invoice_name, number.replace(Regex("[^A-Za-z0-9-]"), "_"))
        val uri = InvoiceProvider.uri(this, orderId, name)
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/pdf").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val share = Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        for (intent in listOf(view, Intent.createChooser(share, null))) {
            try {
                return startActivity(intent)
            } catch (ignored: ActivityNotFoundException) {
            }
        }
        Toast.makeText(this, R.string.invoice_no_viewer, Toast.LENGTH_LONG).show()
    }

    private fun showCode(on: Boolean) {
        val v = if (on) View.VISIBLE else View.GONE
        codebox.visibility = v
        counter.visibility = v
    }

    // ---- The official app's look: ink on the page colour ------------------------------

    /** The app bar runs into the page, without its hairline (bars and colours come from the theme). */
    private fun styleBars() {
        findViewById<View>(R.id.app_bar).background = null
    }

    private fun ink(text: CharSequence, size: Float = 15f) = styled(R.style.Text, text).apply {
        setTextColor(attrColor(R.attr.cTextHeading))
        textSize = size
        setLineSpacing(0f, 1.15f)
    }

    private fun note(text: CharSequence) = ink(text, 13f).apply {
        gravity = Gravity.CENTER
        alpha = 0.8f
    }

    private fun rule() = View(this).apply {
        setBackgroundColor(attrColor(R.attr.cTicketLine))
        minimumHeight = dp(1)
    }

    /** "Label:  value" with the values lined up, as on the official ticket. */
    private fun row(label: String, value: String) = LinearLayout(this).apply {
        addView(ink(getString(R.string.kv_label, label)), LinearLayout.LayoutParams(dp(128), ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(ink(value).apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun options(code: String) =
        repo.prefs.barcode?.let { Barcode.Options(it.first, it.second) } ?: Barcode.defaultOptions(code)

    private fun draw(code: String) {
        val opts = options(code)
        if (drawn == code to opts) return
        drawn = code to opts
        try {
            val bitmap = Barcode.render(code, opts, codeSize)
            codeImage.setImageDrawable(BitmapDrawable(resources, bitmap).apply { isFilterBitmap = false })
            codeLabel.visibility = View.GONE
        } catch (e: Exception) {
            codeImage.setImageDrawable(null)
            codeLabel.text = if (e is UnsupportedOperationException) getString(R.string.svg_unsupported)
            else getString(R.string.render_failed, e.message ?: e.javaClass.simpleName)
            codeLabel.visibility = View.VISIBLE
        }
    }

    private fun renderDebug(t: Ticket, code: String) {
        val debug = findViewById<LinearLayout>(R.id.ticket_debug).apply { removeAllViews() }
        if (!repo.prefs.debug) return
        val qr = repo.qr[ticketId]
        debug.add(debugPanel("Barcode") {
            if (code.isNotEmpty() && Barcode.kind(code) == Barcode.Kind.DATA) {
                val opts = options(code)
                add(segmented(listOf("aztec" to "Aztec", "qr" to "QR"), opts.sym) {
                    repo.prefs.barcode = it to opts.b64
                    render()
                })
                add(CheckBox(this@TicketActivity).apply {
                    setText(R.string.dbg_b64)
                    setTextColor(attrColor(R.attr.cText))
                    isChecked = opts.b64
                    setOnCheckedChangeListener { _, on ->
                        repo.prefs.barcode = opts.sym to on
                        render()
                    }
                }, top = 6)
            }
            val status = qr?.call?.code?.toString() ?: "—"
            add(hint(getString(R.string.dbg_barcode_status, status, t.id.toString(), Barcode.kind(code).name.lowercase(Locale.ROOT))))
            qr?.call?.let {
                add(pre(it.request))
                add(pre(pretty(it.json, it.body)), top = 6)
            }
        }, top = 12)
        debug.add(debugPanel(getString(R.string.dbg_ticket_raw)) { add(pre(pretty(t.raw))) }, top = 12)
        repo.invoiceCall?.let { call ->
            debug.add(debugPanel("Rechnung") {
                add(pre(call.request))
                add(pre(call.body.take(2000)), top = 6)
            }, top = 12)
        }
    }

    companion object {
        private const val EXTRA_ID = "ticket_id"
        private const val STATE_SHOWN_AT = "shown_at"

        fun intent(context: Context, ticketId: Long): Intent =
            Intent(context, TicketActivity::class.java).putExtra(EXTRA_ID, ticketId)
    }
}
