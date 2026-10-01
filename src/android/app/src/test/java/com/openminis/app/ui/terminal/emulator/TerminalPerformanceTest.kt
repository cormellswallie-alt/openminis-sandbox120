package com.openminis.app.ui.terminal.emulator

import org.junit.Assert.*
import org.junit.Test

class TerminalPerformanceTest {
    @Test fun `ANSI and UTF8 parsing matches whole input across arbitrary chunk boundaries`() {
        val random = java.util.Random(731)
        val input = ("abc\r\n\u001b[31m中文😀\u001b[0m\u001b]2;title\u0007" +
            "\u001b[?1049hALT\u001b[?1049l").toByteArray()
        val regular = AnsiParser()
        val fast = AnsiParser()
        val expected = mutableListOf<String>()
        val actual = mutableListOf<String>()
        fun describe(action: ParsedAction): String = when (action) {
            is ParsedAction.CsiDispatch -> "CSI:${action.params.contentToString()}:${action.intermediate}:${action.finalByte}"
            else -> action.toString()
        }
        repeat(100) {
            regular.feed(input, input.size) { expected.add(describe(it)) }
            var offset = 0
            while (offset < input.size) {
                val end = minOf(input.size, offset + 1 + random.nextInt(8))
                val chunk = input.copyOfRange(offset, end)
                fast.feed(chunk, chunk.size) { actual.add(describe(it)) }
                offset = end
            }
        }
        assertEquals(expected, actual)
    }

    private fun filled(): TerminalBuffer = TerminalBuffer(8, 6, 3).also { buffer ->
        for (r in 0 until buffer.rows) for (c in 0 until buffer.cols) {
            buffer.grid[r][c] = TerminalCell('A'.code + r * 8 + c)
        }
    }

    @Test fun `bulk row moves match repeated single steps including history and regions`() {
        for (op in 0..3) for (count in listOf(0, 1, 2, 4, 999999)) {
            val bulk = filled()
            val single = filled()
            for (buffer in listOf(bulk, single)) {
                buffer.setScrollRegion(2, 5)
                buffer.moveCursorTo(2, 2)
            }
            fun apply(buffer: TerminalBuffer, n: Int) {
                when (op) {
                    0 -> buffer.scrollUp(n)
                    1 -> buffer.scrollDown(n)
                    2 -> buffer.insertLines(n)
                    3 -> buffer.deleteLines(n)
                }
            }
            apply(bulk, count)
            val limit = if (op < 2) 4 else 3
            repeat(minOf(count, limit)) { apply(single, 1) }
            for (r in 0 until bulk.rows) assertArrayEquals(single.grid[r], bulk.grid[r])
            assertEquals(single.scrollback.size, bulk.scrollback.size)
            for (r in single.scrollback.indices) assertArrayEquals(single.scrollback[r], bulk.scrollback[r])
            // History must not alias a newly blank screen row.
            bulk.grid[bulk.scrollBottom][0] = TerminalCell('!'.code)
            assertTrue(bulk.scrollback.none { it === bulk.grid[bulk.scrollBottom] })
        }
    }

    @Test fun `bulk character shifts match repeated steps at row boundaries`() {
        for (insert in listOf(true, false)) for (col in 0..7) for (count in listOf(0, 1, 3, 8, 999999)) {
            val bulk = filled()
            val single = filled()
            bulk.moveCursorTo(col, 2)
            single.moveCursorTo(col, 2)
            fun apply(buffer: TerminalBuffer, n: Int) {
                if (insert) buffer.insertCharacters(n) else buffer.deleteCharacters(n)
            }
            apply(bulk, count)
            repeat(minOf(count, 8 - col)) { apply(single, 1) }
            for (r in 0 until bulk.rows) assertArrayEquals(single.grid[r], bulk.grid[r])
        }
    }

    @Test fun `borrowed viewport matches padded snapshots after resize clear and buffer switch`() {
        val em = TerminalEmulator(8, 3)
        em.feed((0..15).joinToString("\r\n") { "row$it" }.toByteArray())
        for (width in listOf(4, 12, 1, 8)) {
            em.resize(width, 4)
            for (offset in 0..em.activeBuffer.scrollback.size) {
                em.scrollOffset = offset
                val expected = em.visibleLines()
                for (r in 0 until em.rows) {
                    val borrowed = em.visibleLineAt(r)
                    for (c in 0 until em.cols) {
                        assertEquals(expected[r][c], borrowed?.getOrNull(c) ?: TerminalCell.BLANK)
                    }
                }
            }
        }
        for (sequence in listOf("\u001b[?1049hALT", "\u001b[?1049l", "\u001bc")) {
            em.feed(sequence.toByteArray())
            val expected = em.visibleLines()
            for (r in 0 until em.rows) assertArrayEquals(expected[r], em.visibleLineAt(r))
        }
        assertNull(em.visibleLineAt(-1))
        assertNull(em.visibleLineAt(em.rows))
    }
}
