package app.cinder.phone

import android.content.Context
import android.util.Log
import java.io.File
import kotlin.concurrent.thread

/**
 * Keeps a Cloudflare Tunnel open so the node on this phone can be reached
 * from the internet at your node address (for example node.yourdomain.com),
 * even on home Wi-Fi or cell data. Uses the tunnel token from Cloudflare.
 */
class Tunnel(private val ctx: Context, private val token: () -> String) {
    @Volatile var state = "Not set up"; private set
    @Volatile var lastLine = ""; private set
    @Volatile private var running = false
    @Volatile private var proc: Process? = null

    fun start() {
        if (running) return
        running = true
        thread(name = "cinder-tunnel", isDaemon = true) {
            var backoff = 5_000L
            while (running) {
                val t = token()
                if (t.isBlank()) { state = "Not set up"; Thread.sleep(3000); continue }
                state = "Connecting…"
                try {
                    val exe = File(ctx.applicationInfo.nativeLibraryDir, "libcloudflared.so")
                    val pb = ProcessBuilder(exe.absolutePath, "tunnel", "--no-autoupdate",
                        "--protocol", "http2", "run", "--token", t)
                    pb.environment()["HOME"] = ctx.filesDir.absolutePath
                    pb.environment()["TMPDIR"] = ctx.cacheDir.absolutePath
                    pb.redirectErrorStream(true)
                    val p = pb.start()
                    proc = p
                    val started = System.currentTimeMillis()
                    p.inputStream.bufferedReader().forEachLine { line ->
                        lastLine = line.substringAfter(" INF ").substringAfter(" ERR ").take(200)
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
                if (!running) break
                if (state != "Token not accepted") state = "Reconnecting…"
                Thread.sleep(backoff)
                backoff = minOf(backoff * 2, 120_000)
            }
            state = "Stopped"
        }
    }

    /** Called after the token changes so the new one is used right away. */
    fun restart() { proc?.destroy() }

    fun stop() { running = false; proc?.destroy() }
}
