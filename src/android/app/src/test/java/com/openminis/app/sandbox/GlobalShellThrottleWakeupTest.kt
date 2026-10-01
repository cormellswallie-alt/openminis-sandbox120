package com.openminis.app.sandbox

import java.util.PriorityQueue
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.coroutines.CoroutineContext

/** Real core coroutines, with an explicit event clock rather than wall-clock deadlines. */
@OptIn(InternalCoroutinesApi::class, ExperimentalCoroutinesApi::class)
class GlobalShellThrottleWakeupTest {
    private class Events : CoroutineDispatcher(), Delay {
        private data class Event(val at: Long, val order: Long, val task: Runnable, var cancelled: Boolean = false)
        private val events = PriorityQueue<Event>(compareBy<Event> { it.at }.thenBy { it.order })
        var now = 0L
        private var order = 0L
        override fun dispatch(context: CoroutineContext, block: Runnable) { enqueue(0, block) }
        private fun enqueue(delay: Long, block: Runnable): Event = Event(now + delay, order++, block).also { events.add(it) }
        override fun scheduleResumeAfterDelay(timeMillis: Long, continuation: CancellableContinuation<Unit>) {
            val event = enqueue(timeMillis, Runnable { continuation.resume(Unit) {} })
            continuation.invokeOnCancellation { event.cancelled = true }
        }
        override fun invokeOnTimeout(timeMillis: Long, block: Runnable, context: CoroutineContext): DisposableHandle {
            val event = enqueue(timeMillis, block)
            return DisposableHandle { event.cancelled = true }
        }
        fun advanceTo(target: Long) {
            require(target >= now)
            while (events.isNotEmpty() && events.peek().at <= target) {
                val event = events.remove()
                now = event.at
                if (!event.cancelled) event.task.run()
            }
            now = target
        }
    }

    private fun scenario(block: (Events, CoroutineScope) -> Unit) {
        val oldCounter = GlobalShellThrottle.processCounter
        val oldClock = GlobalShellThrottle.clock
        val events = Events()
        val job = Job()
        val scope = CoroutineScope(job + events)
        try {
            assertEquals(0, GlobalShellThrottle.runningCount)
            assertEquals(0, GlobalShellThrottle.waitingCount)
            GlobalShellThrottle.clock = { events.now }
            block(events, scope)
        } finally {
            job.cancel()
            events.advanceTo(events.now)
            GlobalShellThrottle.processCounter = oldCounter
            GlobalShellThrottle.clock = oldClock
            assertEquals("running reservations leaked", 0, GlobalShellThrottle.runningCount)
            assertEquals("queue entries leaked", 0, GlobalShellThrottle.waitingCount)
        }
    }

    @Test fun `release admits FIFO waiters before the next poll`() = scenario { events, scope ->
        GlobalShellThrottle.processCounter = { 40 }
        val release = CompletableDeferred<Unit>()
        scope.launch { GlobalShellThrottle.run("holder") { release.await() } }
        events.advanceTo(0)
        val admitted = mutableListOf<Pair<Int, Long>>()
        (1..3).forEach { id -> scope.launch {
            GlobalShellThrottle.run("waiter-$id") { admitted.add(id to events.now) }
        } }
        events.advanceTo(75)
        assertEquals(3, GlobalShellThrottle.waitingCount)
        assertTrue(admitted.isEmpty())
        release.complete(Unit)
        events.advanceTo(75)
        assertEquals(listOf(1 to 75L, 2 to 75L, 3 to 75L), admitted)
        println("release at 75ms: FIFO admissions=$admitted; old poll deadline=250ms")
    }

    @Test fun `external process changes are checked every 250ms without release`() = scenario { events, scope ->
        var live = 40
        val checks = mutableListOf<Long>()
        GlobalShellThrottle.processCounter = { checks.add(events.now); live }
        scope.launch { GlobalShellThrottle.run("holder") { awaitCancellation() } }
        events.advanceTo(0)
        var admittedAt: Long? = null
        scope.launch { GlobalShellThrottle.run("waiter") { admittedAt = events.now } }
        events.advanceTo(249)
        assertNull(admittedAt)
        events.advanceTo(250)
        assertNull(admittedAt)
        live = 0
        events.advanceTo(499)
        assertNull(admittedAt)
        events.advanceTo(500)
        assertEquals(500L, admittedAt)
        assertEquals(listOf(0L, 0L, 250L, 500L), checks)
        println("no release: process checks=$checks; admission=$admittedAt ms")
    }

    @Test fun `cancelled FIFO head and follower leave no reservations`() = scenario { events, scope ->
        GlobalShellThrottle.processCounter = { 40 }
        val release = CompletableDeferred<Unit>()
        scope.launch { GlobalShellThrottle.run("holder") { release.await() } }
        events.advanceTo(0)
        val head = scope.launch { GlobalShellThrottle.run("cancel-head") { error("must not run") } }
        val follower = scope.launch { GlobalShellThrottle.run("cancel-follower") { error("must not run") } }
        var ran = false
        scope.launch { GlobalShellThrottle.run("survivor") { ran = true } }
        events.advanceTo(75)
        head.cancel(); follower.cancel()
        events.advanceTo(75)
        assertEquals(1, GlobalShellThrottle.waitingCount)
        assertEquals(1, GlobalShellThrottle.runningCount)
        release.complete(Unit)
        events.advanceTo(75)
        assertTrue(ran)
        assertEquals(0, GlobalShellThrottle.runningCount)
        assertEquals(0, GlobalShellThrottle.waitingCount)
    }

    @Test fun `start failure keeps its backoff despite release notifications`() = scenario { events, scope ->
        GlobalShellThrottle.processCounter = { 0 }
        val attempts = mutableListOf<Long>()
        scope.launch { GlobalShellThrottle.run("retry", isStartFailure = { it == 1 }) {
            attempts.add(events.now); attempts.size
        } }
        events.advanceTo(75)
        scope.launch { GlobalShellThrottle.run("other") { Unit } }
        events.advanceTo(999)
        assertEquals(listOf(0L), attempts)
        events.advanceTo(1000)
        assertEquals(listOf(0L, 1000L), attempts)
    }
}
