package app.cinder.phone

import android.content.Context
import android.util.Log
import java.io.File
import kotlin.concurrent.thread

/**
 * Makes the node on this phone reachable from the internet, even on home
 * Wi-Fi or cell data, using Cloudflare's tunnel program.
 *
 * With no setup it opens a free "quick tunnel" with a random
 * https://….trycloudflare.com address (no account, no domain). If you later
 * set up your own domain on Cloudflare, paste its tunnel token instead.
 */
class Tunnel(private val ctx: Context, private val token: () -> String) {
    @Volatile var state = "Starting…"; private set
    @Volatile var lastLine = ""; private set
    /** The address the quick tunnel was given, once it's up. */
    @Volatile var quickUrl: String? = null; private set
    @Volatile private var running = false
    @Volatile private var proc: Process? = null

    val connected get() = state == "Connected"

    fun start() {
        if (running) return
        running = true
        thread(name = "cinder-tunnel", isDaemon = true) {
            var backoff = 5_000L
            while (running) {
                val t = token()
                state = "Connecting…"
                quickUrl = null
                try {
                    val exe = File(ctx.applicationInfo.nativeLibraryDir, "libcloudflared.so")
                    val args = mutableListOf(exe.absolutePath, "tunnel", "--no-autoupdate", "--protocol", "http2")
                    if (t.isBlank()) args += listOf("--url", "http://localhost:${NodeRunner.PORT}")
                    else args += listOf("run", "--token", t)
                    val pb = ProcessBuilder(args)
                    pb.environment()["HOME"] = ctx.filesDir.absolutePath
                    pb.environment()["TMPDIR"] = ctx.cacheDir.absolutePath
                    pb.redirectErrorStream(true)
                    val p = pb.start()
                    proc = p
                    val started = System.currentTimeMillis()
                    val urlRe = Regex("""https://[a-z0-9-]+\.trycloudflare\.com""")
                    p.inputStream.bufferedReader().forEachLine { line ->
                        urlRe.find(line)?.let { quickUrl = it.value }
                        lastLine = line.substringAfter(" INF ").substringAfter(" ERR ").trim().take(200)
                        if ("Registered tunnel connection" in line) { state = "Connected"; backoff = 5_000 }
                        if ("Unauthorized" in line || "Invalid tunnel secret" in line) state = "Token not accepted"
                        Log.i("Cinder", "tunnel: $line")
                    }
                    p.waitFor()
                    if (System.currentTimeMillis() - started > 60_000) backoff = 5_000
                } catch (e: Exception) {
                    lastLine = e.message ?: "couldn't start"
                    Log.e("Cinder", "tunnel", e)
                }
                proc = null
                quickUrl = null
                if (!running) break
                if (state != "Token not accepted") state = "Reconnecting…"
                try { Thread.sleep(backoff) } catch (_: InterruptedException) { }
                backoff = minOf(backoff * 2, 120_000)
            }
            state = "Stopped"
        }
    }

    /** Called after the token changes so the new one is used right away. */
    fun restart() { proc?.destroy() }

    fun stop() { running = false; proc?.destroy() }
}
