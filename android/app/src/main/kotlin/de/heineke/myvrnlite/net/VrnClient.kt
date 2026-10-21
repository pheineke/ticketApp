package de.heineke.myvrnlite.net

import android.content.Context
import android.os.Build
import android.util.Base64
import de.heineke.myvrnlite.BuildConfig
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/** A refusal from the VRN server. Network failures are plain [IOException]s instead. */
open class VrnException(val reason: String, message: String = reason) : Exception(message)

/** The access token was rejected (HTTP 401). */
class Unauthorized : VrnException("unauthorized")

/** One API call, kept for the debug panels. */
class ApiCall(val request: String, val code: Int, val body: String) {
    val json: JSONObject? by lazy { runCatching { JSONObject(body) }.getOrNull() }
}

/**
 * The myVRN login and ticket API — a port of webapp/vrn_client.py, which documents every
 * request. Unlike the web client this needs no proxy: the app can follow the vrn://
 * redirect itself, and CORS doesn't apply.
 *
 * All calls block; run them off the main thread.
 */
class VrnClient(private val http: OkHttpClient) {

    fun newLogin() = LoginFlow(http)

    fun refresh(refreshToken: String): Tokens {
        val res = http.newCall(Request.Builder().url("$BASE/Identity/connect/token").post(FormBody.Builder()
            .add("grant_type", "refresh_token").add("refresh_token", refreshToken)
            .add("client_id", CLIENT_ID).add("scope", SCOPE).build()).build()).execute()
        val body = res.use { it.body!!.string() }
        if (!res.isSuccessful) {
            // 400 invalid_grant: revoked or expired. Anything else (5xx) is worth retrying later.
            val reason = if (res.code == 400 || res.code == 401) "refresh_rejected" else "refresh_failed"
            throw VrnException(reason, "refresh: ${res.code} ${body.take(200)}")
        }
        val json = JSONObject(body)
        if (!json.has("refresh_token")) json.put("refresh_token", refreshToken)  // some servers omit it
        return Tokens(json, System.currentTimeMillis())
    }

    /** POST Shop/ListOrdersV2 for active, future and expired tickets of all kinds. */
    fun listOrders(accessToken: String): ApiCall {
        val now = System.currentTimeMillis()
        return shop(accessToken, "Shop/ListOrdersV2", JSONObject()
            .put("From", iso(now - 365 * DAY)).put("To", iso(now + 400 * DAY))
            .put("Index", 0).put("MaxNumber", 50)
            .put("ListOrdersTimeFilterMask", TIME_ACTIVE or TIME_FUTURE or TIME_HISTORIC)
            .put("ListOrdersTicketFilterMask", TICKETS_ALL))
    }

    /** POST Shop/QRCode; [ticketId] is Orders[].Tickets[].Id. */
    fun qrCode(accessToken: String, ticketId: Long): ApiCall =
        shop(accessToken, "Shop/QRCode", JSONObject().put("Id", ticketId))

    /**
     * Profile for the account page. The Identity OData profile answers 403 for the app's
     * `ticketing` token, so fall back to the customer record the app uses when buying
     * tickets (Payment/Customer/PaymentInfos).
     */
    fun profile(accessToken: String): ProfileResult {
        val calls = mutableListOf(get(accessToken, PROFILE_URL))
        calls[0].json?.takeIf { calls[0].code == 200 }?.let { data ->
            val prof = if (data.has("value")) data.optJSONArray("value")?.optJSONObject(0) else data
            if (prof != null) {
                val userName = prof.optJSONObject("User")?.optString("Name")
                return ProfileResult(Profile(
                    title = prof.ci("Title"), first = prof.ci("FirstName"), last = prof.ci("LastName"),
                    email = prof.ci("Email") ?: userName?.ifEmpty { null }, dob = prof.ci("DateOfBirth"),
                    street = prof.ci("StreetAddress"), zip = prof.ci("PostalCode"),
                    city = prof.ci("Locality"), phone = prof.ci("Phone"),
                ), "Identity/OData/UserProfile", calls)
            }
        }
        val rec = get(accessToken, PAYMENT_INFO_URL).also { calls += it }
        val addr = rec.json?.let { j -> j.keys().asSequence().firstOrNull { it.equals("Address", true) }?.let(j::optJSONObject) }
        if (rec.code == 200 && addr != null) {
            return ProfileResult(Profile(
                title = null, first = addr.ci("FirstName"), last = addr.ci("LastName"),
                email = addr.ci("Email"), dob = addr.ci("Dob", "DateOfBirth"),
                street = addr.ci("StreetAddress"), zip = addr.ci("PostalCode"),
                city = addr.ci("Locality"), phone = null,
            ), "Payment/Customer/PaymentInfos", calls)
        }
        return ProfileResult(null, null, calls)
    }

    /**
     * The user's entitlements: the app's OData GET (403 for some accounts), else the web
     * shop's POST. → calls made, the last one holding {value} or {Values}.
     */
    fun entitlements(accessToken: String): List<ApiCall> {
        val first = get(accessToken, ENTITLEMENTS_URL)
        if (first.code == 200 && first.json != null) return listOf(first)
        return listOf(first, post(accessToken, ENTITLEMENTS_POST_URL, JSONObject()
            .put("Usage", 0).put("Index", 0).put("MaxNumber", 20).put("ExpandEntitlementDocument", false)
            .put("AnnotationIsAscending", true).put("CreatedIsAscending", false)))
    }

    /** Universities for the Hochschul-Login: {value: [{EntityId, DisplayNames}]}. */
    fun authorities(accessToken: String): ApiCall = get(accessToken, "$BASE/Dfn/GetAuthorities")

    /**
     * POST Pdfconverter/Invoice {TicketOrderId: [Orders[].Id], IsGuestUser} → {Success, Invoice, Error}:
     * the order's invoice as a PDF (base64), as the app's "Rechnung herunterladen". On the shop's
     * root, not under TicketShop/ (the web shop's CommonOrderInvoicePdf).
     */
    fun invoice(accessToken: String, orderId: Long): ApiCall = post(accessToken, "$BASE/Pdfconverter/Invoice",
        JSONObject().put("TicketOrderId", JSONArray().put(orderId)).put("IsGuestUser", false))

    /** POST TicketShop/Shop/CreateOrder → {Orders} | {RedirectUrl} | {Error}. Buys: only on the user's tap. */
    fun createOrder(accessToken: String, payload: JSONObject): ApiCall = shop(accessToken, "Shop/CreateOrder", payload)

    private fun shop(accessToken: String, path: String, payload: JSONObject): ApiCall = post(accessToken, "$BASE/TicketShop/$path", payload)

    private fun post(accessToken: String, url: String, payload: JSONObject): ApiCall {
        val req = Request.Builder().url(url)
            .header("Authorization", "Bearer $accessToken").header("Accept", "application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaType())).build()
        return call(req, "POST $url\n${payload.toString(2)}")
    }

    private fun get(accessToken: String, url: String): ApiCall = call(Request.Builder().url(url)
        .header("Authorization", "Bearer $accessToken").header("Accept", "application/json").build(), "GET $url")

    private fun call(req: Request, label: String): ApiCall {
        val rec = http.newCall(req).execute().use { ApiCall(label, it.code, it.body!!.string()) }
        if (rec.code == 401) throw Unauthorized()
        return rec
    }

    companion object {
        const val BASE = "https://shop.myvrn.de"
        const val CLIENT_ID = "vrn"
        const val ORGANIZATION = "vrn"
        const val REDIRECT_URI = "vrn://oauth/callback"
        const val SCOPE = "ticketing offline_access"
        private const val PROFILE_URL = "$BASE/Identity/OData/UserProfile?\$expand=User(\$select=Name),User(\$expand=UserProperty)"
        private const val PAYMENT_INFO_URL = "$BASE/Payment/Customer/PaymentInfos"
        // The app selects these fields: without $select the answer includes the uploaded document (403).
        private const val ENTITLEMENTS_URL = "$BASE/Personalization/OData/TicketEntitlement?\$expand=Ticket" +
            "&\$select=ID,UserID,Created,Annotation,EligibleUntil,EligibleFrom,Approved,Usage,Status"
        private const val ENTITLEMENTS_POST_URL = "$BASE/Personalization/Entitlement/TicketEntitlements"

        // ListOrdersV2 bitmasks: TimeFilter{Active, Historic, Future}, TicketFilter{Quota, Abo, Classic, CiBo}.
        private const val TIME_ACTIVE = 1
        private const val TIME_HISTORIC = 2
        private const val TIME_FUTURE = 4
        private const val TICKETS_ALL = 1 or 2 or 4 or 8
        private const val DAY = 86_400_000L

        /** The app's HTTP client (ticket API and journey planner): system + bundled ISRG roots. */
        fun httpClient(context: Context): OkHttpClient {
            val trust = Tls.trustManager(context)
            val userAgent = "myVRNLite/${BuildConfig.VERSION_NAME} (Android ${Build.VERSION.RELEASE})"
            return OkHttpClient.Builder()
                .sslSocketFactory(Tls.socketFactory(trust), trust)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(25, TimeUnit.SECONDS)
                .writeTimeout(25, TimeUnit.SECONDS)
                .addInterceptor { it.proceed(it.request().newBuilder().header("User-Agent", userAgent).build()) }
                .build()
        }

        private fun iso(ms: Long): String =
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))

        /** Case-insensitive lookup (the TicketShop and Identity APIs differ in casing). */
        private fun JSONObject.ci(vararg keys: String): String? {
            for (key in keys) {
                val actual = keys().asSequence().firstOrNull { it.equals(key, ignoreCase = true) } ?: continue
                val v = opt(actual)
                if (v != null && v != JSONObject.NULL && v.toString().isNotEmpty()) return v.toString()
            }
            return null
        }
    }
}

/**
 * One interactive login: [start] → (e-mail code →) [finalize] → tokens.
 *
 * 1. GET  /Identity/connect/authorize (PKCE)       → 302 /authentication/login?returnUrl=…
 * 2. POST /Identity/Authentication/Login           → {Succeeded, TwoFactorProvider, …} + auth cookie
 * 3. POST /Identity/Authentication/FinalizeLogin   "<code>" (only for the e-mail second factor)
 * 4. GET  {returnUrl} with the cookie              → 302 vrn://oauth/callback?code=…&state=…
 * 5. POST /Identity/connect/token                  authorization_code + code_verifier
 */
class LoginFlow internal constructor(base: OkHttpClient) {
    // Own cookie jar for steps 1–4, and no redirect following: we need the Location headers.
    private val http = base.newBuilder().cookieJar(SessionCookies()).followRedirects(false).followSslRedirects(false).build()
    private val verifier = b64url(random(48))
    private val state = b64url(random(16))
    private var returnUrl: String? = null

    /** Steps 1–2. Returns the tokens, or null when an e-mail code is needed ([finalize]). */
    fun start(email: String, password: String): Tokens? {
        authorize()
        val res = postJson("/Identity/Authentication/Login", JSONObject()
            .put("Name", email.trim()).put("Password", password)
            .put("Client", VrnClient.CLIENT_ID).put("Organization", VrnClient.ORGANIZATION)
            .put("RememberLogin", false).toString())
        val json = runCatching { JSONObject(res.second) }.getOrElse { throw VrnException("LoginFailed", "Login: ${res.first}") }
        if (!json.optBoolean("Succeeded")) {
            throw VrnException(FAILURES.firstOrNull { json.optBoolean(it) } ?: "LoginFailed")
        }
        return when (val provider = json.optInt("TwoFactorProvider", MFA_NONE)) {
            MFA_NONE -> finish()
            MFA_EMAIL -> null
            else -> throw VrnException("Unsupported2FA", "2FA-Verfahren $provider wird nicht unterstützt")
        }
    }

    /** Step 3 (e-mail code), then 4–5. */
    fun finalize(code: String): Tokens {
        val (status, body) = postJson("/Identity/Authentication/FinalizeLogin", JSONObject.quote(code.trim()))
        val json = body.trim().takeIf { it.startsWith("{") }?.let { runCatching { JSONObject(it) }.getOrNull() }
        if (status >= 400 || (json != null && json.has("Succeeded") && !json.optBoolean("Succeeded"))) {
            throw VrnException("FinalizeLogin", "$status ${body.take(200)}")
        }
        return finish()
    }

    private fun authorize() {
        val url = "${VrnClient.BASE}/Identity/connect/authorize".toHttpUrl().newBuilder()
            .addQueryParameter("client_id", VrnClient.CLIENT_ID)
            .addQueryParameter("redirect_uri", VrnClient.REDIRECT_URI)
            .addQueryParameter("response_type", "code")
            .addQueryParameter("scope", VrnClient.SCOPE)
            .addQueryParameter("state", state)
            .addQueryParameter("nonce", b64url(random(16)))
            .addQueryParameter("code_challenge", b64url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray())))
            .addQueryParameter("code_challenge_method", "S256")
            .build()
        val (code, location) = head(url)
        returnUrl = location?.let { url.resolve(it)?.queryParameter("returnUrl") }
        if (code != 302 || returnUrl == null) throw VrnException("authorize", "authorize: unexpected $code ${location?.take(120)}")
    }

    /** Steps 4–5: follow the authorize callback to the vrn:// redirect and redeem the code. */
    private fun finish(): Tokens {
        var url = VrnClient.BASE.toHttpUrl().resolve(returnUrl!!)!!
        repeat(5) {  // a hop or two of same-origin redirects before vrn://
            val (code, location) = head(url)
            if (location != null && location.startsWith(VrnClient.REDIRECT_URI)) return redeem(location)
            if (code !in REDIRECTS || location == null) throw VrnException("callback", "callback: no vrn:// redirect ($code)")
            url = url.resolve(location) ?: throw VrnException("callback", "callback: bad redirect")
        }
        throw VrnException("callback", "callback: too many redirects")
    }

    private fun redeem(callback: String): Tokens {
        val q = query(callback)
        q["error"]?.let { throw VrnException("authorize", "authorize error: $it") }
        if (q["state"] != state) throw VrnException("state", "state mismatch")
        val code = q["code"] ?: throw VrnException("callback", "callback without code")
        val res = http.newCall(Request.Builder().url("${VrnClient.BASE}/Identity/connect/token").post(FormBody.Builder()
            .add("grant_type", "authorization_code").add("code", code)
            .add("redirect_uri", VrnClient.REDIRECT_URI).add("client_id", VrnClient.CLIENT_ID)
            .add("code_verifier", verifier).build()).build()).execute()
        val body = res.use { it.body!!.string() }
        if (!res.isSuccessful) throw VrnException("token", "token: ${res.code} ${body.take(200)}")
        return Tokens(JSONObject(body), System.currentTimeMillis())
    }

    /** GET without following redirects → (status, Location). */
    private fun head(url: HttpUrl): Pair<Int, String?> =
        http.newCall(Request.Builder().url(url).build()).execute().use { it.code to it.header("Location") }

    private fun postJson(path: String, body: String): Pair<Int, String> {
        val req = Request.Builder().url(VrnClient.BASE + path)
            .header("Accept", JSON_ODATA)
            .post(body.toRequestBody(JSON_ODATA.toMediaType())).build()
        return http.newCall(req).execute().use { it.code to it.body!!.string() }
    }

    private class SessionCookies : CookieJar {
        private val cookies = mutableListOf<Cookie>()

        @Synchronized
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            for (c in cookies) {
                this.cookies.removeAll { it.name == c.name && it.domain == c.domain && it.path == c.path }
                this.cookies += c
            }
        }

        @Synchronized
        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val now = System.currentTimeMillis()
            cookies.removeAll { it.expiresAt < now }
            return cookies.filter { it.matches(url) }
        }
    }

    private companion object {
        // Same content negotiation the login SPA's fetch wrapper uses.
        const val JSON_ODATA = "application/json;odata.metadata=none;IEEE754Compatible=true;charset=utf-8"
        // TwoFactorProvider enum from the SPA: 0 none, 1 authenticator app, 2 e-mail, 3 SMS.
        const val MFA_NONE = 0
        const val MFA_EMAIL = 2
        val FAILURES = listOf("CredentialValidationFailed", "UserNotActivated", "UserLocked", "InvalidClient", "InvalidOrganization")
        val REDIRECTS = setOf(301, 302, 303, 307)
        val rng = SecureRandom()

        fun random(n: Int) = ByteArray(n).also(rng::nextBytes)

        fun b64url(data: ByteArray): String = Base64.encodeToString(data, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

        /** Query of a URL OkHttp can't parse (the vrn:// custom scheme). */
        fun query(url: String): Map<String, String> = url.substringAfter('?', "").substringBefore('#')
            .split('&').filter { '=' in it }
            .associate { URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
    }
}
