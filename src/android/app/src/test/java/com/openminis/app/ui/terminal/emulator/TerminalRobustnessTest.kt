package com.openminis.app.ui.terminal.emulator

import org.junit.Assert.*
import org.junit.Test

class TerminalRobustnessTest {
    private fun parse(bytes: ByteArray, length: Int = bytes.size): List<ParsedAction> {
        val out = mutableListOf<ParsedAction>()
        AnsiParser().feed(bytes, length, out::add)
        return out
    }
    @Test(timeout = 2500) fun `huge scroll count performs at most one screen scroll`() {
        val em = TerminalEmulator(4, 2)
        em.feed("abc\r\ndef".toByteArray())
        em.feed("\u001b[999999999999999999999999S".toByteArray())
        assertTrue(em.primaryBuffer.scrollback.size <= 2)
        assertEquals(2, em.visibleLines().size)
    }
    @Test fun `long CSI sequence has bounded parameter count and saturated values`() {
        val text = "\u001b[" + List(200) { "99999999999999999999999" }.joinToString(";") + "m"
        val csi = parse(text.toByteArray()).filterIsInstance<ParsedAction.CsiDispatch>().single()
        assertTrue(csi.params.size <= 32)
        assertTrue(csi.params.all { it in 0..1_000_000 })
    }
    @Test fun `oversized OSC is dropped and normal text resumes`() {
        val actions = parse(("\u001b]2;" + "x".repeat(40_000) + "\u0007OK").toByteArray())
        assertTrue(actions.none { it is ParsedAction.OscDispatch })
        assertEquals("OK", actions.filterIsInstance<ParsedAction.Printable>().map { it.codePoint.toChar() }.joinToString(""))
    }
    @Test fun `invalid Unicode cannot reach the renderer as invalid codepoint`() {
        val invalid = listOf(byteArrayOf(0xF4.toByte(),0xBF.toByte(),0xBF.toByte(),0xBF.toByte()),
            byteArrayOf(0xED.toByte(),0xA0.toByte(),0x80.toByte()), byteArrayOf(0xC0.toByte(),0xAF.toByte()))
        invalid.forEach { data ->
            val cp = parse(data).filterIsInstance<ParsedAction.Printable>().single().codePoint
            assertEquals(0xFFFD, cp)
        }
    }
    @Test fun `UTF8 continuation across chunks preserves CJK and supplementary text`() {
        val p=AnsiParser();val out=mutableListOf<ParsedAction>();val data="中文😀".toByteArray()
        data.forEach { p.feed(byteArrayOf(it),1,out::add) }
        assertEquals(listOf('中'.code,'文'.code,0x1F600),out.filterIsInstance<ParsedAction.Printable>().map { it.codePoint })
    }
    @Test fun `oversized feed length and narrow wide-character screen remain safe`() {
        assertEquals(1,parse(byteArrayOf(65),999).size)
        val em=TerminalEmulator(1,1);em.feed("中😀".toByteArray())
        assertTrue(em.cursorPos().first in 0 until 1)
        assertEquals(1,em.visibleLines().single().size)
    }
    @Test(timeout=3000) fun `mixed randomized bytes and resizes stay bounded`() {
        val em=TerminalEmulator(20,8);val random=java.util.Random(48)
        repeat(150) {
            val bytes=ByteArray(256);random.nextBytes(bytes);em.feed(bytes)
            em.resize(1+random.nextInt(24),1+random.nextInt(12))
            val (c,r)=em.cursorPos();assertTrue(c in 0 until em.cols);assertTrue(r in 0 until em.rows)
        }
        assertTrue(em.primaryBuffer.scrollback.size<=2000)
    }
}
