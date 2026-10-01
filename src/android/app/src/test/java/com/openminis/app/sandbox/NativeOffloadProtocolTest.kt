package com.openminis.app.sandbox

import java.io.*
import java.lang.reflect.InvocationTargetException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class NativeOffloadProtocolTest {
    private fun call(name: String, vararg args: Any): Any? {
        val method = NativeOffloadServer::class.java.declaredMethods.single { it.name == name }
        method.isAccessible = true
        return method.invoke(NativeOffloadServer, *args)
    }

    @Test fun `little endian integers match native bytes including signed exits`() {
        for (value in listOf(0, 1, -1, 124, 130, Int.MIN_VALUE, Int.MAX_VALUE, 0x46464F4E)) {
            val bytes = ByteArrayOutputStream()
            call("writeLEInt", DataOutputStream(bytes), value)
            val expected = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
            assertArrayEquals(expected, bytes.toByteArray())
            assertEquals(value, call("readLEInt", DataInputStream(ByteArrayInputStream(expected))))
        }
    }

    @Test fun `strings preserve UTF8 length framing and empty values`() {
        for (value in listOf("", "abc", "路径😀\u0000尾")) {
            val bytes = ByteArrayOutputStream()
            call("writeLEString", DataOutputStream(bytes), value)
            val encoded = bytes.toByteArray()
            assertEquals(value.toByteArray(Charsets.UTF_8).size,
                ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN).int)
            assertEquals(value, call("readLEString", DataInputStream(ByteArrayInputStream(encoded))))
        }
    }

    @Test fun `truncated integer raises EOF rather than accepting partial frame`() {
        for (size in 0..3) {
            try {
                call("readLEInt", DataInputStream(ByteArrayInputStream(ByteArray(size))))
                fail("accepted $size bytes")
            } catch (e: InvocationTargetException) { assertTrue(e.cause is EOFException) }
        }
    }

    @Test fun `oversized and negative strings retain validation`() {
        for (size in listOf(-1, (1 shl 20) + 1)) {
            val bytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(size).array()
            try {
                call("readLEString", DataInputStream(ByteArrayInputStream(bytes)))
                fail("accepted $size bytes")
            } catch (e: InvocationTargetException) { assertTrue(e.cause is IllegalStateException) }
        }
    }
}
