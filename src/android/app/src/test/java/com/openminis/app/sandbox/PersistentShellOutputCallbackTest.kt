package com.openminis.app.sandbox

import org.junit.Assert.*
import org.junit.Test

class PersistentShellOutputCallbackTest {
    @Test
    fun `append precedes chunk callback and chunk precedes every line callback`() {
        val output = StringBuilder("previous:")
        val events = mutableListOf<String>()
        val callbacks = PersistentOutputCallbacks(output,
            { events += "chunk:$it:output=$output" },
            { events += "line:$it:output=$output" },
        )
        callbacks.emit("one\ntwo")
        callbacks.emit(" end\n")
        assertEquals("previous:one\ntwo end\n", output.toString())
        assertEquals(listOf(
            "chunk:one\ntwo:output=previous:one\ntwo",
            "line:one:output=previous:one\ntwo",
            "line:two:output=previous:one\ntwo",
            "chunk: end\n:output=previous:one\ntwo end\n",
            "line: end:output=previous:one\ntwo end\n",
        ), events)
    }

    @Test
    fun `chunk input remains exact while legacy lines remove CR and omit blanks`() {
        val output = StringBuilder()
        val chunks = mutableListOf<String>()
        val lines = mutableListOf<String>()
        val callbacks = PersistentOutputCallbacks(output, { chunks += it }, { lines += it })
        val visible = "\r\nfirst\r\n\npart\rial"
        callbacks.emit(visible)
        callbacks.emit(" rest\n\r\n")
        callbacks.emit("")
        assertEquals(listOf(visible, " rest\n\r\n", ""), chunks)
        assertEquals(visible + " rest\n\r\n", output.toString())
        assertEquals(listOf("first", "partial", " rest"), lines)
    }

    @Test
    fun `null callbacks still accumulate output and line only remains supported`() {
        val output = StringBuilder()
        PersistentOutputCallbacks(output, null, null).emit("raw\r\n")
        val lines = mutableListOf<String>()
        PersistentOutputCallbacks(output, null, { lines += it }).emit("next\nfragment")
        assertEquals("raw\r\nnext\nfragment", output.toString())
        assertEquals(listOf("next", "fragment"), lines)
    }

    @Test
    fun `chunk only callback receives empty and unterminated chunks`() {
        val output = StringBuilder()
        val chunks = mutableListOf<String>()
        val callbacks = PersistentOutputCallbacks(output, { chunks += it }, null)
        callbacks.emit("")
        callbacks.emit("prompt> ")
        assertEquals(listOf("", "prompt> "), chunks)
        assertEquals("prompt> ", output.toString())
    }

    @Test
    fun `chunk failure propagates after append and prevents line delivery`() {
        val output = StringBuilder()
        val lines = mutableListOf<String>()
        val failure = IllegalStateException("chunk failed")
        val callbacks = PersistentOutputCallbacks(output, { throw failure }, { lines += it })
        try {
            callbacks.emit("saved\n")
            fail("callback exception must reach the reader")
        } catch (actual: IllegalStateException) {
            assertSame(failure, actual)
        }
        assertEquals("saved\n", output.toString())
        assertTrue(lines.isEmpty())
    }

    @Test
    fun `line failure propagates after chunk delivery and stops remaining lines`() {
        val output = StringBuilder()
        val events = mutableListOf<String>()
        val failure = IllegalArgumentException("line failed")
        val callbacks = PersistentOutputCallbacks(output,
            { events += "chunk:$it" },
            { events += "line:$it"; throw failure },
        )
        try {
            callbacks.emit("first\nsecond\n")
            fail("callback exception must reach the reader")
        } catch (actual: IllegalArgumentException) {
            assertSame(failure, actual)
        }
        assertEquals("first\nsecond\n", output.toString())
        assertEquals(listOf("chunk:first\nsecond\n", "line:first"), events)
    }
}
