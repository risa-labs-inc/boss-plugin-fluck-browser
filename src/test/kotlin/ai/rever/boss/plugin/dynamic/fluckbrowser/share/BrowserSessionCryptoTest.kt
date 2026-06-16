package ai.rever.boss.plugin.dynamic.fluckbrowser.share

import javax.crypto.AEADBadTagException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Guards the host crypto against divergence from the viewer's WebCrypto (viewer.js):
 * same HKDF-SHA256, label set (bossbrowser-*-v1), salt order (client first), AES-256-GCM
 * with a 1-byte direction AAD. The RFC-5869 vector pins the shared HKDF primitive.
 */
class BrowserSessionCryptoTest {
    private fun hex(s: String): ByteArray = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

    @Test fun hkdfRfc5869Vector() {
        val ikm = hex("0b".repeat(22))
        val salt = hex("000102030405060708090a0b0c")
        val info = hex("f0f1f2f3f4f5f6f7f8f9")
        val okm = BrowserSessionCrypto.hkdf(ikm, salt, info, 42)
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            hex(okm),
        )
    }

    @Test fun deriveKeysDeterministicDistinctAndOrderSensitive() {
        val secret = hex("00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff")
        val saltC = hex("0102030405060708090a0b0c0d0e0f10")
        val saltS = hex("1112131415161718191a1b1c1d1e1f20")
        val a = BrowserSessionCrypto.deriveKeys(secret, saltC, saltS)
        val b = BrowserSessionCrypto.deriveKeys(secret, saltC, saltS)
        assertEquals(hex(a.kC2s), hex(b.kC2s))
        assertEquals(hex(a.kS2c), hex(b.kS2c))
        assertEquals(hex(a.confirm), hex(b.confirm))
        assertEquals(32, a.kC2s.size)
        assertNotEquals(hex(a.kC2s), hex(a.kS2c))
        assertNotEquals(hex(a.kC2s), hex(a.confirm))
        // client salt must come first — swapping changes the keys
        val swapped = BrowserSessionCrypto.deriveKeys(secret, saltS, saltC)
        assertNotEquals(hex(a.kC2s), hex(swapped.kC2s))
    }

    @Test fun frameRoundTripBothDirections() {
        val keys = BrowserSessionCrypto.deriveKeys(
            BrowserSessionCrypto.newSessionSecret(),
            BrowserSessionCrypto.randomSalt(),
            BrowserSessionCrypto.randomSalt(),
        )
        val payload = "{\"t\":\"domMutation\",\"event\":\"héllo 🌍 [0m\"}"
        val encC = BrowserSessionCrypto.FrameCipher(keys.kC2s, BrowserSessionCrypto.DIR_C2S)
        val decC = BrowserSessionCrypto.FrameCipher(keys.kC2s, BrowserSessionCrypto.DIR_C2S)
        assertEquals(payload, decC.decrypt(encC.encrypt(payload)))
        val encS = BrowserSessionCrypto.FrameCipher(keys.kS2c, BrowserSessionCrypto.DIR_S2C)
        val decS = BrowserSessionCrypto.FrameCipher(keys.kS2c, BrowserSessionCrypto.DIR_S2C)
        assertEquals(payload, decS.decrypt(encS.encrypt(payload)))
        // random nonce → repeated encryptions differ
        assertNotEquals(hex(encC.encrypt(payload)), hex(encC.encrypt(payload)))
    }

    @Test fun wrongKeyFailsAuth() {
        val k1 = BrowserSessionCrypto.deriveKeys(BrowserSessionCrypto.newSessionSecret(), BrowserSessionCrypto.randomSalt(), BrowserSessionCrypto.randomSalt())
        val k2 = BrowserSessionCrypto.deriveKeys(BrowserSessionCrypto.newSessionSecret(), BrowserSessionCrypto.randomSalt(), BrowserSessionCrypto.randomSalt())
        val ct = BrowserSessionCrypto.FrameCipher(k1.kC2s, BrowserSessionCrypto.DIR_C2S).encrypt("secret")
        assertFailsWith<AEADBadTagException> {
            BrowserSessionCrypto.FrameCipher(k2.kC2s, BrowserSessionCrypto.DIR_C2S).decrypt(ct)
        }
    }

    @Test fun wrongDirectionFailsAuth() {
        val keys = BrowserSessionCrypto.deriveKeys(BrowserSessionCrypto.newSessionSecret(), BrowserSessionCrypto.randomSalt(), BrowserSessionCrypto.randomSalt())
        val ct = BrowserSessionCrypto.FrameCipher(keys.kC2s, BrowserSessionCrypto.DIR_C2S).encrypt("x")
        assertFailsWith<AEADBadTagException> {
            BrowserSessionCrypto.FrameCipher(keys.kC2s, BrowserSessionCrypto.DIR_S2C).decrypt(ct)
        }
    }

    @Test fun confirmTagMatchesOnlyForSameSecret() {
        val secret = BrowserSessionCrypto.newSessionSecret()
        val saltC = BrowserSessionCrypto.randomSalt()
        val saltS = BrowserSessionCrypto.randomSalt()
        val host = BrowserSessionCrypto.deriveKeys(secret, saltC, saltS)
        val client = BrowserSessionCrypto.deriveKeys(secret, saltC, saltS)
        assertTrue(BrowserSessionCrypto.confirmMatches(host.confirm, client.confirmB64))
        assertFalse(BrowserSessionCrypto.confirmMatches(host.confirm, null))
        val other = BrowserSessionCrypto.deriveKeys(BrowserSessionCrypto.newSessionSecret(), saltC, saltS)
        assertFalse(BrowserSessionCrypto.confirmMatches(host.confirm, other.confirmB64))
    }
}
