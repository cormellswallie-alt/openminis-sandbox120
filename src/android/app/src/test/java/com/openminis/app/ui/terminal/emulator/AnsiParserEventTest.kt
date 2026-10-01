package com.openminis.app.ui.terminal.emulator

import org.junit.Assert.assertEquals
import org.junit.Test

class AnsiParserEventTest {
    @Test fun `retained ASCII events survive another parser and reset`() {
        val parser = AnsiParser()
        val retained = mutableListOf<ParsedAction>()
        parser.feed("A ~\r\n".toByteArray(), 5, retained::add)
        AnsiParser().feed("Z\t!".toByteArray(), 3) { }
        parser.reset()
        parser.feed("B".toByteArray(), 1) { }
        assertEquals(listOf(
            ParsedAction.Printable(65), ParsedAction.Printable(32),
            ParsedAction.Printable(126), ParsedAction.ControlChar(13),
            ParsedAction.ControlChar(10),
        ), retained)
    }

    @Test fun `ASCII boundaries escape and split Unicode produce exact events`() {
        val parser = AnsiParser()
        val retained = mutableListOf<ParsedAction>()
        val bytes = "\u001b[31m ~\u007f中😀\r\n".toByteArray()
        bytes.forEach { parser.feed(byteArrayOf(it), 1, retained::add) }
        val csi = retained.removeAt(0) as ParsedAction.CsiDispatch
        assertEquals(listOf(31), csi.params.toList())
        assertEquals('m', csi.finalByte)
        assertEquals(listOf(
            ParsedAction.Printable(32), ParsedAction.Printable(126),
            ParsedAction.Printable(0x4E2D), ParsedAction.Printable(0x1F600),
            ParsedAction.ControlChar(13), ParsedAction.ControlChar(10),
        ), retained)
    }
}
