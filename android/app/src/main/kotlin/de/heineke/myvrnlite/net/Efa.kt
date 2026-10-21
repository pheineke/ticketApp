package de.heineke.myvrnlite.net

import de.heineke.myvrnlite.data.BERLIN
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * VRN journey planner: the Mentz EFA API behind https://www.vrn.de/mng/ — a port of
 * webapp/efa.py, which documents the requests. No key or login; answers as rapidJSON.
 *
 * All calls block; run them off the main thread.
 */
class Efa(private val http: OkHttpClient) {

    /** Stop/address/POI suggestions for free text. */
    fun stops(query: String, limit: Int = 8): List<Place> {
        val data = call("XML_STOPFINDER_REQUEST", mapOf(
            "type_sf" to "any", "name_sf" to query, "anyObjFilter_sf" to "0", "locationServerActive" to "1",
            "vrnsuggestMacro" to "vrn_suggest", "anyMaxSizeHitList" to limit.toString(),
        ))
        return data.optJSONArray("locations").objects().mapNotNull { loc ->
            val id = loc.optString("id").ifEmpty { return@mapNotNull null }
            val name = loc.optString("name").ifEmpty { return@mapNotNull null }
            val coord = loc.optJSONArray("coord")
            Place(id, name, LOCATION_TYPES[loc.optString("type")] ?: "Ort", coord?.optDouble(0), coord?.optDouble(1),
                best = loc.optBoolean("isBest"))
        }.take(limit)
    }

    /** What's at a point: the nearest address, and the stops around it (nearest first). */
    fun here(lat: Double, lon: Double, maxStops: Int = 4): Pair<Place, List<NearbyStop>> {
        val point = String.format(Locale.ROOT, "%.6f:%.6f:%s", lon, lat, COORD_FMT)
        var name = "Punkt auf der Karte"
        try {
            val loc = call("XML_STOPFINDER_REQUEST", mapOf("type_sf" to "coord", "name_sf" to point))
                .optJSONArray("locations").objects().firstOrNull()
            loc?.optString("name")?.ifEmpty { null }?.let { name = it }
        } catch (e: IOException) {
            // keep the generic name; the stops below may still work
        }
        val data = call("XML_COORD_REQUEST", mapOf(
            "coord" to point, "inclFilter" to "1", "type_1" to "STOP", "radius_1" to "1000", "max" to maxStops.toString(),
        ))
        val stops = data.optJSONArray("locations").objects().mapNotNull { loc ->
            val id = loc.optString("id").ifEmpty { return@mapNotNull null }
            val props = loc.optJSONObject("properties") ?: JSONObject()
            val coord = loc.optJSONArray("coord")
            NearbyStop(
                Place(id, props.optString("STOP_NAME_WITH_PLACE").ifEmpty { loc.optString("name", "?") }, "Haltestelle",
                    coord?.optDouble(0), coord?.optDouble(1)),
                props.optInt("distance", -1).takeIf { it >= 0 },
            )
        }.sortedBy { it.distance ?: Int.MAX_VALUE }.take(maxStops)
        return Place(Place.coordId(lat, lon), name, "Ort auf der Karte", lat, lon) to stops
    }

    /**
     * Connections from [origin] to [destination] around [time] (epoch ms). Sorted by departure;
     * the few EFA adds from before the requested time (after it, for arrival searches) are
     * dropped unless nothing else is left.
     */
    fun trips(origin: Place, destination: Place, time: Long, arrive: Boolean): List<Journey> {
        val params = linkedMapOf<String, String>()
        params += placeParams("origin", origin)
        params += placeParams("destination", destination)
        params += mapOf(
            "itdDate" to fmt("yyyyMMdd", time), "itdTime" to fmt("HHmm", time),
            "itdTripDateTimeDepArr" to if (arrive) "arr" else "dep",
            "useRealtime" to "1", "itPathListActive" to "1", "useUT" to "1", "useProxFootSearch" to "1",
            "calcNumberOfTrips" to "6", "locationServerActive" to "1",
        )
        val data = call("XML_TRIP_REQUEST2", params)
        val journeys = data.optJSONArray("journeys").objects().mapNotNull(::journey)
        if (journeys.isEmpty()) {
            val error = data.optJSONArray("systemMessages").objects().firstOrNull { it.optString("type") == "error" }
            throw EfaException(error?.let(::message) ?: "Keine Verbindung gefunden.")
        }
        val sorted = journeys.sortedWith(compareBy({ it.dep ?: time }, { it.arr ?: time }))
        val kept = if (arrive) sorted.filter { it.arr != null && it.arr <= time + 60_000 }
        else sorted.filter { it.dep != null && it.dep >= time - 60_000 }
        return kept.ifEmpty { sorted }
    }

    // ---- Parsing ---------------------------------------------------------------------

    private fun journey(j: JSONObject): Journey? {
        val legs = j.optJSONArray("legs").objects().map(::leg)
        if (legs.isEmpty()) return null
        // Minutes to change between two rides (walks between them carry their own time).
        for ((prev, leg) in legs.zipWithNext()) {
            if (prev.mode != Mode.WALK && leg.mode != Mode.WALK) {
                val arr = prev.destination.rt ?: prev.destination.time
                val dep = leg.origin.rt ?: leg.origin.time
                if (arr != null && dep != null) leg.transfer = ((dep - arr) / 60_000).toInt()
            }
        }
        val tickets = j.optJSONObject("fare")?.optJSONArray("tickets").objects()
        // "ab 2,62 €" as on vrn.de: the cheapest adult single ticket (2nd class), no supplements.
        val price = tickets.filter {
            it.optString("person") == "ADULT" && it.optString("travellerClass") == "SECOND" &&
                it.optString("timeValidity") == "SINGLE" && it.optString("isSupplement") != "YES" &&
                it.optDouble("priceBrutto", 0.0) > 0
        }.minOfOrNull { it.optDouble("priceBrutto") }
        return Journey(legs, j.optInt("interchanges"), price)
    }

    private fun leg(leg: JSONObject): Leg {
        val tr = leg.optJSONObject("transportation") ?: JSONObject()
        val product = tr.optJSONObject("product") ?: JSONObject()
        val mode = Mode.of(product.optInt("class", -1))
        val status = leg.optJSONArray("realtimeStatus").strings()
        val origin = point(leg.optJSONObject("origin"), "departure")
        val destination = point(leg.optJSONObject("destination"), "arrival")
        // stopSequence includes the leg's own origin and destination.
        val seq = leg.optJSONArray("stopSequence").objects()
        val between = if (seq.size > 2) seq.subList(1, seq.size - 1).map {
            point(it, if (it.has("departureTimePlanned")) "departure" else "arrival")
        } else emptyList()
        val infos = leg.optJSONArray("infos").objects().mapNotNull { info ->
            val title = plain(info.optString("title").ifEmpty { info.optString("subtitle") }.ifEmpty { info.optString("urlText") })
            val text = plain(info.optString("content"))
            if (title.isEmpty() && text.isEmpty()) null else title to if (text == title) "" else text
        }
        val trainType = tr.optJSONObject("properties")?.optString("trainType").orEmpty()
        val line = tr.optString("disassembledName").ifEmpty { tr.optString("number") }
        var label = tr.optString("number").ifEmpty { line }.ifEmpty { product.optString("name") }
        if (trainType.isNotEmpty() && label.all(Char::isDigit) && label.isNotEmpty()) label = "$trainType $label"
        val coords = leg.optJSONArray("coords").arrays().filter { it.length() >= 2 }
            .map { doubleArrayOf(it.optDouble(0), it.optDouble(1)) }
        return Leg(
            mode = mode,
            label = if (mode == Mode.WALK) "" else label,
            name = tr.optString("name").ifEmpty { product.optString("name") },
            direction = tr.optJSONObject("destination")?.optString("name").orEmpty(),
            operator = tr.optJSONObject("operator")?.optString("name").orEmpty(),
            origin = origin, destination = destination, stops = between,
            minutes = leg.optInt("duration") / 60,
            distance = leg.optInt("distance", -1).takeIf { it >= 0 },
            coords = coords,
            infos = infos,
            realtime = "MONITORED" in status,
            cancelled = status.any { "CANCELLED" in it } || origin.cancelled || destination.cancelled,
            longDistance = trainType.uppercase(Locale.ROOT) in LONG_DISTANCE || product.optInt("class") in 14..16,
        )
    }

    private fun point(p: JSONObject?, kind: String): StopTime {
        val o = p ?: JSONObject()
        val props = o.optJSONObject("properties") ?: JSONObject()
        val parent = o.optJSONObject("parent")
        val planned = time(o.optString("${kind}TimePlanned"))
        val estimated = time(o.optString("${kind}TimeEstimated"))
        val coord = o.optJSONArray("coord")
        return StopTime(
            name = o.optString("name").ifEmpty { parent?.optString("name").orEmpty() }.ifEmpty { "?" },
            platform = props.optString("platformName").ifEmpty { props.optString("platform") },
            time = planned, rt = estimated,
            lat = coord?.optDouble(0), lon = coord?.optDouble(1),
            cancelled = o.optBoolean("isCancelled"),
        )
    }

    // ---- HTTP --------------------------------------------------------------------------

    private fun call(endpoint: String, params: Map<String, String>): JSONObject {
        val url = "$BASE/$endpoint".toHttpUrl().newBuilder().apply {
            addQueryParameter("outputFormat", "rapidJSON")
            addQueryParameter("coordOutputFormat", COORD_FMT)
            addQueryParameter("language", "de")
            for ((k, v) in params) addQueryParameter(k, v)
        }.build()
        val res = http.newCall(Request.Builder().url(url).header("Accept", "application/json").build()).execute()
        val body = res.use { it.body!!.string() }
        if (!res.isSuccessful) throw EfaException("VRN-Fahrplanauskunft antwortet mit ${res.code}.")
        return try {
            JSONObject(body)
        } catch (e: Exception) {
            throw EfaException("Unerwartete Antwort der VRN-Fahrplanauskunft.")
        }
    }

    private fun placeParams(prefix: String, place: Place): Map<String, String> {
        val coord = place.coordOf()
        return if (coord != null) {
            mapOf("name_$prefix" to String.format(Locale.ROOT, "%.5f:%.5f:%s", coord.second, coord.first, COORD_FMT), "type_$prefix" to "coord")
        } else {
            mapOf("name_$prefix" to place.id, "type_$prefix" to "any")
        }
    }

    private fun message(err: JSONObject): String {
        val text = err.optString("text")
        return when {
            "no connection" in text || err.optInt("code") == -4000 -> "Keine Verbindung gefunden."
            "multiple matches" in text -> "Start oder Ziel ist nicht eindeutig. Bitte aus den Vorschlägen wählen."
            "no matches" in text || "not found" in text -> "Start oder Ziel wurde nicht gefunden."
            else -> "Keine Verbindung gefunden (${text.ifEmpty { err.optString("code") }})."
        }
    }

    companion object {
        const val BASE = "https://www.vrn.de/mngvrn"
        private const val COORD_FMT = "WGS84[dd.ddddd]"
        private val LONG_DISTANCE = setOf("ICE", "IC", "EC", "ECE", "TGV", "RJ", "RJX", "NJ", "EN", "FLX", "ICD")
        private val LOCATION_TYPES = mapOf("stop" to "Haltestelle", "poi" to "Ort", "street" to "Straße", "address" to "Adresse",
            "singlehouse" to "Adresse", "suburb" to "Ort", "locality" to "Ort")
        private val TAG = Regex("<[^>]+>")
        private val SPACE = Regex("\\s+")

        private val ENTITY = Regex("&(#x?[0-9a-fA-F]+|[a-zA-Z]+);")
        private val NAMED = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
            "auml" to "ä", "ouml" to "ö", "uuml" to "ü", "Auml" to "Ä", "Ouml" to "Ö", "Uuml" to "Ü", "szlig" to "ß")

        /** Disruption texts come as HTML; we show them as text. */
        fun plain(html: String): String {
            val text = ENTITY.replace(TAG.replace(html, " ")) { m ->
                val e = m.groupValues[1]
                when {
                    e.startsWith("#x") || e.startsWith("#X") -> e.drop(2).toIntOrNull(16)?.let { String(Character.toChars(it)) }
                    e.startsWith("#") -> e.drop(1).toIntOrNull()?.let { String(Character.toChars(it)) }
                    else -> NAMED[e]
                } ?: m.value
            }
            return SPACE.replace(text, " ").trim()
        }

        private fun time(value: String): Long? {
            if (value.isEmpty()) return null
            return runCatching {
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }
                    .parse(value)?.time
            }.getOrNull()
        }

        private fun fmt(pattern: String, ms: Long): String =
            SimpleDateFormat(pattern, Locale.ROOT).apply { timeZone = BERLIN }.format(Date(ms))

        private fun JSONArray?.objects(): List<JSONObject> =
            if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

        private fun JSONArray?.arrays(): List<JSONArray> =
            if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONArray(it) }

        private fun JSONArray?.strings(): List<String> =
            if (this == null) emptyList() else (0 until length()).map { optString(it) }
    }
}

class EfaException(message: String) : IOException(message)

/** A place to travel from/to: a stop finder id, or "coord:lat,lon" for a point on the map. */
class Place(val id: String, val name: String, val kind: String = "", val lat: Double? = null, val lon: Double? = null,
            val best: Boolean = false) {
    fun coordOf(): Pair<Double, Double>? = if (id.startsWith("coord:")) id.removePrefix("coord:").split(",").let {
        it.getOrNull(0)?.toDoubleOrNull()?.let { lat -> it.getOrNull(1)?.toDoubleOrNull()?.let { lon -> lat to lon } }
    } else null

    /** A point on the map is a one-off: not worth keeping in the recent searches. */
    val isPoint: Boolean get() = id.startsWith("coord:")

    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("kind", kind)
        .put("lat", lat ?: JSONObject.NULL).put("lon", lon ?: JSONObject.NULL)

    override fun toString() = name  // AutoCompleteTextView puts this into the field

    companion object {
        fun coordId(lat: Double, lon: Double) = String.format(Locale.ROOT, "coord:%.5f,%.5f", lat, lon)

        fun fromJson(j: JSONObject) = Place(j.optString("id"), j.optString("name"), j.optString("kind"),
            if (j.isNull("lat")) null else j.optDouble("lat"), if (j.isNull("lon")) null else j.optDouble("lon"))
    }
}

class NearbyStop(val place: Place, val distance: Int?)

/** Vehicle types: EFA product classes grouped as the web client shows them. */
enum class Mode(val label: String) {
    TRAIN("Zug"), SBAHN("S-Bahn"), UBAHN("U-Bahn"), TRAM("Straßenbahn"), BUS("Bus"), SHIP("Schiff"),
    WALK("Fußweg"), TAXI("Taxi"), BIKE("Fahrrad"), OTHER("Sonstiges");

    companion object {
        fun of(productClass: Int): Mode = when (productClass) {
            0, 13, 14, 15, 16 -> TRAIN
            1 -> SBAHN
            2 -> UBAHN
            3, 4 -> TRAM
            5, 6, 7, 10, 17, 19 -> BUS
            9 -> SHIP
            99, 100 -> WALK
            105 -> TAXI
            107 -> BIKE
            else -> OTHER
        }
    }
}

/** A stop on a leg with planned and realtime (estimated) time, epoch ms. */
class StopTime(val name: String, val platform: String, val time: Long?, val rt: Long?,
               val lat: Double?, val lon: Double?, val cancelled: Boolean) {
    /** Minutes late (negative: early), or null without realtime data. */
    val delay: Int? get() = if (time != null && rt != null) Math.round((rt - time) / 60_000.0).toInt() else null
}

class Leg(
    val mode: Mode, val label: String, val name: String, val direction: String, val operator: String,
    val origin: StopTime, val destination: StopTime, val stops: List<StopTime>,
    val minutes: Int, val distance: Int?, val coords: List<DoubleArray>,
    val infos: List<Pair<String, String>>, val realtime: Boolean, val cancelled: Boolean, val longDistance: Boolean,
) {
    /** Minutes to change from the previous ride, if this leg follows one directly. */
    var transfer: Int? = null
}

class Journey(val legs: List<Leg>, val changes: Int, val price: Double?) {
    private val first = legs.first().origin
    private val last = legs.last().destination
    val dep: Long? = first.time
    val arr: Long? = last.time
    val depPoint: StopTime get() = first
    val arrPoint: StopTime get() = last
    val minutes: Int? = run {
        val d = first.rt ?: first.time
        val a = last.rt ?: last.time
        if (d != null && a != null) ((a - d) / 60_000).toInt() else null
    }
    val rides: List<Leg> get() = legs.filter { it.mode != Mode.WALK }
    val cancelled: Boolean get() = legs.any { it.cancelled }
    val infoCount: Int get() = legs.sumOf { it.infos.size }
    val longDistance: List<String> get() = legs.filter { it.longDistance }.map { it.label }

    /** Identifies the same connection across searches (for früher/später without duplicates). */
    val key: String get() = "$dep $arr " + legs.joinToString("/") { it.label }
}
