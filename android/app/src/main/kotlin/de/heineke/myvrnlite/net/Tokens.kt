package de.heineke.myvrnlite.net

import android.util.Base64
import org.json.JSONObject

/** A token endpoint response and when it arrived. */
class Tokens(val json: JSONObject, val receivedAt: Long) {
    val accessToken: String get() = json.optString("access_token")
    val refreshToken: String? get() = json.optString("refresh_token").ifEmpty { null }
    val scope: String get() = json.optString("scope")
    val claims: JSONObject? get() = jwtClaims(accessToken)
    val idClaims: JSONObject? get() = jwtClaims(json.optString("id_token"))

    /** Access token expiry (epoch ms): its exp claim, else receivedAt + expires_in. */
    val expiresAt: Long
        get() {
            claims?.optLong("exp")?.takeIf { it > 0 }?.let { return it * 1000 }
            val expiresIn = json.optLong("expires_in")
            return if (expiresIn > 0) receivedAt + expiresIn * 1000 else Long.MAX_VALUE
        }

    fun serialize(): String = JSONObject().put("tokens", json).put("receivedAt", receivedAt).toString()

    companion object {
        fun deserialize(s: String): Tokens? = runCatching {
            JSONObject(s).let { Tokens(it.getJSONObject("tokens"), it.getLong("receivedAt")) }
        }.getOrNull()

        /** JWT payload, decoded WITHOUT verifying the signature (display and expiry only). */
        fun jwtClaims(token: String): JSONObject? = runCatching {
            JSONObject(String(Base64.decode(token.split('.')[1], Base64.URL_SAFE), Charsets.UTF_8))
        }.getOrNull()
    }
}

class Profile(
    val title: String?, val first: String?, val last: String?, val email: String?, val dob: String?,
    val street: String?, val zip: String?, val city: String?, val phone: String?,
) {
    fun toJson(): JSONObject = JSONObject().put("title", title).put("first", first).put("last", last)
        .put("email", email).put("dob", dob).put("street", street).put("zip", zip).put("city", city).put("phone", phone)

    companion object {
        fun fromJson(j: JSONObject): Profile {
            fun s(k: String) = if (j.isNull(k)) null else j.optString(k)
            return Profile(s("title"), s("first"), s("last"), s("email"), s("dob"), s("street"), s("zip"), s("city"), s("phone"))
        }
    }
}

/** A profile lookup with the calls it took (for the debug panel). */
class ProfileResult(val profile: Profile?, val source: String?, val calls: List<ApiCall>)
