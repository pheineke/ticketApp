package de.skygazer.myvrnlite

import de.skygazer.myvrnlite.data.Dates
import de.skygazer.myvrnlite.net.Efa
import de.skygazer.myvrnlite.net.Mode
import de.skygazer.myvrnlite.net.Place
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Efa against a recorded XML_TRIP_REQUEST2 answer (Uni Zentral → Mannheim Hbf, 21.10.2026). */
class EfaTest {
    private val urls = mutableListOf<String>()

    private fun efa(resource: String) = Efa(OkHttpClient.Builder().addInterceptor { chain ->
        urls += chain.request().url.toString()
        val body = javaClass.classLoader!!.getResource(resource)!!.readText()
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(body.toResponseBody("application/json".toMediaType())).build()
    }.build())

    private val origin = Place("de:07312:91310", "Kaiserslautern, Uni Zentral")
    private val destination = Place("de:08222:2383", "Mannheim, Hbf Süd")
    private val at = Dates.parse("21.10.2026 13:30")!!

    @Test
    fun parsesJourneys() {
        val js = efa("efa_trip.json").trips(origin, destination, at, arrive = false)
        assertEquals(2, js.size)
        val j = js[0]
        assertEquals("21.10.2026, 13:38", Dates.format(j.dep!!))
        assertEquals(listOf(Mode.BUS, Mode.SBAHN, Mode.WALK), j.legs.map { it.mode })
        assertEquals(listOf("SWK 105", "S2", ""), j.legs.map { it.label })
        assertEquals(1, j.changes)
        assertEquals(15.5, j.price!!, 0.001)  // cheapest adult single, supplements excluded
        assertTrue(j.longDistance.isEmpty())
        val sbahn = j.legs[1]
        assertEquals("10", sbahn.origin.platform)
        assertEquals(12, sbahn.transfer)        // bus arrives 13:46, S2 leaves 13:58
        assertTrue(sbahn.stops.isNotEmpty())
        assertTrue(j.legs.all { it.coords.isNotEmpty() })
        assertNull(j.legs[2].transfer)          // a footpath carries its own time
    }

    @Test
    fun dropsTripsBeforeTheRequestedTime() {
        // EFA answers with a few trips before the requested time; the 13:38 one goes.
        val js = efa("efa_trip.json").trips(origin, destination, Dates.parse("21.10.2026 14:00")!!, arrive = false)
        assertEquals(listOf("21.10.2026, 15:24"), js.map { Dates.format(it.dep!!) })
    }

    @Test
    fun longDistanceTrains() {
        val ice = efa("efa_trip.json").trips(origin, destination, at, arrive = false)[1]
        assertEquals("ICE 9553", ice.legs[1].label)  // number "9553" + trainType "ICE"
        assertEquals(listOf("ICE 9553"), ice.longDistance)
    }

    @Test
    fun requestParameters() {
        val e = efa("efa_trip.json")
        e.trips(origin, destination, at, arrive = true)
        val url = urls.last()
        assertTrue(url, "name_origin=de%3A07312%3A91310" in url && "type_origin=any" in url)
        assertTrue(url, "itdDate=20261021" in url && "itdTime=1330" in url && "itdTripDateTimeDepArr=arr" in url)
        // A point on the map goes as a coordinate: lon:lat.
        e.trips(Place(Place.coordId(49.425, 7.752), "Mein Standort"), destination, at, arrive = false)
        assertTrue(urls.last(), "name_origin=7.75200%3A49.42500%3AWGS84%5Bdd.ddddd%5D" in urls.last() && "type_origin=coord" in urls.last())
    }

    @Test
    fun places() {
        val p = Place(Place.coordId(49.4371234, 7.7629876), "x")
        assertEquals("coord:49.43712,7.76299", p.id)
        assertEquals(49.43712 to 7.76299, p.coordOf())
        assertTrue(p.isPoint)
        assertFalse(origin.isPoint)
        assertNull(origin.coordOf())
    }

    @Test
    fun plainText() {
        assertEquals("Bauarbeiten & Umleitung über Straße", Efa.plain("<p>Bauarbeiten &amp; Umleitung &#252;ber <b>Stra&szlig;e</b></p>"))
    }
}
