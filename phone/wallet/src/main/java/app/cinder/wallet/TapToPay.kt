package app.cinder.wallet

import android.nfc.cardemulation.HostApduService
import android.nfc.tech.IsoDep
import android.os.Bundle

/**
 * Tap to pay, phone to phone.
 *
 * The shop's phone (Take payments) acts as the reader. When the customer's
 * phone touches it, the shop sends the payment request over NFC and the
 * customer's Cinder Pay sheet pops up by itself. Paying still happens over
 * the internet after the customer confirms; NFC only carries the request.
 */
object TapProtocol {
    /** "F0" + "CINDER": a private application ID, the same one tap_to_pay.xml claims. */
    val AID = Core.unhex("F043494E444552")
    val SELECT = byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00, AID.size.toByte()) + AID + byteArrayOf(0x00)
    val OK = byteArrayOf(0x90.toByte(), 0x00)
    val UNKNOWN = byteArrayOf(0x6D, 0x00)
    const val INS_PAY: Byte = 0x10

    fun payCommand(req: PayRequest): ByteArray {
        val data = req.toQuery().toByteArray()
        require(data.size <= 255) { "payment request too long" }
        return byteArrayOf(0x80.toByte(), INS_PAY, 0x00, 0x00, data.size.toByte()) + data
    }

    /** Shop side: send the request to a phone that was just tapped. Returns null if it worked. */
    fun sendTo(dep: IsoDep, req: PayRequest): String? = try {
        dep.connect(); dep.timeout = 3000
        val sel = dep.transceive(SELECT)
        if (!sel.endsWith(OK)) "That phone doesn't have Cinder Wallet."
        else if (!dep.transceive(payCommand(req)).endsWith(OK)) "That phone didn't take the request. Try again."
        else null
    } catch (e: Exception) {
        "Hold the phones together a little longer."
    } finally { runCatching { dep.close() } }

    private fun ByteArray.endsWith(t: ByteArray) = size >= t.size && copyOfRange(size - t.size, size).contentEquals(t)
}

/** Customer side: answers the shop's phone and opens the pay sheet. */
class TapToPayService : HostApduService() {
    override fun processCommandApdu(apdu: ByteArray, extras: Bundle?): ByteArray {
        if (apdu.size >= 4 && apdu[1] == 0xA4.toByte()) return TapProtocol.OK          // SELECT
        if (apdu.size > 5 && apdu[0] == 0x80.toByte() && apdu[1] == TapProtocol.INS_PAY) {
            val len = apdu[4].toInt() and 0xff
            val req = PayRequest.parse(String(apdu, 5, minOf(len, apdu.size - 5)))
                ?: return TapProtocol.UNKNOWN
            PaySheetActivity.open(this, req)
            return TapProtocol.OK
        }
        return TapProtocol.UNKNOWN
    }

    override fun onDeactivated(reason: Int) {}
}
