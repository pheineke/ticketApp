package de.heineke.myvrnlite

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.ResultMetadataType
import com.google.zxing.aztec.AztecReader
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import de.heineke.myvrnlite.ui.Barcode
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import kotlin.random.Random

/** Encodes like the app, then reads the result back with ZXing's scanner code. */
class BarcodeTest {
    // About the size of a VDV/UIC ticket barcode, with every byte value in it.
    private val payload = ByteArray(420) { it.toByte() }.also { Random(42).nextBytes(it, 256, 420) }

    @Test
    fun aztecKeepsBinaryPayload() {
        val result = AztecReader().decode(scan(Barcode.encode(payload, "aztec")))
        assertArrayEquals(payload, result.text.toByteArray(Charsets.ISO_8859_1))
    }

    @Test
    fun qrKeepsBinaryPayload() {
        val result = QRCodeReader().decode(scan(Barcode.encode(payload, "qr")), mapOf(DecodeHintType.PURE_BARCODE to true))
        @Suppress("UNCHECKED_CAST")
        val segments = result.resultMetadata[ResultMetadataType.BYTE_SEGMENTS] as List<ByteArray>
        assertArrayEquals(payload, segments.reduce(ByteArray::plus))
    }

    @Test
    fun detectsPayloadKinds() {
        val b64 = Base64.getEncoder().encodeToString(payload)
        assertEquals(Barcode.Options("aztec", true), Barcode.defaultOptions(b64))
        assertFalse(Barcode.defaultOptions("VRN-TICKET-123").b64)
        assertEquals(Barcode.Kind.DATA, Barcode.kind(b64))
        assertEquals(Barcode.Kind.PNG, Barcode.kind("iVBORw0KGgo="))
        assertEquals(Barcode.Kind.JPEG, Barcode.kind("/9j/4AAQ"))
        assertEquals(Barcode.Kind.SVG, Barcode.kind("PHN2ZyB4bWxucz0i"))
        assertTrue(Barcode.encode(payload, "aztec").width > 40)
    }

    /** The matrix as a scanner would see it: 3 px per module, 4 modules of quiet zone. */
    private fun scan(m: BitMatrix): BinaryBitmap {
        val scale = 3
        val quiet = 4 * scale
        val w = m.width * scale + 2 * quiet
        val h = m.height * scale + 2 * quiet
        val pixels = IntArray(w * h) { i ->
            val x = (i % w - quiet) / scale
            val y = (i / w - quiet) / scale
            val inside = i % w >= quiet && i / w >= quiet && x < m.width && y < m.height
            if (inside && m[x, y]) 0xff000000.toInt() else 0xffffffff.toInt()
        }
        return BinaryBitmap(HybridBinarizer(RGBLuminanceSource(w, h, pixels)))
    }
}
