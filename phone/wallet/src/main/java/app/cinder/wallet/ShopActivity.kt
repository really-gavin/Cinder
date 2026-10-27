package app.cinder.wallet

import android.app.Activity
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.cinder.wallet.Ui.big
import app.cinder.wallet.Ui.box
import app.cinder.wallet.Ui.button
import app.cinder.wallet.Ui.column
import app.cinder.wallet.Ui.dp
import app.cinder.wallet.Ui.field
import app.cinder.wallet.Ui.gap
import app.cinder.wallet.Ui.label
import app.cinder.wallet.Ui.text
import java.security.SecureRandom
import kotlin.concurrent.thread

/**
 * Take payments. Enter the price; the customer either taps their phone on
 * this one (their pay sheet pops up) or scans the code. This screen turns
 * green when the payment arrives on your card.
 */
class ShopActivity : Activity(), NfcAdapter.ReaderCallback {
    private lateinit var wallet: Wallet
    private lateinit var body: LinearLayout
    private val ui = Handler(Looper.getMainLooper())
    private var req: PayRequest? = null
    private var since = 0L
    private var paid = false
    private var statusView: TextView? = null
    private var nfc: NfcAdapter? = null
    private var iphone = false      // true: act as a tag for an iPhone instead of reading Android phones

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        wallet = Wallet(this)
        nfc = NfcAdapter.getDefaultAdapter(this)
        body = column(20)
        setContentView(ScrollView(this).apply { setBackgroundColor(Ui.INK); addView(body); padForSystemBars() })
        askPrice()
    }

    override fun onResume() { super.onResume(); if (req != null && !paid) { listen(); if (iphone) req?.let { NdefTag.offer(this, "${Wallet.SITE}#pay-${it.to}-${it.amount}-${it.ref}") } } }
    override fun onPause() { super.onPause(); nfc?.disableReaderMode(this); NdefTag.stop(this); ui.removeCallbacksAndMessages(null) }

    private fun askPrice() {
        req = null; paid = false; iphone = false
        nfc?.disableReaderMode(this); NdefTag.stop(this)
        body.removeAllViews()
        body.addView(big("Take payments", 34f))
        body.addView(text("Payments go to your card, ${Core.formatId(wallet.id)}.", Ui.DUST).apply { setPadding(0, dp(8), 0, dp(20)) })
        body.addView(label("Price (CDR)"))
        val amt = field("2.5", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        body.addView(amt)
        body.addView(gap(8))
        body.addView(label("Shop name"))
        val name = field("Shown on the customer's phone", value = getSharedPreferences("shop", MODE_PRIVATE).getString("name", "") ?: "")
        body.addView(name)
        val msg = text("", Ui.BAD).also { body.addView(it) }
        body.addView(button("Charge", primary = true) {
            val a = Core.parseAmount(amt.text.toString()) ?: run { msg.text = "Enter the price, like 2.5."; return@button }
            val n = name.text.toString().trim().take(40)
            getSharedPreferences("shop", MODE_PRIVATE).edit().putString("name", n).apply()
            val ref = ByteArray(6).also { SecureRandom().nextBytes(it) }
                .joinToString("") { "0123456789ABCDEFGHJKMNPQRSTVWXYZ"[it.toInt() and 31].toString() }
            req = PayRequest(wallet.id, a, ref, n.ifBlank { null }, null)
            since = System.currentTimeMillis() / 1000 - 60
            showCharge()
        })
    }

    private fun showCharge() {
        val r = req ?: return
        body.removeAllViews()
        body.addView(text(r.name ?: "Amount due", Ui.DUST, 16f))
        body.addView(LinearLayout(this).apply {
            gravity = Gravity.BOTTOM
            addView(big(Core.fmt(r.amount!!), 76f)); addView(text("  CDR", Ui.DUST, 20f))
        })
        body.addView(text("Order ${r.ref}", Ui.DUST))
        body.addView(gap(16))
        statusView = text("", Ui.PAPER, 18f).apply { setPadding(dp(16), dp(14), dp(16), dp(14)) }
        body.addView(statusView)
        body.addView(gap(16))
        val hasNfc = nfc?.isEnabled == true
        body.addView(text(if (hasNfc) "Hold their phone against the back of this one, or let them scan:" else "Let them scan this with Cinder Wallet or their camera:", Ui.DUST))
        body.addView(gap(10))
        val link = "${Wallet.SITE}#pay-${r.to}-${r.amount}-${r.ref}"
        body.addView(ImageView(this).apply {
            setImageBitmap(Ui.qr(link, 720)); adjustViewBounds = true
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        })
        if (hasNfc) {
            val btn = button("Customer has an iPhone") {}
            btn.setOnClickListener {
                iphone = !iphone
                btn.text = if (iphone) "Customer has an Android phone" else "Customer has an iPhone"
                if (iphone) {
                    nfc?.disableReaderMode(this)
                    NdefTag.offer(this, link)
                    setStatus("Hold the top edge of their iPhone against the back of this phone. They tap the notice that appears.", Ui.HIVIS, Ui.INK)
                } else {
                    NdefTag.stop(this); listen()
                    setStatus("Waiting for payment…", Ui.SLAB, Ui.PAPER)
                }
            }
            body.addView(btn)
        }
        body.addView(button("New charge") { askPrice() })
        setStatus("Waiting for payment…", Ui.SLAB, Ui.PAPER)
        listen()
    }

    private fun setStatus(s: String, bg: Int, fg: Int) {
        statusView?.apply { text = s; setTextColor(fg); background = box(bg, bg) }
    }

    private fun listen() {
        if (!iphone) nfc?.enableReaderMode(this, this,
            NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK, null)
        poll()
    }

    /** Called when a phone touches this one. */
    override fun onTagDiscovered(tag: Tag) {
        val r = req ?: return
        if (paid) return
        val dep = IsoDep.get(tag) ?: return
        val err = TapProtocol.sendTo(dep, r)
        ui.post {
            if (err == null) setStatus("Sent to their phone. Waiting for them to pay…", Ui.HIVIS, Ui.INK)
            else setStatus(err, Ui.SLAB, Ui.BAD)
        }
    }

    /** Watches your card for a payment with this order's code. */
    private fun poll() {
        val r = req ?: return
        if (paid) return
        val amount = r.amount ?: return
        thread {
            try {
                val me = wallet.me()
                val tag = "Order ${r.ref}"
                var got = 0L; var coming = 0L; var from: String? = null
                val hist = me.optJSONArray("history")
                for (i in 0 until (hist?.length() ?: 0)) {
                    val e = hist!!.getJSONObject(i)
                    if (e.optString("kind") == "money" && e.optString("why") == "payment" && e.optString("to") == r.to &&
                        e.optString("memo").startsWith(tag)) { got += e.optLong("amount"); from = e.optString("from") }
                }
                val pend = me.optJSONArray("pending")
                for (i in 0 until (pend?.length() ?: 0)) {
                    val t = pend!!.getJSONObject(i)
                    if (t.optString("type") == "pay" && t.optString("to") == r.to && t.optString("memo").startsWith(tag)) {
                        coming += t.optLong("amount"); from = from ?: t.optString("from")
                    }
                }
                ui.post {
                    if (req !== r) return@post
                    when {
                        got >= amount -> { paid = true; nfc?.disableReaderMode(this); setStatus("Paid ✓  from ${Core.formatId(from ?: "")}", Ui.GOOD, Ui.INK) }
                        got + coming >= amount -> setStatus("Payment on its way. Confirming…", Ui.HIVIS, Ui.INK)
                        got + coming > 0 -> setStatus("Only ${Core.fmt(got + coming)} of ${Core.fmt(amount)} CDR so far.", Ui.SLAB, Ui.BAD)
                    }
                }
            } catch (_: Exception) { }
            ui.postDelayed({ poll() }, 3000)
        }
    }
}
