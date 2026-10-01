package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

class MarkdownBoundaryScannerTest {
    private fun reference(content: String, from: Int): Int {
        var index = content.lastIndexOf("\n\n")
        while (index >= from) {
            if (isSealedBoundary(content, index + 2)) return index + 2
            if (index == 0) break
            index = content.lastIndexOf("\n\n", index - 1)
        }
        return from
    }

    @Test
    fun `single pass matches conservative boundary rules on edited and growing input`() {
        val random = Random(73)
        val lines = listOf("", "", "text", "- item", "1. item", "  continuation", "\tmore",
            "```", "```kotlin", "~~~~", "~~~", "$$", "${'$'}${'$'}x${'$'}${'$'}", "\\[", "\\]", "\\[x\\]", " > quote", "\r")
        repeat(400) {
            val text = List(random.nextInt(1, 45)) { lines.random(random) }.joinToString("\n")
            val cuts = listOf(text.length, random.nextInt(text.length + 1))
            for (cut in cuts) {
                val content = text.take(cut)
                for (from in listOf(0, random.nextInt(content.length + 1))) {
                    assertEquals("from=$from content=$content", reference(content, from),
                        MarkdownBoundaryScanner.stablePrefixEnd(content, from))
                }
            }
        }
    }

    @Test
    fun `thousands of blank lines inside an open fence preserve the intro boundary`() {
        val intro = "intro\n\n"
        val body = intro + "```kotlin\n" + "val x = 1\n\n".repeat(10_000)
        assertEquals(intro.length, MarkdownBoundaryScanner.stablePrefixEnd(body, 0))
        val closed = body + "```\n\ntail"
        assertEquals(closed.length - 4, MarkdownBoundaryScanner.stablePrefixEnd(closed, 0))
    }

    @Test
    fun `loose list needs a complete non list line before freezing`() {
        val text = "intro\n\n- one\n\n- two\n\n"
        assertEquals(7, MarkdownBoundaryScanner.stablePrefixEnd(text + "next", 0))
        assertEquals(text.length, MarkdownBoundaryScanner.stablePrefixEnd(text + "next\n", 0))
    }
}
