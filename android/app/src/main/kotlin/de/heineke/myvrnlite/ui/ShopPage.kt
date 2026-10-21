package de.heineke.myvrnlite.ui

import android.app.Activity
import android.app.AlertDialog
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import de.heineke.myvrnlite.R
import de.heineke.myvrnlite.data.Dates
import de.heineke.myvrnlite.data.OrderResult
import de.heineke.myvrnlite.data.Shop

/**
 * Ticketshop: the Deutschland-Semesterticket (0 €) and the student status behind it, as in
 * the web client (webapp/templates/ticketshop.html). The Hochschul-Login runs in [DfnActivity].
 */
class ShopPage(activity: MainActivity) : Page(activity, R.layout.page_shop) {
    private val body: LinearLayout = view.findViewById(R.id.shop_body)
    private var university: Shop.University? = null
    /** Back from the Hochschul-Login: show the outcome once the entitlements are reloaded. */
    private var verified = false

    /** Tab opened: last visit's outcomes go; the profile names the ticket holder in the order. */
    fun onShown() {
        verified = false
        if (repo.orderResult != null) repo.clearOrderResult()
        if (!repo.loggedIn) return
        if (repo.entitlements == null) repo.refreshShop()
        if (repo.profileResult == null) repo.refreshProfile()
    }

    fun onVerifyResult(resultCode: Int) {
        verified = resultCode == Activity.RESULT_OK
        repo.refreshShop()
    }

    override fun render() {
        body.removeAllViews()
        val a = activity
        if (!repo.loggedIn) return empty()
        val st = repo.shopState()

        repo.orderResult?.let { orderResult(it) }
        if (verified && st != null && !repo.loadingShop) {
            val c = st.current
            body.add(if (c != null) a.alert(AlertKind.OK, a.getString(R.string.shop_verified, day(c.until)))
            else a.alert(AlertKind.BAD, a.getString(R.string.shop_not_verified))).bottomGap()
        }

        // ---- Deutschland-Semesterticket
        body.add(a.section(a.getString(R.string.shop_semester)).apply { setPadding(paddingLeft, a.dp(4), paddingRight, paddingBottom) })
        body.add(panel {
            add(LinearLayout(a).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(ImageView(a).apply { setImageResource(R.drawable.deutschlandticket) }, LinearLayout.LayoutParams(a.dp(44), a.dp(44)))
                addView(LinearLayout(a).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(a.dp(12), 0, 0, 0)
                    addView(a.styled(R.style.Text_Heading, a.getString(R.string.shop_semester)))
                    addView(a.styled(R.style.Text_Hint, a.getString(R.string.shop_semester_sub)))
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            })
            when {
                st == null && repo.shopError != null -> add(a.alert(AlertKind.BAD, a.getString(R.string.shop_load_failed, repo.shopError)), top = 12)
                st == null -> add(a.hint(a.getString(R.string.shop_loading)), top = 8)
                st.running != null -> add(a.alert(AlertKind.OK, a.getString(R.string.shop_running,
                    st.thisMonth?.to?.let { a.getString(R.string.shop_running_until, day(it)) } ?: "", day(st.current?.until))), top = 12)
                st.current != null -> {
                    add(a.hint(a.getString(R.string.shop_can_buy, day(st.current.until), day(st.monthStart))), top = 8)
                    add(Button(a, null, 0, R.style.Button).apply {
                        setText(if (repo.buying) R.string.shop_buying else R.string.shop_buy)
                        isEnabled = !repo.buying
                        alpha = if (repo.buying) 0.6f else 1f
                        setOnClickListener { confirm() }
                    }, top = 6)
                }
                else -> add(a.alert(AlertKind.INFO, a.getString(R.string.shop_no_entitlement,
                    st.student.firstOrNull()?.let { a.getString(R.string.shop_last_entitlement, day(it.until)) } ?: "")), top = 12)
            }
        })

        // ---- Student status and the Hochschul-Login
        body.add(a.section(a.getString(R.string.shop_status)))
        body.add(panel { status(st) })

        if (st != null && st.tickets.isNotEmpty()) {
            body.add(a.section(a.getString(R.string.shop_history)))
            body.add(panel {
                add(a.kvTable(st.tickets.take(6).map { t ->
                    (t.from?.let { Dates.format(it, false).substring(3) } ?: "—") to a.getString(R.string.shop_order, t.orderNumber)
                }, padV = 6))
            })
        }

        if (repo.prefs.debug) {
            body.add(a.debugPanel("Berechtigungen") {
                if (repo.entitlementCalls.isEmpty()) add(a.hint(a.getString(R.string.dbg_not_loaded)))
                repo.entitlementCalls.forEach { c ->
                    add(a.hint(c.code.toString()), top = 6)
                    add(a.pre(c.request))
                    add(a.pre(pretty(c.json, c.body)), top = 6)
                }
            }, top = 16)
        }
    }

    private fun LinearLayout.status(st: Shop.State?) {
        val a = activity
        if (st == null) return
        if (st.student.isNotEmpty()) {
            add(a.kvTable(st.student.take(4).map { e ->
                val state = a.getString(statusLabel(e.status)) + (if (e === st.current) a.getString(R.string.shop_status_current) else "")
                val created = a.getString(R.string.shop_status_created, e.created?.let { Dates.format(it) } ?: "—") +
                    (if (e.viaDfn) a.getString(R.string.shop_status_dfn) else "")
                "${day(e.from)} – ${day(e.until)}" to "$state\n$created"
            }, padV = 6))
            if (st.current == null) {
                val newest = st.student.first()
                val pending = !newest.approved || newest.status == Shop.Status.UNKNOWN || newest.status == Shop.Status.PENDING
                add(a.hint(a.getString(if (pending) R.string.shop_none_today_pending else R.string.shop_none_today, day(st.now))), top = 4)
            }
        } else {
            add(a.hint(if (st.otherCount > 0) a.resources.getQuantityString(R.plurals.shop_no_student, st.otherCount, st.otherCount)
            else a.getString(R.string.shop_no_student_plain)))
        }
        if (st.current == null) {
            add(a.hint(a.getString(R.string.shop_verify_explain)), top = 6)
            val mail = repo.profile?.email ?: repo.tokens?.claims?.optString("email")?.ifEmpty { null }
            add(a.hint(if (mail != null) a.getString(R.string.shop_same_account, mail) else a.getString(R.string.shop_same_account_plain)))
        }
        val unis = repo.universities
        if (unis.isNullOrEmpty()) {
            if (unis != null) add(a.alert(AlertKind.BAD, a.getString(R.string.shop_no_universities)), top = 8)
            return
        }
        val uni = university?.takeIf { u -> unis.any { it.id == u.id } } ?: Shop.preferred(unis)
        university = uni
        add(a.styled(R.style.FieldLabel, a.getString(if (st.current != null) R.string.shop_reverify else R.string.shop_university)), top = 10)
        add(Button(a, null, 0, R.style.Button_Small_Outline).apply {
            text = uni?.name
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            setOnClickListener { pickUniversity(unis) }
        }, top = 4)
        add(Button(a, null, 0, if (st.current != null) R.style.Button_Outline else R.style.Button).apply {
            setText(R.string.shop_verify_start)
            setOnClickListener { uni?.let { a.startActivityForResult(DfnActivity.intent(a, it), MainActivity.REQ_DFN) } }
        }, top = 8)
    }

    private fun pickUniversity(unis: List<Shop.University>) {
        val selected = unis.indexOfFirst { it.id == university?.id }
        AlertDialog.Builder(activity).setTitle(R.string.shop_university)
            .setSingleChoiceItems(unis.map { it.name }.toTypedArray(), selected) { d, which ->
                university = unis[which]
                d.dismiss()
                render()
            }
            .setNegativeButton(R.string.cancel, null).show()
    }

    /** Summary, AGB and the order as it will be sent; books only on "Jetzt kostenlos buchen". */
    private fun confirm() {
        val a = activity
        val st = repo.shopState() ?: return
        val payload = repo.semesterOrder() ?: return
        val user = payload.getJSONArray("Purchase").getJSONObject(0).optJSONObject("PurchaseUser")
        val content = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(a.dp(22), a.dp(8), a.dp(22), 0)
            add(a.kvTable(listOfNotNull(
                a.getString(R.string.shop_valid_from) to day(st.monthStart),
                a.getString(R.string.shop_renewal) to a.getString(R.string.shop_renewal_value, day(st.current?.until)),
                user?.let { a.getString(R.string.shop_holder) to "${it.optString("FirstName")} ${it.optString("LastName")}" },
                a.getString(R.string.shop_price) to a.getString(R.string.shop_price_free),
            ), padV = 6))
            add(a.styled(R.style.Text_Link, a.getString(R.string.shop_agb_read)).apply {
                textSize = 13f
                setPadding(0, a.dp(6), 0, a.dp(6))
                setOnClickListener { a.openUrl(URL_AGB) }
            }, top = 6, width = ViewGroup.LayoutParams.WRAP_CONTENT)
            if (repo.prefs.debug) {
                add(a.hint(a.getString(R.string.shop_payload)), top = 6)
                add(a.pre(payload.toString(2)))
            }
        }
        val agb = CheckBox(a).apply {
            setText(R.string.shop_agb)
            textSize = 13f
        }
        content.add(agb, top = 4)
        val dialog = AlertDialog.Builder(a).setTitle(R.string.shop_confirm_title)
            .setView(ScrollView(a).apply { addView(content) })
            .setPositiveButton(R.string.shop_confirm) { _, _ -> repo.buySemesterTicket(payload) }
            .setNegativeButton(R.string.cancel, null).show()
        val ok = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        ok.isEnabled = false
        agb.setOnCheckedChangeListener { _, on -> ok.isEnabled = on }
    }

    private fun orderResult(r: OrderResult) {
        val a = activity
        val text = when (r.kind) {
            OrderResult.Kind.BOOKED -> a.getString(R.string.shop_booked)
            OrderResult.Kind.PAYMENT -> a.getString(R.string.shop_payment)
            OrderResult.Kind.FAILED -> a.getString(R.string.shop_failed, r.error ?: "—")
        }
        body.add(LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            addView(a.alert(if (r.kind == OrderResult.Kind.BOOKED) AlertKind.OK else AlertKind.BAD, text))
            if (r.kind == OrderResult.Kind.BOOKED) add(Button(a, null, 0, R.style.Button_Small).apply {
                setText(R.string.shop_to_tickets)
                setOnClickListener {
                    repo.clearOrderResult()
                    a.select(MainActivity.Tab.TICKETS)
                }
            }, top = 8, width = ViewGroup.LayoutParams.WRAP_CONTENT)
            val call = r.call
            if (repo.prefs.debug && call != null) add(a.debugPanel("CreateOrder") {
                add(a.pre(call.request))
                add(a.pre(pretty(call.json, call.body)), top = 6)
            }, top = 8)
        }).bottomGap()
    }

    private fun empty() {
        val a = activity
        body.add(LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(a.dp(20), a.dp(40), a.dp(20), a.dp(40))
            addView(ImageView(a).apply {
                setImageResource(R.drawable.ic_ticket)
                tint(a.attrColor(R.attr.brandInk))
                alpha = 0.6f
            }, LinearLayout.LayoutParams(a.dp(48), a.dp(48)))
            addView(a.styled(R.style.Text, a.getString(R.string.shop_login_first)).apply {
                gravity = Gravity.CENTER
                setTextColor(a.attrColor(R.attr.cTextMuted))
                setPadding(0, a.dp(10), 0, a.dp(14))
            })
            addView(Button(a, null, 0, R.style.Button).apply {
                setText(R.string.shop_to_account)
                setOnClickListener { a.select(MainActivity.Tab.ACCOUNT) }
            })
        })
    }

    private fun panel(fill: LinearLayout.() -> Unit) = LinearLayout(activity, null, 0, R.style.Panel).apply {
        orientation = LinearLayout.VERTICAL
        fill()
    }

    private fun day(ms: Long?) = ms?.let { Dates.format(it, false) } ?: "—"

    private fun statusLabel(s: Shop.Status) = when (s) {
        Shop.Status.APPROVED -> R.string.entitlement_approved
        Shop.Status.PENDING -> R.string.entitlement_pending
        Shop.Status.REFUSED -> R.string.entitlement_refused
        Shop.Status.LOCKED -> R.string.entitlement_locked
        Shop.Status.UNKNOWN -> R.string.entitlement_unknown
    }

    private fun View.bottomGap() {
        (layoutParams as LinearLayout.LayoutParams).bottomMargin = activity.dp(12)
    }

    private companion object {
        const val URL_AGB = "https://www.vrn.de/_inc/app/agb/"
    }
}
