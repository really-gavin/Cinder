package app.cinder.wallet

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Tiny JSON-over-HTTP helper for talking to a Cinder node. */
object Net {
    class NodeError(msg: String) : IOException(msg)

    fun getText(url: String, timeoutMs: Int = 10_000): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs
        try {
            val code = c.responseCode
            val body = (if (code in 200..299) c.inputStream else c.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw NodeError(errorText(body, code))
            return body
        } finally { c.disconnect() }
    }

    fun getJson(url: String, timeoutMs: Int = 10_000) = JSONObject(getText(url, timeoutMs))

    fun postJson(url: String, body: JSONObject, timeoutMs: Int = 15_000): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs
        c.requestMethod = "POST"; c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        try {
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw NodeError(errorText(text, code))
            return JSONObject(text)
        } finally { c.disconnect() }
    }

    private fun errorText(body: String, code: Int) =
        try { JSONObject(body).optString("error", "node said $code") } catch (_: Exception) { "node said $code" }

    /**
     * The website's config.js lists the network's public node. Miners read it
     * so a published setup QR keeps working when the node moves.
     */
    fun nodeFromSite(site: String): String? {
        val js = getText(site.trimEnd('/') + "/config.js")
        return js.lineSequence()
            .map { it.trim() }
            .filterNot { it.startsWith("//") }
            .mapNotNull { Regex("""['"](https?://[^'"]+)['"]""").find(it)?.groupValues?.get(1) }
            .firstOrNull()?.trimEnd('/')
    }
}
