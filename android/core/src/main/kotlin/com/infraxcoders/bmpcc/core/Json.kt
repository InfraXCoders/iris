package com.infraxcoders.bmpcc.core

/**
 * Small JSON reader/writer (no dependencies, so the same code runs in the app and in plain JVM tests).
 * Objects become Map<String, Any?> (insertion ordered), arrays List<Any?>, numbers Double or Long.
 */
object Json {
    class ParseException(message: String) : Exception(message)

    fun parse(text: String): Any? {
        val p = Parser(text)
        p.ws()
        val v = p.value()
        p.ws()
        if (p.i != text.length) throw ParseException("Unexpected data at ${p.i}")
        return v
    }

    private class Parser(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun value(): Any? {
            if (i >= s.length) throw ParseException("Unexpected end")
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c.isDigit()) num() else throw ParseException("Unexpected '$c' at $i")
            }
        }
        fun lit(word: String, v: Any?): Any? {
            if (!s.startsWith(word, i)) throw ParseException("Expected $word at $i")
            i += word.length
            return v
        }
        fun obj(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            i++; ws()
            if (s[i] == '}') { i++; return m }
            while (true) {
                ws()
                if (s[i] != '"') throw ParseException("Expected key at $i")
                val k = str()
                ws()
                if (s[i] != ':') throw ParseException("Expected ':' at $i")
                i++; ws()
                m[k] = value()
                ws()
                when (s[i]) {
                    ',' -> i++
                    '}' -> { i++; return m }
                    else -> throw ParseException("Expected ',' or '}' at $i")
                }
            }
        }
        fun arr(): List<Any?> {
            val l = ArrayList<Any?>()
            i++; ws()
            if (s[i] == ']') { i++; return l }
            while (true) {
                ws(); l.add(value()); ws()
                when (s[i]) {
                    ',' -> i++
                    ']' -> { i++; return l }
                    else -> throw ParseException("Expected ',' or ']' at $i")
                }
            }
        }
        fun str(): String {
            val sb = StringBuilder()
            i++
            while (true) {
                if (i >= s.length) throw ParseException("Unterminated string")
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        when (val e = s[i++]) {
                            '"' -> sb.append('"'); '\\' -> sb.append('\\'); '/' -> sb.append('/')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000C'); 'n' -> sb.append('\n')
                            'r' -> sb.append('\r'); 't' -> sb.append('\t')
                            'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                            else -> throw ParseException("Bad escape \\$e")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }
        fun num(): Any {
            val start = i
            if (s[i] == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] in ".eE+-")) i++
            val t = s.substring(start, i)
            return if (t.any { it in ".eE" }) t.toDouble() else (t.toLongOrNull() ?: t.toDouble())
        }
    }

    fun write(value: Any?, pretty: Boolean = true): String {
        val sb = StringBuilder()
        write(sb, value, if (pretty) 0 else -1)
        return sb.toString()
    }

    private fun write(sb: StringBuilder, v: Any?, indent: Int) {
        val nl = indent >= 0
        fun pad(n: Int) { if (nl) { sb.append('\n'); repeat(n) { sb.append("  ") } } }
        when (v) {
            null -> sb.append("null")
            is String -> quote(sb, v)
            is Boolean -> sb.append(v)
            is Int, is Long -> sb.append(v.toString())
            is Double -> sb.append(if (v == Math.floor(v) && !v.isInfinite() && Math.abs(v) < 1e15) v.toLong().toString() else v.toString())
            is Float -> write(sb, v.toDouble(), indent)
            is Map<*, *> -> {
                if (v.isEmpty()) { sb.append("{}"); return }
                sb.append('{')
                var first = true
                for ((k, x) in v) {
                    if (!first) sb.append(',')
                    first = false
                    pad(indent + 1)
                    quote(sb, k.toString())
                    sb.append(if (nl) " : " else ":")
                    write(sb, x, if (nl) indent + 1 else -1)
                }
                pad(indent)
                sb.append('}')
            }
            is List<*> -> {
                if (v.isEmpty()) { sb.append("[]"); return }
                sb.append('[')
                v.forEachIndexed { idx, x ->
                    if (idx > 0) sb.append(',')
                    pad(indent + 1)
                    write(sb, x, if (nl) indent + 1 else -1)
                }
                pad(indent)
                sb.append(']')
            }
            else -> quote(sb, v.toString())
        }
    }

    private fun quote(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
        }
        sb.append('"')
    }
}

/** Typed reads from a parsed JSON object, with defaults for missing or null keys. */
internal class JObj(val m: Map<String, Any?>) {
    fun str(k: String, def: String = ""): String = (m[k] as? String) ?: def
    fun strOrNull(k: String): String? = m[k] as? String
    fun dbl(k: String, def: Double = 0.0): Double = (m[k] as? Number)?.toDouble() ?: def
    fun dblOrNull(k: String): Double? = (m[k] as? Number)?.toDouble()
    fun long(k: String, def: Long = 0): Long = (m[k] as? Number)?.toLong() ?: def
    fun bool(k: String, def: Boolean = false): Boolean = (m[k] as? Boolean) ?: def
    fun objs(k: String): List<JObj> = (m[k] as? List<*>)?.mapNotNull { (it as? Map<*, *>)?.let { x -> JObj(x.toStringKeys()) } } ?: emptyList()
    fun objsOrNull(k: String): List<JObj>? = if (m[k] is List<*>) objs(k) else null
    fun obj(k: String): JObj? = (m[k] as? Map<*, *>)?.let { JObj(it.toStringKeys()) }
}

internal fun Map<*, *>.toStringKeys(): Map<String, Any?> = entries.associate { it.key.toString() to it.value }
