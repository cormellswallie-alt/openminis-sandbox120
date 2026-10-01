package com.openminis.app.ui.chat

/** Finds the newest safe blank line in one pass, including loose-list lookahead. */
internal object MarkdownBoundaryScanner {
    private val bullet = Regex("^[-*+]\\s+.*")
    private val numbered = Regex("^\\d+[.)]\\s+.*")

    private fun listContinuation(line: String): Boolean = line.isNotEmpty() &&
        (line[0] == ' ' || line[0] == '\t' || bullet.matches(line) || numbered.matches(line))

    fun stablePrefixEnd(content: String, searchFrom: Int): Int {
        var best = searchFrom
        var pendingListBoundary = -1
        var fenceChar = '\u0000'
        var fenceLength = 0
        var maths = 0
        var bracketMathOpen = false
        var lastNonBlank: String? = null
        var start = 0
        while (start < content.length) {
            val newline = content.indexOf('\n', start)
            val end = if (newline < 0) content.length else newline
            val line = content.substring(start, end)
            if (line.isNotBlank()) {
                if (pendingListBoundary >= 0) {
                    if (newline >= 0 && !listContinuation(line)) best = pendingListBoundary
                    pendingListBoundary = -1
                }
                lastNonBlank = line
            }
            val trimmed = line.trimStart(' ', '\t')
            val marker = trimmed.firstOrNull()
            var run = 0
            if (marker == '`' || marker == '~') {
                while (run < trimmed.length && trimmed[run] == marker) run++
            }
            if (fenceLength > 0) {
                if (marker == fenceChar && run >= fenceLength && trimmed.substring(run).isBlank()) {
                    fenceLength = 0
                }
            } else if (run >= 3) {
                fenceChar = marker!!
                fenceLength = run
            } else if (bracketMathOpen) {
                if (line.contains("\\]")) bracketMathOpen = false
            } else if (trimmed.startsWith("\\[")) {
                bracketMathOpen = trimmed.indexOf("\\]", 2) < 0
            } else if (trimmed.startsWith("$$")) {
                if (trimmed.indexOf("$$", 2) < 0) maths++
            }
            // The old backwards search considered every pair, including overlapping blank lines.
            if (newline >= searchFrom && newline >= 0 && newline + 1 < content.length &&
                content[newline + 1] == '\n' && fenceLength == 0 && maths % 2 == 0 && !bracketMathOpen
            ) {
                val candidate = newline + 2
                if (lastNonBlank?.let(::listContinuation) == true) pendingListBoundary = candidate
                else best = candidate
            }
            if (newline < 0) break
            start = newline + 1
        }
        return best
    }
}
