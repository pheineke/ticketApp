package de.heineke.myvrnlite.data

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Calendar

/**
 * Ticketshop: entitlements ("Berechtigungsnachweise") and the Deutschland-Semesterticket —
 * a port of webapp/shop.py, which documents the requests.
 *
 * The Semesterticket (0 €) is a subscription bought once per semester and renewed monthly by
 * the backend. It needs a DEUTSCHLAND_TICKET_STUDENT entitlement for the semester, which the
 * Hochschul-Login (DFN-AAI, [verifyUrl]) creates.
 */
object Shop {
    const val SEMESTER_TICKET = "9996"
    const val SEMESTER_NAME = "Deutschland-Semesterticket"
    const val STUDENT_ANNOTATION = "DEUTSCHLAND_TICKET_STUDENT"

    // The shop's login page continues to returnUrl after signing in; Dfn/Federate then runs the
    // SAML login at the university and comes back to its own returnUrl with dfn_approved=true.
    private const val LOGIN_URL = "https://shop.myvrn.de/authentication/login?organization=vrn&client=vrn&clientUsage=App&returnUrl="
    const val ENTITLEMENTS_PAGE = "/authentication/private/tickets/entitlements"
    const val APPROVED_MARK = "dfn_approved="

    /** Hochschul-Login for a university (Dfn/GetAuthorities EntityId), in a browser or WebView. */
    fun verifyUrl(entityId: String): String =
        LOGIN_URL + enc("/Dfn/Federate?entityId=${enc(entityId)}&returnUrl=${enc(ENTITLEMENTS_PAGE)}")

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    class University(val id: String, val name: String)

    /** Dfn/GetAuthorities → universities sorted by name (German name preferred). */
    fun universities(json: JSONObject?): List<University> {
        val list = json?.optJSONArray("value") ?: return emptyList()
        return (0 until list.length()).mapNotNull { list.optJSONObject(it) }.mapNotNull { a ->
            val id = a.optString("EntityId").ifEmpty { return@mapNotNull null }
            val names = a.optJSONArray("DisplayNames")?.let { n -> (0 until n.length()).mapNotNull(n::optJSONObject) }.orEmpty()
                .associate { it.optString("Language") to it.optString("Name") }
            University(id, names["de"]?.ifEmpty { null } ?: names["en"]?.ifEmpty { null } ?: id)
        }.sortedBy { it.name.lowercase() }
    }

    /** RPTU (formerly TU Kaiserslautern) first, as in the web client. */
    fun preferred(unis: List<University>): University? = unis.firstOrNull { "uni-kl.de" in it.id } ?: unis.firstOrNull()

    enum class Status { UNKNOWN, PENDING, APPROVED, REFUSED, LOCKED }

    class Entitlement(val raw: JSONObject) {
        val id: Long = raw.optLong("Id", raw.optLong("ID"))
        val ticketType: String? = runCatching { JSONObject(raw.optString("Annotation")).optString("TicketType") }.getOrNull()?.ifEmpty { null }
        val from: Long? = Dates.parse(raw.opt("EligibleFrom"))
        val until: Long? = Dates.parse(raw.opt("EligibleUntil"))
        val created: Long? = Dates.parse(raw.opt("Created"))
        val approved: Boolean = raw.optBoolean("Approved")
        val status: Status = Status.values().getOrElse(raw.optInt("Status")) { Status.UNKNOWN }
        /** Created by the Hochschul-Login (rather than an uploaded document). */
        val viaDfn: Boolean = raw.optString("EditedBy").startsWith("dfn:")
        val student: Boolean get() = ticketType == STUDENT_ANNOTATION

        fun validAt(now: Long) = approved && from != null && until != null && now in from..until
    }

    /** A Semesterticket in the order history. */
    class SemesterTicket(val order: JSONObject, val ticket: JSONObject) {
        val from: Long? = Dates.parse(ticket.opt("ValidFrom"))
        val to: Long? = Dates.parse(ticket.opt("ValidTo"))
        val orderNumber: String = order.optString("OrderNumber")
    }

    /** What the Ticketshop page shows. */
    class State(entitlements: List<Entitlement>, orders: JSONArray?, val now: Long) {
        /** DEUTSCHLAND_TICKET_STUDENT entitlements, newest first. */
        val student = entitlements.filter { it.student }.sortedByDescending { it.created ?: 0 }
        val otherCount = entitlements.count { !it.student }
        /** The entitlement valid today. */
        val current = student.sortedByDescending { it.from ?: 0 }.firstOrNull { it.validAt(now) }
        val tickets = semesterTickets(orders)
        val thisMonth = tickets.firstOrNull { it.from != null && it.to != null && now >= it.from && now < it.to }
        /**
         * The subscription renews itself monthly: a Semesterticket starting within the current
         * entitlement's period means it's running, so never offer a second one. (Last semester's
         * final ticket ends a few hours into the new period: it doesn't count.)
         */
        val running = current?.from?.let { since -> tickets.firstOrNull { it.from != null && it.from >= since - HOUR } }
        val canBuy = current != null && running == null
        val monthStart = monthStart(now)
    }

    fun semesterTickets(orders: JSONArray?): List<SemesterTicket> {
        val out = mutableListOf<SemesterTicket>()
        for (i in 0 until (orders?.length() ?: 0)) {
            val o = orders!!.optJSONObject(i) ?: continue
            val ts = o.optJSONArray("Tickets") ?: continue
            for (j in 0 until ts.length()) {
                val t = ts.optJSONObject(j) ?: continue
                if (t.optString("TicketId") == SEMESTER_TICKET && !t.optBoolean("Canceled") && !t.optBoolean("Refunded")) {
                    out += SemesterTicket(o, t)
                }
            }
        }
        return out.sortedByDescending { it.from ?: Long.MIN_VALUE }
    }

    class Person(val first: String?, val last: String?, val dob: String?, val gender: String?)

    /**
     * CreateOrder payload for this month's Semesterticket (0 €), as webapp/shop.py builds it:
     * ticket 9996 in the vrn tariff, from the start of the month (Berlin time) to the next,
     * monthly validity (TimeValidity 5). Accepted by the backend on 21.10.2026.
     */
    fun semesterOrder(template: SemesterTicket?, person: Person?, now: Long): JSONObject {
        val t = template?.ticket
        val start = monthStart(now)
        val order = JSONObject()
            .put("TicketId", SEMESTER_TICKET)
            .put("Ticket", t?.optString("TicketName")?.ifEmpty { null } ?: SEMESTER_NAME)
            .put("ProductNumber", t?.optString("ProductNumber")?.ifEmpty { null } ?: SEMESTER_TICKET)
            .put("TicketClass", t?.optString("TicketClass")?.ifEmpty { null } ?: SEMESTER_TICKET)
            .put("Tariff", (t?.optString("Tariff")?.ifEmpty { null } ?: "vrn").lowercase())
            .put("AreaNumber", "1")  // "deutschlandweit"
            .put("DateTime", iso(start))
            .put("DateTimeUntil", iso(nextMonth(start)))
            .put("ValidMinutes", 0)
            .put("Price", 0.0)
            .put("VatRate", 7.0)
            .put("TimeValidity", t?.optInt("TimeValidity")?.takeIf { it != 0 } ?: 5)
        if (person?.first != null && person.last != null) {
            order.put("PurchaseUser", JSONObject().put("LastName", person.last).put("FirstName", person.first).apply {
                person.dob?.let { put("Dob", it) }
                person.gender?.let { put("Gender", it) }
            })
        }
        // As the web shop sends them: AppName = <organization>_<client>_<clientUsage>.
        val options = JSONArray()
        for ((k, v) in listOf("AppName" to "vrn_vrn_App", "ClientName" to "vrn", "OrganizationName" to "vrn")) {
            options.put(JSONObject().put("Key", k).put("Value", v))
        }
        return JSONObject().put("Purchase", JSONArray().put(order)).put("CommonOptions", options)
    }

    /** Holder data for the order: name from the profile, gender/birth date from an earlier ticket. */
    fun person(first: String?, last: String?, profileDob: String?, template: SemesterTicket?): Person {
        val detail = template?.ticket?.optJSONArray("Details")?.optJSONObject(0)
        fun s(k: String) = detail?.takeUnless { it.isNull(k) }?.optString(k)?.ifEmpty { null }
        return Person(first, last, s("Dob") ?: profileDob, s("Gender"))
    }

    fun monthStart(now: Long): Long = Calendar.getInstance(BERLIN).run {
        timeInMillis = now
        set(Calendar.DAY_OF_MONTH, 1)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        timeInMillis
    }

    fun nextMonth(start: Long): Long = Calendar.getInstance(BERLIN).run {
        timeInMillis = start
        add(Calendar.MONTH, 1)
        timeInMillis
    }

    private fun iso(ms: Long): String = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.000'Z'", java.util.Locale.US)
        .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date(ms))

    private const val HOUR = 3_600_000L
}
