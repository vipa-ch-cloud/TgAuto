package com.v1pach.telescript

/**
 * Minimal ANSI handling for the console: line breaks, carriage return and SGR colors/bold/underline.
 * Colors: -1 = default, 0..15 = palette index, otherwise RGB packed as RGB_FLAG or 0xRRGGBB.
 * Every other escape sequence (cursor movement, OSC titles, ...) is dropped.
 */
const val RGB_FLAG = 0x01000000

data class AnsiStyle(val fg: Int = -1, val bg: Int = -1, val bold: Boolean = false, val underline: Boolean = false)

data class AnsiSpan(val text: String, val style: AnsiStyle)

class AnsiLineBuilder {
    private var style = AnsiStyle()
    private val spans = ArrayList<AnsiSpan>()
    private val sb = StringBuilder()
    private var width = 0
    val lines = ArrayList<List<AnsiSpan>>()

    fun feed(s: String) {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '\u001B' -> { i = escape(s, i); continue }
                c == '\n' -> newline()
                c == '\r' -> if (!(i + 1 < s.length && s[i + 1] == '\n')) { sb.clear(); spans.clear(); width = 0 }
                c == '\t' -> { val n = 8 - width % 8; repeat(n) { sb.append(' ') }; width += n }
                c < ' ' || c == '\u007F' -> {}
                else -> { sb.append(c); width++ }
            }
            i++
        }
    }

    /** Ends a trailing line that had no newline. */
    fun flush() { if (sb.isNotEmpty() || spans.isNotEmpty()) newline() }

    private fun closeSpan() {
        if (sb.isNotEmpty()) { spans.add(AnsiSpan(sb.toString(), style)); sb.clear() }
    }

    private fun newline() {
        closeSpan()
        lines.add(ArrayList(spans))
        spans.clear()
        width = 0
    }

    /** Returns the index after the escape sequence starting at [start]. */
    private fun escape(s: String, start: Int): Int {
        if (start + 1 >= s.length) return s.length
        return when (s[start + 1]) {
            '[' -> {
                var j = start + 2
                while (j < s.length && s[j].code !in 0x40..0x7E) j++
                if (j >= s.length) return s.length
                if (s[j] == 'm') sgr(s.substring(start + 2, j))
                j + 1
            }
            ']' -> {
                var j = start + 2
                while (j < s.length && s[j] != '\u0007' && !(s[j] == '\u001B' && j + 1 < s.length && s[j + 1] == '\\')) j++
                if (j >= s.length) s.length else if (s[j] == '\u0007') j + 1 else j + 2
            }
            else -> start + 2
        }
    }

    private fun sgr(params: String) {
        val p = if (params.isEmpty()) listOf(0) else params.split(';', ':').map { it.toIntOrNull() ?: 0 }
        closeSpan()
        var st = style
        var i = 0
        while (i < p.size) {
            when (val n = p[i]) {
                0 -> st = AnsiStyle()
                1 -> st = st.copy(bold = true)
                22 -> st = st.copy(bold = false)
                4 -> st = st.copy(underline = true)
                24 -> st = st.copy(underline = false)
                in 30..37 -> st = st.copy(fg = n - 30)
                39 -> st = st.copy(fg = -1)
                in 40..47 -> st = st.copy(bg = n - 40)
                49 -> st = st.copy(bg = -1)
                in 90..97 -> st = st.copy(fg = n - 90 + 8)
                in 100..107 -> st = st.copy(bg = n - 100 + 8)
                38, 48 -> {
                    val color = when (p.getOrNull(i + 1)) {
                        5 -> { val v = p.getOrNull(i + 2); i += 2; v?.let { palette256(it) } }
                        2 -> {
                            val r = p.getOrNull(i + 2); val g = p.getOrNull(i + 3); val b = p.getOrNull(i + 4)
                            i += 4
                            if (r != null && g != null && b != null) RGB_FLAG or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255) else null
                        }
                        else -> null
                    }
                    if (color != null) st = if (n == 38) st.copy(fg = color) else st.copy(bg = color)
                }
            }
            i++
        }
        style = st
    }

    private fun palette256(v: Int): Int = when {
        v < 16 -> v
        v < 232 -> {
            val k = v - 16
            val steps = intArrayOf(0, 95, 135, 175, 215, 255)
            RGB_FLAG or (steps[k / 36] shl 16) or (steps[k / 6 % 6] shl 8) or steps[k % 6]
        }
        else -> { val g = 8 + (v - 232).coerceAtMost(23) * 10; RGB_FLAG or (g shl 16) or (g shl 8) or g }
    }
}
