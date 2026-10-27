package app.cinder.wallet

import android.content.ComponentName
import android.nfc.NfcAdapter
import android.nfc.cardemulation.CardEmulation
import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import android.app.Activity

/**
 * Makes this phone look like an NFC tag holding one web link. An iPhone (XS or newer)
 * reads it by touching the top edge to the back of this phone, with no app needed,
 * and offers to open the link in Safari. That's how a payment request reaches an iPhone,
 * which can't be read as a tap-to-pay card itself.
 */
object NdefTag {
    @Volatile private var file: ByteArray? = null

    /** Start offering [url]. Call [stop] when the screen closes. */
    fun offer(a: Activity, url: String) {
        file = ndefFile(url)
        runCatching {
            val adapter = NfcAdapter.getDefaultAdapter(a) ?: return
            CardEmulation.getInstance(adapter).setPreferredService(a, ComponentName(a, NdefTagService::class.java))
        }
    }

    fun stop(a: Activity) {
        file = null
        runCatching {
            val adapter = NfcAdapter.getDefaultAdapter(a) ?: return
            CardEmulation.getInstance(adapter).unsetPreferredService(a)
        }
    }

    fun current(): ByteArray? = file

    /** NDEF file: 2-byte length, then one short URI record. */
    fun ndefFile(url: String): ByteArray {
        val (code, rest) = when {
            url.startsWith("https://www.") -> 0x02 to url.removePrefix("https://www.")
            url.startsWith("https://") -> 0x04 to url.removePrefix("https://")
            url.startsWith("http://") -> 0x03 to url.removePrefix("http://")
            else -> 0x00 to url
        }
        val payload = byteArrayOf(code.toByte()) + rest.toByteArray(Charsets.UTF_8)
        require(payload.size < 250) { "link too long" }
        val record = byteArrayOf(0xD1.toByte(), 0x01, payload.size.toByte(), 0x55) + payload
        return byteArrayOf((record.size shr 8).toByte(), record.size.toByte()) + record
    }
}

/** The NFC Forum "Type 4 tag" conversation: select the app, read the capability file, read the message. */
class NdefTagService : HostApduService() {
    private var appSelected = false
    private var file = 0   // 1 = capability container, 2 = the NDEF message

    override fun processCommandApdu(c: ByteArray, extras: Bundle?): ByteArray {
        val msg = NdefTag.current() ?: return NOT_FOUND
        if (c.size < 4) return ERROR
        val ins = c[1].toInt() and 0xFF
        val p1 = c[2].toInt() and 0xFF
        when (ins) {
            0xA4 -> {   // SELECT
                if (p1 == 0x04) {
                    val lc = if (c.size > 4) c[4].toInt() and 0xFF else 0
                    val name = if (c.size >= 5 + lc) c.copyOfRange(5, 5 + lc) else return ERROR
                    appSelected = name.contentEquals(AID)
                    file = 0
                    return if (appSelected) OK else NOT_FOUND
                }
                if (p1 == 0x00 && appSelected && c.size >= 7) {
                    file = when (((c[5].toInt() and 0xFF) shl 8) or (c[6].toInt() and 0xFF)) {
                        0xE103 -> 1
                        0xE104 -> 2
                        else -> 0
                    }
                    return if (file != 0) OK else NOT_FOUND
                }
                return NOT_FOUND
            }
            0xB0 -> {   // READ BINARY
                val data = when (file) { 1 -> CC; 2 -> msg; else -> return NOT_FOUND }
                val off = (p1 shl 8) or (c[3].toInt() and 0xFF)
                val le = if (c.size > 4) (c[4].toInt() and 0xFF).let { if (it == 0) 256 else it } else 256
                if (off > data.size) return ERROR
                return data.copyOfRange(off, minOf(data.size, off + le)) + OK
            }
        }
        return NOT_SUPPORTED
    }

    override fun onDeactivated(reason: Int) { appSelected = false; file = 0 }

    companion object {
        private val AID = byteArrayOf(0xD2.toByte(), 0x76, 0x00, 0x00, 0x85.toByte(), 0x01, 0x01)
        private val OK = byteArrayOf(0x90.toByte(), 0x00)
        private val NOT_FOUND = byteArrayOf(0x6A, 0x82.toByte())
        private val ERROR = byteArrayOf(0x6A, 0x86.toByte())
        private val NOT_SUPPORTED = byteArrayOf(0x6D, 0x00)
        // Capability container: version 2.0, read-only, NDEF file E104 up to 255 bytes.
        private val CC = byteArrayOf(0x00, 0x0F, 0x20, 0x00, 0x3B, 0x00, 0x34, 0x04, 0x06,
            0xE1.toByte(), 0x04, 0x00, 0xFF.toByte(), 0x00, 0xFF.toByte())
    }
}
