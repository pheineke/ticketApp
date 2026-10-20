package de.heineke.myvrnlite.ui

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
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

        for (g in tickets.groups) {
            val debugOnly = g.group == Group.EXPIRED || g.group == Group.UNKNOWN
            val open = !debugOnly || g.group in expanded
            val header = list.add(a.section(if (!debugOnly) label(g.group)
            else a.getString(R.string.group_debug, label(g.group), g.tickets.size, if (open) "▾" else "▸")))
            if (list.childCount == 1) header.setPadding(header.paddingLeft, a.dp(4), header.paddingRight, header.paddingBottom)
            if (debugOnly) {
                header.setOnClickListener {
                    if (!expanded.remove(g.group)) expanded += g.group
                    render()
                }
            }
            if (open) for (t in g.tickets) list.add(card(t))
        }

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

    private fun label(g: Group) = activity.getString(when (g) {
        Group.CURRENT -> R.string.group_current
        Group.FUTURE -> R.string.group_future
        Group.QUOTA -> R.string.group_quota
        Group.EXPIRED -> R.string.group_expired
        Group.UNKNOWN -> R.string.group_unknown
    })

    /** Ticket card: logo, name, validity, status chip (GullivrTicketCardView). */
    private fun card(t: Ticket): View {
        val a = activity
        val v = a.layoutInflater.inflate(R.layout.item_ticket, list, false)
        val logo = v.findViewById<FrameLayout>(R.id.ticket_logo)
        val img = v.findViewById<ImageView>(R.id.ticket_logo_img)
        if (t.dticket) {
            img.setImageResource(R.drawable.deutschlandticket)
        } else {
            logo.setBackgroundResource(R.drawable.bg_circle_brand)
            img.setImageResource(R.drawable.ic_ticket)
            img.tint(a.color(android.R.color.white))
            img.layoutParams = FrameLayout.LayoutParams(a.dp(22), a.dp(22), Gravity.CENTER)
        }
        v.findViewById<TextView>(R.id.ticket_name).text = t.name
        v.findViewById<TextView>(R.id.ticket_meta).text = when (t.group) {
            Group.FUTURE -> a.getString(R.string.meta_future, Dates.formatRaw(t.validFrom, false), Dates.formatRaw(t.validTo))
            Group.QUOTA -> a.getString(R.string.meta_quota, t.usedQuota ?: "0", t.allowedQuota)
            Group.EXPIRED -> a.getString(R.string.meta_expired, Dates.formatRaw(t.validTo))
            Group.UNKNOWN -> a.getString(R.string.meta_unknown, t.id.toString())
            Group.CURRENT -> a.getString(R.string.meta_current, Dates.formatRaw(t.validTo))
        }
        v.findViewById<TextView>(R.id.ticket_chip).apply {
            val (text, bg, fg) = when (t.group) {
                Group.CURRENT -> Triple(a.getString(R.string.chip_current), R.drawable.bg_chip_current, a.color(R.color.success))
                Group.FUTURE -> Triple(a.getString(R.string.chip_future, Dates.formatRaw(t.validFrom, false).take(6)),
                    R.drawable.bg_chip_brand, a.attrColor(R.attr.brandInk))
                Group.QUOTA -> Triple(a.getString(R.string.chip_quota), R.drawable.bg_chip_brand, a.attrColor(R.attr.brandInk))
                Group.EXPIRED -> Triple(a.getString(R.string.chip_expired), R.drawable.bg_chip_muted, a.color(R.color.text_muted))
                Group.UNKNOWN -> Triple(a.getString(R.string.chip_unknown), R.drawable.bg_chip_muted, a.color(R.color.text_muted))
            }
            this.text = text
            setBackgroundResource(bg)
            setTextColor(fg)
        }
        v.findViewById<ImageView>(R.id.ticket_chevron).tint(a.color(R.color.text_muted))
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
                setTextColor(a.color(R.color.text_muted))
                setPadding(0, a.dp(10), 0, a.dp(14))
            })
            if (button != null) addView(Button(a, null, 0, R.style.Button).apply {
                this.text = button
                setOnClickListener { onClick?.invoke() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        })
    }

    private fun View.bottomGap() {
        (layoutParams as LinearLayout.LayoutParams).bottomMargin = activity.dp(12)
    }
}
