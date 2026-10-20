package de.heineke.myvrnlite.ui

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import de.heineke.myvrnlite.R
import de.heineke.myvrnlite.data.Dates
import de.heineke.myvrnlite.data.Group
import de.heineke.myvrnlite.data.Ticket
import java.util.Locale

/**
 * Ticket detail with the barcode — the screen shown at a ticket check. Full brightness and
 * screen on while visible; the count-up timer shows the ticket is live, not a screenshot.
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
    private lateinit var counterInfo: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent(R.layout.activity_ticket)
        ticketId = intent.getLongExtra(EXTRA_ID, -1)
        shownAt = savedInstanceState?.getLong(STATE_SHOWN_AT) ?: SystemClock.elapsedRealtime()
        codebox = findViewById(R.id.codebox)
        codeImage = findViewById(R.id.code_image)
        codeLabel = findViewById(R.id.code_label)
        counter = findViewById(R.id.counter)
        counterInfo = findViewById(R.id.counter_info)

        titleView.setText(R.string.title_ticket)
        navButton.setImageResource(R.drawable.ic_arrow_back)
        navButton.contentDescription = getString(R.string.back)
        navButton.setOnClickListener { finish() }
        action.setOnClickListener { repo.loadQr(ticketId, force = true) }
        (counterInfo as TextView).compoundDrawablesRelative[2]?.mutate()?.setTint(color(R.color.text_muted))
        counterInfo.setOnClickListener {
            AlertDialog.Builder(this).setTitle(R.string.counter_info).setMessage(R.string.counter_info_text)
                .setPositiveButton(R.string.understood, null).show()
        }
        // Tap spins the code card a full turn; the next tap spins it back the other way.
        codebox.setOnClickListener {
            turned = !turned
            codebox.animate().rotation(if (turned) 360f else 0f).setDuration(600)
                .setInterpolator(AccelerateDecelerateInterpolator()).start()
        }

        // As large as possible (scanners read big codes best), but with header and timer on screen;
        // the ticket data below may scroll.
        val dm = resources.displayMetrics
        val box = maxOf(dp(220), minOf((dm.widthPixels * 0.84f).toInt(), dp(380), dm.heightPixels - dp(230)))
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
        findViewById<TextView>(R.id.ticket_sub).text = buildString {
            append(when (t.group) {
                Group.EXPIRED -> getString(R.string.ticket_sub_expired, Dates.formatRaw(t.validTo))
                Group.FUTURE -> getString(R.string.ticket_sub_future, Dates.formatRaw(t.validFrom))
                else -> getString(R.string.ticket_sub_current, Dates.formatRaw(t.validTo))
            })
            t.area?.let { append(getString(R.string.ticket_sub_area, it)) }
            t.priceLevel?.let { append(getString(R.string.ticket_sub_price, it)) }
        }

        val qr = repo.qr[ticketId]
        val code = qr?.code.orEmpty()
        showCode(code.isNotEmpty())
        when {
            code.isNotEmpty() -> {
                draw(code)
                if (qr?.call == null) alerts.add(hint(getString(R.string.ticket_offline_copy, Dates.format(qr!!.savedAt))).apply {
                    gravity = Gravity.CENTER
                })
            }
            ticketId in repo.loadingQr -> alerts.add(hint(getString(R.string.ticket_code_loading)).apply { gravity = Gravity.CENTER })
            else -> {
                val reason = repo.qrError[ticketId]?.ifEmpty { null }
                alerts.add(alert(AlertKind.BAD, getString(R.string.no_qr) + (reason?.let { " $it" } ?: "")))
            }
        }

        findViewById<FrameLayout>(R.id.ticket_kv).apply {
            removeAllViews()
            addView(kvTable(listOfNotNull(
                t.holder?.let { getString(R.string.kv_holder) to it },
                t.dob?.let { getString(R.string.kv_dob) to Dates.formatRaw(it, false) },
                getString(R.string.kv_validity) to getString(R.string.validity_range, Dates.formatRaw(t.validFrom, false), Dates.formatRaw(t.validTo)),
                t.number?.let { getString(R.string.kv_number) to it },
            ), padV = 6))
        }
        renderDebug(t, code)
    }

    private fun showCode(on: Boolean) {
        val v = if (on) View.VISIBLE else View.GONE
        codebox.visibility = v
        counter.visibility = v
        counterInfo.visibility = v
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
                    setTextColor(color(R.color.text))
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
    }

    companion object {
        private const val EXTRA_ID = "ticket_id"
        private const val STATE_SHOWN_AT = "shown_at"

        fun intent(context: Context, ticketId: Long): Intent =
            Intent(context, TicketActivity::class.java).putExtra(EXTRA_ID, ticketId)
    }
}
