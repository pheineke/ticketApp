package de.heineke.myvrnlite.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import de.heineke.myvrnlite.R
import de.heineke.myvrnlite.net.Journey
import de.heineke.myvrnlite.net.Leg
import de.heineke.myvrnlite.net.Mode
import de.heineke.myvrnlite.net.StopTime
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * Views for connections, laid out like vrn.de and the web client: a row per connection
 * (ab/an times | duration over the vehicles | price) and the timeline of a connection.
 */

// Views are built on the main thread only, so one formatter will do.
private val HM = SimpleDateFormat("HH:mm", Locale.GERMANY).apply { timeZone = de.heineke.myvrnlite.data.BERLIN }

fun hm(ms: Long?): String = if (ms == null) "—" else HM.format(Date(ms))

/** Planned time in bold; the corrected time next to it (or below) only when it differs: red late, green early. */
fun Context.clock(p: StopTime, cancelled: Boolean = false, sizeSp: Int = 16, below: Boolean = false, bold: Boolean = true): CharSequence {
    val s = SpannableStringBuilder(hm(p.time))
    s.setSpan(AbsoluteSizeSpan(sizeSp, true), 0, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    if (bold) s.setSpan(StyleSpan(Typeface.BOLD), 0, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    if (cancelled) s.setSpan(StrikethroughSpan(), 0, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    val delay = p.delay
    if (delay != null && delay != 0) {
        val start = s.length
        s.append(if (below) "\n" else " ").append(hm(p.rt))
        s.setSpan(ForegroundColorSpan(color(if (delay > 0) R.color.error else R.color.success)), start, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        s.setSpan(AbsoluteSizeSpan(sizeSp - 3, true), start, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        s.setSpan(StyleSpan(Typeface.BOLD), start, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    return s
}

/** "1 Std 25 Min." with the numbers emphasised. */
fun Context.duration(minutes: Int?): CharSequence {
    if (minutes == null) return "—"
    val s = SpannableStringBuilder()
    fun num(n: Int) {
        val start = s.length
        s.append(n.toString())
        s.setSpan(StyleSpan(Typeface.BOLD), start, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        s.setSpan(ForegroundColorSpan(color(R.color.text_heading)), start, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        s.setSpan(AbsoluteSizeSpan(15, true), start, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    if (minutes >= 60) { num(minutes / 60); s.append(" ").append(getString(R.string.fp_std)).append(" ") }
    num(minutes % 60)
    s.append(" ").append(getString(R.string.fp_min))
    return s
}

fun Context.price(value: Double?): String? =
    value?.let { getString(R.string.fp_price, String.format(Locale.GERMANY, "%.2f", it)) }

/** Vehicle icon + line, as vrn.de shows it: "SWK 105", "RE1", "S2". */
fun Context.lineBadge(leg: Leg): View = LinearLayout(this).apply {
    gravity = Gravity.CENTER_VERTICAL
    contentDescription = "${leg.mode.label} ${leg.label}"
    addView(ImageView(context).apply {
        setImageResource(RouteMap.modeIcon(leg.mode))
        tint(attrColor(R.attr.accentText))
    }, LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(4) })
    addView(styled(R.style.Text_Heading, leg.label.ifEmpty { leg.mode.label }).apply { textSize = 14f })
}

private fun Context.icon(res: Int, sizeDp: Int, tintColor: Int) = ImageView(this).apply {
    setImageResource(res)
    tint(tintColor)
    layoutParams = ViewGroup.LayoutParams(dp(sizeDp), dp(sizeDp))
}

/** Warning line under a connection: cancelled, long-distance train, or disruption notes. */
fun Context.journeyNote(j: Journey): View? {
    val (text, bad) = when {
        j.cancelled -> getString(R.string.fp_cancelled) to true
        j.longDistance.isNotEmpty() -> getString(R.string.fp_long_distance, j.longDistance.joinToString(", ")) to false
        j.infoCount > 0 -> resources.getQuantityString(R.plurals.fp_infos, j.infoCount, j.infoCount) to false
        else -> return null
    }
    val c = color(if (bad) R.color.error else R.color.warning)
    return LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(icon(R.drawable.ic_warning, 14, c), LinearLayout.LayoutParams(dp(14), dp(14)).apply { marginEnd = dp(5) })
        addView(styled(R.style.Text, text).apply { textSize = 12f; setTextColor(c) })
    }
}

/** The ab/an times column of a connection row. */
private fun Context.timesColumn(j: Journey): View = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    minimumWidth = dp(92)
    for ((label, p) in listOf(R.string.fp_ab to j.depPoint, R.string.fp_an to j.arrPoint)) {
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(styled(R.style.Text, getString(label)).apply { setTextColor(color(R.color.text_muted)) },
                LinearLayout.LayoutParams(dp(22), ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(styled(R.style.Text_Heading, clock(p, j.cancelled)))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(30)))
    }
}

/** Duration, changes and price on one line (wraps on narrow panels). */
private fun Context.summaryLine(j: Journey, extra: String? = null): View = FlowLayout(this).apply {
    hGap = dp(10)
    vGap = 0
    addView(styled(R.style.Text, duration(j.minutes)).apply { setTextColor(color(R.color.text_muted)) })
    addView(styled(R.style.Text_Hint, resources.getQuantityString(R.plurals.fp_changes, j.changes, j.changes)).apply { setPadding(0, dp(2), 0, 0) })
    price(j.price)?.let { addView(styled(R.style.Text, it).apply { textSize = 13f; setTextColor(attrColor(R.attr.accentText)) }) }
    extra?.let { addView(styled(R.style.Text_Hint, it).apply { setPadding(0, dp(2), 0, 0) }) }
}

/** One connection in the result list. */
fun Context.journeyRow(j: Journey, onClick: () -> Unit): View = LinearLayout(this).apply {
    setBackgroundResource(R.drawable.bg_card)
    setPadding(dp(14), dp(12), dp(6), dp(12))
    if (j.cancelled) alpha = 0.75f
    addView(timesColumn(j))
    addView(LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(8), dp(4), 0, 0)
        addView(summaryLine(j))
        addView(FlowLayout(context).apply {
            setPadding(0, dp(8), 0, 0)
            j.legs.forEachIndexed { i, leg ->
                if (i > 0) addView(icon(R.drawable.ic_chevron_right, 16, color(R.color.text_muted)))
                if (leg.mode == Mode.WALK) addView(icon(R.drawable.ic_mode_walk, 18, attrColor(R.attr.accentText)).apply {
                    contentDescription = getString(R.string.fp_walk, leg.minutes)
                }) else addView(lineBadge(leg))
            }
        })
        journeyNote(j)?.let { addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) }) }
    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    addView(icon(R.drawable.ic_chevron_right, 20, color(R.color.text_muted)), LinearLayout.LayoutParams(dp(20), dp(20)).apply { gravity = Gravity.CENTER_VERTICAL })
    setOnClickListener { onClick() }
}

/** The summary row on top of a connection's detail. */
fun Context.journeySummary(j: Journey): View = LinearLayout(this).apply {
    setBackgroundResource(R.drawable.bg_panel)
    setPadding(dp(14), dp(12), dp(10), dp(12))
    addView(timesColumn(j))
    addView(LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(8), dp(4), 0, 0)
        addView(summaryLine(j))
        j.dep?.let { addView(styled(R.style.Text_Hint, de.heineke.myvrnlite.data.Dates.format(it, false)).apply { textSize = 13f; setPadding(0, dp(8), 0, 0) }) }
    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
}

// ---- Timeline -----------------------------------------------------------------------

/** The coloured line and stop dots left of the timeline rows. */
class RailView(context: Context, private val kind: Kind, private val lineColor: Int) : View(context) {
    enum class Kind { START, END, LINE, WALK, NONE }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val d = resources.displayMetrics.density

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val dotY = 11 * d
        paint.pathEffect = null
        paint.style = Paint.Style.FILL
        paint.color = lineColor
        when (kind) {
            Kind.LINE -> canvas.drawRect(cx - 2 * d, 0f, cx + 2 * d, height.toFloat(), paint)
            Kind.START -> canvas.drawRect(cx - 2 * d, dotY, cx + 2 * d, height.toFloat(), paint)
            Kind.END -> canvas.drawRect(cx - 2 * d, 0f, cx + 2 * d, dotY, paint)
            Kind.WALK -> {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 2 * d
                paint.pathEffect = DashPathEffect(floatArrayOf(2 * d, 3 * d), 0f)
                canvas.drawLine(cx, 2 * d, cx, height - 2 * d, paint)
            }
            Kind.NONE -> Unit
        }
        if (kind == Kind.START || kind == Kind.END) {
            paint.style = Paint.Style.FILL
            paint.color = context.color(R.color.surface)
            canvas.drawCircle(cx, dotY, 5.5f * d, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 3 * d
            paint.color = lineColor
            canvas.drawCircle(cx, dotY, 5.5f * d, paint)
        }
    }
}

/** A connection's legs: times | coloured line | stops, rides, footpaths, changes. */
fun Context.timeline(j: Journey): View = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setBackgroundResource(R.drawable.bg_panel)
    setPadding(dp(6), dp(12), dp(12), dp(12))
    j.legs.forEachIndexed { i, leg ->
        val muted = color(R.color.text_muted)
        if (leg.mode == Mode.WALK) {
            val text = if (leg.distance != null) getString(R.string.fp_walk_dist, leg.minutes, leg.distance)
            else getString(R.string.fp_walk, leg.minutes)
            addView(tlRow(null, RailView(context, RailView.Kind.WALK, muted), LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(8), 0, dp(8))
                addView(LinearLayout(context).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(icon(R.drawable.ic_mode_walk, 16, muted), LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginEnd = dp(4) })
                    addView(styled(R.style.Text, text).apply { textSize = 13f; setTextColor(muted) })
                })
                if (i == 0) addView(styled(R.style.Text_Hint, getString(R.string.fp_walk_from, leg.origin.name)))
                if (i == j.legs.size - 1) addView(styled(R.style.Text_Hint, getString(R.string.fp_walk_to, leg.destination.name)))
            }))
            return@forEachIndexed
        }
        leg.transfer?.let {
            addView(tlRow(null, RailView(context, RailView.Kind.NONE, 0), styled(R.style.Text, getString(R.string.fp_transfer, it)).apply {
                textSize = 13f
                setTextColor(muted)
                setTypeface(typeface, Typeface.ITALIC)
                setPadding(0, dp(8), 0, dp(8))
            }))
        }
        val c = color(RouteMap.modeColor(leg.mode))
        addView(tlRow(clock(leg.origin, leg.cancelled, 16, below = true), RailView(context, RailView.Kind.START, c), stopLine(leg.origin)))
        addView(tlRow(null, RailView(context, RailView.Kind.LINE, c), rideBlock(leg)))
        addView(tlRow(clock(leg.destination, leg.cancelled, 16, below = true), RailView(context, RailView.Kind.END, c), stopLine(leg.destination)))
    }
}

private fun Context.tlRow(time: CharSequence?, rail: View, content: View): View = LinearLayout(this).apply {
    addView(styled(R.style.Text_Heading, time ?: "").apply { gravity = Gravity.END; setPadding(0, 0, dp(4), 0) },
        LinearLayout.LayoutParams(dp(72), ViewGroup.LayoutParams.WRAP_CONTENT))
    addView(rail, LinearLayout.LayoutParams(dp(22), ViewGroup.LayoutParams.MATCH_PARENT))
    addView(content, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(6) })
}

private fun Context.stopLine(p: StopTime): View = FlowLayout(this).apply {
    hGap = dp(8)
    addView(styled(R.style.Text_Heading, p.name).apply { textSize = 14f })
    if (p.platform.isNotEmpty()) addView(styled(R.style.Text_Hint, getString(R.string.fp_platform, p.platform)).apply { setPadding(0, dp(2), 0, 0) })
}

private fun Context.rideBlock(leg: Leg): View = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(0, dp(8), 0, dp(10))
    addView(FlowLayout(context).apply {
        hGap = dp(8)
        addView(lineBadge(leg))
        if (leg.direction.isNotEmpty()) addView(styled(R.style.Text, "→ ${leg.direction}"))
    })
    val sub = buildString {
        append(getString(R.string.fp_ride_sub, leg.minutes))
        if (leg.operator.isNotEmpty()) append(" · ").append(leg.operator)
        if (!leg.realtime) append(" · ").append(getString(R.string.fp_no_realtime))
        if (leg.cancelled) append(" · ").append(getString(R.string.fp_falls_out))
    }
    addView(styled(R.style.Text_Hint, sub))
    if (leg.stops.isNotEmpty()) {
        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            for (s in leg.stops) {
                addView(LinearLayout(context).apply {
                    addView(styled(R.style.Text, clock(s, sizeSp = 13, bold = false)).apply { setTextColor(color(R.color.text_muted)) },
                        LinearLayout.LayoutParams(dp(52), ViewGroup.LayoutParams.WRAP_CONTENT))
                    addView(styled(R.style.Text, s.name).apply { textSize = 13f })
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) })
            }
        }
        addView(toggle(resources.getQuantityString(R.plurals.fp_stops, leg.stops.size, leg.stops.size), attrColor(R.attr.brandInk), list))
        addView(list)
    }
    for ((title, text) in leg.infos) {
        val body = styled(R.style.Text, text).apply { textSize = 12f; visibility = View.GONE; setPadding(0, dp(2), 0, dp(4)) }
        addView(toggle("⚠ " + title.ifEmpty { getString(R.string.fp_info) }, color(R.color.warning), if (text.isNotEmpty()) body else null))
        if (text.isNotEmpty()) addView(body)
    }
}

/** "▸ 5 Zwischenhalte": tap to show/hide [target]. */
private fun Context.toggle(label: String, c: Int, target: View?): TextView = styled(R.style.Text, "▸ $label").apply {
    textSize = 12f
    setTextColor(c)
    setPadding(0, dp(6), 0, dp(2))
    if (target != null) setOnClickListener {
        val open = target.visibility != View.VISIBLE
        target.visibility = if (open) View.VISIBLE else View.GONE
        text = (if (open) "▾ " else "▸ ") + label
    }
}
