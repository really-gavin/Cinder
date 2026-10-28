package app.cinder.wallet

import java.math.BigInteger
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The Cinder rules a wallet needs, in plain Kotlin (no Android), matching
 * cinder/consensus.py exactly: keys, card IDs, backup words, transactions.
 */
object Core {
    const val CHAIN_ID = "cinder-testnet-3"
    const val ASH = 100_000_000L
    private const val ID_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    const val REGISTER_WORK_BITS = 20
    const val TX_WORK_BITS = 18

    fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)
    fun sha256d(b: ByteArray) = sha256(sha256(b))
    fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    fun unhex(h: String) = ByteArray(h.length / 2) { h.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    // ---------------------------------------------------------------- card IDs
    private fun checkChar(body: String): Char {
        var total = 0
        body.forEachIndexed { i, c -> total += (i + 1) * ID_ALPHABET.indexOf(c) }
        return ID_ALPHABET[total % 31]
    }
    fun normalizeId(s: String) = s.uppercase().replace(" ", "").replace("-", "")
        .replace('O', '0').replace('I', '1').replace('L', '1')
    fun isValidId(s: String) = s.length == 16 && s.all { it in ID_ALPHABET } && checkChar(s.substring(0, 15)) == s[15]
    fun formatId(s: String) = s.chunked(4).joinToString(" ")

    fun idFromPub(pubHex: String): String {
        val h = BigInteger(1, sha256d("cinder-id|".toByteArray() + unhex(pubHex)))
        val body = StringBuilder()
        for (i in 0 until 15) body.append(ID_ALPHABET[h.shiftRight(5 * i).and(BigInteger.valueOf(31)).toInt()])
        return body.toString() + checkChar(body.toString())
    }

    // ---------------------------------------------------------------- backup words
    fun phraseFromEntropy(entropy: ByteArray, words: List<String>): List<String> {
        val bits = BigInteger(1, entropy).shiftLeft(4).or(BigInteger.valueOf(((sha256(entropy)[0].toInt() and 0xff) shr 4).toLong()))
        return (0 until 12).map { words[bits.shiftRight(11 * (11 - it)).and(BigInteger.valueOf(2047)).toInt()] }
    }

    fun entropyFromPhrase(phrase: String, words: List<String>): ByteArray {
        val parts = phrase.lowercase().trim().split(Regex("\\s+"))
        require(parts.size == 12) { "A backup phrase is exactly 12 words." }
        var bits = BigInteger.ZERO
        for (w in parts) {
            val i = words.indexOf(w)
            require(i >= 0) { "\"$w\" isn't one of the backup words. Check the spelling." }
            bits = bits.shiftLeft(11).or(BigInteger.valueOf(i.toLong()))
        }
        val raw = bits.shiftRight(4).toByteArray()
        val entropy = ByteArray(16)
        val src = if (raw.size > 16) raw.copyOfRange(raw.size - 16, raw.size) else raw
        System.arraycopy(src, 0, entropy, 16 - src.size, src.size)
        require(((sha256(entropy)[0].toInt() and 0xff) shr 4) == bits.and(BigInteger.valueOf(15)).toInt()) {
            "Those words don't check out. One of them is probably wrong or out of order."
        }
        return entropy
    }

    class Keys(val priv: BigInteger, val pub: String, val id: String)

    fun keysFromEntropy(entropy: ByteArray): Keys {
        var k = sha256("cinder-key|".toByteArray() + entropy)
        while (true) {
            val v = BigInteger(1, k)
            if (v.signum() > 0 && v < Secp256k1.N) break
            k = sha256(k)
        }
        val priv = BigInteger(1, k)
        val pub = Secp256k1.publicKeyCompressed(priv)
        return Keys(priv, pub, idFromPub(pub))
    }

    // ---------------------------------------------------------------- transactions
    /** Same as Python json.dumps(sort_keys=True, separators=(",", ":"), ensure_ascii=False) for flat objects. */
    fun canonical(fields: Map<String, Any>): String = fields.toSortedMap().entries.joinToString(",", "{", "}") { (k, v) ->
        quote(k) + ":" + when (v) { is String -> quote(v); is Long, is Int -> v.toString(); else -> error("bad field $k") }
    }
    private fun quote(s: String): String {
        val b = StringBuilder("\"")
        for (c in s) when (c) {
            '"' -> b.append("\\\""); '\\' -> b.append("\\\\")
            '\n' -> b.append("\\n"); '\r' -> b.append("\\r"); '\t' -> b.append("\\t")
            '\b' -> b.append("\\b"); '\u000c' -> b.append("\\f")
            else -> if (c < ' ') b.append("\\u%04x".format(c.code)) else b.append(c)
        }
        return b.append('"').toString()
    }

    fun message(fields: Map<String, Any>) = (CHAIN_ID + "|" + canonical(fields - "sig")).toByteArray(Charsets.UTF_8)
    fun txid(fields: Map<String, Any>) = hex(sha256d(message(fields)))

    /**
     * Builds a signed transaction. Free (fee 0): finds the small proof of work
     * the network asks for instead of a fee. [progress] gets rough 0..1.
     */
    fun buildTx(keys: Keys, fields: Map<String, Any>, nonce: Long, progress: (Double) -> Unit = {},
                cancelled: () -> Boolean = { false }): Map<String, Any> {
        val type = fields["type"] as String
        val tx = LinkedHashMap<String, Any>(fields)
        tx["from"] = keys.id; tx["nonce"] = nonce; tx["fee"] = 0L; tx["work"] = 0L
        val bits = if (type == "register") REGISTER_WORK_BITS else TX_WORK_BITS
        // Everything before the work number, so each try only hashes the end.
        val marker = 918273645546372819L
        tx["work"] = marker
        val text = CHAIN_ID + "|" + canonical(tx)
        val at = text.indexOf(marker.toString())
        val pre = text.substring(0, at).toByteArray(Charsets.UTF_8)
        val post = text.substring(at + marker.toString().length).toByteArray(Charsets.UTF_8)
        val base = MessageDigest.getInstance("SHA-256").apply { update(pre) }
        val sha = MessageDigest.getInstance("SHA-256")
        val expected = 1L shl bits
        var w = (System.nanoTime() and 0xFFFFFFFFFFL)
        var tries = 0L
        while (true) {
            val m = base.clone() as MessageDigest
            m.update(w.toString().toByteArray()); m.update(post)
            val d = sha.digest(m.digest())
            if (leadingZeroBits(d) >= bits) break
            w++; tries++
            if (tries and 0x3FFF == 0L) {
                if (cancelled()) throw InterruptedException("cancelled")
                progress(minOf(0.95, tries.toDouble() / (expected * 1.5)))
            }
        }
        tx["work"] = w
        val digest = sha256(message(tx))
        tx["sig"] = Secp256k1.sign(keys.priv, digest)
        return tx
    }

    private fun leadingZeroBits(d: ByteArray): Int {
        var n = 0
        for (b in d) {
            val v = b.toInt() and 0xff
            if (v == 0) { n += 8; continue }
            return n + Integer.numberOfLeadingZeros(v) - 24
        }
        return n
    }

    fun fmt(ash: Long, maxDp: Int = 8): String {
        val neg = ash < 0; val a = Math.abs(ash)
        val whole = "%,d".format(a / ASH)
        val frac = (a % ASH).toString().padStart(8, '0').take(maxDp).trimEnd('0')
        return (if (neg) "-" else "") + whole + (if (frac.isNotEmpty()) ".$frac" else "")
    }

    fun parseAmount(s: String): Long? {
        val t = s.trim()
        if (!Regex("""^\d+(\.\d{1,8})?$""").matches(t)) return null
        val (w, f) = (t.split(".") + "").let { it[0] to it[1] }
        val v = BigInteger(w).multiply(BigInteger.valueOf(ASH)).add(BigInteger((f.padEnd(8, '0')).ifEmpty { "0" }))
        return if (v.signum() > 0 && v.bitLength() < 62) v.toLong() else null
    }
}

/** secp256k1 ECDSA with RFC 6979 deterministic nonces and low-S signatures. */
object Secp256k1 {
    val P = BigInteger("fffffffffffffffffffffffffffffffffffffffffffffffffffffffefffffc2f", 16)
    val N = BigInteger("fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364141", 16)
    private val GX = BigInteger("79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798", 16)
    private val GY = BigInteger("483ada7726a3c4655da4fbfc0e1108a8fd17b448a68554199c47d08ffb10d4b8", 16)
    private val THREE = BigInteger.valueOf(3)

    private class Pt(val x: BigInteger?, val y: BigInteger?) { val inf get() = x == null }
    private val INF = Pt(null, null)
    private val G = Pt(GX, GY)

    private fun add(a: Pt, b: Pt): Pt {
        if (a.inf) return b
        if (b.inf) return a
        val ax = a.x!!; val ay = a.y!!; val bx = b.x!!; val by = b.y!!
        val l = if (ax == bx) {
            if ((ay + by).mod(P).signum() == 0) return INF
            THREE * ax * ax * (BigInteger.valueOf(2) * ay).modInverse(P)
        } else (by - ay) * (bx - ax).modInverse(P)
        val lm = l.mod(P)
        val x = (lm * lm - ax - bx).mod(P)
        return Pt(x, (lm * (ax - x) - ay).mod(P))
    }

    private fun mul(k: BigInteger, p: Pt = G): Pt {
        var r = INF; var q = p
        for (i in 0 until k.bitLength()) {
            if (k.testBit(i)) r = add(r, q)
            q = add(q, q)
        }
        return r
    }

    private fun bytes32(v: BigInteger): ByteArray {
        val b = v.toByteArray()
        return when {
            b.size == 32 -> b
            b.size > 32 -> b.copyOfRange(b.size - 32, b.size)
            else -> ByteArray(32 - b.size) + b
        }
    }

    fun publicKeyCompressed(priv: BigInteger): String {
        val p = mul(priv)
        return (if (p.y!!.testBit(0)) "03" else "02") + Core.hex(bytes32(p.x!!))
    }

    /** Returns the 64-byte r||s signature as hex, over a 32-byte digest. */
    fun sign(priv: BigInteger, digest: ByteArray): String {
        val z = BigInteger(1, digest).mod(N)
        for (k in rfc6979(priv, digest)) {
            val r = mul(k).x!!.mod(N)
            if (r.signum() == 0) continue
            var s = k.modInverse(N) * (z + r * priv) % N
            if (s.signum() == 0) continue
            if (s > N.shiftRight(1)) s = N - s
            return Core.hex(bytes32(r) + bytes32(s))
        }
        error("unreachable")
    }

    private fun rfc6979(priv: BigInteger, digest: ByteArray) = sequence {
        val x = bytes32(priv); val h = bytes32(BigInteger(1, digest).mod(N))
        var v = ByteArray(32) { 1 }; var k = ByteArray(32)
        fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray {
            val m = Mac.getInstance("HmacSHA256"); m.init(SecretKeySpec(key, "HmacSHA256"))
            parts.forEach { m.update(it) }; return m.doFinal()
        }
        k = hmac(k, v, byteArrayOf(0), x, h); v = hmac(k, v)
        k = hmac(k, v, byteArrayOf(1), x, h); v = hmac(k, v)
        while (true) {
            v = hmac(k, v)
            val t = BigInteger(1, v)
            if (t.signum() > 0 && t < N) yield(t)
            k = hmac(k, v, byteArrayOf(0)); v = hmac(k, v)
        }
    }
}
