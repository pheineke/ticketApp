package de.skygazer.myvrnlite.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import de.skygazer.myvrnlite.R
import de.skygazer.myvrnlite.data.NotLoggedIn
import de.skygazer.myvrnlite.data.SessionExpired
import de.skygazer.myvrnlite.net.VrnException
import org.json.JSONObject
import java.io.IOException

fun Context.dp(v: Number): Int = (v.toFloat() * resources.displayMetrics.density + 0.5f).toInt()

fun Context.color(id: Int): Int =
    if (Build.VERSION.SDK_INT >= 23) getColor(id) else @Suppress("DEPRECATION") resources.getColor(id)

/** A palette colour from the theme (R.attr.brand …). */
fun Context.attrColor(attr: Int): Int {
    val tv = TypedValue()
    theme.resolveAttribute(attr, tv, true)
    return if (tv.resourceId != 0) color(tv.resourceId) else tv.data
}

/** A drawable resource from the theme (R.attr.dPanel …): the shapes differ per design. */
fun Context.attrRes(attr: Int): Int {
    val tv = TypedValue()
    theme.resolveAttribute(attr, tv, true)
    return tv.resourceId
}

/** The Modern design is active (Settings > Farben). */
val Context.modern: Boolean
    get() {
        val tv = TypedValue()
        return theme.resolveAttribute(R.attr.modernDesign, tv, true) && tv.data != 0
    }

fun ImageView.tint(color: Int) = setColorFilter(color, PorterDuff.Mode.SRC_IN)

fun Context.openUrl(url: String) {
    try {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (ignored: ActivityNotFoundException) {
        Toast.makeText(this, R.string.no_browser, Toast.LENGTH_SHORT).show()
    }
}

/** A user-facing message for a failed call. */
fun Context.errorText(e: Throwable): String = when {
    e is IOException -> getString(R.string.err_network)
    e is SessionExpired || e is NotLoggedIn -> getString(R.string.err_session_expired)
    e is VrnException && e.reason == "CredentialValidationFailed" -> getString(R.string.err_credentials)
    e is VrnException && e.reason == "UserLocked" -> getString(R.string.err_locked)
    e is VrnException && e.reason == "UserNotActivated" -> getString(R.string.err_not_activated)
    else -> e.message ?: e.javaClass.simpleName
}

fun pretty(json: JSONObject?, fallback: String = ""): String = (json?.toString(2) ?: fallback).take(20_000)

// ---- Builders for the dynamic parts of the pages ------------------------------------

fun LinearLayout.add(view: View, top: Int = 0, width: Int = ViewGroup.LayoutParams.MATCH_PARENT): View {
    addView(view, LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = context.dp(top) })
    return view
}

fun Context.styled(style: Int, text: CharSequence? = null): TextView =
    TextView(this, null, 0, style).apply { if (text != null) this.text = text }

fun Context.section(text: CharSequence) = styled(R.style.Text_Section, text)

/** Modern: the page's large title (and a line under it) above the content, instead of in the bar. */
fun Context.pageHead(title: CharSequence, sub: CharSequence? = null): View = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(2), dp(2), dp(2), dp(12))
    addView(styled(R.style.Text_Heading, title).apply {
        textSize = 32f
        typeface = Typeface.DEFAULT_BOLD
        letterSpacing = -0.02f
    })
    if (sub != null) add(styled(R.style.Text, sub).apply {
        textSize = 15f
        setTextColor(attrColor(R.attr.cTextMuted))
    }, top = 4)
}

fun Context.hint(text: CharSequence) = styled(R.style.Text_Hint, text).apply { setPadding(dp(2), dp(6), dp(2), dp(6)) }

enum class AlertKind { BAD, OK, INFO }

fun Context.alert(kind: AlertKind, text: CharSequence) = styled(R.style.Text, text).apply {
    textSize = 13f
    val pad = dp(10)
    setPadding(dp(if (kind == AlertKind.INFO) 15 else 12), pad, dp(12), pad)
    when (kind) {
        AlertKind.BAD -> { setBackgroundResource(R.drawable.bg_alert_bad); setTextColor(color(R.color.error)) }
        AlertKind.OK -> if (modern) {
            // Modern: what went well in the accent colour (lime).
            background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(attrColor(R.attr.cAccent)) }
            setTextColor(attrColor(R.attr.cAccentInk))
        } else {
            setBackgroundResource(R.drawable.bg_alert_ok)
            setTextColor(color(R.color.success))
        }
        AlertKind.INFO -> { setBackgroundResource(R.drawable.bg_alert_info); setTextColor(attrColor(R.attr.cTextHeading)) }
    }
}

/** Label/value rows with hairlines between them (the web client's .kv table). */
fun Context.kvTable(rows: List<Pair<String, CharSequence>>, padV: Int = 8): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        rows.forEachIndexed { i, (label, value) ->
            if (i > 0) addView(View(context).apply { setBackgroundColor(attrColor(R.attr.cDivider)) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
            addView(LinearLayout(context).apply {
                setPadding(0, dp(padV), 0, dp(padV))
                addView(styled(R.style.Text, label).apply { setTextColor(attrColor(R.attr.cTextMuted)); setPadding(0, 0, dp(12), 0) })
                addView(styled(R.style.Text_Heading, value).apply { gravity = Gravity.END },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            })
        }
    }

/** Monospace block for raw API data (selectable, so it can be copied). */
fun Context.pre(text: String) = styled(R.style.Text, text).apply {
    typeface = Typeface.MONOSPACE
    textSize = 11.5f
    setTextIsSelectable(true)
    setBackgroundResource(R.drawable.bg_pre)
    val pad = dp(10)
    setPadding(pad, pad, pad, pad)
}

/** Collapsible "DEBUG · …" panel; [fill] adds the body views. */
fun Context.debugPanel(title: String, open: Boolean = false, fill: LinearLayout.() -> Unit): View {
    val body = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), 0, dp(12), dp(12))
        visibility = if (open) View.VISIBLE else View.GONE
        fill()
    }
    return LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.bg_debug)
        addView(styled(R.style.Text, getString(R.string.dbg_panel, title)).apply {
            setTextColor(color(R.color.warning))
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setOnClickListener { body.visibility = if (body.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
        })
        addView(body)
    }
}

/** Segmented control: one selected option, filled with the brand colour (Modern: a raised pill on a track). */
fun Context.segmented(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit): LinearLayout =
    if (modern) segmentedModern(options, selected, onSelect) else LinearLayout(this).apply {
        setBackgroundResource(R.drawable.bg_seg)
        setPadding(dp(1), dp(1), dp(1), dp(1))  // keep the selected fill inside the border
        val brand = attrColor(R.attr.brand)
        options.forEachIndexed { i, (key, label) ->
            if (i > 0) addView(View(context).apply { setBackgroundColor(brand) }, LinearLayout.LayoutParams(dp(1), ViewGroup.LayoutParams.MATCH_PARENT))
            val on = key == selected
            addView(styled(R.style.Text, label).apply {
                gravity = Gravity.CENTER
                textSize = 13f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setPadding(dp(6), dp(9), dp(6), dp(9))
                setTextColor(if (on) color(android.R.color.white) else attrColor(R.attr.brandInk))
                if (on) setBackgroundResource(R.drawable.bg_seg_on)
                setOnClickListener { if (!on) onSelect(key) }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

private fun Context.segmentedModern(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit): LinearLayout =
    LinearLayout(this).apply {
        background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(attrColor(R.attr.cTrack))
        }
        setPadding(dp(4), dp(4), dp(4), dp(4))
        options.forEachIndexed { i, (key, label) ->
            val on = key == selected
            addView(styled(R.style.Text, label).apply {
                gravity = Gravity.CENTER
                textSize = 15f
                typeface = if (on) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                setPadding(dp(4), dp(11), dp(4), dp(11))
                setTextColor(attrColor(if (on) R.attr.cTextHeading else R.attr.cTextMuted))
                if (on) {
                    background = GradientDrawable().apply {
                        cornerRadius = dp(11).toFloat()
                        setColor(attrColor(R.attr.cSurface))
                    }
                    elevation = dp(1).toFloat()
                }
                setOnClickListener { if (!on) onSelect(key) }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (i > 0) marginStart = dp(4) })
        }
    }
