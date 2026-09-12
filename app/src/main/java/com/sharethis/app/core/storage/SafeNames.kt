package com.sharethis.app.core.storage

/**
 * Filename hardening — pure JVM, unit-tested, shared by the engine and
 * [StorageBridge].
 *
 * Received filenames are attacker-controlled input. They are reduced to a
 * single path segment that can never:
 *  - escape the destination directory (`../`, `/`, `\`, absolute paths);
 *  - carry control characters or FAT/exFAT-illegal characters;
 *  - impersonate Windows reserved device names (CON, NUL, …);
 *  - exceed [MAX_NAME_LENGTH] (extension preserved);
 *  - be empty, "." or "..".
 *
 * Unicode (Hindi, emoji, CJK, …) is explicitly *preserved* — only
 * structurally dangerous characters are replaced.
 */
object SafeNames {

    const val MAX_NAME_LENGTH = 120

    // Structural filesystem-illegal characters.
    private val ILLEGAL = Regex("[\\\\/:*?\"<>|]")

    // Control characters (and DEL) are removed entirely.
    private val CONTROLS = Regex("[\u0000-\u001F\u007F]")

    // Windows reserved device names (still fatal on some OEM MTP stacks).
    private val RESERVED = setOf(
        "CON", "PRN", "AUX", "NUL",
        "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"
    )

    /**
     * Collapses any attacker-supplied string into one safe file name.
     * Directory components are removed, not sanitized in place.
     */
    fun sanitize(raw: String): String {
        // Only the last path segment survives — "../etc/passwd" → "passwd".
        var name = raw.substringAfterLast('/').substringAfterLast('\\')
        name = name.replace(CONTROLS, "")
        name = name.replace(ILLEGAL, "_")
        name = name.trim().trimEnd('.', ' ')
        if (name.isEmpty() || name == "." || name == "..") name = "file"
        if (name.length > MAX_NAME_LENGTH) {
            val ext = name.substringAfterLast('.', "")
            name = if (ext.length in 1..16 && name.length > ext.length + 1) {
                name.take(MAX_NAME_LENGTH - ext.length - 1) + "." + ext
            } else {
                name.take(MAX_NAME_LENGTH)
            }
        }
        val stem = name.substringBefore('.').uppercase()
        if (stem in RESERVED) name = "_$name"
        return name
    }

    /** Returns `name` unchanged when no collision exists, otherwise `base (n).ext`. */
    fun disambiguate(name: String, exists: (String) -> Boolean): String {
        if (!exists(name)) return name
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var index = 1
        var candidate = "$base ($index)$ext"
        while (exists(candidate) && index < 10_000) {
            index++
            candidate = "$base ($index)$ext"
        }
        return candidate
    }
}
