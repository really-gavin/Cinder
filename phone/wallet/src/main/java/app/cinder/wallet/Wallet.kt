package app.cinder.wallet

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec

/**
 * The card on this phone. The backup words and SPP are locked with a key
 * that lives in the phone's secure hardware, so the files alone are useless.
 * Payments are confirmed with your fingerprint, face or screen lock (or the
 * card PIN on phones without one).
 */
class Wallet(context: Context) {
    private val ctx = context.applicationContext
    private val p = ctx.getSharedPreferences("wallet", Context.MODE_PRIVATE)

    val hasCard get() = p.contains("entropy")
    val id: String get() = p.getString("id", "") ?: ""
    var node: String
        get() = p.getString("node", "") ?: ""
        set(v) = p.edit().putString("node", v.trim().trimEnd('/')).apply()

    val words: List<String> by lazy { ctx.assets.open("words.txt").bufferedReader().readLines().filter { it.isNotBlank() } }

    // ------------------------------------------------------------- making a card
    fun create(entropy: ByteArray, pin: String): String {
        val spp = newSpp()
        val keys = Core.keysFromEntropy(entropy)
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        p.edit()
            .putString("entropy", seal(entropy))
            .putString("spp", seal(spp.toByteArray()))
            .putString("id", keys.id)
            .putString("pin_salt", b64(salt))
            .putString("pin_hash", b64(pinHash(pin, salt)))
            .apply()
        return spp
    }

    fun remove() { p.edit().clear().apply() }

    fun keys() = Core.keysFromEntropy(open(p.getString("entropy", null)!!))
    fun phrase() = Core.phraseFromEntropy(open(p.getString("entropy", null)!!), words)
    fun spp() = String(open(p.getString("spp", null)!!))
    fun checkPin(pin: String): Boolean {
        val salt = unb64(p.getString("pin_salt", null) ?: return false)
        return b64(pinHash(pin, salt)) == p.getString("pin_hash", null)
    }

    // ------------------------------------------------------------- the network
    @Volatile private var cached: Pair<String, Long>? = null

    /** A node to talk to: one you set, else the one the node phone announced, else the website's list. */
    fun nodeUrl(): String {
        node.takeIf { it.isNotBlank() }?.let { return it }
        cached?.let { (u, at) -> if (System.currentTimeMillis() - at < 5 * 60_000) return u }
        val u = runCatching { Discovery.find() }.getOrNull()
            ?: runCatching { Net.nodeFromSite(SITE) }.getOrNull()
            ?: runCatching { Net.nodeFromSite(SITE + "website") }.getOrNull()
            ?: throw Net.NodeError("Can't find the Cinder network right now. Check your internet connection.")
        cached = u to System.currentTimeMillis()
        return u
    }

    fun forgetNode() { cached = null }

    fun me(): JSONObject = call { Net.getJson("$it/api/id/$id") }

    fun lookup(cardId: String): JSONObject = call { Net.getJson("$it/api/id/$cardId") }

    private fun <T> call(f: (String) -> T): T = try { f(nodeUrl()) } catch (e: Exception) { forgetNode(); throw e }

    /** Puts a brand-new card on the network (free; a few seconds of phone work). */
    fun register(progress: (Double) -> Unit = {}) {
        val k = keys()
        val tx = Core.buildTx(k, mapOf("type" to "register", "pub" to k.pub), 0, progress)
        call { Net.postJson("$it/api/tx", JSONObject(tx)) }
    }

    /** Signs and sends a payment. Returns the receipt number. */
    fun pay(to: String, amount: Long, memo: String, progress: (Double) -> Unit = {}): String {
        val me = me()
        if (!me.optBoolean("registered")) throw Net.NodeError(
            if (me.optBoolean("registering")) "Your card is still being set up. Try again in a minute." else "Your card isn't on the network yet.")
        if (me.optLong("available") < amount) throw Net.NodeError("You have ${Core.fmt(me.optLong("available"))} CDR, which isn't enough.")
        val tx = Core.buildTx(keys(), mapOf("type" to "pay", "to" to to, "amount" to amount, "memo" to memo),
            me.getLong("next_nonce"), progress)
        val res = call { Net.postJson("$it/api/tx", JSONObject(tx)) }
        return res.optString("txid")
    }

    // ------------------------------------------------------------- locking
    private fun hwKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build())
        return g.generateKey()
    }

    private fun seal(plain: ByteArray): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, hwKey()) }
        return b64(c.iv) + ":" + b64(c.doFinal(plain))
    }

    private fun open(sealed: String): ByteArray {
        val (iv, ct) = sealed.split(":")
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, hwKey(), GCMParameterSpec(128, unb64(iv))) }
        return c.doFinal(unb64(ct))
    }

    private fun pinHash(pin: String, salt: ByteArray): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(pin.toCharArray(), salt, 120_000, 256)).encoded

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun unb64(s: String) = Base64.decode(s, Base64.NO_WRAP)

    companion object {
        private const val ALIAS = "cinder-wallet"
        const val SITE = "https://really-gavin.github.io/Cinder/"
        private const val ID_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

        fun newEntropy() = ByteArray(16).also { SecureRandom().nextBytes(it) }
        private fun newSpp(): String {
            val r = ByteArray(6).also { SecureRandom().nextBytes(it) }
            return r.joinToString("") { ID_ALPHABET[it.toInt() and 31].toString() }
        }
    }
}

/** A payment someone is asking for: from a link, a QR code or a tap. */
data class PayRequest(val to: String, val amount: Long?, val ref: String?, val name: String?, val memo: String?) {
    /** The note that goes with the payment; checkout screens look for "Order <ref>". */
    val note get() = ref?.let { "Order $it" } ?: memo.orEmpty()

    fun toQuery(): String = buildString {
        append("to=").append(to)
        amount?.let { append("&amount=").append(it) }
        ref?.let { append("&ref=").append(it) }
        name?.let { append("&name=").append(android.net.Uri.encode(it)) }
        memo?.let { append("&memo=").append(android.net.Uri.encode(it)) }
    }

    companion object {
        /** Understands cinderpay:// links, website checkout links (#pay-…) and bare card IDs. */
        fun parse(text: String): PayRequest? {
            val t = text.trim()
            Regex("""#pay-([0-9A-Z]{16})-(\d{1,16})-([0-9A-Z]{6})""").find(t)?.let { m ->
                return of(m.groupValues[1], m.groupValues[2].toLong(), m.groupValues[3], null, null)
            }
            val q = when {
                t.startsWith("cinderpay://pay") -> t.substringAfter("?", "")
                t.startsWith("to=") -> t
                else -> null
            }
            if (q != null) {
                val kv = q.split("&").mapNotNull { it.split("=", limit = 2).takeIf { p -> p.size == 2 } }
                    .associate { (k, v) -> k to android.net.Uri.decode(v) }
                return of(kv["to"] ?: return null, kv["amount"]?.toLongOrNull(), kv["ref"], kv["name"], kv["memo"])
            }
            val id = Core.normalizeId(t)
            return if (Core.isValidId(id)) of(id, null, null, null, null) else null
        }

        private fun of(to: String, amount: Long?, ref: String?, name: String?, memo: String?): PayRequest? {
            val id = Core.normalizeId(to)
            if (!Core.isValidId(id)) return null
            if (amount != null && amount <= 0) return null
            val r = ref?.takeIf { Regex("^[0-9A-Z]{6}$").matches(it) }
            return PayRequest(id, amount, r, name?.take(40)?.takeIf { it.isNotBlank() }, memo?.take(80))
        }
    }
}
