package com.openminis.app.sandbox

import java.io.File

/** File-only boot migration; explicit link/rename and unrelated settings remain intact. */
internal object GitObjectConfigPolicy {
    fun apply(rootfs: File): Boolean {
        val config = File(rootfs, "etc/gitconfig")
        val before = if (config.exists()) config.readText() else ""
        val after = migrate(before)
        if (before == after) return false
        check(config.parentFile.isDirectory || config.parentFile.mkdirs())
        config.writeText(after)
        return true
    }

    internal fun migrate(text: String): String {
        val edits = mutableListOf<Pair<IntRange, String>>()
        var core = false
        var found = false
        // Read logical lines so a continuation cannot masquerade as a section/key.
        var start = 0
        while (start < text.length) {
            var end = start
            do {
                val newline = text.indexOf('\n', end)
                end = if (newline < 0) text.length else newline + 1
                var tail = end - 1
                if (tail >= start && text[tail] == '\n') tail--
                if (tail >= start && text[tail] == '\r') tail--
                var slashes = 0
                while (tail >= start && text[tail--] == '\\') slashes++
            } while (end < text.length && slashes % 2 == 1)
            val line = text.substring(start, end)
            var offset = 0
            val header = Regex("^\\s*\\[([^\\]\\r\\n]*)]\\s*").find(line)
            if (header != null) {
                // Only the bare section qualifies, never [core "sub"] or [core.sub].
                core = header.groupValues[1].trim().equals("core", ignoreCase = true)
                offset = header.range.last + 1
            }
            if (core) {
                val key = Regex("^[ \\t]*createObject[ \\t]*(?:=[ \\t]*|(?=[;#\\r\\n]|$))", RegexOption.IGNORE_CASE)
                    .find(line.substring(offset))
                if (key != null) {
                    found = true
                    val valueStart = offset + key.range.last + 1
                    var valueEnd = valueStart
                    var quoted = false
                    var escaped = false
                    val decoded = StringBuilder()
                    while (valueEnd < line.length) {
                        val c = line[valueEnd]
                        if (escaped) {
                            when (c) {
                                '\n' -> Unit
                                '\r' -> if (line.getOrNull(valueEnd + 1) == '\n') valueEnd++ else decoded.append(c)
                                'n' -> decoded.append('\n')
                                't' -> decoded.append('\t')
                                'b' -> decoded.append('\b')
                                else -> decoded.append(c)
                            }
                            escaped = false
                        } else when {
                            c == '\\' -> escaped = true
                            c == '"' -> quoted = !quoted
                            !quoted && (c == '#' || c == ';' || c == '\r' || c == '\n') -> break
                            else -> decoded.append(c)
                        }
                        valueEnd++
                    }
                    if (decoded.toString().trim() == "copy") {
                        while (valueEnd > valueStart && line[valueEnd - 1] in " \t") valueEnd--
                        edits += (start + valueStart until start + valueEnd) to "rename"
                    }
                }
            }
            start = end
        }
        val result = StringBuilder(text)
        for ((range, value) in edits.asReversed()) result.replace(range.first, range.last + 1, value)
        if (!found) {
            val newline = if (text.contains("\r\n")) "\r\n" else "\n"
            if (result.isNotEmpty() && result.last() != '\n') result.append(newline)
            result.append("[core]").append(newline).append("\tcreateObject = rename").append(newline)
        }
        return result.toString()
    }
}
