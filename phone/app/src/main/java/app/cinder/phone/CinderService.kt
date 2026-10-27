package app.cinder.phone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log

/** Keeps the miner or node running in the background, with a notification. */
class CinderService : Service() {
    lateinit var prefs: Prefs
    var miner: Miner? = null; private set
    var node: NodeRunner? = null; private set
    var tunnel: Tunnel? = null; private set
    private var wake: PowerManager.WakeLock? = null
    @Volatile private var siteNode: Pair<String, Long>? = null
    @Volatile private var hot = false

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        if (wake == null) {
            wake = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "cinder:run").apply { acquire() }
        }
        when (prefs.mode) {
            "miner" -> {
                val m = miner ?: Miner(
                    nodeUrl = ::minerNode, cardId = { prefs.cardId }, canMine = ::pauseReason,
                    log = { Log.i("Cinder", "miner: $it"); if (it.startsWith("template:")) forgetNode() }).also { miner = it }
                m.start()
            }
            "node" -> {
                (node ?: NodeRunner(this, prefs).also { node = it }).start()
                (tunnel ?: Tunnel(this) { prefs.tunnelToken }.also { tunnel = it }).start()
                startAnnouncing()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        miner?.stop(); tunnel?.stop()
        wake?.let { if (it.isHeld) it.release() }
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ------------------------------------------------------------- node: tell everyone where it is
    /** This node's public address: your own domain if set, else the quick tunnel's. */
    fun publicAddress(): String? =
        prefs.publicUrl.takeIf { it.isNotBlank() && prefs.tunnelToken.isNotBlank() } ?: tunnel?.quickUrl

    @Volatile var announced: Pair<String, Long>? = null; private set
    @Volatile var announceError: String? = null; private set
    private var announcing = false

    private fun startAnnouncing() {
        if (announcing) return
        announcing = true
        kotlin.concurrent.thread(name = "cinder-announce", isDaemon = true) {
            while (instance === this) {
                val url = publicAddress()
                val key = prefs.announceKey
                val last = announced
                val due = last == null || last.first != url || System.currentTimeMillis() - last.second > 20 * 60_000
                if (key.isBlank()) announceError = "This phone doesn't have the network key"
                else if (url != null && tunnel?.connected == true && due) {
                    try { Discovery.announce(key, url); announced = url to System.currentTimeMillis(); announceError = null }
                    catch (e: Exception) { announceError = e.message ?: "couldn't post the address" }
                }
                try { Thread.sleep(15_000) } catch (_: InterruptedException) { }
            }
        }
    }

    // ------------------------------------------------------------- miner rules
    /**
     * The node to mine on: one typed on this phone, else the address the node
     * phone announced, else the one the website's config.js lists.
     */
    private fun minerNode(): String? {
        prefs.node.takeIf { it.isNotBlank() }?.let { return it }
        siteNode?.let { (url, at) -> if (System.currentTimeMillis() - at < 5 * 60_000) return url }
        val url = runCatching { Discovery.find() }.getOrNull()
            ?: runCatching { Net.nodeFromSite(prefs.site) }.getOrNull()
            ?: runCatching { Net.nodeFromSite(prefs.site.trimEnd('/') + "/website") }.getOrNull()
            ?: return null
        siteNode = url to System.currentTimeMillis()
        return url
    }

    /** Forget the cached node so the miner looks again (after errors). */
    fun forgetNode() { siteNode = null }

    /** Phones mine only when it's safe: plugged in and not hot. */
    fun pauseReason(): String? {
        val b = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val plugged = b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        val tempC = b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0
        // Stop at 40 °C, start again under 37 °C.
        hot = if (hot) tempC >= 37.0 else tempC >= 40.0
        if (prefs.onlyWhileCharging && !plugged) return "Paused: plug in to mine"
        if (hot) return "Paused: cooling down (${"%.0f".format(tempC)}°C)"
        if (Build.VERSION.SDK_INT >= 29) {
            val status = (getSystemService(POWER_SERVICE) as PowerManager).currentThermalStatus
            if (status >= PowerManager.THERMAL_STATUS_MODERATE) return "Paused: phone is warm"
        }
        return null
    }

    fun batteryInfo(): Pair<Boolean, Double> {
        val b = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return false to 0.0
        return (b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0) to
            b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0
    }

    // ------------------------------------------------------------- notification
    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Cinder", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE)
        val text = if (prefs.mode == "node") "Running a Cinder node" else "Cinder miner is on"
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat).setContentTitle("Cinder").setContentText(text)
            .setContentIntent(open).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(1, n)
    }

    companion object {
        private const val CHANNEL = "cinder"
        @Volatile var instance: CinderService? = null; private set

        fun start(ctx: Context) {
            val p = Prefs(ctx)
            if (p.mode == null || !p.running) return
            ctx.startForegroundService(Intent(ctx, CinderService::class.java))
        }

        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, CinderService::class.java)) }
    }
}
