package com.openminis.app.sandbox

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Test

class ShellLineBufferTest {
    @Test fun `empty lines CR unicode and incomplete tail retain fresh semantics`() {
        val lines = ArrayList<String>()
        val buffer = ShellLineBuffer()
        for (chunk in listOf("", "\n甲", "😀\r", "\n\n尾", "巴")) buffer.feed(chunk, lines::add)
        assertEquals(listOf("", "甲😀\r", ""), lines)
        buffer.feed("\n", lines::add)
        assertEquals(listOf("", "甲😀\r", "", "尾巴"), lines)
    }

    @Test fun `random chunk boundaries match whole output lines`() {
        val random = Random(41)
        repeat(100) {
            val text = buildString { repeat(2000) { append("ab\n\r甲😀"[random.nextInt(7)]) } }
            val actual = ArrayList<String>()
            val buffer = ShellLineBuffer()
            var offset = 0
            while (offset < text.length) {
                val end = minOf(text.length, offset + random.nextInt(1, 100))
                buffer.feed(text.substring(offset, end), actual::add)
                offset = end
            }
            val expected = text.split('\n').dropLast(1)
            assertEquals(expected, actual)
        }
    }

    @Test fun `large unfinished line survives many chunks without repeated scans`() {
        val buffer = ShellLineBuffer()
        val actual = ArrayList<String>()
        repeat(1024) { buffer.feed("x".repeat(1024), actual::add) }
        assertEquals(emptyList<String>(), actual)
        buffer.feed("\nnext\n", actual::add)
        assertEquals(listOf("x".repeat(1024 * 1024), "next"), actual)
    }
}
