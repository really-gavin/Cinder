package app.cinder.wallet

import android.app.Activity
import android.app.AlertDialog
import android.app.KeyguardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/** Cinder's look, shared by every screen. */
object Ui {
    const val INK = 0xFF1F1E1B.toInt(); const val SLAB = 0xFF2E2D28.toInt(); const val MORTAR = 0xFF4A4740.toInt()
    const val PAPER = 0xFFEBE8DF.toInt(); const val DUST = 0xFFA29D90.toInt(); const val HIVIS = 0xFFF3D317.toInt()
    const val GOOD = 0xFF7FC58A.toInt(); const val BAD = 0xFFF08A80.toInt()
    val DISPLAY: Typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)

    fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    fun Context.gap(h: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(h)) }
    fun Context.column(pad: Int = 0) = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(pad), dp(pad), dp(pad), dp(pad)) }
    fun Context.rowOf(vararg v: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        v.forEach { addView(it) }
    }

    fun Context.big(s: String, size: Float, color: Int = PAPER) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color); typeface = DISPLAY; includeFontPadding = false
    }
    fun Context.text(s: String, color: Int = PAPER, size: Float = 15f) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color); setLineSpacing(0f, 1.2f)
    }
    fun Context.label(s: String) = TextView(this).apply { text = s; textSize = 15f; setTextColor(PAPER); typeface = Typeface.DEFAULT_BOLD; setPadding(0, 0, 0, dp(4)) }

    fun Context.box(color: Int = SLAB, stroke: Int = MORTAR, radius: Int = 2) = GradientDrawable().apply {
        setColor(color); setStroke(dp(1), stroke); cornerRadius = dp(radius).toFloat()
    }

    fun Context.field(hint: String, type: Int = InputType.TYPE_CLASS_TEXT, value: String = "") = EditText(this).apply {
        setText(value); this.hint = hint; inputType = type; textSize = 18f
        setTextColor(PAPER); setHintTextColor(MORTAR); setSingleLine()
        setPadding(dp(14), dp(12), dp(14), dp(12)); background = box()
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6); bottomMargin = dp(6) }
    }

    fun Context.button(s: String, primary: Boolean = false, onClick: () -> Unit) = Button(this).apply {
        text = s; isAllCaps = false; textSize = 17f; typeface = Typeface.DEFAULT_BOLD
        setTextColor(if (primary) INK else PAPER); stateListAnimator = null
        background = GradientDrawable().apply {
            setColor(if (primary) HIVIS else INK); setStroke(dp(2), if (primary) HIVIS else PAPER); cornerRadius = dp(2).toFloat()
        }
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(56)).apply { topMargin = dp(8) }
        setOnClickListener { onClick() }
    }

    /** The card, drawn like the website's: dark slab, number in fours, hazard stripe. */
    fun Context.card(id: String, foot: String): View {
        val f = FrameLayout(this)
        f.background = GradientDrawable().apply { setColor(INK); setStroke(dp(1), MORTAR); cornerRadius = dp(14).toFloat() }
        f.clipToOutline = true
        val col = column(20)
        col.addView(rowOf(
            big("CINDER", 20f).apply { letterSpacing = 0.08f; layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f) },
            ImageView(this).apply { setImageResource(R.drawable.ic_mark); layoutParams = LinearLayout.LayoutParams(dp(30), dp(21)) }))
        col.addView(gap(34))
        col.addView(big(Core.formatId(id), 27f).apply { letterSpacing = 0.06f; isSingleLine = true })
        col.addView(gap(22))
        col.addView(rowOf(text("Card ID", DUST, 12f).apply { layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f) }, text(foot, DUST, 12f)))
        col.addView(gap(10))
        f.addView(col)
        f.addView(View(this).apply {
            background = StripeDrawable(dp(14))
            layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, dp(9), Gravity.BOTTOM)
        })
        return f
    }

    fun qr(text: String, size: Int): Bitmap {
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 2))
        val px = IntArray(size * size) { if (m[it % size, it / size]) INK else Color.WHITE }
        return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
    }

    /**
     * Confirms it's you before money moves: fingerprint, face or screen lock,
     * like Apple Pay. Phones with none of those use the card PIN.
     */
    fun confirm(a: Activity, wallet: Wallet, title: String, subtitle: String, ok: () -> Unit) {
        val km = a.getSystemService(KeyguardManager::class.java)
        val cb = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) = ok()
        }
        when {
            Build.VERSION.SDK_INT >= 30 && a.getSystemService(BiometricManager::class.java)
                .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL) == BiometricManager.BIOMETRIC_SUCCESS ->
                BiometricPrompt.Builder(a).setTitle(title).setSubtitle(subtitle)
                    .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                    .build().authenticate(CancellationSignal(), a.mainExecutor, cb)
            Build.VERSION.SDK_INT == 29 && km.isDeviceSecure ->
                @Suppress("DEPRECATION")
                BiometricPrompt.Builder(a).setTitle(title).setSubtitle(subtitle).setDeviceCredentialAllowed(true)
                    .build().authenticate(CancellationSignal(), a.mainExecutor, cb)
            else -> askPin(a, wallet, title, ok)
        }
    }

    fun askPin(a: Activity, wallet: Wallet, title: String, ok: () -> Unit) {
        val input = EditText(a).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "6-digit card PIN"; textSize = 22f; gravity = Gravity.CENTER
        }
        val d = AlertDialog.Builder(a).setTitle(title).setView(FrameLayout(a).apply {
            setPadding(a.dp(24), a.dp(8), a.dp(24), 0); addView(input)
        }).setPositiveButton("Confirm", null).setNegativeButton("Cancel", null).create()
        d.setOnShowListener {
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (wallet.checkPin(input.text.toString())) { d.dismiss(); ok() }
                else { input.text.clear(); input.error = "Wrong PIN" }
            }
        }
        d.show()
    }
}

/** Diagonal hi-vis hazard stripe. */
class StripeDrawable(private val w: Int) : android.graphics.drawable.Drawable() {
    private val paint = android.graphics.Paint().apply { color = Ui.HIVIS; isAntiAlias = true }
    override fun draw(c: android.graphics.Canvas) {
        c.drawColor(Ui.INK)
        val h = bounds.height().toFloat()
        var x = -h
        while (x < bounds.width() + h) {
            val path = android.graphics.Path().apply {
                moveTo(x, h); lineTo(x + w, h); lineTo(x + w + h, 0f); lineTo(x + h, 0f); close()
            }
            c.drawPath(path, paint); x += 2 * w
        }
    }
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = android.graphics.PixelFormat.OPAQUE
}

/** Android 15 draws apps under the status and navigation bars; keep content clear of them. */
fun View.padForSystemBars(top: Boolean = true) {
    val base = intArrayOf(paddingLeft, paddingTop, paddingRight, paddingBottom)
    setOnApplyWindowInsetsListener { v, insets ->
        val (l, t, r, b) = if (Build.VERSION.SDK_INT >= 30) {
            val i = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
            listOf(i.left, i.top, i.right, i.bottom)
        } else @Suppress("DEPRECATION") listOf(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
        v.setPadding(base[0] + l, base[1] + (if (top) t else 0), base[2] + r, base[3] + b)
        insets
    }
    requestApplyInsets()
}
