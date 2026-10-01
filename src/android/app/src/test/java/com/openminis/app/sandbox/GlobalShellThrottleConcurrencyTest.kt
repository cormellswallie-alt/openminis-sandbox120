package com.openminis.app.sandbox

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class GlobalShellThrottleConcurrencyTest {
    @Test fun `real dispatcher burst commits reservations before unlocking admission`() = runBlocking {
        val counter = GlobalShellThrottle.processCounter
        val clock = GlobalShellThrottle.clock
        try {
            GlobalShellThrottle.processCounter = { 0 }
            GlobalShellThrottle.clock = { System.nanoTime() / 1_000_000 }
            val active = AtomicInteger()
            val peak = AtomicInteger()
            val start = CompletableDeferred<Unit>()
            val results = (1..24).map { id -> async(Dispatchers.Default) {
                start.await()
                GlobalShellThrottle.run("parallel-$id") {
                    val n = active.incrementAndGet()
                    peak.accumulateAndGet(n) { a, b -> maxOf(a, b) }
                    try { delay(100); id } finally { active.decrementAndGet() }
                }
            } }
            start.complete(Unit)
            assertEquals((1..24).toList(), results.awaitAll().map { (it as GlobalShellThrottle.Outcome.Ran).value })
            assertTrue("peak=${peak.get()}", peak.get() <= GlobalShellThrottle.PROCESS_BUDGET / GlobalShellThrottle.PROCESSES_PER_COMMAND)
            assertEquals(0, GlobalShellThrottle.runningCount)
            assertEquals(0, GlobalShellThrottle.waitingCount)
        } finally {
            GlobalShellThrottle.processCounter = counter
            GlobalShellThrottle.clock = clock
        }
    }

    @Test fun `cancellation and queue timeout release reservations before the next command`() = runBlocking {
        val counter = GlobalShellThrottle.processCounter
        try {
            GlobalShellThrottle.processCounter = { 40 }
            val started = CompletableDeferred<Unit>()
            val holder = launch {
                GlobalShellThrottle.run("holder") { started.complete(Unit); awaitCancellation() }
            }
            started.await()
            val queued = launch { GlobalShellThrottle.run("cancel-queued") { error("must not run") } }
            while (GlobalShellThrottle.waitingCount == 0) yield()
            queued.cancelAndJoin()
            val outcome = GlobalShellThrottle.run("queue-timeout", maxQueueWaitMs = 25) { error("must not run") }
            assertTrue(outcome is GlobalShellThrottle.Outcome.QueueTimedOut)
            holder.cancelAndJoin()
            assertEquals(0, GlobalShellThrottle.runningCount)
            assertEquals(0, GlobalShellThrottle.waitingCount)
            assertEquals(GlobalShellThrottle.Outcome.Ran(7, 0, false, 0), GlobalShellThrottle.run("after") { 7 })
        } finally { GlobalShellThrottle.processCounter = counter }
    }
}
