package com.openminis.app.sandbox

import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class ShellArrayOutputTest {
    @Test fun `array slices and reused storage match string chunks`() {
        val old = ShellLineBuffer()
        val new = ShellLineBuffer()
        val expected = ArrayList<String>()
        val actual = ArrayList<String>()
        val random = kotlin.random.Random(71)
        val chars = CharArray(113)
        repeat(3000) {
            val n = random.nextInt(0, 100)
            val text = buildString { repeat(n) { append("ab\n\r甲😀"[random.nextInt(7)]) } }
            text.toCharArray(chars, 7)
            old.feed(text, expected::add)
            new.feed(chars, 7, n, actual::add)
            chars.fill('!')
        }
        old.feed("\n", expected::add)
        new.feed(charArrayOf('\n'), 0, 1, actual::add)
        assertEquals(expected, actual)
    }

    @Test fun `invalid slices are rejected before changing pending output`() {
        val buffer = ShellLineBuffer()
        for ((offset, length) in listOf(-1 to 1, 0 to -1, 2 to 1, 1 to Int.MAX_VALUE)) {
            try { buffer.feed(charArrayOf('a'), offset, length) {}; fail("invalid slice") }
            catch (_: IllegalArgumentException) {}
        }
    }

    @Test fun `real shell pipe preserves unicode large output and exit status`() {
        val p = ProcessBuilder("/bin/sh", "-c", "i=0; while [ \"\$i\" -lt 5000 ]; do printf '甲😀abc\\n'; i=\$((i+1)); done; exit 7").start()
        val output = StringBuilder()
        val lines = ArrayList<String>()
        val buffer = ShellLineBuffer()
        val chars = CharArray(13)
        p.inputStream.reader(StandardCharsets.UTF_8).use { reader ->
            while (true) {
                val n = reader.read(chars)
                if (n < 0) break
                output.append(chars, 0, n)
                buffer.feed(chars, 0, n, lines::add)
            }
        }
        assertTrue(p.waitFor(10, TimeUnit.SECONDS))
        assertEquals(7, p.exitValue())
        assertEquals("甲😀abc\n".repeat(5000), output.toString())
        assertEquals(List(5000) { "甲😀abc" }, lines)
    }
}
