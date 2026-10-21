package de.heineke.myvrnlite.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import de.heineke.myvrnlite.net.ApiCall
import de.heineke.myvrnlite.net.LoginFlow
import de.heineke.myvrnlite.net.Profile
import de.heineke.myvrnlite.net.ProfileResult
import de.heineke.myvrnlite.net.Tokens
import de.heineke.myvrnlite.net.Unauthorized
import de.heineke.myvrnlite.net.VrnClient
import de.heineke.myvrnlite.net.VrnException
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors

class NotLoggedIn : VrnException("not_logged_in")

/** The refresh token was rejected: the user has to log in again. */
class SessionExpired : VrnException("session_expired")

/** A ticket's barcode payload (Shop/QRCode → QrCode). [call] is null for the offline copy. */
class QrCode(val code: String, val savedAt: Long, val call: ApiCall? = null)

/**
 * App state and the work behind the screens: tokens, tickets, barcodes and profile.
 *
 * State is read and changed on the main thread. Network calls run one at a time on [io]
 * (which also keeps token refreshes from racing) and post their results back; listeners
 * hear about every change.
 */
class Repo(context: Context) {
    private val app = context.applicationContext
    val prefs = Prefs(app)
    private val client by lazy { VrnClient.create(app) }
    private val io = Executors.newSingleThreadExecutor()
    private val disk = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val listeners = LinkedHashSet<() -> Unit>()
    private val tokenFile = SealedFile(File(app.filesDir, "tokens.bin"))
    private val offlineFile = SealedFile(File(app.filesDir, "offline.bin"))
    private val tokenLock = Any()

    /** Written by login/logout here and by token refreshes on [io]. */
    @Volatile var tokens: Tokens? = tokenFile.read()?.let(Tokens::deserialize)
        private set
    val loggedIn: Boolean get() = tokens != null

    /** Set when the session ended on its own (refresh token rejected), for the account page. */
    var sessionExpired = false
        private set

    // A login waiting for the e-mail code.
    var pendingLogin: LoginFlow? = null
        private set
    var pendingEmail: String? = null
        private set

    var orders: JSONArray? = null
        private set
    /** When [orders] was fetched (possibly in an earlier run, for the offline copy). */
    var ordersAt = 0L
        private set
    var ordersCall: ApiCall? = null
        private set
    var ordersError: String? = null
        private set
    /** The last attempt to reach the server failed for lack of network. */
    var offline = false
        private set
    var loadingTickets = false
        private set
    private var lastRefresh = 0L
    /** Bumped on login and logout, so results of calls started before are dropped. */
    private var session = 0

    val qr = HashMap<Long, QrCode>()
    val qrError = HashMap<Long, String>()
    val loadingQr = HashSet<Long>()

    var profile: Profile? = null
        private set
    var profileResult: ProfileResult? = null
        private set
    var loadingProfile = false
        private set

    /** What the offline copy on disk holds: (tickets, saved at), or null if there is none. */
    var offlineState: Pair<Int, Long>? = null
        private set
    private var offlineSub: String? = null

    init {
        restoreOffline()
    }

    fun addListener(l: () -> Unit) = listeners.add(l)

    fun removeListener(l: () -> Unit) = listeners.remove(l)

    private fun changed() = listeners.toList().forEach { it() }

    fun tickets(now: Long = System.currentTimeMillis()) = TicketList.from(orders, prefs.debug, now)

    fun findTicket(id: Long): Ticket? = TicketList.from(orders, true, System.currentTimeMillis()).tickets.find { it.id == id }

    // ---- Login ---------------------------------------------------------------

    fun login(email: String, password: String, remember: Boolean, done: (Throwable?) -> Unit) {
        prefs.remember = remember
        background({ client.newLogin().let { flow -> flow to flow.start(email, password) } }) { r ->
            r.onSuccess { (flow, tokens) ->
                if (tokens != null) loggedIn(tokens) else {
                    pendingLogin = flow
                    pendingEmail = email.trim()
                }
            }
            done(r.exceptionOrNull())
        }
    }

    fun submitCode(code: String, done: (Throwable?) -> Unit) {
        val flow = pendingLogin ?: return done(VrnException("no_pending_login"))
        background({ flow.finalize(code) }) { r ->
            r.onSuccess(::loggedIn)
            done(r.exceptionOrNull())
        }
    }

    fun cancelLogin() {
        pendingLogin = null
        pendingEmail = null
        changed()
    }

    private fun loggedIn(t: Tokens) {
        val sub = t.claims?.optString("sub")?.ifEmpty { null }
        if (offlineSub != null && offlineSub != sub) clearData()  // another account's offline copy
        newSession()
        synchronized(tokenLock) { tokens = t }
        saveTokens(t)
        pendingLogin = null
        pendingEmail = null
        sessionExpired = false
        offlineSub = sub
        refreshTickets()
        refreshProfile()
    }

    fun logout() {
        newSession()
        synchronized(tokenLock) { tokens = null }
        pendingLogin = null
        pendingEmail = null
        clearData()
        disk.execute { tokenFile.delete() }
        changed()
    }

    /** Forgets calls in flight: their results belong to the previous login. */
    private fun newSession() {
        session++
        loadingTickets = false
        loadingProfile = false
        loadingQr.clear()
    }

    private fun clearData() {
        orders = null
        ordersAt = 0
        ordersCall = null
        ordersError = null
        offline = false
        qr.clear()
        qrError.clear()
        profile = null
        profileResult = null
        offlineSub = null
        offlineState = null
        disk.execute { offlineFile.delete() }
    }

    /** Debug: refresh the access token now. */
    fun refreshToken(done: (Throwable?) -> Unit) {
        val t = tokens ?: return done(NotLoggedIn())
        background({ refreshTokens(t) }) { done(it.exceptionOrNull()) }
    }

    private fun saveTokens(t: Tokens) {
        val remember = prefs.remember
        disk.execute { if (remember) tokenFile.write(t.serialize()) else tokenFile.delete() }
    }

    /** Runs [call] with a fresh access token, refreshing first if needed and once on 401. [io] only. */
    private fun <T> authed(call: (String) -> T): T {
        var t = tokens ?: throw NotLoggedIn()
        if (t.expiresAt - System.currentTimeMillis() < 60_000) t = refreshTokens(t)
        return try {
            call(t.accessToken)
        } catch (ignored: Unauthorized) {
            call(refreshTokens(t).accessToken)
        }
    }

    /** [io] only. A rejected refresh token ends the session; network errors don't. */
    private fun refreshTokens(old: Tokens): Tokens {
        val new = try {
            client.refresh(old.refreshToken ?: throw VrnException("refresh_rejected"))
        } catch (e: VrnException) {
            if (e.reason == "refresh_rejected") {
                main.post { expireSession(old) }
                throw SessionExpired()
            }
            throw e
        }
        synchronized(tokenLock) {
            if (tokens !== old) return tokens ?: throw NotLoggedIn()  // logged out (or in again) meanwhile
            tokens = new
        }
        saveTokens(new)  // VRN rotates refresh tokens
        return new
    }

    /** Keeps the tickets (and the offline copy): they're still valid, only the login is gone. */
    private fun expireSession(old: Tokens) {
        synchronized(tokenLock) { if (tokens === old) tokens = null else return }
        sessionExpired = true
        ordersCall = null
        profileResult = null
        disk.execute { tokenFile.delete() }
        changed()
    }

    // ---- Tickets ---------------------------------------------------------------

    /** Refreshes when the app comes to the front, at most every few minutes. */
    fun refreshIfStale() {
        if (System.currentTimeMillis() - lastRefresh > STALE_MS) refreshTickets()
    }

    fun refreshTickets() {
        if (!loggedIn || loadingTickets) return
        loadingTickets = true
        lastRefresh = System.currentTimeMillis()
        changed()
        val s = session
        background({ authed(client::listOrders) }) { r ->
            if (s != session) return@background
            loadingTickets = false
            r.onSuccess { call ->
                ordersCall = call
                val json = call.json
                val error = json?.optJSONObject("Error")
                when {
                    error != null -> ordersError = error.optString("Message").ifEmpty { "ListOrdersV2 failed" }
                    call.code != 200 || json == null -> ordersError = "HTTP ${call.code}"
                    else -> {
                        orders = json.optJSONArray("Orders") ?: JSONArray()
                        ordersAt = System.currentTimeMillis()
                        ordersError = null
                        offline = false
                        saveOffline()
                        prefetchQr()
                    }
                }
            }.onFailure { e ->
                if (e is IOException) offline = true
                else if (e !is SessionExpired) ordersError = e.message
            }
        }
    }

    /** Barcodes of the usable tickets, so they can be shown without network. */
    private fun prefetchQr() {
        val ids = tickets().usable.mapNotNull { it.id }.take(OFFLINE_MAX_TICKETS)
        if (ids.isEmpty()) return
        val s = session
        background({ ids.associateWith { runCatching { fetchQr(it) } } }) { r ->
            if (s != session) return@background
            r.getOrNull()?.forEach { (id, res) -> res.onSuccess { (q, err) -> storeQr(id, q, err) } }
            saveOffline()
        }
    }

    /** Fetches a ticket's barcode unless we have one already (or [force]). */
    fun loadQr(id: Long, force: Boolean = false) {
        if (!loggedIn || id in loadingQr || (!force && qr[id]?.code?.isNotEmpty() == true)) return
        loadingQr += id
        changed()
        val s = session
        background({ fetchQr(id) }) { r ->
            if (s != session) return@background
            loadingQr -= id
            r.onSuccess { (q, err) ->
                storeQr(id, q, err)
                saveOffline()
            }.onFailure { e ->
                if (e is IOException) offline = true
                qrError[id] = e.message ?: e.javaClass.simpleName
            }
        }
    }

    /** [io] only. → (barcode, error message from the server). */
    private fun fetchQr(id: Long): Pair<QrCode, String?> {
        val call = authed { client.qrCode(it, id) }
        val json = call.json
        val code = if (json == null || json.isNull("QrCode")) "" else json.optString("QrCode")
        val error = json?.optJSONObject("Error")?.optString("Message")?.ifEmpty { null }
            ?: if (call.code != 200) "HTTP ${call.code}" else null
        return QrCode(code, System.currentTimeMillis(), call) to error
    }

    /** Keeps a barcode we already have when a new fetch comes back without one. */
    private fun storeQr(id: Long, q: QrCode, error: String?) {
        val old = qr[id]
        qr[id] = if (q.code.isEmpty() && old != null && old.code.isNotEmpty()) QrCode(old.code, old.savedAt, q.call) else q
        if (q.code.isEmpty()) qrError[id] = error ?: "" else qrError.remove(id)
    }

    // ---- Account ---------------------------------------------------------------

    fun refreshProfile() {
        if (!loggedIn || loadingProfile) return
        loadingProfile = true
        changed()
        val s = session
        background({ authed(client::profile) }) { r ->
            if (s != session) return@background
            loadingProfile = false
            r.onSuccess {
                profileResult = it
                if (it.profile != null) {
                    profile = it.profile
                    saveOffline()
                }
            }.onFailure { if (it is IOException) offline = true }
        }
    }

    // ---- Offline copy ------------------------------------------------------------

    fun setOfflineEnabled(on: Boolean) {
        prefs.offline = on
        if (on) saveOffline() else clearOffline()
    }

    fun clearOffline() {
        offlineState = null
        disk.execute { offlineFile.delete() }
        changed()
    }

    private fun saveOffline() {
        val orders = orders
        if (!prefs.offline || orders == null) return
        val usable = TicketList.from(orders, false, System.currentTimeMillis()).usable.mapNotNull { it.id }.toSet()
        val codes = JSONObject()
        for ((id, q) in qr) {
            if (id in usable && q.code.isNotEmpty()) codes.put(id.toString(), JSONObject().put("code", q.code).put("savedAt", q.savedAt))
        }
        val text = JSONObject().put("savedAt", ordersAt).put("sub", offlineSub).put("orders", orders)
            .put("qr", codes).put("profile", profile?.toJson()).toString()
        offlineState = usable.size to ordersAt
        disk.execute { offlineFile.write(text) }
    }

    private fun restoreOffline() {
        val json = offlineFile.read()?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return
        val sub = json.optString("sub").ifEmpty { null }
        if (tokens != null && sub != tokens?.claims?.optString("sub")?.ifEmpty { null }) {  // not this account's
            offlineFile.delete()
            return
        }
        offlineSub = sub
        orders = json.optJSONArray("orders")
        ordersAt = json.optLong("savedAt")
        json.optJSONObject("qr")?.let { codes ->
            for (key in codes.keys()) {
                val id = key.toLongOrNull() ?: continue
                codes.optJSONObject(key)?.let { qr[id] = QrCode(it.optString("code"), it.optLong("savedAt")) }
            }
        }
        json.optJSONObject("profile")?.let { profile = Profile.fromJson(it) }
        offlineState = TicketList.from(orders, false, System.currentTimeMillis()).usable.size to ordersAt
    }

    private fun <T> background(work: () -> T, done: (Result<T>) -> Unit) {
        io.execute {
            val result = try {
                Result.success(work())
            } catch (e: Exception) {
                Result.failure(e)
            }
            main.post {
                done(result)
                changed()
            }
        }
    }

    private companion object {
        // Valid tickets whose barcode is kept for offline use (as OFFLINE_MAX_TICKETS in app.py).
        const val OFFLINE_MAX_TICKETS = 6
        const val STALE_MS = 5 * 60_000L
    }
}
