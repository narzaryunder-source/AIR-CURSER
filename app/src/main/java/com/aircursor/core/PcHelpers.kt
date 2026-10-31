package com.aircursor.core

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToLong

/** Small calculator: + - × ÷ % and brackets, with normal operator precedence. */
object Calc {
    /** Returns the result as display text, or null when the expression is incomplete/invalid. */
    fun evaluate(expr: String): String? {
        val tokens = tokenize(expr.replace('×', '*').replace('÷', '/').replace(" ", "")) ?: return null
        if (tokens.isEmpty()) return null
        val p = Parser(tokens)
        val v = p.expression() ?: return null
        if (p.pos != tokens.size || v.isNaN() || v.isInfinite()) return null
        return format(v)
    }

    fun format(v: Double): String {
        if (abs(v) < 1e15 && v == v.roundToLong().toDouble()) return v.roundToLong().toString()
        val s = String.format(java.util.Locale.US, "%.10g", v)
        if (s.contains('e') || s.contains('E')) return s
        return if (s.contains('.')) s.trimEnd('0').trimEnd('.') else s
    }

    private fun tokenize(s: String): List<String>? {
        val out = ArrayList<String>()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c.isDigit() || c == '.' -> {
                    val start = i
                    while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
                    val num = s.substring(start, i)
                    if (num.count { it == '.' } > 1 || num == ".") return null
                    out += num
                    continue
                }
                c in "+-*/%()" -> out += c.toString()
                else -> return null
            }
            i++
        }
        return out
    }

    private class Parser(val t: List<String>) {
        var pos = 0
        private fun peek() = t.getOrNull(pos)

        fun expression(): Double? {
            var v = term() ?: return null
            while (peek() == "+" || peek() == "-") {
                val op = t[pos++]
                val r = term() ?: return null
                v = if (op == "+") v + r else v - r
            }
            return v
        }

        private fun term(): Double? {
            var v = factor() ?: return null
            while (peek() == "*" || peek() == "/") {
                val op = t[pos++]
                val r = factor() ?: return null
                if (op == "/" && r == 0.0) return null
                v = if (op == "*") v * r else v / r
            }
            return v
        }

        private fun factor(): Double? {
            val tok = peek() ?: return null
            var v = when {
                tok == "-" -> { pos++; -(factor() ?: return null) }
                tok == "+" -> { pos++; factor() ?: return null }
                tok == "(" -> {
                    pos++
                    val inner = expression() ?: return null
                    if (peek() != ")") return null
                    pos++
                    inner
                }
                else -> { pos++; tok.toDoubleOrNull() ?: return null }
            }
            while (peek() == "%") { // percent: 50% = 0.5
                pos++
                v /= 100.0
            }
            return v
        }
    }
}

/** File names that never overwrite something that already exists. */
object SafeNames {
    /** "photo.png" -> "photo (2).png" if taken, and so on. */
    fun unique(name: String, existing: Collection<String>): String {
        val taken = existing.map { it.lowercase() }.toSet()
        if (name.lowercase() !in taken) return name
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var n = 2
        while (true) {
            val candidate = "$base ($n)$ext"
            if (candidate.lowercase() !in taken) return candidate
            n++
        }
    }

    /** Removes characters that are not allowed in file names. */
    fun clean(name: String): String =
        name.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_").take(120).ifBlank { "Untitled" }
}

/** Keyboard cursor ("mouse keys") speed: starts precise, speeds up while a key is held. */
object KeyMouse {
    fun step(repeatCount: Int, density: Float): Float =
        min(6f + repeatCount * 2.5f, 40f) * density
}
