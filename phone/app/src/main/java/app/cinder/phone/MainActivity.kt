package app.cinder.phone

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipboardManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import java.net.Inet4Address
import java.util.Locale
import kotlin.concurrent.thread

/** The phone's one screen: what it's doing, and the few settings it has. */
class MainActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var body: LinearLayout
    private val ui = Handler(Looper.getMainLooper())
    private val rows = mutableMapOf<String, TextView>()
    private var statusView: TextView? = null
    private var nodeStatus: org.json.JSONObject? = null
    private var nodeError: String? = null

    private val INK = 0xFF1F1E1B.toInt(); private val SLAB = 0xFF2E2D28.toInt()
    private val MORTAR = 0xFF4A4740.toInt(); private val PAPER = 0xFFEBE8DF.toInt()
    private val DUST = 0xFFA29D90.toInt(); private val HIVIS = 0xFFF3D317.toInt()
    private val GOOD = 0xFF7FC58A.toInt(); private val BAD = 0xFFF08A80.toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        val scroll = ScrollView(this).apply { setBackgroundColor(INK); isFillViewport = true }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(20), dp(20), dp(28)) }
        scroll.addView(root)
        root.addView(header())
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(body)
        setContentView(scroll)
        scroll.padForSystemBars()
        render()
        handleSetupLink(intent)
    }

    override fun onNewIntent(intent: android.content.Intent) { super.onNewIntent(intent); handleSetupLink(intent) }

    /** cinderphone://setup?d=… opened from the Cinder phone page. */
    private fun handleSetupLink(i: android.content.Intent?) {
        val uri = i?.data ?: return
        if (uri.scheme != "cinderphone") return
        setIntent(android.content.Intent())
        val json = try {
            org.json.JSONObject(String(java.util.Base64.getUrlDecoder().decode(uri.getQueryParameter("d") ?: "")))
        } catch (e: Exception) { null }
        val mode = json?.optString("mode")
        if (json == null || (mode != "miner" && mode != "node")) {
            AlertDialog.Builder(this).setMessage("That setup link is damaged. Open it again from the Cinder phone page.")
                .setPositiveButton("OK", null).show(); return
        }
        val what = if (mode == "node") "a Cinder node" else "a Cinder miner"
        AlertDialog.Builder(this).setTitle("Set up this phone as $what?")
            .setMessage(if (mode == "node") "It will keep a copy of the network and share it with everyone. It works best plugged in and on Wi-Fi."
                        else "It will mine while plugged in and pause when it gets warm.")
            .setPositiveButton("Set up") { _, _ ->
                CinderService.stop(this)
                prefs.saveJson(json); prefs.running = true
                CinderService.start(this); askForBackground(); render()
            }
            .setNegativeButton("Cancel", null).show()
    }

    /** Phones that just installed the app (not fully set up) need two OKs to run all day. */
    private fun askForBackground() {
        if (Setup.isManaged(this)) return
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    private fun backgroundAllowed() =
        getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    override fun onResume() { super.onResume(); CinderService.start(this); render(); tick() }
    override fun onPause() { super.onPause(); ui.removeCallbacksAndMessages(null) }

    // ------------------------------------------------------------- screens
    private fun render() {
        body.removeAllViews(); rows.clear(); statusView = null
        when (prefs.mode) {
            "miner" -> minerScreen()
            "node" -> nodeScreen()
            else -> chooseScreen()
        }
        body.addView(gap(28))
        body.addView(footer())
    }

    private fun chooseScreen() {
        body.addView(big("Set up this phone", 34f))
        body.addView(text("Pick what this phone does for the Cinder network. You can change it later.", DUST).also { it.setPadding(0, dp(8), 0, dp(20)) })
        fun pick(m: String, mine: Boolean, node: Boolean) {
            prefs.mode = m; prefs.alsoMine = mine; prefs.alsoNode = node; prefs.running = true
            CinderService.start(this); askForBackground(); render()
        }
        body.addView(button("Mine Cinder", primary = true) { pick("miner", true, false) })
        body.addView(text("Earn CDR. Your card gets the rewards.", DUST).also { it.setPadding(0, dp(4), 0, dp(14)) })
        body.addView(button("Run a node") { pick("node", false, true) })
        body.addView(text("Keep a copy of the network and help everyone connect.", DUST).also { it.setPadding(0, dp(4), 0, dp(14)) })
        body.addView(button("Both") { pick("node", true, true) })
        body.addView(text("Run a node and mine at the same time.", DUST).also { it.setPadding(0, dp(4), 0, 0) })
    }

    private fun minerScreen() {
        statusView = big("…", 30f).also { body.addView(it) }
        body.addView(gap(16))
        body.addView(panel(
            *listOfNotNull(row("Speed"), row("Blocks found"), row("Mining to"), row("Network"), row("Battery"),
                if (prefs.alsoNode) row("Node") else null).toTypedArray()))
        body.addView(gap(22))
        body.addView(label("Your card ID"))
        body.addView(text("Rewards go to this card. It's on the front of your card in the Cinder wallet.", DUST))
        val card = edit(CardId.format(prefs.cardId), "XXXX XXXX XXXX XXXX", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS)
        body.addView(card)
        val msg = text("", BAD); body.addView(msg)
        body.addView(button("Save card ID") {
            val id = CardId.normalize(card.text.toString())
            if (!CardId.isValid(id)) { msg.text = "That card ID isn't right. It's 16 letters and numbers; one looks mistyped." }
            else { prefs.cardId = id; card.setText(CardId.format(id)); msg.text = ""; toastRow("Saved") }
        })
        body.addView(gap(18))
        body.addView(chargingSwitch())
        body.addView(gap(14))
        body.addView(Switch(this).apply {
            text = "Also run a node on this phone"; setTextColor(PAPER); textSize = 16f
            isChecked = prefs.alsoNode
            setOnCheckedChangeListener { _, on ->
                prefs.alsoNode = on
                if (on) CinderService.start(this@MainActivity)
                else ui.postDelayed({ android.os.Process.killProcess(android.os.Process.myPid()) }, 300)   // the node lives in this process
                render()
            }
        })
        body.addView(text("Mine and keep a copy of the network at the same time. Uses more battery and data.", DUST))
        if (prefs.alsoNode && prefs.announceKey.isBlank())
            body.addView(text("This phone doesn't have your network key, so it works as a node on this Wi-Fi but can't be found from outside.", DUST))
        body.addView(gap(18))
        body.addView(startStop())
    }

    private fun chargingSwitch(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(Switch(context).apply {
            text = "Only mine while charging"; setTextColor(PAPER); textSize = 16f
            isChecked = prefs.onlyWhileCharging
            setOnCheckedChangeListener { _, on -> prefs.onlyWhileCharging = on }
        })
        addView(text("Off: it mines on battery too, and pauses at 20% battery or 40°C.", DUST))
    }


    private fun nodeScreen() {
        statusView = big("…", 30f).also { body.addView(it) }
        body.addView(gap(16))
        body.addView(panel(*listOfNotNull(row("Block"), row("Peers"), row("On this Wi-Fi"), row("Public address"), row("Everyone finds it"),
            if (prefs.alsoMine) row("Mining") else null).toTypedArray()))
        if (prefs.announceKey.isBlank()) {
            body.addView(gap(14))
            body.addView(text("This phone doesn't have your network key yet, so miners and the website can't find it. Open your private node setup link on this phone to add it.", BAD))
        }
        body.addView(gap(18))
        body.addView(Switch(this).apply {
            text = "Also mine on this phone"; setTextColor(PAPER); textSize = 16f
            isChecked = prefs.alsoMine
            setOnCheckedChangeListener { _, on ->
                prefs.alsoMine = on
                if (on) CinderService.start(this@MainActivity) else CinderService.instance?.stopMiner()
                render()
            }
        })
        if (prefs.alsoMine) {
            body.addView(text("Rewards go to this card:", DUST).also { it.setPadding(0, dp(6), 0, 0) })
            val card = edit(CardId.format(prefs.cardId), "XXXX XXXX XXXX XXXX", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS)
            body.addView(card)
            val cmsg = text("", BAD); body.addView(cmsg)
            body.addView(button("Save card ID") {
                val id = CardId.normalize(card.text.toString())
                if (!CardId.isValid(id)) cmsg.text = "That card ID isn't right. It's 16 letters and numbers; one looks mistyped."
                else { prefs.cardId = id; card.setText(CardId.format(id)); cmsg.text = ""; toastRow("Saved") }
            })
            body.addView(gap(8))
            body.addView(chargingSwitch())
        }
        body.addView(gap(18))
        body.addView(text("The public address is made for you, free, and can change when the phone restarts. The phone tells everyone the new one automatically.", DUST))
        body.addView(gap(18))
        body.addView(startStop())
        body.addView(gap(14))

        // Optional: a fixed address on your own domain.
        val adv = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = if (prefs.tunnelToken.isBlank()) View.GONE else View.VISIBLE }
        body.addView(button(if (prefs.tunnelToken.isBlank()) "Use my own domain (optional)" else "My own domain") {
            adv.visibility = if (adv.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        })
        body.addView(adv)
        adv.addView(gap(12))
        adv.addView(label("Public address"))
        adv.addView(text("Like https://node.yourdomain.com", DUST))
        val pub = edit(prefs.publicUrl, "https://node.yourdomain.com", InputType.TYPE_TEXT_VARIATION_URI)
        adv.addView(pub)
        adv.addView(label("Cloudflare tunnel token"))
        adv.addView(text("Leave empty to use the free automatic address.", DUST))
        val tok = edit(if (prefs.tunnelToken.isNotBlank()) "••••••••" else "", "Paste the token", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        adv.addView(tok)
        adv.addView(button("Paste from clipboard") {
            val clip = getSystemService(ClipboardManager::class.java).primaryClip
            val t = clip?.getItemAt(0)?.coerceToText(this)?.toString()?.trim().orEmpty()
            val m = Regex("eyJ[A-Za-z0-9_\\-=]{40,}").find(t)       // works with the whole install command too
            tok.setText(m?.value ?: t)
        })
        val msg = text("", DUST); adv.addView(msg)
        adv.addView(button("Save", primary = true) {
            val url = pub.text.toString().trim()
            if (url.isNotEmpty() && !url.startsWith("https://")) { msg.setTextColor(BAD); msg.text = "The public address starts with https://"; return@button }
            prefs.publicUrl = url
            val t = tok.text.toString().trim()
            if (t != "••••••••") {
                if (t.isNotEmpty() && !Prefs.isTunnelToken(t)) {
                    msg.setTextColor(BAD)
                    msg.text = "That isn't a Cloudflare tunnel token. It's a long code starting with eyJ. Leave the box empty to use the free automatic address."
                    return@button
                }
                prefs.tunnelToken = t; CinderService.instance?.tunnel?.restart()
            }
            msg.setTextColor(GOOD); msg.text = if (t.isEmpty()) "Saved. Using the free automatic address." else "Saved."
        })
    }

    private fun startStop(): View = button(if (prefs.running) "Stop" else "Start") {
        prefs.running = !prefs.running
        if (prefs.running) CinderService.start(this) else {
            CinderService.stop(this)
            if (prefs.serves) {
                // The node runs inside this app's process; stopping fully means a restart.
                ui.postDelayed({ android.os.Process.killProcess(android.os.Process.myPid()) }, 300)
            }
        }
        render()
    }

    private fun footer(): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (Setup.isManaged(this)) {
            box.addView(text("Cinder manages this phone. Releasing it stops Cinder and gives the phone back. A factory reset afterwards leaves it like new.", DUST))
            box.addView(button("Release this phone") {
                AlertDialog.Builder(this).setTitle("Release this phone?")
                    .setMessage("Cinder stops and no longer manages this phone. To use it as Cinder again you'll need to reset it and scan the setup code.")
                    .setPositiveButton("Release") { _, _ -> Setup.release(this); prefs.mode = null; render() }
                    .setNegativeButton("Cancel", null).show()
            })
        } else if (prefs.mode != null) {
            if (!backgroundAllowed()) {
                box.addView(text("Android may stop Cinder to save battery. Allow it to run in the background so it keeps going.", DUST))
                box.addView(button("Let Cinder run in the background", primary = true) {
                    startActivity(android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        android.net.Uri.parse("package:$packageName")))
                })
                box.addView(gap(14))
            }
            box.addView(button("Change what this phone does") { CinderService.stop(this); prefs.mode = null; render() })
        }
        return box
    }

    // ------------------------------------------------------------- live values
    private fun tick() {
        update()
        ui.postDelayed(::tick, 1000)
    }

    private fun update() {
        val svc = CinderService.instance
        if (prefs.mode == "node" && prefs.mines) {
            val m = svc?.miner
            set("Mining", when {
                !prefs.running -> "Stopped"
                !CardId.isValid(prefs.cardId) -> "Enter your card ID below"
                m == null -> "Starting…"
                else -> "${m.status}  ·  ${rate(m.hashrate)}  ·  ${m.found.get()} found"
            })
        }
        if (prefs.mode == "miner" && prefs.alsoNode) {
            if (prefs.running) thread {
                try { nodeStatus = Net.getJson("http://127.0.0.1:${NodeRunner.PORT}/api/status", 2000); nodeError = null }
                catch (e: Exception) { nodeStatus = null; nodeError = svc?.node?.crashed }
            }
            val s = nodeStatus
            val a = svc?.announced
            set("Node", when {
                !prefs.running -> "Stopped"
                s != null -> "Running, block ${s.optInt("height")}" + (if (a != null) ", found by everyone" else "")
                nodeError != null -> "Stopped: $nodeError"
                else -> "Starting…"
            })
        }
        when (prefs.mode) {
            "miner" -> {
                val m = svc?.miner
                setStatus(when {
                    !prefs.running -> "Stopped" to DUST
                    m == null -> "Starting…" to DUST
                    m.status.startsWith("Mining") -> m.status to HIVIS
                    m.status.startsWith("Paused") -> m.status to PAPER
                    else -> m.status to BAD
                })
                set("Speed", m?.let { rate(it.hashrate) } ?: "—")
                set("Blocks found", (m?.found?.get() ?: 0).toString())
                set("Mining to", if (CardId.isValid(prefs.cardId)) CardId.format(prefs.cardId) else "No card yet")
                set("Network", m?.lastError ?: prefs.node.ifBlank { "From ${prefs.site.removePrefix("https://").trimEnd('/')}" })
                svc?.batteryInfo()?.let { (plugged, t) -> set("Battery", "${if (plugged) "Charging" else "Not charging"}, ${"%.0f".format(t)}°C") }
            }
            "node" -> {
                if (prefs.running) thread {
                    try { nodeStatus = Net.getJson("http://127.0.0.1:${NodeRunner.PORT}/api/status", 2000); nodeError = null }
                    catch (e: Exception) { nodeStatus = null; nodeError = svc?.node?.crashed }
                }
                val s = nodeStatus
                setStatus(when {
                    !prefs.running -> "Stopped" to DUST
                    s != null -> "Node running" to GOOD
                    nodeError != null -> "Node stopped: $nodeError" to BAD
                    else -> "Starting the node…" to DUST
                })
                set("Block", s?.optInt("height")?.let { String.format(Locale.US, "%,d", it) } ?: "—")
                set("Peers", s?.optJSONArray("peers")?.length()?.toString() ?: "—")
                set("On this Wi-Fi", localIp()?.let { "http://$it:${NodeRunner.PORT}" } ?: "Not on Wi-Fi")
                val t = svc?.tunnel
                set("Public address", svc?.publicAddress() ?: when {
                    !prefs.running -> "—"
                    t == null || !t.connected -> "Opening… ${t?.lastLine.orEmpty()}".trim()
                    else -> "Waiting for an address"
                })
                val a = svc?.announced
                set("Everyone finds it", when {
                    prefs.announceKey.isBlank() -> "No network key"
                    svc?.announceError != null -> svc.announceError!!
                    a != null -> "Yes, posted ${ago(a.second)}"
                    else -> "Not yet"
                })
            }
        }
    }

    private fun ago(ms: Long): String {
        val m = (System.currentTimeMillis() - ms) / 60_000
        return if (m < 1) "just now" else "$m min ago"
    }

    private fun localIp(): String? {
        val cm = getSystemService(ConnectivityManager::class.java)
        val lp = cm.getLinkProperties(cm.activeNetwork) ?: return null
        return lp.linkAddresses.map { it.address }.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress
    }

    private fun rate(r: Double): String {
        var v = r; var i = 0; val u = listOf("H/s", "kH/s", "MH/s", "GH/s")
        while (v >= 1000 && i < u.size - 1) { v /= 1000; i++ }
        return String.format(Locale.US, if (i == 0) "%.0f %s" else "%.2f %s", v, u[i])
    }

    private fun setStatus(s: Pair<String, Int>) { statusView?.apply { text = s.first; setTextColor(s.second) } }
    private fun set(k: String, v: String) { rows[k]?.text = v }
    private fun toastRow(s: String) = android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show()

    // ------------------------------------------------------------- little view kit
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun gap(h: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(h)) }

    private fun header(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(0, 0, 0, dp(26))
        addView(ImageView(context).apply { setImageResource(R.drawable.ic_mark); layoutParams = LinearLayout.LayoutParams(dp(34), dp(24)) })
        addView(TextView(context).apply {
            text = "CINDER"; setTextColor(PAPER); textSize = 24f; letterSpacing = 0.08f
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD); setPadding(dp(10), 0, 0, 0)
        })
        addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) })
        val pf = Prefs(context)
        val mode = when { pf.mode == null -> null; pf.mines && pf.serves -> "Node + Miner"; pf.serves -> "Node"; else -> "Miner" }
        if (mode != null) addView(TextView(context).apply {
            text = mode; setTextColor(INK); textSize = 13f; typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(10), dp(4), dp(10), dp(4)); background = GradientDrawable().apply { setColor(HIVIS); cornerRadius = dp(2).toFloat() }
        })
    }

    private fun big(s: String, size: Float) = TextView(this).apply {
        text = s; textSize = size; setTextColor(PAPER)
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    }
    private fun text(s: String, color: Int = PAPER) = TextView(this).apply { text = s; textSize = 15f; setTextColor(color); setLineSpacing(0f, 1.2f) }
    private fun label(s: String) = TextView(this).apply { text = s; textSize = 16f; setTextColor(PAPER); typeface = Typeface.DEFAULT_BOLD; setPadding(0, 0, 0, dp(4)) }

    private fun row(k: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(10), 0, dp(10))
        addView(TextView(context).apply { text = k; textSize = 15f; setTextColor(DUST); layoutParams = LinearLayout.LayoutParams(dp(120), WRAP_CONTENT) })
        val v = TextView(context).apply { text = "—"; textSize = 15f; setTextColor(PAPER); typeface = Typeface.MONOSPACE; layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f) }
        rows[k] = v; addView(v)
    }

    private fun panel(vararg kids: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(6), dp(16), dp(6))
        background = GradientDrawable().apply { setColor(SLAB); setStroke(dp(1), MORTAR); cornerRadius = dp(2).toFloat() }
        kids.forEachIndexed { i, v ->
            if (i > 0) addView(View(context).apply { setBackgroundColor(MORTAR); layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 1) })
            addView(v)
        }
    }

    private fun edit(value: String, hint: String, type: Int) = EditText(this).apply {
        setText(value); this.hint = hint; inputType = type; textSize = 17f
        setTextColor(PAPER); setHintTextColor(MORTAR); setSingleLine()
        setPadding(dp(12), dp(12), dp(12), dp(12))
        background = GradientDrawable().apply { setColor(SLAB); setStroke(dp(1), MORTAR); cornerRadius = dp(2).toFloat() }
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8); bottomMargin = dp(6) }
    }

    private fun button(s: String, primary: Boolean = false, onClick: () -> Unit) = Button(this).apply {
        text = s; isAllCaps = false; textSize = 16f; typeface = Typeface.DEFAULT_BOLD
        setTextColor(if (primary) INK else PAPER)
        background = GradientDrawable().apply {
            setColor(if (primary) HIVIS else INK); setStroke(dp(2), if (primary) HIVIS else PAPER); cornerRadius = dp(2).toFloat()
        }
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(52)).apply { topMargin = dp(6) }
        setOnClickListener { onClick() }
    }
}

/** Android 15 draws apps under the status and navigation bars; keep content clear of them. */
fun View.padForSystemBars() {
    val base = intArrayOf(paddingLeft, paddingTop, paddingRight, paddingBottom)
    setOnApplyWindowInsetsListener { v, insets ->
        val (l, t, r, b) = if (android.os.Build.VERSION.SDK_INT >= 30) {
            val i = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
            listOf(i.left, i.top, i.right, i.bottom)
        } else @Suppress("DEPRECATION") listOf(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
        v.setPadding(base[0] + l, base[1] + t, base[2] + r, base[3] + b)
        insets
    }
    requestApplyInsets()
}
