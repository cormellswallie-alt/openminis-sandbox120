package com.openminis.app.ui.terminal.emulator

import org.junit.Assert.*
import org.junit.Test

class TerminalSliceInputTest {
    @Test fun slicesPreserveUtf8AnsiAndIgnorePadding() {
        val text = "甲🙂\u001b[31mred\u001b[0m\r\nline\u001b[6n"
        val bytes = text.toByteArray()
        val whole = TerminalEmulator(40, 8)
        val sliced = TerminalEmulator(40, 8)
        val expectedReplies = mutableListOf<String>()
        val replies = mutableListOf<String>()
        whole.onResponse = { expectedReplies.add(it.toString(Charsets.UTF_8)) }
        sliced.onResponse = { replies.add(it.toString(Charsets.UTF_8)) }
        whole.feed(bytes)
        for (byte in bytes) {
            val reusable = byteArrayOf('!'.code.toByte(), byte, '!'.code.toByte())
            sliced.feed(reusable, 1, 1)
            reusable.fill('?'.code.toByte())
        }
        assertEquals(whole.cursorPos(), sliced.cursorPos())
        whole.primaryBuffer.grid.indices.forEach { row ->
            assertArrayEquals(whole.primaryBuffer.grid[row], sliced.primaryBuffer.grid[row])
        }
        assertEquals(expectedReplies, replies)
        assertEquals(bytes.size.toLong(), sliced.version.value)
    }

    @Test fun invalidSliceDoesNotAlterParserOrNotifyUi() {
        val emulator = TerminalEmulator(20, 5)
        for ((offset, length) in listOf(-1 to 1, 0 to -1, 2 to 1, 1 to Int.MAX_VALUE)) {
            try { emulator.feed(byteArrayOf(65), offset, length); fail("Invalid slice") }
            catch (_: IllegalArgumentException) { }
        }
        assertEquals(0L, emulator.version.value)
        emulator.feed(byteArrayOf(65), 0, 1)
        assertEquals(65, emulator.primaryBuffer.grid[0][0].char)
        assertEquals(1L, emulator.version.value)
    }

    @Test fun legacyLengthClampingRemainsCompatible() {
        val parser = AnsiParser()
        val emitted = mutableListOf<ParsedAction>()
        parser.feed(byteArrayOf(65, 66), Int.MAX_VALUE, emitted::add)
        parser.feed(byteArrayOf(67), -1, emitted::add)
        assertEquals(listOf(ParsedAction.Printable(65), ParsedAction.Printable(66)), emitted)
    }
}
