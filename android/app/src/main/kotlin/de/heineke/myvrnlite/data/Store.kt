package de.heineke.myvrnlite.data

import android.annotation.TargetApi
import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** User settings (the web client keeps these in cookies). */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** "system", "light" or "dark". */
    var theme: String
        get() = sp.getString("theme", "system")!!
        set(v) = sp.edit().putString("theme", v).apply()

    /** "lite" (red myVRN Lite palette) or "vrn" (original VRN blue/navy). */
    var palette: String
        get() = sp.getString("palette", "lite")!!
        set(v) = sp.edit().putString("palette", v).apply()

    var offline: Boolean
        get() = sp.getBoolean("offline", true)
        set(v) = sp.edit().putBoolean("offline", v).apply()

    var debug: Boolean
        get() = sp.getBoolean("debug", false)
        set(v) = sp.edit().putBoolean("debug", v).apply()

    /** "Angemeldet bleiben" for the next login. */
    var remember: Boolean
        get() = sp.getBoolean("remember", true)
        set(v) = sp.edit().putBoolean("remember", v).apply()

    /** Barcode rendering chosen in debug mode ("aztec"/"qr" + base64 → bytes), or null for the default. */
    var barcode: Pair<String, Boolean>?
        get() = sp.getString("barcode_sym", null)?.let { it to sp.getBoolean("barcode_b64", true) }
        set(v) {
            if (v == null) sp.edit().remove("barcode_sym").remove("barcode_b64").apply()
            else sp.edit().putString("barcode_sym", v.first).putBoolean("barcode_b64", v.second).apply()
        }
}

/**
 * Seals small blobs (tokens, the offline copy) with an AES key in the Android Keystore.
 * The Keystore only holds AES keys from Android 6, so on 5.x the data is stored as is:
 * still private to the app and excluded from backups, just not encrypted at rest.
 */
object SecureBox {
    private const val ALIAS = "myvrnlite"
    private const val KEYSTORE = "AndroidKeyStore"

    fun seal(plain: String): String {
        if (Build.VERSION.SDK_INT < 23) return "p:$plain"
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return "k1:" + Base64.encodeToString(cipher.iv + cipher.doFinal(plain.toByteArray()), Base64.NO_WRAP)
    }

    /** The sealed text, or null if it can't be opened (e.g. the key is gone). */
    fun open(sealed: String): String? = when {
        sealed.startsWith("p:") -> sealed.substring(2)
        sealed.startsWith("k1:") && Build.VERSION.SDK_INT >= 23 -> try {
            val data = Base64.decode(sealed.substring(3), Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data, 0, 12))
            String(cipher.doFinal(data, 12, data.size - 12))
        } catch (e: Exception) {
            Log.w("SecureBox", "cannot open sealed data", e)
            null
        }
        else -> null
    }

    @TargetApi(23)  // callers check the version
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build())
        }.generateKey()
    }
}

/** A sealed text file in the app's private storage, written atomically. */
class SealedFile(private val file: File) {
    fun read(): String? = if (file.exists()) runCatching { SecureBox.open(file.readText()) }.getOrNull() else null

    /** Runs on an executor, where an exception (full disk, Keystore failure) would kill the app. */
    fun write(text: String) {
        try {
            val tmp = File(file.path + ".tmp")
            tmp.writeText(SecureBox.seal(text))
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        } catch (e: Exception) {
            Log.w("SealedFile", "cannot write ${file.name}", e)
        }
    }

    fun delete() {
        file.delete()
    }
}
