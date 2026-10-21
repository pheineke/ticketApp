package de.heineke.myvrnlite.ui

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import de.heineke.myvrnlite.R
import de.heineke.myvrnlite.data.Dates
import de.heineke.myvrnlite.data.Group
import de.heineke.myvrnlite.data.Ticket

/** My tickets (start tab): grouped as in MyTicketsGroupType, newest data first, offline copy if need be. */
class TicketsPage(activity: MainActivity) : Page(activity, R.layout.page_tickets) {
    private val list: LinearLayout = view.findViewById(R.id.tickets_list)
    private val expanded = HashSet<Group>()  // debug-only groups the user opened

    override fun render() {
        list.removeAllViews()
        val a = activity
        val tickets = repo.tickets()
        val hasCopy = repo.orders != null
        if (!repo.loggedIn && !hasCopy) {
            empty(a.getString(R.string.tickets_logged_out), a.getString(R.string.login)) { a.select(MainActivity.Tab.ACCOUNT) }
            return
        }
        val stand = Dates.format(repo.ordersAt)
        when {
            !repo.loggedIn -> banner(a.getString(R.string.offline_logged_out, stand), a.getString(R.string.login)) {
                a.select(MainActivity.Tab.ACCOUNT)
            }
            repo.offline && hasCopy -> banner(a.getString(R.string.offline_banner, stand), a.getString(R.string.retry)) {
                repo.refreshTickets()
            }
        }
        repo.ordersError?.let { list.add(a.alert(AlertKind.BAD, a.getString(R.string.tickets_load_failed, it)), top = 0).bottomGap() }
        if (!hasCopy) {
            if (repo.loadingTickets) empty(a.getString(R.string.tickets_loading))
            else if (repo.offline) empty(a.getString(R.string.err_network), a.getString(R.string.retry)) { repo.refreshTickets() }
        }

        var n = 0
        for (g in tickets.groups) {
            val debugOnly = g.group == Group.EXPIRED || g.group == Group.UNKNOWN
            val open = !debugOnly || g.group in expanded
            val header = list.add(heading(if (!debugOnly) label(g.group)
            else a.getString(R.string.group_debug, label(g.group), g.tickets.size, if (open) "▾" else "▸")))
            if (list.childCount == 1) header.setPadding(header.paddingLeft, a.dp(2), header.paddingRight, header.paddingBottom)
            if (debugOnly) {
                header.setOnClickListener {
                    if (!expanded.remove(g.group)) expanded += g.group
                    render()
                }
            }
            if (open) addCards(g.tickets.map { card(it, ++n) })
        }
        if (repo.loggedIn && hasCopy) list.add(accountHint(), top = 4)

        if (hasCopy && tickets.groups.isEmpty() && repo.ordersError == null) {
            empty(a.getString(if (tickets.hiddenCount > 0) R.string.tickets_none_valid else R.string.tickets_empty))
        }
        if (tickets.hiddenCount > 0 && !repo.prefs.debug) {
            list.add(a.hint(a.resources.getQuantityString(R.plurals.tickets_hidden, tickets.hiddenCount, tickets.hiddenCount))
                .apply { gravity = Gravity.CENTER })
        }
        if (repo.prefs.debug) {
            list.add(a.debugPanel("ListOrdersV2") {
                val call = repo.ordersCall
                if (call == null) add(a.hint(a.getString(R.string.dbg_not_loaded)))
                else {
                    val n = repo.orders?.length() ?: 0
                    add(a.hint(a.resources.getQuantityString(R.plurals.dbg_orders_status, n, call.code.toString(), n)))
                    add(a.pre(call.request))
                    add(a.pre(pretty(call.json, call.body)), top = 10)
                }
            }, top = 12)
        }
    }

    /** Ticket cards, two per row from tablet width on (as the web client's grid). */
    private fun addCards(cards: List<View>) {
        val a = activity
        val columns = if (a.resources.configuration.screenWidthDp >= 600) 2 else 1
        // list.add() sets its own layout params, so the gap between cards is given here.
        if (columns == 1) return cards.forEachIndexed { i, c -> list.add(c, top = if (i == 0) 0 else 14) }
        cards.chunked(columns).forEachIndexed { r, row ->
            list.add(LinearLayout(a).apply {
                row.forEachIndexed { i, c ->
                    addView(c, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                        if (i > 0) marginStart = a.dp(16)
                    })
                }
                // Keep a lone last card at its column's width.
                repeat(columns - row.size) { addView(View(a), LinearLayout.LayoutParams(0, 0, 1f).apply { marginStart = a.dp(16) }) }
            }, top = if (r == 0) 0 else 14)
        }
    }

    private fun label(g: Group) = activity.getString(when (g) {
        Group.CURRENT -> R.string.group_current
        Group.FUTURE -> R.string.group_future
        Group.QUOTA -> R.string.group_quota
        Group.EXPIRED -> R.string.group_expired
        Group.UNKNOWN -> R.string.group_unknown
    })

    /** Group heading, large as "Tickets" in the official app. */
    private fun heading(text: String) = activity.styled(R.style.Text_Heading, text).apply {
        textSize = 24f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(activity.dp(2), activity.dp(14), activity.dp(2), activity.dp(12))
    }

    /** "Dir werden nicht alle Tickets angezeigt? Überprüfe in deinem Konto …", Konto bold, to the tab. */
    private fun accountHint(): View {
        val a = activity
        val link = a.getString(R.string.tickets_check_account_link)
        val text = a.getString(R.string.tickets_check_account, link)
        return a.styled(R.style.Text, SpannableString(text).apply {
            val i = text.indexOf(link)
            setSpan(StyleSpan(Typeface.BOLD), i, i + link.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }).apply {
            textSize = 15f
            setTextColor(a.attrColor(R.attr.cTextHeading))
            setPadding(a.dp(2), a.dp(10), a.dp(2), a.dp(10))
            setOnClickListener { a.select(MainActivity.Tab.ACCOUNT) }
        }
    }

    /**
     * Ticket-shaped card (as the official app): a stub with the icon and "#n name", the body
     * with the name, the status pill and the validity. The card is always light, in dark mode too.
     */
    private fun card(t: Ticket, n: Int): View {
        val a = activity
        val v = a.layoutInflater.inflate(R.layout.item_ticket, list, false)
        val ink = a.attrColor(R.attr.cTcardInk)
        v.background = TicketCardDrawable(a.attrColor(R.attr.cTcardTop), a.attrColor(R.attr.cTcardBody), a.attrColor(R.attr.cBg),
            a.resources.getDimension(R.dimen.ticket_stub), a.dp(14).toFloat(), a.dp(11).toFloat())
        v.findViewById<ImageView>(R.id.ticket_logo_img).apply {
            if (t.dticket) setImageResource(R.drawable.deutschlandticket_light)
            else {
                setImageResource(R.drawable.ic_ticket)
                tint(ink)
            }
        }
        v.findViewById<TextView>(R.id.ticket_no).text = a.getString(R.string.ticket_card_no, n, t.name)
        v.findViewById<TextView>(R.id.ticket_name).text = t.name
        v.findViewById<TextView>(R.id.ticket_meta).text = when (t.group) {
            Group.QUOTA -> a.getString(R.string.meta_quota, t.usedQuota ?: "0", t.allowedQuota)
            Group.EXPIRED -> Dates.formatRaw(t.validTo)
            Group.UNKNOWN -> a.getString(R.string.meta_unknown, t.id.toString())
            Group.CURRENT, Group.FUTURE -> a.getString(R.string.validity_until, Dates.formatRaw(t.validFrom), Dates.formatRaw(t.validTo))
        }
        v.findViewById<TextView>(R.id.ticket_chip).apply {
            val pill = GradientDrawable().apply { cornerRadius = a.dp(10).toFloat() }
            val label = when (t.group) {
                Group.CURRENT -> R.string.chip_current.also { pill.setColor(VALID_GREEN); setTextColor(Color.WHITE) }
                Group.FUTURE, Group.QUOTA -> (if (t.group == Group.FUTURE) R.string.chip_future else R.string.chip_quota).also {
                    pill.setColor(a.attrColor(R.attr.cTcardTop))
                    pill.setStroke(a.dp(1), ink)
                    setTextColor(ink)
                }
                Group.EXPIRED, Group.UNKNOWN -> (if (t.group == Group.EXPIRED) R.string.chip_expired else R.string.chip_unknown).also {
                    pill.setColor(0x14000000)
                    setTextColor(ink)
                }
            }
            setText(label)
            background = pill
        }
        if (t.group == Group.EXPIRED) v.alpha = 0.6f
        t.id?.let { id -> v.setOnClickListener { a.startActivity(TicketActivity.intent(a, id)) } }
        return v
    }

    /** Info strip with an action button (offline, logged out). */
    private fun banner(text: String, button: String, onClick: () -> Unit) {
        val a = activity
        list.add(LinearLayout(a).apply {
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_alert_info)
            setPadding(a.dp(15), a.dp(8), a.dp(8), a.dp(8))
            addView(a.styled(R.style.Text_Heading, text).apply { textSize = 13f },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(Button(a, null, 0, R.style.Button_Small_Outline).apply {
                this.text = button
                setOnClickListener { onClick() }
            })
        }).bottomGap()
    }

    private fun empty(text: String, button: String? = null, onClick: (() -> Unit)? = null) {
        val a = activity
        list.add(LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(a.dp(20), a.dp(40), a.dp(20), a.dp(40))
            addView(ImageView(a).apply {
                setImageResource(R.drawable.ic_ticket)
                tint(a.attrColor(R.attr.brandInk))
                alpha = 0.6f
            }, LinearLayout.LayoutParams(a.dp(48), a.dp(48)))
            addView(a.styled(R.style.Text, text).apply {
                gravity = Gravity.CENTER
                setTextColor(a.attrColor(R.attr.cTextMuted))
                setPadding(0, a.dp(10), 0, a.dp(14))
            })
            if (button != null) addView(Button(a, null, 0, R.style.Button).apply {
                this.text = button
                setOnClickListener { onClick?.invoke() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        })
    }

    private companion object {
        /** The "Gültig" pill: dark green with white, readable on the light card. */
        const val VALID_GREEN = 0xff237804.toInt()
    }

    private fun View.bottomGap() {
        (layoutParams as LinearLayout.LayoutParams).bottomMargin = activity.dp(12)
    }
}
