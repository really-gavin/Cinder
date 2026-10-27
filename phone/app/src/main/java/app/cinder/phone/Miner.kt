package app.cinder.phone

import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Mines Cinder blocks: fetch a block template from the node, try nonces on
 * every worker thread, hand the block back when one beats the target.
 *
 * Pure Kotlin with no Android calls, so it can be tested on a computer too.
 * [canMine] lets the phone pause it (unplugged, too hot).
 */
class Miner(
    private val nodeUrl: () -> String?,
    private val cardId: () -> String,
    private val canMine: () -> String? = { null },     // null = ok, else the reason it's paused
    private val workers: Int = maxOf(1, Runtime.getRuntime().availableProcessors() - 1),
    private val log: (String) -> Unit = {},
) {
    class Job(val id: Long, val block: JSONObject, val prefix: ByteArray, val target: ByteArray, val height: Int) {
        val solved = AtomicBoolean(false)
    }

    @Volatile var running = false; private set
    @Volatile var status: String = "Stopped"; private set
    @Volatile var job: Job? = null; private set
    @Volatile var lastError: String? = null; private set
    val hashes = AtomicLong()
    val found = AtomicLong()
    @Volatile var height = 0; private set

    private var threads = listOf<Thread>()
    private val lock = Object()
    private var nextJobId = 0L
    private var hashesAtStart = 0L
    private var rateStart = System.nanoTime()
    @Volatile var hashrate = 0.0; private set

    fun start() {
        if (running) return
        running = true
        threads = (0 until workers).map { i -> thread(name = "cinder-mine-$i", isDaemon = true) { work(i) } } +
            thread(name = "cinder-mine-boss", isDaemon = true) { boss() }
    }

    fun stop() {
        running = false
        job = null
        status = "Stopped"
        synchronized(lock) { lock.notifyAll() }
    }

    // ------------------------------------------------------------- coordinator
    private fun boss() {
        while (running) {
            updateRate()
            val pause = canMine()
            if (pause != null) { job = null; status = pause; sleep(3000); continue }
            val node = try { nodeUrl() } catch (e: Exception) { null }
            if (node.isNullOrBlank()) { job = null; status = "Looking for the network…"; sleep(5000); continue }
            val card = cardId()
            if (!CardId.isValid(card)) { job = null; status = "Enter your card ID to start"; sleep(2000); continue }
            try {
                val tpl = Net.getJson("$node/api/mining/template?id=$card")
                val block = tpl.getJSONObject("block")
                val j = Job(++nextJobId, block, tpl.getString("prefix").toByteArray(Charsets.UTF_8),
                    hexToBytes(tpl.getString("target")), block.getInt("height"))
                job = j
                height = j.height - 1
                lastError = null
                status = "Mining block ${j.height}"
                synchronized(lock) { lock.notifyAll() }
                // Work on it until it's solved, the chain moves on, or 10 seconds pass.
                val until = System.currentTimeMillis() + 10_000
                while (running && !j.solved.get() && System.currentTimeMillis() < until) {
                    sleep(1000)
                    updateRate()
                    if (canMine() != null) break
                    try {
                        val tip = Net.getJson("$node/api/status", 5000).getInt("height")
                        if (tip >= j.height) break
                    } catch (_: Exception) { }
                }
            } catch (e: Exception) {
                job = null
                lastError = e.message ?: e.javaClass.simpleName
                status = "Can't reach the node"
                log("template: $lastError")
                sleep(5000)
            }
        }
    }

    private fun submit(j: Job, nonce: Long) {
        if (!j.solved.compareAndSet(false, true)) return
        val node = nodeUrl() ?: return
        try {
            val b = JSONObject(j.block.toString()).put("nonce", nonce)
            val res = Net.postJson("$node/api/mining/submit", b)
            if (res.optString("status") == "new-tip") {
                found.incrementAndGet()
                log("found block ${j.height}")
            } else log("block ${j.height} not used: ${res.optString("status")}")
        } catch (e: Exception) {
            log("submit: ${e.message}")
        }
        synchronized(lock) { lock.notifyAll() }
    }

    // ------------------------------------------------------------- hashing
    private fun work(index: Int) {
        val sha = MessageDigest.getInstance("SHA-256")
        val digits = ByteArray(20)
        while (running) {
            val j = job
            if (j == null || j.solved.get()) {
                synchronized(lock) { try { lock.wait(1000) } catch (_: InterruptedException) { } }
                continue
            }
            // Each worker owns its own slice of nonces.
            val base = MessageDigest.getInstance("SHA-256").apply { update(j.prefix) }
            val cloneable = try { base.clone(); true } catch (_: CloneNotSupportedException) { false }
            var nonce = (index.toLong() shl 40) + (System.nanoTime() and 0xFFFFFFF)
            while (running && job === j && !j.solved.get()) {
                var n = 0
                while (n < 20_000) {
                    val len = writeDigits(nonce, digits)
                    val d1 = if (cloneable) {
                        val m = base.clone() as MessageDigest
                        m.update(digits, 20 - len, len); m.digest()
                    } else {
                        sha.update(j.prefix); sha.update(digits, 20 - len, len); sha.digest()
                    }
                    val d2 = sha.digest(d1)
                    if (d2[0] == 0.toByte() && lessOrEqual(d2, j.target)) {
                        n++
                        submit(j, nonce)
                        break
                    }
                    nonce++; n++
                }
                hashes.addAndGet(n.toLong())
            }
        }
    }

    private fun updateRate() {
        val now = System.nanoTime()
        val secs = (now - rateStart) / 1e9
        if (secs >= 5) {
            val h = hashes.get()
            hashrate = (h - hashesAtStart) / secs
            hashesAtStart = h; rateStart = now
        }
    }

    companion object {
        /** Writes the decimal digits of n right-aligned into buf, returns how many. */
        fun writeDigits(n: Long, buf: ByteArray): Int {
            var v = n; var i = buf.size
            if (v == 0L) { buf[--i] = '0'.code.toByte(); return 1 }
            while (v > 0) { buf[--i] = ('0'.code + (v % 10).toInt()).toByte(); v /= 10 }
            return buf.size - i
        }

        fun lessOrEqual(hash: ByteArray, target: ByteArray): Boolean {
            for (i in 0 until 32) {
                val a = hash[i].toInt() and 0xff
                val b = target[i].toInt() and 0xff
                if (a != b) return a < b
            }
            return true
        }

        fun hexToBytes(h: String) = ByteArray(h.length / 2) { h.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

        private fun sleep(ms: Long) = try { Thread.sleep(ms) } catch (_: InterruptedException) { }
    }
}
