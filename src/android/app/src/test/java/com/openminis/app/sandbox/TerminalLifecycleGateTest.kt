package com.openminis.app.sandbox

import org.junit.Assert.*
import org.junit.Test

class TerminalLifecycleGateTest {
    @Test fun `double start while booting cannot allocate two PTYs`() {
        val gate=TerminalLifecycleGate();val token=gate.begin()!!
        assertNull(gate.begin());assertTrue(gate.isCurrent(token))
    }
    @Test fun `stopped boot and queued input cannot touch next PTY`() {
        val gate=TerminalLifecycleGate();val old=gate.begin()!!
        assertEquals(old,gate.token());gate.invalidate();val next=gate.begin()!!
        assertFalse(gate.isCurrent(old));assertFalse(gate.finish(old))
        assertTrue(gate.isCurrent(next));assertEquals(next,gate.token())
    }
    @Test fun `old wait completion does not stop a restarted session`() {
        val gate=TerminalLifecycleGate();val first=gate.begin()!!;assertTrue(gate.finish(first))
        val second=gate.begin()!!;assertFalse(gate.finish(first));assertTrue(gate.isCurrent(second))
    }
    @Test fun `parallel start requests accept exactly one generation`() {
        val gate=TerminalLifecycleGate();val count=java.util.concurrent.atomic.AtomicInteger()
        val threads=(1..20).map { Thread { if(gate.begin()!=null)count.incrementAndGet() } }
        threads.forEach(Thread::start);threads.forEach(Thread::join)
        assertEquals(1,count.get())
    }
}
