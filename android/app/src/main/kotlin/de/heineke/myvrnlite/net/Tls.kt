package de.heineke.myvrnlite.net

import android.annotation.SuppressLint
import android.content.Context
import de.heineke.myvrnlite.R
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * TLS trust for shop.myvrn.de on old Androids.
 *
 * The server's Let's Encrypt chain ends at ISRG Root X1, which Android only ships from
 * 7.1.1 on, so on Android 5–7.0 every request would fail with "Trust anchor for
 * certification path not found". We therefore trust the system CAs plus the ISRG roots
 * bundled in res/raw (what a network security config would do, but that needs API 24).
 */
object Tls {
    private val BUNDLED_ROOTS = intArrayOf(R.raw.isrg_root_x1, R.raw.isrg_root_x2)

    fun trustManager(context: Context): X509TrustManager {
        val bundled = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            val certs = CertificateFactory.getInstance("X.509")
            for (res in BUNDLED_ROOTS) {
                context.resources.openRawResource(res).use { setCertificateEntry("root-$res", certs.generateCertificate(it)) }
            }
        }
        return SystemPlusBundled(platformTrustManager(null), platformTrustManager(bundled))
    }

    fun socketFactory(trustManager: X509TrustManager): SSLSocketFactory =
        SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), null) }.socketFactory

    /** The platform trust manager for [keyStore], or for the system CAs when it's null. */
    private fun platformTrustManager(keyStore: KeyStore?): X509TrustManager {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(keyStore)
        return factory.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    // Only widens the trust anchors; every chain is still checked by a platform trust manager.
    @SuppressLint("CustomX509TrustManager")
    private class SystemPlusBundled(
        private val system: X509TrustManager,
        private val bundled: X509TrustManager,
    ) : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
            system.checkClientTrusted(chain, authType)

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            try {
                system.checkServerTrusted(chain, authType)
            } catch (e: CertificateException) {
                try {
                    bundled.checkServerTrusted(chain, authType)
                } catch (_: CertificateException) {
                    throw e  // report the system's verdict, it's the more useful one
                }
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers + bundled.acceptedIssuers
    }
}
