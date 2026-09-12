package com.sharethis.app.core.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

class SessionCryptoTest {

    private val random = SecureRandom()

    @Test
    fun `ecdhe both directions derive identical keys`() {
        val a = SessionCrypto.generateKeyPair(random)
        val b = SessionCrypto.generateKeyPair(random)
        val secretA = SessionCrypto.ecdhSecret(a.private, b.public as java.security.interfaces.ECPublicKey)!!
        val secretB = SessionCrypto.ecdhSecret(b.private, a.public as java.security.interfaces.ECPublicKey)!!
        assertArrayEquals(secretA, secretB)

        val pin = "512890"
        val keysA = SessionCrypto.deriveKeys(secretA, pin, "challenge-a".toByteArray(), "challenge-b".toByteArray(), true)
        val keysB = SessionCrypto.deriveKeys(secretB, pin, "challenge-b".toByteArray(), "challenge-a".toByteArray(), false)
        assertArrayEquals(keysA.finishKey, keysB.finishKey)
        assertArrayEquals(keysA.sendKey, keysB.sendKey)
        assertArrayEquals(keysA.recvKey, keysB.recvKey)
    }

    @Test
    fun `wrong pin produces different keys`() {
        val a = SessionCrypto.generateKeyPair(random)
        val b = SessionCrypto.generateKeyPair(random)
        val secret = SessionCrypto.ecdhSecret(a.private, b.public as java.security.interfaces.ECPublicKey)!!
        val good = SessionCrypto.deriveKeys(secret, "111111", ByteArray(16), ByteArray(16), true)
        val bad = SessionCrypto.deriveKeys(secret, "111112", ByteArray(16), ByteArray(16), true)
        assertFalse(good.finishKey.contentEquals(bad.finishKey))
    }

    @Test
    fun `finish mac verifies and is pin-bound`() {
        val sender = SessionCrypto.generateKeyPair(random)
        val receiver = SessionCrypto.generateKeyPair(random)
        val senderPub = SessionCrypto.encodePublicKey(sender.public as java.security.interfaces.ECPublicKey)
        val receiverPub = SessionCrypto.encodePublicKey(receiver.public as java.security.interfaces.ECPublicKey)
        val senderChallenge = ByteArray(16) { 1 }
        val receiverChallenge = ByteArray(16) { 2 }
        val secret = SessionCrypto.ecdhSecret(sender.private, receiver.public as java.security.interfaces.ECPublicKey)!!
        val keys = SessionCrypto.deriveKeys(secret, "512890", senderChallenge, receiverChallenge, true)

        val senderMac = SessionCrypto.finishMac(
            keys.finishKey, "sharethis/v2/auth", 42L, senderPub, receiverPub,
            senderChallenge, receiverChallenge, SessionCryptoTest.DIRECTION_X
        )
        val receiverMac = SessionCrypto.finishMac(
            keys.finishKey, "sharethis/v2/auth", 42L, senderPub, receiverPub,
            senderChallenge, receiverChallenge, SessionCryptoTest.DIRECTION_Y
        )
        // Different directions must produce different MACs…
        assertFalse(senderMac.contentEquals(receiverMac))
        // …and each side recomputes the other's MAC identically.
        assertArrayEquals(senderMac, SessionCrypto.finishMac(
            keys.finishKey, "sharethis/v2/auth", 42L, senderPub, receiverPub,
            senderChallenge, receiverChallenge, DIRECTION_X
        ))

        // A wrong PIN never reproduces the MAC.
        val wrongKeys = SessionCrypto.deriveKeys(secret, "999999", senderChallenge, receiverChallenge, true)
        val wrongMac = SessionCrypto.finishMac(
            wrongKeys.finishKey, "sharethis/v2/auth", 42L, senderPub, receiverPub,
            senderChallenge, receiverChallenge, DIRECTION_X
        )
        assertFalse(senderMac.contentEquals(wrongMac))
    }

    @Test
    fun `gcm aead round-trips and detects tampering`() {
        val key = ByteArray(32) { it.toByte() }
        val aead = SessionCrypto.newAead(key, byteArrayOf(9, 9, 9, 9))
        val plaintext = "chunk payload 12345".toByteArray()
        val sealed = aead.seal(plaintext, 7)
        assertArrayEquals(plaintext, aead.open(sealed, 7))
        assertNotEquals(plaintext.size, sealed.size) // tag appended

        sealed[sealed.size / 2] = (sealed[sealed.size / 2] + 1).toByte()
        try {
            aead.open(sealed, 7)
            throw AssertionError("tampered ciphertext must not decrypt")
        } catch (_: Exception) {
            // AEADBadTagException / SecurityException — both fine.
        }
    }

    @Test(expected = Exception::class)
    fun `gcm rejects nonce reuse across sequences`() {
        val key = ByteArray(32) { 5 }
        val aead = SessionCrypto.newAead(key, byteArrayOf(9, 9, 9, 9))
        val sealedAtFive = aead.seal("hello".toByteArray(), 5)
        // Opening a blob sealed for sequence 5 as if it were sequence 6 must fail.
        aead.open(sealedAtFive, 6)
    }

    @Test
    fun `nonces are unique per sequence`() {
        val key = ByteArray(32)
        val aead = SessionCrypto.newAead(key, byteArrayOf(9, 9, 9, 9))
        // Verify the internal construction: prefix + big-endian counter.
        // (Indirectly: same key, different sequences must not cross-decrypt.)
        val sealed0 = aead.seal("zero".toByteArray(), 0)
        val sealed1 = aead.seal("one".toByteArray(), 1)
        assertArrayEquals("zero".toByteArray(), aead.open(sealed0, 0))
        assertArrayEquals("one".toByteArray(), aead.open(sealed1, 1))
        try {
            aead.open(sealed0, 1)
            throw AssertionError("sequence confusion")
        } catch (_: Exception) { }
    }

    @Test
    fun `cbc-hmac fallback round-trips and detects tampering`() {
        val key = ByteArray(32) { (it * 3).toByte() }
        val prefix = ByteArray(4) { 9 }
        val aead = SessionCrypto.cbcHmacAeadForTest(key, prefix)
        val plaintext = ByteArray(5000) { (it % 251).toByte() }
        val sealed = aead.seal(plaintext, 12)
        assertArrayEquals(plaintext, aead.open(sealed, 12))
        sealed[sealed.size - 3] = (sealed[sealed.size - 3] + 1).toByte()
        try {
            aead.open(sealed, 12)
            throw AssertionError("tampered HMAC must not verify")
        } catch (_: Exception) { }
        // Wrong sequence (IV mismatch) must fail too.
        val resealed = aead.seal(plaintext, 12)
        try {
            aead.open(resealed, 13)
            throw AssertionError("IV/sequence mismatch must fail")
        } catch (_: Exception) { }
    }

    @Test
    fun `public key encoding round-trips`() {
        val pair = SessionCrypto.generateKeyPair(random)
        val encoded = SessionCrypto.encodePublicKey(pair.public as java.security.interfaces.ECPublicKey)
        assertTrue(encoded.size in 60..SessionCrypto.MAX_PUBLIC_KEY_BYTES)
        val decoded = SessionCrypto.decodePublicKey(encoded)
        assertEquals(pair.public, decoded)
    }

    @Test
    fun `hostile public key input is rejected not thrown`() {
        assertNull(SessionCrypto.decodePublicKey(ByteArray(0)))
        assertNull(SessionCrypto.decodePublicKey(ByteArray(9999)))
        assertNull(SessionCrypto.decodePublicKey("garbage".toByteArray()))
    }

    @Test
    fun `constant time equals`() {
        val a = byteArrayOf(1, 2, 3)
        assertTrue(SessionCrypto.constantTimeEquals(a, byteArrayOf(1, 2, 3)))
        assertFalse(SessionCrypto.constantTimeEquals(a, byteArrayOf(1, 2, 4)))
        assertFalse(SessionCrypto.constantTimeEquals(a, byteArrayOf(1, 2)))
    }

    companion object {
        const val DIRECTION_X = 0
        const val DIRECTION_Y = 1
    }
}
