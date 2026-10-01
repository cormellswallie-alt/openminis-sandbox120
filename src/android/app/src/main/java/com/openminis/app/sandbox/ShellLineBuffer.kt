package com.openminis.app.sandbox

/** Keeps the unfinished line; scans only new text, including for very long lines. */
internal class ShellLineBuffer {
    private val pending = StringBuilder()

    /** Consumes the reader's reusable array synchronously; never retains it. */
    fun feed(chars: CharArray, offset: Int, length: Int, callback: (String) -> Unit) {
        require(offset >= 0 && length >= 0 && offset <= chars.size - length)
        val end = offset + length
        var start = offset
        for (index in offset until end) {
            if (chars[index] != '\n') continue
            if (pending.isEmpty()) {
                callback(String(chars, start, index - start))
            } else {
                pending.append(chars, start, index - start)
                val line = pending.toString()
                pending.setLength(0)
                callback(line)
            }
            start = index + 1
        }
        pending.append(chars, start, end - start)
    }

    fun feed(text: String, callback: (String) -> Unit) {
        var start = 0
        for (index in text.indices) {
            if (text[index] != '\n') continue
            if (pending.isEmpty()) {
                callback(text.substring(start, index))
            } else {
                pending.append(text, start, index)
                val line = pending.toString()
                pending.setLength(0)
                callback(line)
            }
            start = index + 1
        }
        pending.append(text, start, text.length)
    }
}
