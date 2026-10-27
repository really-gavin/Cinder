package app.cinder.wallet

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.CheckBox
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import app.cinder.wallet.Ui.big
import app.cinder.wallet.Ui.box
import app.cinder.wallet.Ui.button
import app.cinder.wallet.Ui.card
import app.cinder.wallet.Ui.column
import app.cinder.wallet.Ui.dp
import app.cinder.wallet.Ui.field
import app.cinder.wallet.Ui.gap
import app.cinder.wallet.Ui.label
import app.cinder.wallet.Ui.rowOf
import app.cinder.wallet.Ui.text
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import org.json.JSONObject
import kotlin.concurrent.thread

/** Cinder Wallet: your card, balance and activity, plus send, receive, scan and take payments. */
class WalletActivity : Activity() {
    private lateinit var wallet: Wallet
    private lateinit var body: LinearLayout
    private val ui = Handler(Looper.getMainLooper())
    private var balanceView: TextView? = null
    private var stateView: TextView? = null
    private var listView: LinearLayout? = null
    private var onHome = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        wallet = Wallet(this)
        body = column(20).apply { setPadding(dp(20), dp(20), dp(20), dp(36)) }
        setContentView(ScrollView(this).apply { setBackgroundColor(Ui.INK); isFillViewport = true; addView(body); padForSystemBars() })
        if (wallet.hasCard) home() else welcome()
    }

    override fun onResume() { super.onResume(); if (onHome) refresh() }
    override fun onPause() { super.onPause(); ui.removeCallbacksAndMessages(null) }

    private fun clear() { onHome = false; body.removeAllViews() }

    private fun brand() = rowOf(
        ImageView(this).apply { setImageResource(R.drawable.ic_mark); layoutParams = LinearLayout.LayoutParams(dp(34), dp(24)) },
        big("  CINDER WALLET", 22f).apply { letterSpacing = 0.06f })

    // ------------------------------------------------------------- first run
    private fun welcome() {
        clear()
        body.addView(brand()); body.addView(gap(40))
        body.addView(big("Your Cinder card,\nin your pocket.", 40f))
        body.addView(text("Pay at shops by tapping your phone, pay online with one button, and send CDR to anyone's card.", Ui.DUST, 16f).apply { setPadding(0, dp(14), 0, dp(30)) })
        body.addView(button("Make a new card", primary = true) { choosePin(null) })
        body.addView(button("I have backup words") { restore() })
    }

    private fun restore() {
        clear()
        body.addView(big("Bring back your card", 30f)); body.addView(gap(10))
        body.addView(text("Type your 12 backup words in order, with spaces between them.", Ui.DUST))
        val words = field("12 words", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS).apply {
            setSingleLine(false); minLines = 3; gravity = Gravity.TOP
        }
        body.addView(words)
        val msg = text("", Ui.BAD).also { body.addView(it) }
        body.addView(button("Next", primary = true) {
            try { choosePin(Core.entropyFromPhrase(words.text.toString(), wallet.words)) }
            catch (e: Exception) { msg.text = e.message }
        })
        body.addView(button("Back") { welcome() })
    }

    private fun choosePin(restored: ByteArray?) {
        clear()
        body.addView(big("Choose a card PIN", 30f)); body.addView(gap(10))
        body.addView(text("6 digits. You'll use it to see your backup words, and to pay on phones without a fingerprint or screen lock.", Ui.DUST))
        val a = field("PIN", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        val b = field("Same PIN again", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        body.addView(a); body.addView(b)
        val msg = text("", Ui.BAD).also { body.addView(it) }
        body.addView(button(if (restored == null) "Make my card" else "Restore card", primary = true) {
            val pin = a.text.toString()
            if (!Regex("^\\d{6}$").matches(pin)) { msg.text = "The PIN is exactly 6 digits."; return@button }
            if (pin != b.text.toString()) { msg.text = "The two PINs don't match."; return@button }
            val entropy = restored ?: Wallet.newEntropy()
            val spp = wallet.create(entropy, pin)
            reveal(spp, restored == null)
        })
    }

    private fun reveal(spp: String, isNew: Boolean) {
        clear()
        body.addView(big(if (isNew) "This is your card." else "Your card is back.", 32f)); body.addView(gap(16))
        body.addView(card(wallet.id, "Testnet")); body.addView(gap(16))
        body.addView(rowOf(text("SPP  ", Ui.DUST), big(spp, 26f, Ui.HIVIS).apply { letterSpacing = 0.2f }))
        body.addView(text("Your 3-digit payment code for the Cinder website. Forgot it later? It is in the backup words screen, and your 12 words bring it back. Payments in this app use your fingerprint instead.", Ui.DUST, 14f))
        if (isNew) {
            body.addView(gap(20))
            body.addView(label("Your 12 backup words"))
            body.addView(text("If you lose this phone, these bring your card back. Anyone who has them can spend your CDR, so write them on paper and keep them private.", Ui.DUST, 14f))
            body.addView(gap(8))
            val grid = GridLayout(this).apply { columnCount = 3 }
            wallet.phrase().forEachIndexed { i, w ->
                grid.addView(text("${i + 1}  $w", Ui.PAPER, 16f).apply {
                    typeface = android.graphics.Typeface.MONOSPACE; setPadding(dp(8), dp(8), dp(8), dp(8))
                    background = box(); layoutParams = GridLayout.LayoutParams(GridLayout.spec(i / 3), GridLayout.spec(i % 3, 1f)).apply { width = 0; setMargins(dp(3), dp(3), dp(3), dp(3)) }
                })
            }
            body.addView(grid)
        }
        body.addView(gap(14))
        val ok = CheckBox(this).apply { text = if (isNew) "I wrote down my words and SPP" else "I wrote down my SPP"; setTextColor(Ui.PAPER); textSize = 16f }
        body.addView(ok)
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; visibility = View.GONE }
        val msg = text("", Ui.DUST)
        val go = button("Finish", primary = true) {}
        body.addView(go); body.addView(bar); body.addView(msg)
        go.setOnClickListener {
            if (!ok.isChecked) { msg.setTextColor(Ui.BAD); msg.text = "Tick the box once they're written down."; return@setOnClickListener }
            go.isEnabled = false; bar.visibility = View.VISIBLE; msg.setTextColor(Ui.DUST); msg.text = "Putting your card on the network…"
            thread {
                val err = try {
                    val me = wallet.me()
                    if (!me.optBoolean("registered") && !me.optBoolean("registering")) wallet.register { p -> ui.post { bar.progress = (p * 100).toInt() } }
                    null
                } catch (e: Exception) { e.message }
                ui.post {
                    if (err != null) Toast.makeText(this, "Saved. It'll finish joining the network later: $err", Toast.LENGTH_LONG).show()
                    home()
                }
            }
        }
    }

    // ------------------------------------------------------------- home
    private fun home() {
        clear(); onHome = true
        body.addView(brand()); body.addView(gap(20))
        body.addView(card(wallet.id, "Testnet")); body.addView(gap(18))
        body.addView(text("Balance", Ui.DUST))
        balanceView = big("…", 56f).also { body.addView(it) }
        stateView = text("", Ui.DUST, 14f).also { body.addView(it) }
        body.addView(gap(14))
        val grid = GridLayout(this).apply { columnCount = 2 }
        fun tile(t: String, primary: Boolean, f: () -> Unit) = button(t, primary, f).apply {
            layoutParams = GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f)).apply {
                width = 0; height = dp(58); setMargins(dp(4), dp(4), dp(4), dp(4))
            }
        }
        grid.addView(tile("Scan to pay", true) { scan() })
        grid.addView(tile("Send", false) { send() })
        grid.addView(tile("Receive", false) { receive() })
        grid.addView(tile("Take payments", false) { startActivity(Intent(this, ShopActivity::class.java)) })
        body.addView(grid)
        body.addView(text("At a shop, hold your phone near theirs and Cinder Pay opens by itself.", Ui.DUST, 14f).apply { setPadding(dp(4), dp(10), dp(4), 0) })
        body.addView(gap(22))
        body.addView(big("Activity", 26f))
        listView = column().also { body.addView(it) }
        body.addView(gap(26))
        body.addView(button("Show backup words") { showWords() })
        body.addView(button("Network") { network() })
        body.addView(button("Remove card from this phone") { removeCard() })
        refresh()
    }

    private fun refresh() {
        thread {
            val me = try { wallet.me() } catch (e: Exception) { ui.post { stateView?.text = e.message }; null }
            if (me != null) ui.post { paint(me) }
            ui.postDelayed({ if (onHome) refresh() }, 8000)
        }
    }

    private fun paint(me: JSONObject) {
        balanceView?.text = Core.fmt(me.optLong("available"), 4) + " CDR"
        val bits = mutableListOf<String>()
        if (!me.optBoolean("registered")) bits += if (me.optBoolean("registering")) "Your card is joining the network. It works after the next block."
            else "Your card isn't on the network yet. Open Network to retry."
        val diff = me.optLong("balance") - me.optLong("available")
        if (diff > 0) bits += "${Core.fmt(diff)} CDR on its way out."
        stateView?.text = bits.joinToString(" ")
        val list = listView ?: return
        list.removeAllViews()
        val rows = mutableListOf<Triple<String, String, Long>>()
        val pend = me.optJSONArray("pending")
        for (i in 0 until (pend?.length() ?: 0)) {
            val t = pend!!.getJSONObject(i)
            if (t.optString("type") != "pay") continue
            val out = t.optString("from") == wallet.id
            rows += Triple("Waiting", (if (out) "To " + Core.formatId(t.optString("to")) else "From " + Core.formatId(t.optString("from"))) + memo(t), if (out) -t.optLong("amount") else t.optLong("amount"))
        }
        val hist = me.optJSONArray("history")
        for (i in 0 until (hist?.length() ?: 0)) {
            val e = hist!!.getJSONObject(i)
            if (e.optString("kind") != "money") continue
            val inn = e.optString("to") == wallet.id
            val who = if (inn) e.optString("from") else e.optString("to")
            val what = when (e.optString("why")) {
                "payment" -> (if (inn) "From " else "To ") + Core.formatId(who)
                "mined" -> "Mined a block"
                "job pay" -> if (inn) "Paid for a job" else "Paid out for a job"
                "job escrow" -> "Locked for a job"; "job refund" -> "Job refund"
                "loan" -> if (inn) "Loan received" else "Loan sent"
                else -> e.optString("why").replaceFirstChar { it.uppercase() }
            }
            rows += Triple(ago(e.optLong("time")), what + memo(e), if (inn) e.optLong("amount") else -e.optLong("amount"))
            if (rows.size >= 30) break
        }
        if (rows.isEmpty()) list.addView(text("Nothing yet. Everyone starts at zero: get paid, do a job, or mine.", Ui.DUST).apply { setPadding(0, dp(10), 0, 0) })
        rows.forEach { (whenTxt, what, amt) ->
            list.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(12), 0, dp(12))
                addView(text(whenTxt, Ui.DUST, 14f).apply { layoutParams = LinearLayout.LayoutParams(dp(78), WRAP_CONTENT) })
                addView(text(what, Ui.PAPER, 15f).apply { layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f) })
                addView(text((if (amt > 0) "+" else "") + Core.fmt(amt, 4), if (amt > 0) Ui.GOOD else Ui.BAD, 15f).apply { gravity = Gravity.END })
            })
            list.addView(View(this).apply { setBackgroundColor(Ui.MORTAR); layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 1) })
        }
    }

    private fun memo(o: JSONObject) = o.optString("memo").takeIf { it.isNotBlank() }?.let { " “$it”" } ?: ""
    private fun ago(t: Long): String {
        val d = System.currentTimeMillis() / 1000 - t
        return when { t == 0L -> ""; d < 60 -> "just now"; d < 3600 -> "${d / 60}m ago"; d < 86400 -> "${d / 3600}h ago"; else -> "${d / 86400}d ago" }
    }

    // ------------------------------------------------------------- actions
    private fun scan() {
        GmsBarcodeScanning.getClient(this).startScan()
            .addOnSuccessListener { code ->
                val r = PayRequest.parse(code.rawValue ?: "")
                if (r == null) Toast.makeText(this, "That isn't a Cinder payment code.", Toast.LENGTH_LONG).show()
                else PaySheetActivity.open(this, r)
            }
            .addOnFailureListener { Toast.makeText(this, "The scanner isn't available: ${it.message}", Toast.LENGTH_LONG).show() }
    }

    private fun send() {
        clear()
        body.addView(big("Send CDR", 32f)); body.addView(gap(14))
        body.addView(label("To card ID"))
        val to = field("XXXX XXXX XXXX XXXX", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        body.addView(to)
        body.addView(label("Amount (CDR)"))
        val amt = field("0.00", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        body.addView(amt)
        body.addView(label("Note (optional, public)"))
        val note = field("For the pizza").apply { filters = arrayOf(android.text.InputFilter.LengthFilter(80)) }
        body.addView(note)
        val msg = text("", Ui.BAD).also { body.addView(it) }
        body.addView(button("Review", primary = true) {
            val id = Core.normalizeId(to.text.toString())
            val a = Core.parseAmount(amt.text.toString())
            when {
                !Core.isValidId(id) -> msg.text = "That card ID isn't right. It's 16 letters and numbers; one looks mistyped."
                id == wallet.id -> msg.text = "That's your own card."
                a == null -> msg.text = "Enter an amount, like 2.5."
                else -> { PaySheetActivity.open(this, PayRequest(id, a, null, null, note.text.toString().trim())); home() }
            }
        })
        body.addView(button("Back") { home() })
    }

    private fun receive() {
        clear()
        body.addView(big("Receive", 32f)); body.addView(gap(8))
        body.addView(text("Anyone with Cinder Wallet can scan this to pay you.", Ui.DUST)); body.addView(gap(14))
        body.addView(ImageView(this).apply {
            setImageBitmap(Ui.qr("cinderpay://pay?to=${wallet.id}", 720)); adjustViewBounds = true
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        })
        body.addView(gap(14))
        body.addView(big(Core.formatId(wallet.id), 30f).apply { gravity = Gravity.CENTER; letterSpacing = 0.06f })
        body.addView(button("Copy card ID", primary = true) {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Cinder card ID", Core.formatId(wallet.id)))
            Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
        })
        body.addView(button("Share") {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, "My Cinder card ID: ${Core.formatId(wallet.id)}"), "Share card ID"))
        })
        body.addView(button("Back") { home() })
    }

    private fun showWords() {
        Ui.askPin(this, wallet, "Enter your card PIN") {
            AlertDialog.Builder(this).setTitle("Backup words")
                .setMessage(wallet.phrase().mapIndexed { i, w -> "${i + 1}. $w" }.joinToString("\n") + "\n\nSPP: " + wallet.spp())
                .setPositiveButton("Done", null).show()
        }
    }

    private fun network() {
        val input = field("Automatic", InputType.TYPE_TEXT_VARIATION_URI, wallet.node)
        AlertDialog.Builder(this).setTitle("Network")
            .setMessage("Cinder Wallet finds the network by itself. To use a particular node, enter its address.")
            .setView(LinearLayout(this).apply { setPadding(dp(20), 0, dp(20), 0); addView(input) })
            .setPositiveButton("Save") { _, _ ->
                wallet.node = input.text.toString(); wallet.forgetNode()
                thread {
                    try {
                        val me = wallet.me()
                        if (!me.optBoolean("registered") && !me.optBoolean("registering")) wallet.register()
                    } catch (_: Exception) { }
                    ui.post { refresh() }
                }
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun removeCard() {
        AlertDialog.Builder(this).setTitle("Remove this card?")
            .setMessage("Without your 12 backup words, the CDR on it is gone for good.")
            .setPositiveButton("Remove") { _, _ -> Ui.askPin(this, wallet, "Enter your card PIN") { wallet.remove(); welcome() } }
            .setNegativeButton("Cancel", null).show()
    }
}
