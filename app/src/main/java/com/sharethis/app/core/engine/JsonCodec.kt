package com.sharethis.app.core.engine

/**
 * Tiny dependency-free JSON writer/parser for flat wire objects.
 *
 * Pure JVM (no org.json / Android dependency) so the wire protocol stays
 * unit-testable on the JVM and adds zero bloat to the APK.
 *
 * Supported: string / long / boolean / null values plus one level of nested
 * raw values (objects/arrays are preserved verbatim as substrings).
 */
object JsonCodec {

    fun escape(value: String): String {
        val sb = StringBuilder(value.length + 8)
        for (c in value) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                else -> if (c.code < 0x20) {
                    sb.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                } else {
                    sb.append(c)
                }
            }
        }
        return sb.toString()
    }

    fun obj(vararg pairs: Pair<String, Any?>): String {
        val sb = StringBuilder("{")
        pairs.forEachIndexed { index, (key, value) ->
            if (index > 0) sb.append(',')
            sb.append('"').append(escape(key)).append("\":")
            sb.append(encodeValue(value))
        }
        return sb.append('}').toString()
    }

    fun encodeValue(value: Any?): String = when (value) {
        null -> "null"
        is String -> "\"${escape(value)}\""
        is Number, is Boolean -> value.toString()
        is RawJson -> value.json
        else -> "\"${escape(value.toString())}\""
    }

    /** Marker for pre-encoded nested JSON (arrays / objects). */
    @JvmInline
    value class RawJson(val json: String)

    /**
     * Parses a flat JSON object into key -> raw-value-token pairs.
     * Quoted strings are unescaped; nested objects/arrays are returned
     * verbatim (including braces) so callers can decode them further.
     */
    fun parseObject(json: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        val text = json.trim()
        if (!text.startsWith("{") || !text.endsWith("}")) return result
        val body = text.substring(1, text.length - 1)
        for (part in splitTopLevel(body, ',')) {
            val kv = splitTopLevel(part, ':')
            if (kv.size != 2) continue
            val key = unquote(kv[0].trim()) ?: continue
            result[key] = kv[1].trim()
        }
        return result
    }

    /** Splits a JSON array body into raw element tokens (top level only). */
    fun parseArray(json: String): List<String> {
        val text = json.trim()
        if (!text.startsWith("[") || !text.endsWith("]")) return emptyList()
        val body = text.substring(1, text.length - 1).trim()
        if (body.isEmpty()) return emptyList()
        return splitTopLevel(body, ',')
    }

    fun splitTopLevel(input: String, delimiter: Char): List<String> {
        val parts = ArrayList<String>()
        val current = StringBuilder()
        var inString = false
        var escaped = false
        var depth = 0
        for (c in input) {
            when {
                escaped -> {
                    current.append(c)
                    escaped = false
                }
                c == '\\' && inString -> {
                    current.append(c)
                    escaped = true
                }
                c == '"' -> {
                    current.append(c)
                    inString = !inString
                }
                !inString && (c == '{' || c == '[') -> {
                    current.append(c)
                    depth++
                }
                !inString && (c == '}' || c == ']') -> {
                    current.append(c)
                    depth--
                }
                !inString && depth == 0 && c == delimiter -> {
                    parts.add(current.toString())
                    current.clear()
                }
                else -> current.append(c)
            }
        }
        parts.add(current.toString())
        return parts
    }

    fun unquote(token: String): String? {
        val text = token.trim()
        if (text.length < 2 || !text.startsWith("\"") || !text.endsWith("\"")) return null
        val sb = StringBuilder()
        var i = 1
        while (i < text.length - 1) {
            val c = text[i]
            if (c == '\\' && i + 1 < text.length - 1) {
                when (val e = text[i + 1]) {
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'b' -> sb.append('\b')
                    'u' -> {
                        if (i + 5 < text.length - 1) {
                            val hex = text.substring(i + 2, i + 6)
                            sb.append(hex.toIntOrNull(16)?.toChar() ?: '?')
                            i += 4
                        }
                    }
                    else -> sb.append(e)
                }
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    fun asString(token: String?): String = token?.let { unquote(it) } ?: ""

    fun asLong(token: String?, default: Long = 0L): Long {
        val raw = token?.trim() ?: return default
        if (raw.startsWith("\"")) return unquote(raw)?.toLongOrNull() ?: default
        return raw.toLongOrNull() ?: default
    }

    fun asInt(token: String?, default: Int = 0): Int = asLong(token, default.toLong()).toInt()

    fun asBoolean(token: String?, default: Boolean = false): Boolean {
        val raw = token?.trim() ?: return default
        if (raw.startsWith("\"")) return unquote(raw)?.toBoolean() ?: default
        return when (raw) {
            "true", "1" -> true
            "false", "0" -> false
            else -> default
        }
    }
}
