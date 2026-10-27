package app.cinder.wallet

import android.animation.ObjectAnimator
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import app.cinder.wallet.Ui.big
import app.cinder.wallet.Ui.box
import app.cinder.wallet.Ui.button
import app.cinder.wallet.Ui.column
import app.cinder.wallet.Ui.dp
import app.cinder.wallet.Ui.field
import app.cinder.wallet.Ui.gap
import app.cinder.wallet.Ui.rowOf
import app.cinder.wallet.Ui.text
import kotlin.concurrent.thread

/**
 * Cinder Pay: the sheet that slides up when something asks you to pay.
 * Shows who and how much; you tap Pay and confirm with your fingerprint.
 */
class PaySheetActivity : Activity() {
    private lateinit var wallet: Wallet
    private lateinit var sheet: LinearLayout
    private var req: PayRequest? = null
    private val ui = Handler(Looper.getMainLooper())
    @Volatile private var available: Long? = null
    private var balanceView: TextView? = null
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        wallet = Wallet(this)
        // Show over the lock screen when a tap opened it, like a payment terminal would.
        if (android.os.Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        window.setLayout(MATCH_PARENT, MATCH_PARENT)
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)

        val root = FrameLayout(this).apply { setOnClickListener { if (!busy) finish() } }
        sheet = column(22).apply {
            background = GradientDrawable().apply {
                setColor(Ui.INK); cornerRadii = floatArrayOf(dp(18).toFloat(), dp(18).toFloat(), dp(18).toFloat(), dp(18).toFloat(), 0f, 0f, 0f, 0f)
            }
            isClickable = true   // taps inside don't close it
            layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM)
            setPadding(dp(22), dp(14), dp(22), dp(28))
        }
        root.addView(sheet)
        setContentView(root)
        sheet.padForSystemBars(top = false)
        load(intent)
        sheet.translationY = 600f
        ObjectAnimator.ofFloat(sheet, "translationY", 600f, 0f).setDuration(260).start()
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); if (!busy) load(intent) }

    private fun load(i: Intent?) {
        req = i?.getStringExtra(EXTRA_REQUEST)?.let { PayRequest.parse(it) } ?: i?.dataString?.let { PayRequest.parse(it) }
        render()
    }

    private fun header(): View = rowOf(
        ImageView(this).apply { setImageResource(R.drawable.ic_mark); layoutParams = LinearLayout.LayoutParams(dp(28), dp(20)) },
        big("  Cinder Pay", 22f).apply { layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f) },
        TextView(this).apply { text = "Cancel"; textSize = 16f; setTextColor(Ui.DUST); setPadding(dp(12), dp(8), 0, dp(8)); setOnClickListener { if (!busy) finish() } })

    private fun handle() = View(this).apply {
        background = box(Ui.MORTAR, Ui.MORTAR, 3)
        layoutParams = LinearLayout.LayoutParams(dp(40), dp(5)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(14) }
    }

    private fun render() {
        sheet.removeAllViews()
        sheet.addView(handle())
        sheet.addView(header())
        sheet.addView(gap(18))
        val r = req
        when {
            r == null -> {
                sheet.addView(text("This payment link is damaged. Ask the shop to show it again."))
                sheet.addView(button("Close") { finish() })
            }
            !wallet.hasCard -> {
                sheet.addView(text("Set up Cinder Wallet first. It takes a minute, then come back to this payment."))
                sheet.addView(button("Set up Cinder Wallet", primary = true) {
                    startActivity(Intent(this, WalletActivity::class.java)); finish()
                })
            }
            r.to == wallet.id -> {
                sheet.addView(text("That's your own card. Show this code to the person paying you instead."))
                sheet.addView(button("Close") { finish() })
            }
            else -> payView(r)
        }
    }

    private fun payView(r: PayRequest) {
        sheet.addView(text(r.name ?: "Pay", Ui.DUST, 16f))
        sheet.addView(text(Core.formatId(r.to), Ui.PAPER, 16f).apply { typeface = android.graphics.Typeface.MONOSPACE })
        sheet.addView(gap(16))
        var amountField: android.widget.EditText? = null
        if (r.amount != null) {
            sheet.addView(rowOf(big(Core.fmt(r.amount), 64f), text("  CDR", Ui.DUST, 18f)))
        } else {
            sheet.addView(Ui.run { label("Amount (CDR)") })
            amountField = field("0.00", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
            sheet.addView(amountField)
        }
        r.ref?.let { sheet.addView(text("Order $it", Ui.DUST)) }
        if (r.ref == null && !r.memo.isNullOrBlank()) sheet.addView(text("“${r.memo}”", Ui.DUST))
        sheet.addView(gap(10))
        balanceView = text("Checking your balance…", Ui.DUST, 14f).also { sheet.addView(it) }
        val msg = text("", Ui.BAD).also { sheet.addView(it) }
        val payBtn = button(if (r.amount != null) "Pay ${Core.fmt(r.amount)} CDR" else "Pay", primary = true) {}
        sheet.addView(payBtn)
        sheet.addView(text("Payments can't be reversed.", Ui.DUST, 13f).apply { gravity = Gravity.CENTER; setPadding(0, dp(10), 0, 0) })

        thread {
            try {
                val me = wallet.me()
                available = me.optLong("available")
                ui.post { balanceView?.text = "Balance ${Core.fmt(available!!, 4)} CDR" + (r.amount?.let { a -> if (a <= available!!) ", ${Core.fmt(available!! - a, 4)} after" else "" } ?: "") }
            } catch (e: Exception) { ui.post { balanceView?.text = e.message } }
        }

        payBtn.setOnClickListener {
            val amount = r.amount ?: Core.parseAmount(amountField?.text.toString())
            if (amount == null) { msg.text = "Enter an amount, like 2.5."; return@setOnClickListener }
            available?.let { if (amount > it) { msg.text = "You have ${Core.fmt(it)} CDR, which isn't enough."; return@setOnClickListener } }
            msg.text = ""
            Ui.confirm(this, wallet, "Pay ${Core.fmt(amount)} CDR", r.name ?: Core.formatId(r.to)) { send(r, amount) }
        }
    }

    private fun send(r: PayRequest, amount: Long) {
        busy = true
        sheet.removeAllViews()
        sheet.addView(handle()); sheet.addView(header()); sheet.addView(gap(30))
        sheet.addView(ProgressBar(this).apply { layoutParams = LinearLayout.LayoutParams(dp(56), dp(56)).apply { gravity = Gravity.CENTER_HORIZONTAL } })
        sheet.addView(text("Paying…", Ui.PAPER, 18f).apply { gravity = Gravity.CENTER; setPadding(0, dp(14), 0, dp(40)) })
        thread {
            try {
                val txid = wallet.pay(r.to, amount, r.note)
                ui.post { done(r, amount, txid) }
            } catch (e: Exception) {
                ui.post {
                    busy = false; render()
                    sheet.addView(text(e.message ?: "The payment didn't go through.", Ui.BAD).apply { setPadding(0, dp(8), 0, 0) }, 4)
                }
            }
        }
    }

    private fun done(r: PayRequest, amount: Long, txid: String) {
        sheet.removeAllViews()
        sheet.addView(handle()); sheet.addView(header()); sheet.addView(gap(26))
        val check = big("✓", 72f, Ui.INK).apply {
            gravity = Gravity.CENTER; background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Ui.GOOD) }
            layoutParams = LinearLayout.LayoutParams(dp(110), dp(110)).apply { gravity = Gravity.CENTER_HORIZONTAL }
        }
        sheet.addView(check)
        sheet.addView(big("Paid", 40f).apply { gravity = Gravity.CENTER; setPadding(0, dp(16), 0, 0) })
        sheet.addView(text("${Core.fmt(amount)} CDR to ${r.name ?: Core.formatId(r.to)}", Ui.DUST, 16f).apply { gravity = Gravity.CENTER })
        sheet.addView(text("It lands with the next block, usually within a minute.", Ui.DUST, 14f).apply { gravity = Gravity.CENTER; setPadding(0, dp(6), 0, dp(24)) })
        check.scaleX = 0.4f; check.scaleY = 0.4f
        check.animate().scaleX(1f).scaleY(1f).setDuration(320).start()
        setResult(RESULT_OK, Intent().putExtra("txid", txid))
        ui.postDelayed({ busy = false; finish() }, 2600)
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(0, android.R.anim.fade_out)
    }

    companion object {
        const val EXTRA_REQUEST = "request"

        fun open(ctx: Context, req: PayRequest) {
            ctx.startActivity(Intent(ctx, PaySheetActivity::class.java)
                .putExtra(EXTRA_REQUEST, "cinderpay://pay?" + req.toQuery())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

private typealias GradientDrawable = android.graphics.drawable.GradientDrawable
