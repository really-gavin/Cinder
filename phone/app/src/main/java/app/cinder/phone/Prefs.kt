package app.cinder.phone

import android.content.Context
import android.content.SharedPreferences
import android.os.BaseBundle

/** Everything this phone remembers. Set by the setup QR code, editable on screen. */
class Prefs(context: Context) {
    private val p: SharedPreferences =
        context.applicationContext.getSharedPreferences("cinder", Context.MODE_PRIVATE)

    var mode: String?                      // "miner" or "node"
        get() = p.getString("mode", null)
        set(v) = p.edit().putString("mode", v).apply()
    var cardId: String
        get() = p.getString("card", "") ?: ""
        set(v) = p.edit().putString("card", v).apply()
    /** Node the miner talks to. Empty means: look it up from the website. */
    var node: String
        get() = p.getString("node", "") ?: ""
        set(v) = p.edit().putString("node", v.trimEnd('/')).apply()
    var site: String
        get() = p.getString("site", DEFAULT_SITE) ?: DEFAULT_SITE
        set(v) = p.edit().putString("site", v).apply()
    /** Cloudflare tunnel token for your own domain. Anything that isn't a real token counts as empty. */
    var tunnelToken: String
        get() = (p.getString("tunnel_token", "") ?: "").takeIf { isTunnelToken(it) } ?: ""
        set(v) = p.edit().putString("tunnel_token", v.trim()).apply()
    var publicUrl: String
        get() = p.getString("public_url", "") ?: ""
        set(v) = p.edit().putString("public_url", v.trim().trimEnd('/')).apply()
    var peers: String                       // comma separated
        get() = p.getString("peers", "") ?: ""
        set(v) = p.edit().putString("peers", v).apply()
    /** Signs this node's public address. Comes from your node setup code. */
    var announceKey: String
        get() = p.getString("announce_key", "") ?: ""
        set(v) = p.edit().putString("announce_key", v.trim()).apply()
    /** Off by default: the phone mines on battery too, and pauses itself when the battery gets low. */
    var onlyWhileCharging: Boolean
        get() = p.getBoolean("only_charging_v2", false)
        set(v) = p.edit().putBoolean("only_charging_v2", v).apply()
    /** A node phone also mines (into the card above) at the same time. */
    var alsoMine: Boolean
        get() = p.getBoolean("also_mine", true)
        set(v) = p.edit().putBoolean("also_mine", v).apply()
    /** A mining phone also runs a node at the same time. */
    var alsoNode: Boolean
        get() = p.getBoolean("also_node", false)
        set(v) = p.edit().putBoolean("also_node", v).apply()
    val mines: Boolean get() = mode == "miner" || (mode == "node" && alsoMine)
    val serves: Boolean get() = mode == "node" || (mode == "miner" && alsoNode)
    var running: Boolean
        get() = p.getBoolean("running", true)
        set(v) = p.edit().putBoolean("running", v).apply()

    /** Settings carried in the setup QR code (the "admin extras bundle"). */
    fun saveExtras(extras: BaseBundle?) {
        if (extras == null) return
        fun s(key: String) = extras.getString(key)?.takeIf { it.isNotBlank() }
        s("mode")?.let { if (it == "miner" || it == "node") mode = it }
        s("card")?.let { cardId = CardId.normalize(it) }
        s("node")?.let { node = it }
        s("site")?.let { site = it }
        s("tunnel_token")?.let { tunnelToken = it }
        s("public_url")?.let { publicUrl = it }
        s("peers")?.let { peers = it }
        s("announce_key")?.let { announceKey = it }
        s("also_mine")?.let { alsoMine = it == "1" || it == "true" }
        s("also_node")?.let { alsoNode = it == "1" || it == "true" }
    }

    /** Same settings, from a setup link opened on the phone. */
    fun saveJson(j: org.json.JSONObject) {
        val b = android.os.PersistableBundle()
        j.keys().forEach { k -> b.putString(k, j.optString(k)) }
        saveExtras(b)
    }

    companion object {
        /** Real tokens are base64 JSON with the account ("a"), tunnel ("t") and secret ("s"). */
        fun isTunnelToken(t: String): Boolean = try {
            val raw = t.trim()
            val bytes = runCatching { java.util.Base64.getDecoder().decode(raw) }
                .getOrElse { java.util.Base64.getUrlDecoder().decode(raw) }
            val j = org.json.JSONObject(String(bytes))
            j.has("a") && j.has("t") && j.has("s")
        } catch (_: Exception) { false }

        const val DEFAULT_SITE = "https://really-gavin.github.io/Cinder/"
    }
}

/** Card IDs: 16 characters, Crockford base32, last one is a check digit. */
object CardId {
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    fun normalize(s: String): String = s.uppercase()
        .replace(" ", "").replace("-", "")
        .replace('O', '0').replace('I', '1').replace('L', '1')

    fun isValid(s: String): Boolean {
        if (s.length != 16 || s.any { it !in ALPHABET }) return false
        var total = 0
        for (i in 0 until 15) total += (i + 1) * ALPHABET.indexOf(s[i])
        return ALPHABET[total % 31] == s[15]
    }

    fun format(s: String) = s.chunked(4).joinToString(" ")
}
