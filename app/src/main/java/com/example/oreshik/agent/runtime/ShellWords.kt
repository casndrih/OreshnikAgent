package com.example.oreshik.agent.runtime

/**
 * Small POSIX-like argument splitter used before launching managed runtimes.
 * Currently referenced by RuntimeManager only; kept public for tests.
 */
@Suppress("unused")
object ShellWords {
    fun split(input: String): List<String> {
        val out = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var escape = false
        fun flush() { if (current.isNotEmpty()) { out += current.toString(); current.setLength(0) } }
        for (c in input) {
            if (escape) { current.append(c); escape = false; continue }
            if ((c == '\\') && (quote != '\'')) {
                escape = true
                continue
            }
            if (quote != null) {
                if (c == quote) quote = null else current.append(c)
            } else when (c) {
                '\'', '"' -> quote = c
                ' ', '\t', '\n', '\r' -> flush()
                else -> current.append(c)
            }
        }
        if (escape) current.append('\\')
        flush()
        return out
    }
}
