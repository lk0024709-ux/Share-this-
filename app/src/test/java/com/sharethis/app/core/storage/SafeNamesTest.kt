package com.sharethis.app.core.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SafeNamesTest {

    @Test
    fun `path traversal is neutralized`() {
        assertEquals("passwd", SafeNames.sanitize("../../etc/passwd"))
        assertEquals("passwd", SafeNames.sanitize("/etc/passwd"))
        assertEquals("c.jpg", SafeNames.sanitize("C:\\Users\\me\\..\\c.jpg"))
        assertEquals("file", SafeNames.sanitize(".."))
        assertEquals("file", SafeNames.sanitize("."))
        assertEquals("file", SafeNames.sanitize(""))
        assertEquals("file", SafeNames.sanitize("   "))
    }

    @Test
    fun `illegal characters replaced`() {
        assertEquals("a_b_c.txt", SafeNames.sanitize("a<b>c.txt"))
        assertEquals("co_lon.mp4", SafeNames.sanitize("co:lon.mp4"))
    }

    @Test
    fun `control characters stripped`() {
        assertEquals("clean.png", SafeNames.sanitize("cl\ne\u0000an.png"))
    }

    @Test
    fun `unicode hindi and emoji preserved`() {
        assertEquals("मेरी फ़ाइल.jpg", SafeNames.sanitize("मेरी फ़ाइल.jpg"))
        assertEquals("वीडियो 🎬.mp4", SafeNames.sanitize("वीडियो 🎬.mp4"))
        assertEquals("照片.png", SafeNames.sanitize("/sdcard/照片.png"))
    }

    @Test
    fun `reserved windows device names prefixed`() {
        assertEquals("_CON", SafeNames.sanitize("CON"))
        assertEquals("_NUL.txt", SafeNames.sanitize("NUL.txt"))
        assertEquals("_com1.jpg", SafeNames.sanitize("com1.jpg"))
    }

    @Test
    fun `long names truncated preserving extension`() {
        val long = "a".repeat(300) + ".jpg"
        val result = SafeNames.sanitize(long)
        assertTrue("length ${result.length}", result.length <= SafeNames.MAX_NAME_LENGTH)
        assertTrue(result.endsWith(".jpg"))
    }

    @Test
    fun `trailing dots and spaces trimmed`() {
        assertEquals("name", SafeNames.sanitize("name... "))
        assertEquals("name.jpg", SafeNames.sanitize("name.jpg. "))
    }

    @Test
    fun `disambiguate appends counter before extension`() {
        val existing = mutableSetOf("photo.jpg")
        assertEquals("photo (1).jpg", SafeNames.disambiguate("photo.jpg") { existing.contains(it) })
        existing.add("photo (1).jpg")
        assertEquals("photo (2).jpg", SafeNames.disambiguate("photo.jpg") { existing.contains(it) })
        assertEquals("new.txt", SafeNames.disambiguate("new.txt") { false })
        // No extension case.
        val noExt = mutableSetOf("README")
        assertEquals("README (1)", SafeNames.disambiguate("README") { noExt.contains(it) })
    }
}
