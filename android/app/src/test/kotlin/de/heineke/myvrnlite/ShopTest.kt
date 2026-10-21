package de.heineke.myvrnlite

import de.heineke.myvrnlite.data.Dates
import de.heineke.myvrnlite.data.Shop
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Shop against the shapes the backend answered with in October 2026 (names made up). */
class ShopTest {
    private val now = Dates.parse("2026-10-21T14:00:00Z")!!

    private fun entitlement(id: Long, from: String, until: String, created: String, status: Int = 2) = JSONObject()
        .put("Id", id).put("Status", status).put("Approved", status == 2)
        .put("EligibleFrom", from).put("EligibleUntil", until).put("Created", created)
        .put("Annotation", """{"TicketType":"DEUTSCHLAND_TICKET_STUDENT"}""").put("EditedBy", "dfn:abc")

    private val summer = entitlement(1, "2026-03-31T22:00:00Z", "2026-09-30T21:59:59Z", "2026-04-01T18:20:00Z")
    private val winter = entitlement(2, "2026-09-30T22:00:00Z", "2027-03-31T21:59:59Z", "2026-10-21T10:13:00Z")
    private val other = JSONObject().put("Id", 3).put("Status", 2).put("Approved", true)
        .put("Annotation", """{"TicketType":"BAHNCARD"}""")

    private fun order(number: String, from: String, to: String) = JSONObject().put("OrderNumber", number)
        .put("Tickets", JSONArray().put(JSONObject().put("TicketId", "9996").put("TicketName", "Deutschland-Semesterticket")
            .put("ProductNumber", "9996").put("TicketClass", "9996").put("Tariff", "vrn").put("TimeValidity", 5)
            .put("ValidFrom", from).put("ValidTo", to)
            .put("Details", JSONArray().put(JSONObject().put("Gender", "m").put("Dob", "2003-02-18T11:00:00Z")))))

    // Last semester's final ticket: ends a few hours into the new period.
    private val september = order("A-1", "2026-08-31T22:00:00Z", "2026-10-01T02:00:00Z")
    private val october = order("A-2", "2026-09-30T22:00:00Z", "2026-11-01T02:00:00Z")

    private fun state(vararg orders: JSONObject, ents: List<JSONObject> = listOf(summer, winter, other)) =
        Shop.State(ents.map(Shop::Entitlement), JSONArray(orders.toList()), now)

    @Test
    fun newSemesterOffersTheTicket() {
        val st = state(september)
        assertEquals(2L, st.current!!.id)
        assertEquals(listOf(2L, 1L), st.student.map { it.id })  // newest first
        assertEquals(1, st.otherCount)
        assertNull(st.running)  // September's ticket overlaps the new period by hours: not running
        assertTrue(st.canBuy)
        assertTrue(st.current.viaDfn)
    }

    @Test
    fun boughtTicketMeansRunning() {
        val st = state(september, october)
        assertEquals("A-2", st.running!!.orderNumber)
        assertSame(st.running, st.thisMonth)
        assertFalse(st.canBuy)
    }

    @Test
    fun noEntitlementForToday() {
        val st = state(september, ents = listOf(summer))
        assertNull(st.current)
        assertFalse(st.canBuy)
        val pending = state(september, ents = listOf(summer,
            entitlement(4, "2026-09-30T22:00:00Z", "2027-03-31T21:59:59Z", "2026-10-21T10:13:00Z", status = 1)))
        assertNull(pending.current)
        assertEquals(Shop.Status.PENDING, pending.student.first().status)
    }

    @Test
    fun orderAsAcceptedByTheBackend() {
        val st = state(september)
        val template = st.tickets.first()
        val payload = Shop.semesterOrder(template, Shop.person("Max", "Muster", null, template), now)
        val o = payload.getJSONArray("Purchase").getJSONObject(0)
        assertEquals("9996", o.getString("TicketId"))
        assertEquals("vrn", o.getString("Tariff"))
        assertEquals("1", o.getString("AreaNumber"))
        assertEquals("2026-09-30T22:00:00.000Z", o.getString("DateTime"))       // 01.10. 00:00 Berlin
        assertEquals("2026-10-31T23:00:00.000Z", o.getString("DateTimeUntil"))  // 01.11. 00:00 Berlin (winter time)
        assertEquals(0.0, o.getDouble("Price"), 0.0)
        assertEquals(5, o.getInt("TimeValidity"))
        val user = o.getJSONObject("PurchaseUser")
        assertEquals("Muster", user.getString("LastName"))
        assertEquals("m", user.getString("Gender"))
        assertEquals("2003-02-18T11:00:00Z", user.getString("Dob"))
        assertEquals("vrn_vrn_App", payload.getJSONArray("CommonOptions").getJSONObject(0).getString("Value"))
        // Without a name: no PurchaseUser.
        assertFalse(Shop.semesterOrder(null, null, now).getJSONArray("Purchase").getJSONObject(0).has("PurchaseUser"))
    }

    @Test
    fun verifyUrlAsInTheWebClient() {
        assertEquals("https://shop.myvrn.de/authentication/login?organization=vrn&client=vrn&clientUsage=App&returnUrl=" +
            "%2FDfn%2FFederate%3FentityId%3Dhttps%253A%252F%252Fidp.uni-kl.de%252Fidp%252Fshibboleth" +
            "%26returnUrl%3D%252Fauthentication%252Fprivate%252Ftickets%252Fentitlements",
            Shop.verifyUrl("https://idp.uni-kl.de/idp/shibboleth"))
    }

    @Test
    fun universities() {
        val json = JSONObject().put("value", JSONArray()
            .put(JSONObject().put("EntityId", "https://shibboleth.hs-kl.de/idp/shibboleth")
                .put("DisplayNames", JSONArray().put(JSONObject().put("Language", "de").put("Name", "Hochschule Kaiserslautern"))))
            .put(JSONObject().put("EntityId", "https://idp.uni-kl.de/idp/shibboleth")
                .put("DisplayNames", JSONArray().put(JSONObject().put("Language", "en").put("Name", "RPTU Kaiserslautern-Landau")))))
        val unis = Shop.universities(json.toString())
        assertEquals(listOf("Hochschule Kaiserslautern", "RPTU Kaiserslautern-Landau"), unis.map { it.name })
        assertEquals("https://idp.uni-kl.de/idp/shibboleth", Shop.preferred(unis)!!.id)
        // The shop answers with a plain array.
        assertEquals(unis.map { it.id }, Shop.universities(json.getJSONArray("value").toString()).map { it.id })
        assertTrue(Shop.universities("<html>").isEmpty())
    }
}
