package de.skygazer.myvrnlite.data

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Ticket times are German local time, whatever the phone is set to. */
val BERLIN: TimeZone = TimeZone.getTimeZone("Europe/Berlin")

object Dates {
    private val NET_DATE = Regex("""/Date\((-?\d+)([+-]\d{4})?\)/""")
    private val ISO = Regex("""(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2})(?::(\d{2}))?(?:\.(\d+))?(Z|[+-]\d{2}:?\d{2})?""")
    private val DE = Regex("""(\d{1,2})\.(\d{1,2})\.(\d{4})(?:,?\s+(\d{1,2}):(\d{2}))?""")

    /**
     * Epoch ms for the date shapes the .NET backend emits: ISO 8601 with any number of
     * fraction digits, "/Date(ms+hhmm)/" and dd.mm.yyyy. Naive times are Berlin time.
     */
    fun parse(value: Any?): Long? {
        val v = (value as? String)?.trim()?.ifEmpty { null } ?: return null
        NET_DATE.matchEntire(v)?.let { return it.groupValues[1].toLong() }
        ISO.matchEntire(v)?.groupValues?.let { g ->
            val zone = when (val tz = g[8]) {
                "" -> BERLIN
                "Z" -> TimeZone.getTimeZone("UTC")
                else -> TimeZone.getTimeZone("GMT" + tz.take(3) + ":" + tz.takeLast(2))
            }
            val millis = g[7].take(3).padEnd(3, '0').toInt()
            return epoch(zone, g[1].toInt(), g[2].toInt(), g[3].toInt(), g[4].toInt(), g[5].toInt(), g[6].toIntOrNull() ?: 0, millis)
        }
        DE.matchEntire(v)?.groupValues?.let { g ->
            return epoch(BERLIN, g[3].toInt(), g[2].toInt(), g[1].toInt(), g[4].toIntOrNull() ?: 0, g[5].toIntOrNull() ?: 0, 0, 0)
        }
        return null
    }

    private fun epoch(zone: TimeZone, y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int, ms: Int): Long? =
        Calendar.getInstance(zone).run {
            isLenient = false
            clear()
            set(y, mo - 1, d, h, mi, s)
            set(Calendar.MILLISECOND, ms)
            try { timeInMillis } catch (ignored: IllegalArgumentException) { null }  // e.g. 31.02.
        }

    fun format(ms: Long, withTime: Boolean = true): String =
        SimpleDateFormat(if (withTime) "dd.MM.yyyy, HH:mm" else "dd.MM.yyyy", Locale.GERMANY)
            .apply { timeZone = BERLIN }.format(Date(ms))

    /** A raw API date for display; unparseable values are shown as they are. */
    fun formatRaw(raw: String?, withTime: Boolean = true): String =
        parse(raw)?.let { format(it, withTime) } ?: raw?.ifEmpty { null } ?: "—"
}

enum class Group { CURRENT, FUTURE, QUOTA, EXPIRED, UNKNOWN }

/** One ticket card/detail, from OrderResult + OrderResultTicket (ticket_view in app.py). */
class Ticket(
    val id: Long?,
    val name: String,
    val group: Group,
    val validFrom: String?,
    val validTo: String?,
    val start: Long?,
    val end: Long?,
    val priceLevel: String?,
    val area: String?,
    val holder: String?,
    val dob: String?,
    val number: String?,
    /** When the order was placed (Orders[].BookingDate). */
    val booked: String?,
    /** Orders[].Id: the invoice is per order. */
    val orderId: Long?,
    val orderNumber: String?,
    val usedQuota: String?,
    val allowedQuota: String?,
    val hidden: Boolean,
    val raw: JSONObject,
) {
    val dticket: Boolean get() = "deutschland" in name.lowercase(Locale.ROOT)

    companion object {
        fun from(order: JSONObject, t: JSONObject, now: Long): Ticket {
            val (startRaw, start) = firstDate(t, "ValidFrom", "ActivationFrom")
            val (endRaw, end) = firstDate(t, "ValidTo", "ValidUntil", "ActivationUntil")
            val allowed = present(t, "AllowedQuota")
            // -1 means "not a quota ticket" (e.g. Deutschland-Semesterticket), so only positive counts.
            val group = when {
                allowed is Number && allowed.toDouble() > 0 -> Group.QUOTA
                end == null -> Group.UNKNOWN
                end < now -> Group.EXPIRED
                start != null && start > now -> Group.FUTURE
                else -> Group.CURRENT
            }
            return Ticket(
                id = (t.opt("Id") as? Number)?.toLong(),
                name = detail(t, "TicketName") ?: present(order, "Product")?.toString() ?: "Ticket",
                group = group,
                validFrom = startRaw, validTo = endRaw, start = start, end = end,
                priceLevel = detail(t, "PriceLevel"),
                area = detail(t, "AreaName") ?: detail(t, "TariffArea"),
                holder = detail(t, "Holder"),
                dob = detail(t, "Dob"),
                number = detail(t, "AuthorizationNumber") ?: present(order, "OrderNumber")?.toString(),
                booked = present(order, "BookingDate")?.toString(),
                orderId = (order.opt("Id") as? Number)?.toLong(),
                orderNumber = present(order, "OrderNumber")?.toString(),
                usedQuota = present(t, "UsedQuota")?.toString(),
                allowedQuota = allowed?.toString(),
                hidden = present(t, "Canceled") != null || present(t, "Refunded") != null,
                raw = t,
            )
        }

        private fun details(t: JSONObject): List<JSONObject> = when (val d = t.opt("Details")) {
            is JSONArray -> (0 until d.length()).mapNotNull { d.optJSONObject(it) }
            is JSONObject -> listOf(d)
            else -> emptyList()
        }

        /** The value if it's "truthy" in the Python sense (not null, "", false or 0). */
        private fun present(src: JSONObject, key: String): Any? = when (val v = src.opt(key)) {
            null, JSONObject.NULL, "", false -> null
            is Number -> v.takeIf { it.toDouble() != 0.0 }
            else -> v
        }

        /** A ticket-level field, else the first Details entry that has it. */
        private fun detail(t: JSONObject, key: String): String? =
            (present(t, key) ?: details(t).firstNotNullOfOrNull { present(it, key) })?.toString()

        /** First parseable date among ticket-level and Details fields, with its raw value. */
        private fun firstDate(t: JSONObject, vararg keys: String): Pair<String?, Long?> {
            for (key in keys) {
                for (src in listOf(t) + details(t)) {
                    val raw = src.opt(key) as? String ?: continue
                    Dates.parse(raw)?.let { return raw to it }
                }
            }
            return null to null
        }
    }
}

class TicketGroup(val group: Group, val tickets: List<Ticket>)

/** Tickets grouped for the list, plus how many were left out (expired/undated, shown in debug mode). */
class TicketList(val groups: List<TicketGroup>, val hiddenCount: Int) {
    val tickets: List<Ticket> get() = groups.flatMap { it.tickets }

    /** The tickets someone might show at a ticket check: valid now, soon, or quota. */
    val usable: List<Ticket> get() = groups.filter { it.group in VISIBLE }.flatMap { it.tickets }

    companion object {
        private val VISIBLE = setOf(Group.CURRENT, Group.FUTURE, Group.QUOTA)

        fun from(orders: JSONArray?, debug: Boolean, now: Long): TicketList {
            val tickets = (0 until (orders?.length() ?: 0)).mapNotNull { orders!!.optJSONObject(it) }.flatMap { o ->
                val ts = o.optJSONArray("Tickets") ?: JSONArray()
                (0 until ts.length()).mapNotNull { ts.optJSONObject(it) }.map { Ticket.from(o, it, now) }
            }.filter { debug || !it.hidden }
            val far = Long.MAX_VALUE
            val order: Map<Group, Comparator<Ticket>> = mapOf(
                Group.CURRENT to compareBy { it.end ?: far },       // expiring soonest first
                Group.FUTURE to compareBy { it.start ?: far },      // starting soonest first
                Group.QUOTA to compareBy { it.end ?: far },
                Group.EXPIRED to compareByDescending { it.end },    // most recent first
                Group.UNKNOWN to compareBy { it.id.toString() },
            )
            var hidden = 0
            val groups = Group.entries.mapNotNull { g ->
                val items = tickets.filter { it.group == g }.sortedWith(order.getValue(g))
                when {
                    !debug && g !in VISIBLE -> { hidden += items.size; null }
                    items.isEmpty() -> null
                    else -> TicketGroup(g, items)
                }
            }
            return TicketList(groups, hidden)
        }
    }
}
