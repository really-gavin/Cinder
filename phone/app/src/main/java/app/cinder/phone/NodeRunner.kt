package app.cinder.phone

import android.content.Context
import android.util.Log
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.io.File
import kotlin.concurrent.thread

/**
 * Runs the real Cinder node (the same node.py as on a computer) inside the
 * app, serving the API, the peer protocol and the website on port 8642.
 */
class NodeRunner(private val ctx: Context, private val prefs: Prefs) {
    @Volatile var started = false; private set
    @Volatile var crashed: String? = null; private set

    fun start() {
        if (started) return
        started = true
        thread(name = "cinder-node", isDaemon = true) {
            try {
                if (!Python.isStarted()) Python.start(AndroidPlatform(ctx))
                val site = unpackSite()
                val data = File(ctx.filesDir, "chain").apply { mkdirs() }
                Python.getInstance().getModule("phone_node").callAttr(
                    "run", data.absolutePath, site.absolutePath, prefs.publicUrl, prefs.peers, PORT)
            } catch (e: Throwable) {
                crashed = e.message ?: e.javaClass.simpleName
                Log.e("Cinder", "node stopped", e)
            }
        }
    }

    /** Copies the website out of the app so the node can serve it. */
    private fun unpackSite(): File {
        val out = File(ctx.filesDir, "website")
        val stamp = File(out, ".version")
        val version = ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime.toString()
        if (stamp.exists() && stamp.readText() == version) return out
        out.deleteRecursively()
        fun copy(asset: String, dest: File) {
            val kids = ctx.assets.list(asset) ?: emptyArray()
            if (kids.isEmpty()) {
                dest.parentFile?.mkdirs()
                ctx.assets.open(asset).use { input -> dest.outputStream().use { input.copyTo(it) } }
            } else kids.forEach { copy("$asset/$it", File(dest, it)) }
        }
        copy("website", out)
        stamp.writeText(version)
        return out
    }

    companion object { const val PORT = 8642 }
}
