package com.openminis.app.ui.terminal.emulator

import org.junit.Assert.*
import org.junit.Test

class TerminalPerformanceTest {
    @Test fun `full feed preserves fixed Unicode color DSR selection and history expectations`() {
        val em = TerminalEmulator(8, 2)
        val responses = mutableListOf<String>()
        em.onResponse = { responses.add(it.toString(Charsets.UTF_8)) }
        val version = em.version.value
        em.feed("AB\u001b[31m中\u001b[0m😀\u001b[6n".toByteArray())
        assertEquals(version + 1, em.version.value)
        assertEquals(listOf("\u001b[1;7R"), responses)
        assertEquals(TerminalColor.Indexed(1), em.activeBuffer.grid[0][2].foreground)
        assertEquals(2, em.activeBuffer.grid[0][2].width)
        assertTrue(em.activeBuffer.grid[0][3].isWideTrailer)
        assertEquals(0x1F600, em.activeBuffer.grid[0][4].char)
        assertEquals("AB中 😀", em.getSelectedText(0, 0, 4, 0))
        val history = TerminalEmulator(8, 2)
        history.feed("one\r\ntwo\r\nthree".toByteArray())
        assertEquals(1, history.primaryBuffer.scrollback.size)
        history.scrollOffset = 1
        assertEquals("one\ntwo", history.getSelectedText(0, 0, 7, 1))
        history.setSelectionRect(0, 0, 2, 1)
        history.feed("\r\nfour".toByteArray())
        assertArrayEquals(intArrayOf(0, 0, 2, 1), history.selectionRect.value)
    }

    @Test fun `same size resize preserves cells but resets region and wrap`() {
        val buf = TerminalBuffer(8, 4)
        val style = CursorStyle()
        buf.writeChar('A'.code, style, true)
        buf.setScrollRegion(2, 3)
        buf.wrapPending = true
        buf.resize(8, 4)
        assertEquals('A'.code, buf.grid[0][0].char)
        assertEquals(0, buf.scrollTop)
        assertEquals(3, buf.scrollBottom)
        assertFalse(buf.wrapPending)
        val row = buf.grid[0]
        buf.scrollUp(1)
        buf.writeChar('B'.code, style, true)
        assertEquals('A'.code, row[0].char)
        assertSame(row, buf.scrollback.first())
    }

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

    @Test fun `random full feeds match byte chunks through resize history and selection`() {
        val random = java.util.Random(117)
        val whole = TerminalEmulator(12, 4)
        val chunked = TerminalEmulator(12, 4)
        val wholeResponses = mutableListOf<String>()
        val chunkedResponses = mutableListOf<String>()
        whole.onResponse = { wholeResponses.add(it.toString(Charsets.UTF_8)) }
        chunked.onResponse = { chunkedResponses.add(it.toString(Charsets.UTF_8)) }
        val tokens = listOf("abc 123", "中文😀", "\r\n", "\u001b[31mred\u001b[0m",
            "\u001b[2J", "\u001b[2S", "\u001b[1T", "\u001b[2;3H", "\u001b[2L", "\u001b[2P",
            "\u001b[?1049hALT\u001b[?1049l", "\u001b[6n", "\u001b]2;title\u0007")
        repeat(500) { iteration ->
            val data = tokens[random.nextInt(tokens.size)].toByteArray()
            whole.feed(data)
            data.forEach { chunked.feed(byteArrayOf(it)) }
            if (iteration % 11 == 0) {
                val cols = 1 + random.nextInt(20); val rows = 1 + random.nextInt(8)
                whole.resize(cols, rows); chunked.resize(cols, rows)
            }
            for ((a, b) in listOf(whole.primaryBuffer to chunked.primaryBuffer,
                whole.alternateBuffer to chunked.alternateBuffer)) {
                assertEquals(a.cursorCol, b.cursorCol); assertEquals(a.cursorRow, b.cursorRow)
                assertEquals(a.wrapPending, b.wrapPending)
                assertEquals(a.scrollTop, b.scrollTop); assertEquals(a.scrollBottom, b.scrollBottom)
                assertEquals(a.scrollback.size, b.scrollback.size)
                for (r in a.grid.indices) assertArrayEquals(a.grid[r], b.grid[r])
                for (r in a.scrollback.indices) assertArrayEquals(a.scrollback[r], b.scrollback[r])
            }
            val offset = random.nextInt(whole.activeBuffer.scrollback.size + 1)
            whole.scrollOffset = offset; chunked.scrollOffset = offset
            assertEquals(whole.getSelectedText(0, 0, whole.cols - 1, whole.rows - 1),
                chunked.getSelectedText(0, 0, chunked.cols - 1, chunked.rows - 1))
            assertEquals(wholeResponses, chunkedResponses)
            assertEquals(whole.title, chunked.title)
            assertEquals(whole.isAlternateActive, chunked.isAlternateActive)
        }
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
