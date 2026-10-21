package de.skygazer.myvrnlite.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import de.skygazer.myvrnlite.R
import de.skygazer.myvrnlite.data.Dates
import de.skygazer.myvrnlite.data.Group
import de.skygazer.myvrnlite.data.InvoiceProvider
import de.skygazer.myvrnlite.data.Ticket
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
        setContent(if (modern) R.layout.activity_ticket_modern else R.layout.activity_ticket)
        ticketId = intent.getLongExtra(EXTRA_ID, -1)
        shownAt = savedInstanceState?.getLong(STATE_SHOWN_AT) ?: SystemClock.elapsedRealtime()
        codebox = findViewById(R.id.codebox)
        codeImage = findViewById(R.id.code_image)
        codeLabel = findViewById(R.id.code_label)
        counter = findViewById(R.id.counter)
        styleBars()

        backBar(R.string.title_ticket)
        action.setOnClickListener { repo.loadQr(ticketId, force = true) }
        counter.setOnClickListener {
            val booked = repo.findTicket(ticketId)?.booked?.let { getString(R.string.counter_info_booked, Dates.formatRaw(it)) }
            infoSheet(getString(R.string.counter_info), listOfNotNull(getString(R.string.counter_info_text), booked))
        }
        // Tap spins the code card a full turn; the next tap spins it back the other way.
        codebox.setOnClickListener {
            turned = !turned
            codebox.animate().rotation(if (turned) 360f else 0f).setDuration(600)
                .setInterpolator(AccelerateDecelerateInterpolator()).start()
        }
        if (modern) {
            // Modern: holding the code lifts it to the front, as large as fits; letting go puts it back.
            codebox.setOnLongClickListener { liftCode() }
            codebox.setOnTouchListener { _, e ->
                if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) dropCode()
                false  // click and long click still run
            }
        }

        // As large as possible (scanners read big codes best), but with name and timer on screen;
        // the ticket data below may scroll.
        val dm = resources.displayMetrics
        val box = if (modern) {
            // Inside the ticket card (page and card padding), below its header row.
            counter.background = GradientDrawable().apply {
                cornerRadius = dp(99).toFloat()
                setStroke(dp(1), attrColor(R.attr.cDivider))
            }
            maxOf(dp(220), minOf(dm.widthPixels - dp(74), dp(340), dm.heightPixels - dp(260)))
        } else maxOf(dp(220), minOf(dm.widthPixels - dp(48), dp(400), dm.heightPixels - dp(220)))
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
        findViewById<TextView>(R.id.ticket_name).text = if (modern && t.dticket) getString(R.string.dticket_label) else t.name
        if (modern) renderModernHead(t)
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
            if (t.dticket && modern) {
                add(styled(R.style.Text, getString(R.string.ticket_terms_modern)).apply {
                    textSize = 13f
                    setTextColor(attrColor(R.attr.cTextMuted))
                    setLineSpacing(0f, 1.2f)
                }, top = 16)
            } else if (t.dticket) {
                rule(top = 20)
                add(ink(getString(R.string.ticket_terms_personal)), top = 14)
                add(ink(getString(R.string.ticket_terms_tariff)), top = 10)
            }
        }
        if (modern) renderTiles(t) else findViewById<LinearLayout>(R.id.ticket_kv).apply {
            removeAllViews()
            rule(top = 20)
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

    // ---- Modern: state pill, number, name and the data as tiles ---------------------------

    private fun renderModernHead(t: Ticket) {
        findViewById<TextView>(R.id.ticket_pill).apply {
            val valid = t.group == Group.CURRENT
            setText(when (t.group) {
                Group.CURRENT -> R.string.state_current
                Group.FUTURE -> R.string.state_future
                Group.QUOTA -> R.string.chip_quota
                Group.EXPIRED -> R.string.chip_expired
                Group.UNKNOWN -> R.string.chip_unknown
            })
            setTextColor(attrColor(if (valid) R.attr.cAccentInk else R.attr.cTextHeading))
            background = GradientDrawable().apply {
                cornerRadius = dp(99).toFloat()
                setColor(attrColor(if (valid) R.attr.cAccent else R.attr.cTrack))
            }
        }
        findViewById<TextView>(R.id.ticket_number).apply {
            text = t.number
            visibility = if (t.number != null) View.VISIBLE else View.GONE
        }
        findViewById<View>(R.id.ticket_hint).visibility = if (t.dticket) View.VISIBLE else View.GONE
        val month = t.month?.let { (m, y) -> "${getString(R.string.months).split(',')[m]} $y" }
        findViewById<TextView>(R.id.ticket_title).text = month ?: t.name
        findViewById<TextView>(R.id.ticket_sub).text =
            listOfNotNull(if (month != null) t.name else null, t.period ?: Dates.formatRaw(t.validTo)).joinToString(" · ")
    }

    /** Label/value tiles, two per row. */
    private fun renderTiles(t: Ticket) {
        val tiles = listOfNotNull(
            t.holder?.let { R.string.kv_holder to it },
            t.dob?.let { R.string.kv_dob to Dates.formatRaw(it, false) },
            R.string.kv_valid_from to Dates.formatRaw(t.validFrom),
            R.string.kv_valid_to to Dates.formatRaw(t.validTo),
            if (t.dticket) R.string.kv_valid_in to getString(R.string.valid_in_germany) else t.area?.let { R.string.kv_area to it },
            t.booked?.let { R.string.kv_booked to Dates.formatRaw(it) },
            t.priceLevel?.let { R.string.kv_price_level to it },
        )
        findViewById<LinearLayout>(R.id.ticket_kv).apply {
            removeAllViews()
            tiles.chunked(2).forEach { pair ->
                add(LinearLayout(context).apply {
                    pair.forEachIndexed { i, (label, value) ->
                        addView(tile(getString(label), value), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                            if (i > 0) marginStart = dp(12)
                        })
                    }
                    if (pair.size == 1) addView(View(context), LinearLayout.LayoutParams(0, 0, 1f).apply { marginStart = dp(12) })
                }, top = 12)
            }
        }
    }

    private fun tile(label: String, value: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.m_tile)
        setPadding(dp(14), dp(14), dp(14), dp(14))
        addView(styled(R.style.Text, label).apply { textSize = 13f; setTextColor(attrColor(R.attr.cTextMuted)) })
        add(styled(R.style.Text, value).apply {
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(attrColor(R.attr.cTextHeading))
        }, top = 4)
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

    /** A 1dp line. Added with its own height: a plain View under WRAP_CONTENT takes all the room there is. */
    private fun LinearLayout.rule(top: Int) = addView(View(context).apply { setBackgroundColor(attrColor(R.attr.cTicketLine)) },
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply { topMargin = dp(top) })

    /** "Label:  value" with the values lined up, as on the official ticket. */
    private fun row(label: String, value: String) = LinearLayout(this).apply {
        addView(ink(getString(R.string.kv_label, label)), LinearLayout.LayoutParams(dp(128), ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(ink(value).apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun options(code: String) =
        repo.prefs.barcode?.let { Barcode.Options(it.first, it.second) } ?: Barcode.defaultOptions(code)

    // ---- Modern: the code lifted to the front while held ------------------------------

    private var lifted: View? = null

    /** A dimmed page and the code on a white card in the middle, rendered for its size (sharp). */
    private fun liftCode(): Boolean {
        val (code, opts) = drawn ?: return false
        if (lifted != null) return true
        val dm = resources.displayMetrics
        val size = minOf(dm.widthPixels, dm.heightPixels) - dp(48)
        val bitmap = try {
            Barcode.render(code, opts, size - dp(32))
        } catch (e: Exception) {
            return false
        }
        val card = FrameLayout(this).apply {
            background = GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setColor(color(R.color.code_paper)) }
            setPadding(dp(16), dp(16), dp(16), dp(16))
            elevation = dp(12).toFloat()
            addView(ImageView(this@TicketActivity).apply {
                setImageDrawable(BitmapDrawable(resources, bitmap).apply { isFilterBitmap = false })
                contentDescription = getString(R.string.ticket_code)
            }, FrameLayout.LayoutParams(size - dp(32), size - dp(32)))
        }
        val overlay = FrameLayout(this).apply {
            setBackgroundColor(0x99000000.toInt())
            alpha = 0f
            addView(card, FrameLayout.LayoutParams(size, size, Gravity.CENTER))
        }
        (window.decorView as ViewGroup).addView(overlay, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        overlay.animate().alpha(1f).setDuration(120).start()
        card.scaleX = 0.85f
        card.scaleY = 0.85f
        card.animate().scaleX(1f).scaleY(1f).setDuration(160).setInterpolator(DecelerateInterpolator()).start()
        lifted = overlay
        return true  // consumes the long click: no spin on release
    }

    private fun dropCode() {
        val overlay = lifted ?: return
        lifted = null
        overlay.animate().alpha(0f).setDuration(120).withEndAction { (overlay.parent as? ViewGroup)?.removeView(overlay) }.start()
    }

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
