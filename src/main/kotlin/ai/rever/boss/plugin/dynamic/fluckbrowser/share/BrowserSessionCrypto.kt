package ai.rever.boss.plugin.dynamic.fluckbrowser.share

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * End-to-end encryption for co-browse tab sharing. Ported from BossTerm's
 * `SessionCrypto` (the scheme is payload-agnostic), with browser-specific HKDF
 * info labels (`bossbrowser-{c2s,s2c,kc}-v1`) so it can't be confused with a
 * terminal session.
 *
 * A relay (Cloudflare/Tailscale tunnel) terminates TLS, so `wss` alone isn't
 * end-to-end. We close that gap with a pre-shared 32-byte secret that travels in
 * the share link's URL fragment (`#k=…`) — browsers never send the fragment to
 * any server. Each WebSocket connection derives fresh AES-256-GCM keys
 * (HKDF-SHA256) and every frame is encrypted.
 *
 * Wire format of an encrypted (binary) frame:
 *   nonce(12) || AES-256-GCM(ciphertext) || tag(16),  AAD = 1 direction byte.
 *
 * The web viewer mirrors this with WebCrypto (`crypto.subtle`): HKDF-SHA256 with
 * the same info labels, AES-GCM, 12-byte IV / 128-bit tag / `additionalData` =
 * the direction byte. Keep the two in lock-step.
 */
object BrowserSessionCrypto {
    const val NONCE_LEN = 12
    const val TAG_BITS = 128
    const val KEY_LEN = 32

    /** AAD direction tags — bind each ciphertext to its direction so it can't be reflected. */
    const val DIR_C2S: Byte = 0x00 // client → host
    const val DIR_S2C: Byte = 0x01 // host → client

    private val rng = SecureRandom()
    private val b64UrlEnc = Base64.getUrlEncoder().withoutPadding()
    private val b64UrlDec = Base64.getUrlDecoder()

    /** A fresh 32-byte session secret (the `#k=` fragment value), per share. */
    fun newSessionSecret(): ByteArray = ByteArray(KEY_LEN).also { rng.nextBytes(it) }

    fun encodeSecretB64Url(secret: ByteArray): String = b64UrlEnc.encodeToString(secret)
    fun decodeSecretB64Url(s: String): ByteArray = b64UrlDec.decode(s)

    fun randomSalt(): ByteArray = ByteArray(16).also { rng.nextBytes(it) }

    /** HKDF-SHA256 (RFC 5869): extract then expand. Matches WebCrypto deriveBits. */
    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, len: Int): ByteArray {
        val prk = hmac(salt, ikm) // extract
        val out = ByteArray(len)
        var pos = 0
        var counter = 1
        var prev = ByteArray(0)
        while (pos < len) {
            val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(prk, "HmacSHA256")) }
            mac.update(prev); mac.update(info); mac.update(counter.toByte())
            prev = mac.doFinal()
            val n = minOf(prev.size, len - pos)
            System.arraycopy(prev, 0, out, pos, n)
            pos += n; counter++
        }
        return out
    }

    private fun hmac(key: ByteArray, msg: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(if (key.isEmpty()) ByteArray(32) else key, "HmacSHA256"))
        }.doFinal(msg)

    /** Per-connection keys + a key-confirmation tag, derived from the secret + both salts. */
    class DerivedKeys(val kC2s: ByteArray, val kS2c: ByteArray, val confirm: ByteArray) {
        val confirmB64: String get() = b64UrlEnc.encodeToString(confirm)
    }

    fun deriveKeys(secret: ByteArray, saltC: ByteArray, saltS: ByteArray): DerivedKeys {
        val salt = saltC + saltS
        return DerivedKeys(
            kC2s = hkdf(secret, salt, "bossbrowser-c2s-v1".toByteArray(Charsets.UTF_8), KEY_LEN),
            kS2c = hkdf(secret, salt, "bossbrowser-s2c-v1".toByteArray(Charsets.UTF_8), KEY_LEN),
            confirm = hkdf(secret, salt, "bossbrowser-kc-v1".toByteArray(Charsets.UTF_8), KEY_LEN),
        )
    }

    /** Constant-time compare for the key-confirmation tag (b64url). */
    fun confirmMatches(expected: ByteArray, gotB64: String?): Boolean {
        val got = runCatching { b64UrlDec.decode(gotB64 ?: return false) }.getOrNull() ?: return false
        return MessageDigest.isEqual(expected, got)
    }

    /**
     * Encrypts/decrypts one direction's frames with a fixed key + AAD direction
     * byte. Use one instance per direction per connection.
     */
    class FrameCipher(key: ByteArray, dir: Byte) {
        private val keySpec = SecretKeySpec(key, "AES")
        private val aad = byteArrayOf(dir)

        fun encrypt(plaintextUtf8: String): ByteArray {
            val nonce = ByteArray(NONCE_LEN).also { rng.nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(TAG_BITS, nonce))
            cipher.updateAAD(aad)
            val body = cipher.doFinal(plaintextUtf8.toByteArray(Charsets.UTF_8)) // ciphertext||tag
            return nonce + body
        }

        /** @throws javax.crypto.AEADBadTagException on auth failure (wrong key / tamper). */
        fun decrypt(frame: ByteArray): String {
            require(frame.size > NONCE_LEN) { "frame too short" }
            val nonce = frame.copyOfRange(0, NONCE_LEN)
            val body = frame.copyOfRange(NONCE_LEN, frame.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(TAG_BITS, nonce))
            cipher.updateAAD(aad)
            return String(cipher.doFinal(body), Charsets.UTF_8)
        }
    }

    /** Short, human-comparable fingerprint of a secret (first 8 hex of SHA-256). */
    fun fingerprint(secret: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(secret)
            .take(4).joinToString("") { "%02x".format(it) }
}
