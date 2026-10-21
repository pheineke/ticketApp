package de.skygazer.myvrnlite.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Base64
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.aztec.encoder.Encoder
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Ticket barcodes. Shop/QRCode returns the payload, not an image: for VRN tickets it's
 * base64 binary data (VDV/UIC) shown as an Aztec code — the same defaults the web client
 * uses with bwip-js. Debug mode can switch to QR or raw text.
 */
object Barcode {
    enum class Kind { PNG, JPEG, SVG, DATA }

    /** Symbology ("aztec" or "qr") and whether the payload is base64 to decode into bytes. */
    data class Options(val sym: String, val b64: Boolean)

    // bwip-js's azteccode default; the Aztec spec's recommended minimum.
    private const val AZTEC_EC_PERCENT = 23
    private val BASE64 = Regex("^[A-Za-z0-9+/]+={0,2}$")

    /** Base64 images are shown as they are; anything else is a payload to encode. */
    fun kind(code: String): Kind {
        val c = code.trim()
        return when {
            c.startsWith("iVBOR") -> Kind.PNG
            c.startsWith("/9j/") -> Kind.JPEG
            c.startsWith("PHN2Zy") || c.startsWith("<svg") -> Kind.SVG
            else -> Kind.DATA
        }
    }

    fun defaultOptions(code: String) = Options("aztec", code.length % 4 == 0 && BASE64.matches(code))

    /** The module matrix for [payload], without quiet zone. Encoded as bytes, no ECI. */
    fun encode(payload: ByteArray, sym: String): BitMatrix = when (sym) {
        "qr" -> QRCodeWriter().encode(String(payload, Charsets.ISO_8859_1), BarcodeFormat.QR_CODE, 0, 0,
            mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M))
        else -> Encoder.encode(payload, AZTEC_EC_PERCENT, Encoder.DEFAULT_AZTEC_LAYERS).matrix
    }

    /** [code] as a bitmap at least [minSize] px wide. Throws if it can't be drawn. */
    fun render(code: String, options: Options, minSize: Int): Bitmap = when (kind(code)) {
        Kind.PNG, Kind.JPEG -> Base64.decode(code.trim(), Base64.DEFAULT).let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            ?: throw IllegalArgumentException("image")
        Kind.SVG -> throw UnsupportedOperationException("svg")
        Kind.DATA -> {
            val bytes = if (options.b64) Base64.decode(code, Base64.DEFAULT)
            else code.toByteArray(if (code.all { it.code < 256 }) Charsets.ISO_8859_1 else Charsets.UTF_8)
            bitmap(encode(bytes, options.sym), minSize)
        }
    }

    /** Whole pixels per module, so scaling never blurs a module edge. */
    private fun bitmap(m: BitMatrix, minSize: Int): Bitmap {
        val scale = maxOf(1, (minSize + m.width - 1) / m.width)
        val w = m.width * scale
        val h = m.height * scale
        val pixels = IntArray(w * h)
        for (y in 0 until m.height) {
            val row = y * scale * w
            for (x in 0 until m.width) {
                pixels.fill(if (m[x, y]) Color.BLACK else Color.WHITE, row + x * scale, row + (x + 1) * scale)
            }
            for (r in 1 until scale) System.arraycopy(pixels, row, pixels, row + r * w, w)
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }
}
