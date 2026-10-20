package de.heineke.myvrnlite

import de.heineke.myvrnlite.data.Dates
import de.heineke.myvrnlite.data.Group
import de.heineke.myvrnlite.data.TicketList
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DatesTest {
    @Test
    fun naiveTimesAreBerlinTime() {
        // .NET emits 7 fraction digits; 2026-10-31 23:59 CET = 22:59 UTC.
        assertEquals(1793487540000, Dates.parse("2026-10-31T23:59:00.0000000"))
        assertEquals("31.10.2026, 23:59", Dates.format(Dates.parse("2026-10-31T23:59:00")!!))
        // Summer time: 2026-07-01 12:00 CEST = 10:00 UTC.
        assertEquals(1782900000000, Dates.parse("2026-07-01 12:00"))
    }

    @Test
    fun explicitZones() {
        assertEquals(1782900000000, Dates.parse("2026-07-01T10:00:00Z"))
        assertEquals(1782900000000, Dates.parse("2026-07-01T12:00:00+02:00"))
        assertEquals(1782900000000, Dates.parse("2026-07-01T12:00:00.123+0200")!! - 123)
        assertEquals(1782900000000, Dates.parse("/Date(1782900000000+0200)/"))
    }

    @Test
    fun germanDates() {
        assertEquals("01.11.2026", Dates.format(Dates.parse("1.11.2026")!!, withTime = false))
        assertEquals("01.11.2026, 07:05", Dates.format(Dates.parse("01.11.2026, 7:05")!!))
    }

    @Test
    fun rejectsGarbage() {
        assertNull(Dates.parse(null))
        assertNull(Dates.parse(""))
        assertNull(Dates.parse("31.02.2026"))
        assertNull(Dates.parse("bald"))
        assertEquals("bald", Dates.formatRaw("bald"))
        assertEquals("—", Dates.formatRaw(null))
    }
}

class TicketListTest {
    private val now = Dates.parse("2026-10-20T12:00:00")!!

    private fun ticket(id: Long, from: String?, to: String?, extra: JSONObject.() -> Unit = {}) = JSONObject().apply {
        put("Id", id)
        if (from != null) put("ValidFrom", from)
        if (to != null) put("ValidTo", to)
        extra()
    }

    private fun orders(vararg tickets: JSONObject) = JSONArray().put(JSONObject()
        .put("Product", "Deutschlandticket").put("OrderNumber", "A-1").put("Tickets", JSONArray(tickets.toList())))

    @Test
    fun groupsAndOrders() {
        val list = TicketList.from(orders(
            ticket(1, "2026-10-01T00:00:00", "2026-10-31T23:59:59"),
            ticket(2, "2026-10-15T00:00:00", "2026-10-25T23:59:59"),
            ticket(3, "2026-11-01T00:00:00", "2026-11-30T23:59:59"),
            ticket(4, "2026-09-01T00:00:00", "2026-09-30T23:59:59"),
            ticket(5, null, null),
            ticket(6, null, "2026-12-31T00:00:00") { put("AllowedQuota", 10); put("UsedQuota", 0) },
            ticket(7, "2026-10-01T00:00:00", "2026-10-31T23:59:59") { put("Canceled", true) },
        ), debug = false, now = now)

        assertEquals(listOf(Group.CURRENT, Group.FUTURE, Group.QUOTA), list.groups.map { it.group })
        assertEquals(listOf(2L, 1L), list.groups[0].tickets.map { it.id })  // expiring soonest first
        assertEquals(2, list.hiddenCount)  // expired + undated; cancelled ones aren't counted
        assertEquals(listOf(2L, 1L, 3L, 6L), list.usable.map { it.id })
        assertEquals("0", list.groups[2].tickets[0].usedQuota ?: "0")
    }

    @Test
    fun debugShowsEverything() {
        val list = TicketList.from(orders(
            ticket(4, "2026-09-01T00:00:00", "2026-09-30T23:59:59"),
            ticket(8, "2026-08-01T00:00:00", "2026-08-31T23:59:59"),
            ticket(7, "2026-10-01T00:00:00", "2026-10-31T23:59:59") { put("Refunded", true) },
        ), debug = true, now = now)
        assertEquals(listOf(Group.CURRENT, Group.EXPIRED), list.groups.map { it.group })
        assertEquals(listOf(4L, 8L), list.groups[1].tickets.map { it.id })  // most recent first
        assertTrue(list.groups[0].tickets[0].hidden)
        assertEquals(0, list.hiddenCount)
    }

    @Test
    fun readsDetails() {
        val t = TicketList.from(orders(ticket(9, null, null) {
            put("Details", JSONArray()
                .put(JSONObject().put("TicketName", "").put("ValidUntil", "2026-12-31T23:59:59"))
                .put(JSONObject().put("TicketName", "Deutschland-Semesterticket").put("Holder", "Erika Mustermann")
                    .put("Dob", "1999-04-01T00:00:00").put("AreaName", "VRN").put("PriceLevel", 0)))
            put("ValidFrom", "2026-10-01T00:00:00")
            put("AllowedQuota", -1)
        }), debug = false, now = now).tickets.single()

        assertEquals("Deutschland-Semesterticket", t.name)
        assertTrue(t.dticket)
        assertEquals(Group.CURRENT, t.group)  // AllowedQuota -1 isn't a quota ticket
        assertEquals("2026-12-31T23:59:59", t.validTo)
        assertEquals("Erika Mustermann", t.holder)
        assertEquals("VRN", t.area)
        assertNull(t.priceLevel)  // 0 counts as "not set", as in the web client
        assertEquals("A-1", t.number)
        assertFalse(t.hidden)
    }

    @Test
    fun fallsBackToProductName() {
        val t = TicketList.from(orders(ticket(10, "2026-10-01T00:00:00", "2026-10-31T23:59:59")), false, now).tickets.single()
        assertEquals("Deutschlandticket", t.name)
    }
}
