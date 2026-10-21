package de.heineke.myvrnlite.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import de.heineke.myvrnlite.net.Efa
import de.heineke.myvrnlite.net.EfaException
import de.heineke.myvrnlite.net.Journey
import de.heineke.myvrnlite.net.NearbyStop
import de.heineke.myvrnlite.net.Place
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.Executors

/**
 * The Fahrplan tab's state: the search (origin, destination, time), its results and the
 * recent searches. Lives as long as the app, so a rotation or tab switch keeps the results.
 * Read and changed on the main thread; requests run on [io] and post back.
 */
class Fahrplan(context: Context, private val efa: Efa) {
    private val prefs = context.applicationContext.getSharedPreferences("fahrplan", Context.MODE_PRIVATE)
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val listeners = LinkedHashSet<() -> Unit>()

    var from: Place? = null
    var to: Place? = null
    /** Requested time (epoch ms), or null for "now". */
    var time: Long? = null
    var arrive = false

    var journeys: List<Journey> = emptyList()
        private set
    var error: String? = null
        private set
    var loading = false
        private set
    /** Loading earlier (-1) or later (+1) trips, else 0. */
    var loadingMore = 0
        private set
    /** The time and mode the shown results were searched for. */
    var searchedTime = 0L
        private set
    var searchedArrive = false
        private set
    /** Counts successful searches, so the UI can tell a new result list from more of the same. */
    var searchId = 0
        private set
    private var seq = 0

    fun addListener(l: () -> Unit) = listeners.add(l)

    fun removeListener(l: () -> Unit) = listeners.remove(l)

    private fun changed() = listeners.toList().forEach { it() }

    /**
     * Search with the current settings. [fromText]/[toText] are what the fields show: if no
     * suggestion was picked for one, the best stop-finder match for the text is used.
     */
    fun search(fromText: String, toText: String) {
        val when_ = time ?: System.currentTimeMillis()
        val arr = arrive
        val pickedFrom = from?.takeIf { it.name == fromText }
        val pickedTo = to?.takeIf { it.name == toText }
        if (fromText.isBlank() || toText.isBlank()) {
            error = "Bitte Start und Ziel angeben."
            return changed()
        }
        val mine = ++seq
        loading = true
        error = null
        changed()
        run({
            val o = pickedFrom ?: resolve(fromText)
            val d = pickedTo ?: resolve(toText)
            Triple(o, d, efa.trips(o, d, when_, arr))
        }) { r ->
            if (mine != seq) return@run
            loading = false
            r.onSuccess { (o, d, list) ->
                from = o
                to = d
                journeys = list
                searchedTime = when_
                searchedArrive = arr
                searchId++
                remember(o, d)
            }.onFailure {
                journeys = emptyList()
                error = message(it)
            }
        }
    }

    /** Earlier (-1) or later (+1) trips, added to the list without duplicates. */
    fun more(direction: Int) {
        val o = from ?: return
        val d = to ?: return
        if (journeys.isEmpty() || loading || loadingMore != 0) return
        val at = if (direction > 0) (journeys.last().dep ?: searchedTime) + 60_000
        else (journeys.first().dep ?: searchedTime) - 45 * 60_000
        val mine = seq
        loadingMore = direction
        changed()
        run({ efa.trips(o, d, at, false) }) { r ->
            loadingMore = 0
            if (mine != seq) return@run
            r.onSuccess { list ->
                val have = journeys.map { it.key }.toSet()
                val fresh = list.filter { it.key !in have }
                journeys = if (direction > 0) journeys + fresh else fresh + journeys
            }.onFailure { error = message(it) }
        }
    }

    fun clearResults() {
        seq++
        journeys = emptyList()
        error = null
        loading = false
        changed()
    }

    /** Stop-finder suggestions; blocking, for AutoCompleteTextView's background filtering. */
    fun suggest(text: String): List<Place> = try {
        efa.stops(text)
    } catch (e: IOException) {
        emptyList()
    }

    /** What's at a map point (address + nearby stops); [done] runs on the main thread. */
    fun here(lat: Double, lon: Double, done: (Result<Pair<Place, List<NearbyStop>>>) -> Unit) {
        Thread {
            val r = runCatching { efa.here(lat, lon) }
            main.post { done(r) }
        }.start()
    }

    private fun resolve(text: String): Place {
        val hits = efa.stops(text, 3)
        return hits.firstOrNull { it.best } ?: hits.firstOrNull() ?: throw EfaException("„$text“ wurde nicht gefunden.")
    }

    private fun message(e: Throwable): String = when (e) {
        is EfaException -> e.message ?: "Keine Verbindung gefunden."
        is IOException -> "VRN-Fahrplanauskunft nicht erreichbar. Bitte Verbindung prüfen."
        else -> e.message ?: e.javaClass.simpleName
    }

    // ---- Recent searches -------------------------------------------------------------

    val recent: List<Pair<Place, Place>>
        get() = runCatching {
            val arr = JSONArray(prefs.getString("recent", "[]"))
            (0 until arr.length()).map { arr.getJSONObject(it) }
                .map { Place.fromJson(it.getJSONObject("from")) to Place.fromJson(it.getJSONObject("to")) }
        }.getOrDefault(emptyList())

    private fun remember(o: Place, d: Place) {
        if (o.isPoint || d.isPoint) return  // points on the map are one-offs
        val list = listOf(o to d) + recent.filterNot { it.first.id == o.id && it.second.id == d.id }
        val arr = JSONArray()
        list.take(RECENT_MAX).forEach { (a, b) -> arr.put(JSONObject().put("from", a.toJson()).put("to", b.toJson())) }
        prefs.edit().putString("recent", arr.toString()).apply()
    }

    fun clearRecent() {
        prefs.edit().remove("recent").apply()
        changed()
    }

    private fun <T> run(work: () -> T, done: (Result<T>) -> Unit) {
        io.execute {
            val r = try {
                Result.success(work())
            } catch (e: Exception) {
                Result.failure(e)
            }
            main.post {
                done(r)
                changed()
            }
        }
    }

    private companion object {
        const val RECENT_MAX = 6
    }
}
