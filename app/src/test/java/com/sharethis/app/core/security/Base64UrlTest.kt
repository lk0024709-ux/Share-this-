package com.sharethis.app.core.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Base64UrlTest {

    @Test
    fun `round-trips every byte value`() {
        val bytes = ByteArray(256) { it.toByte() }
        val encoded = Base64Url.encode(bytes)
        // URL-safe alphabet only — no +, / or padding.
        assertTrue(encoded.all { it.isLetterOrDigit() || it == '-' || it == '_' })
        assertArrayEquals(bytes, Base64Url.decode(encoded)!!)
    }

    @Test
    fun `empty input`() {
        assertEquals("", Base64Url.encode(ByteArray(0)))
        assertArrayEquals(ByteArray(0), Base64Url.decode("")!!)
    }

    @Test
    fun `known vector`() {
        // "Man" -> standard base64 "TWFu" (same in url alphabet).
        assertEquals("TWFu", Base64Url.encode("Man".toByteArray()))
        assertArrayEquals("Man".toByteArray(), Base64Url.decode("TWFu")!!)
    }

    @Test
    fun `accepts standard base64 with plus and slash`() {
        // 0xFB 0xFF 0xBF encodes to "+/+/" in the standard alphabet.
        val decoded = Base64Url.decode("+/+/")!!
        assertArrayEquals(byteArrayOf(0xFB.toByte(), 0xFF.toByte(), 0xBF.toByte()), decoded)
        assertArrayEquals(decoded, Base64Url.decode(Base64Url.encode(decoded))!!)
    }

    @Test
    fun `rejects garbage`() {
        assertNull(Base64Url.decode("!!!not base64!!!"))
        assertNull(Base64Url.decode("a")) // 4n+1 length impossible
        assertNull(Base64Url.decode("abcde"))
    }

    @Test
    fun `hex round-trip`() {
        val bytes = ByteArray(64) { (it * 7).toByte() }
        val hex = Base64Url.encodeToHex(bytes)
        assertTrue(hex.matches(Regex("[0-9a-f]+")))
        assertArrayEquals(bytes, Base64Url.decodeHex(hex)!!)
        assertNull(Base64Url.decodeHex("zz"))
        assertNull(Base64Url.decodeHex("abc"))
    }
}
