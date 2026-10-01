package com.openminis.app.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownParserPerformanceTest {
    @Test
    fun `long task item continuation retains all lines and checked flag`() {
        val lines = List(10_000) { "continuation $it" }
        val markdown = "- [x] first\n" + lines.joinToString("\n") { "  $it" } + "\n- last"
        val block = MarkdownParser.parse(markdown).single() as MarkdownParser.Block.BulletList
        assertEquals(2, block.items.size)
        assertEquals(true, block.items[0].checked)
        assertEquals("first\n" + lines.joinToString("\n"), block.items[0].content)
        assertEquals(MarkdownParser.ListItem("last"), block.items[1])
    }

    @Test
    fun `no math fast path keeps block parser output identical`() {
        val markdown = "# Heading\n\n- [ ] todo\n  continued\n- other\n\n" +
            "| a | b |\n| :- | -: |\n| one | two |\n\n```kotlin\nval x = 1\n```\n\n" +
            "![clip](minis://workspace/movie.mp4)\n\nplain prose"
        val result = MarkdownParser.parseWithMath(markdown)
        assertEquals(MarkdownParser.parse(markdown), result.blocks)
        assertTrue(result.mathSpans.isEmpty())
    }

    @Test
    fun `all math delimiters still extract while inline code stays literal`() {
        val markdown = "${'$'}x${'$'} and \\(y\\)\n\n${'$'}${'$'}z^2${'$'}${'$'}\n\n\\[a+b\\]\n\n`literal ${'$'}q${'$'}`"
        val result = MarkdownParser.parseWithMath(markdown)
        assertEquals(listOf("x", "y", "z^2", "a+b"), result.mathSpans.map { it.latex })
        assertTrue(result.blocks.any { it is MarkdownParser.Block.Paragraph && it.content.contains("`literal ${'$'}q${'$'}`") })
    }
}
