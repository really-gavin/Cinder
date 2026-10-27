package app.cinder.wallet

import org.json.JSONObject
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import java.util.Base64

/**
 * How everyone finds the network without a domain or any setup.
 *
 * The node phone opens a free Cloudflare quick tunnel (a random
 * https://….trycloudflare.com address) and posts that address, signed with
 * the network key, to a public message board (ntfy.sh). Miners and the
 * website read the newest correctly signed post. Anyone can post there, but
 * only the node phone can sign, so fake addresses are ignored.
 */
object Discovery {
    const val RELAY = "https://ntfy.sh"
    const val TOPIC = "cinder-d24ebbd9527ffd25b672"
    const val PUBLIC_KEY = "04f832239da18bcb77fff34722da7e41c4cc0c2d96240a1882336e34fc33d9b90e5e882ab62eb65bcc02e21a96ccf88f9928553f37ebb279f24b8aecfb1aed1b64"
    private const val CHAIN = "cinder-testnet-2"
    private const val MAX_AGE_S = 24 * 3600L

    fun text(url: String, t: Long) = "cinder-node|$CHAIN|$url|$t"

    private val params: ECParameterSpec by lazy {
        AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }
            .getParameterSpec(ECParameterSpec::class.java)
    }

    fun sign(privateKeyHex: String, url: String, t: Long): String {
        val key = KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(BigInteger(privateKeyHex, 16), params))
        val s = Signature.getInstance("SHA256withECDSA").apply { initSign(key); update(text(url, t).toByteArray()) }
        return Base64.getEncoder().encodeToString(s.sign())
    }

    fun verify(url: String, t: Long, sigB64: String, publicKeyHex: String = PUBLIC_KEY): Boolean = try {
        val raw = Core.unhex(publicKeyHex)
        val point = ECPoint(BigInteger(1, raw.copyOfRange(1, 33)), BigInteger(1, raw.copyOfRange(33, 65)))
        val key = KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, params))
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(key); update(text(url, t).toByteArray()); verify(Base64.getDecoder().decode(sigB64))
        }
    } catch (_: Exception) { false }

    /** Posts this node's address. */
    fun announce(privateKeyHex: String, url: String, relay: String = RELAY) {
        val t = System.currentTimeMillis() / 1000
        val msg = JSONObject().put("url", url).put("t", t).put("sig", sign(privateKeyHex, url, t))
        Net.postJson("$relay/$TOPIC", msg)
    }

    /** The newest correctly signed node address from the last day, if any. */
    fun find(relay: String = RELAY): String? {
        val body = Net.getText("$relay/$TOPIC/json?poll=1&since=24h", 8000)
        return newestValid(body, System.currentTimeMillis() / 1000)
    }

    fun newestValid(ndjson: String, now: Long): String? {
        var best: Pair<Long, String>? = null
        for (line in ndjson.lineSequence()) {
            try {
                val ev = JSONObject(line)
                if (ev.optString("event") != "message") continue
                val m = JSONObject(ev.getString("message"))
                val url = m.getString("url"); val t = m.getLong("t")
                if (!url.startsWith("https://") || t > now + 600 || t < now - MAX_AGE_S) continue
                if (!verify(url, t, m.getString("sig"))) continue
                if (best == null || t > best.first) best = t to url.trimEnd('/')
            } catch (_: Exception) { }
        }
        return best?.second
    }
}
