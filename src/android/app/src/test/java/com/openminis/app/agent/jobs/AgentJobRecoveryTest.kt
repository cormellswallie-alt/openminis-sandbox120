package com.openminis.app.agent.jobs

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class AgentJobRecoveryTest {
    @Before fun reset() = AgentJobRegistry.resetForTest()
    @After fun cleanup() = AgentJobRegistry.resetForTest()

    private fun child(parent: String = "P", session: String? = null) = AgentJobRegistry.register(
        title = "research", origin = AgentJobOrigin.TOOL, trigger = AgentJobTrigger.Immediate,
        target = AgentJobTarget.ChildOfCurrent(parent, "tool"), prompt = "work",
        then = AgentJobThen.FollowUpParent(null), runSessionId = session,
    )

    @Test fun `resumed session resolves to active owner then latest finished job`() {
        val old = child(session = "S")
        AgentJobRegistry.finish(old.id, AgentJobState.FAILED, "partial")
        val resumed = child(session = "S")
        assertEquals(resumed.id, AgentJobRegistry.jobForSession("S")?.id)
        assertTrue(AgentJobRegistry.hasActiveRun("S"))
        AgentJobRegistry.markRunning(resumed.id, "S")
        AgentJobRegistry.cancel(resumed.id, "user-stop")
        assertEquals(resumed.id, AgentJobRegistry.jobForSession("S")?.id)
        assertFalse(AgentJobRegistry.hasActiveRun("S"))
    }

    @Test fun `pending child reservation protects loading or retrying session from eviction`() {
        val j = child(session = "S")
        assertTrue(AgentJobRegistry.hasActiveRun("S"))
        AgentJobRegistry.markRunning(j.id, "S")
        assertTrue(AgentJobRegistry.hasActiveRun("S"))
        AgentJobRegistry.finish(j.id, AgentJobState.DONE, "done")
        assertFalse(AgentJobRegistry.hasActiveRun("S"))
    }

    @Test fun `fresh reservation claims child before VM loading and rejects stopped or duplicate ownership`() {
        val reserved = child()
        assertTrue(AgentJobRegistry.claimRunSession(reserved.id, "S"))
        assertTrue(AgentJobRegistry.hasAgentWork("S"))
        assertTrue(AgentJobRegistry.hasAgentWork("P"))
        val duplicate = child()
        assertFalse(AgentJobRegistry.claimRunSession(duplicate.id, "S"))
        AgentJobRegistry.cancel(reserved.id, "stop")
        assertFalse(AgentJobRegistry.hasAgentWork("S"))
        assertFalse(AgentJobRegistry.claimRunSession(reserved.id, "late-S"))
    }

    @Test fun `late startup cannot resurrect a cancelled reservation and is stopped immediately`() {
        val j = child(session = "S")
        val callbacks = AtomicInteger()
        AgentJobRegistry.followUpDispatcher = { _, _, _ -> callbacks.incrementAndGet() }
        AgentJobRegistry.cancel(j.id, "user-stop-before-ready")
        var stops = 0
        AgentJobRegistry.markRunning(j.id, "S") { stops++ }
        AgentJobRegistry.finish(j.id, AgentJobState.DONE, "late success")
        assertEquals(AgentJobState.CANCELLED, AgentJobRegistry.job(j.id)?.state)
        assertEquals(0, AgentJobRegistry.job(j.id)?.firedCount)
        assertEquals(1, stops)
        assertEquals(1, callbacks.get())
        assertEquals(0, AgentJobRegistry.runningChildJobCount)
    }

    @Test fun `markRunning repeated by a watcher does not count an extra firing`() {
        val j = child()
        var stopped = 0
        AgentJobRegistry.markRunning(j.id, "S") { stopped++ }
        AgentJobRegistry.markRunning(j.id, "S") { stopped += 100 }
        assertEquals(1, AgentJobRegistry.job(j.id)?.firedCount)
        AgentJobRegistry.cancel(j.id, "stop")
        assertEquals(1, stopped)
    }

    @Test fun `concurrent finish and Stop produce one terminal event and one callback`() {
        val pool = Executors.newFixedThreadPool(8)
        try {
            repeat(40) {
                val j = child()
                val hooks = AtomicInteger()
                val tabs = AtomicInteger()
                val callbacks = AtomicInteger()
                AgentJobRegistry.markRunning(j.id, "S-$it") { }
                AgentJobRegistry.setCompletionHook(j.id) { hooks.incrementAndGet() }
                AgentJobRegistry.registerTabRelease(j.id) { tabs.incrementAndGet() }
                AgentJobRegistry.followUpDispatcher = { _, _, _ -> callbacks.incrementAndGet() }
                val gate = CountDownLatch(1)
                val tasks = (0 until 8).map { n ->
                    pool.submit {
                        check(gate.await(5, TimeUnit.SECONDS))
                        if (n % 2 == 0) AgentJobRegistry.cancel(j.id, "stop")
                        else AgentJobRegistry.finish(j.id, AgentJobState.DONE, "done")
                    }
                }
                gate.countDown()
                tasks.forEach { it.get(10, TimeUnit.SECONDS) }
                assertFalse(AgentJobRegistry.job(j.id)!!.isActive)
                assertEquals(1, hooks.get())
                assertEquals(1, tabs.get())
                assertEquals(1, callbacks.get())
                assertEquals(0, AgentJobRegistry.runningChildJobCount)
            }
        } finally { pool.shutdownNow() }
    }

    @Test fun `repeated resume enqueue drains only once and does not consume extra backlog slots`() {
        val item = AgentJobRegistry.QueuedDelegation("P", """{"__resume_child":"S"}""", "resume-S")
        assertTrue(AgentJobRegistry.enqueueDelegation(item))
        assertTrue(AgentJobRegistry.enqueueDelegation(item))
        assertEquals(1, AgentJobRegistry.queuedCount("P"))
        var started = 0
        AgentJobRegistry.registerQueuedStarter("P") { started++; true }
        AgentJobRegistry.drainIfStalled()
        AgentJobRegistry.drainIfStalled()
        assertEquals(1, started)
    }

    @Test fun `parent cancel cascade drops backlog before any completion can start it`() {
        val a = child()
        child()
        var started = 0
        AgentJobRegistry.registerQueuedStarter("P") { started++; true }
        AgentJobRegistry.enqueueDelegation(AgentJobRegistry.QueuedDelegation("P", "{}", "q"))
        AgentJobRegistry.cancelAll("P", "parent-deleted")
        assertEquals(0, started)
        assertEquals(0, AgentJobRegistry.queuedCount("P"))
        assertEquals(AgentJobState.CANCELLED, AgentJobRegistry.job(a.id)?.state)
        assertEquals(0, AgentJobRegistry.runningChildJobCount)
    }

    @Test fun `parallel reservations enforce ten slots before provider startup`() {
        val pool = Executors.newFixedThreadPool(20)
        val gate = CountDownLatch(1)
        try {
            val tasks = (0 until 20).map { n -> pool.submit<AgentJob?> {
                check(gate.await(5, TimeUnit.SECONDS))
                AgentJobRegistry.tryRegisterChild(
                    "work-$n", AgentJobOrigin.TOOL, "P", "tool-$n", "task",
                    runSessionId = "child-$n",
                )
            } }
            gate.countDown()
            val accepted = tasks.mapNotNull { it.get(10, TimeUnit.SECONDS) }
            assertEquals(10, accepted.size)
            assertEquals(10, AgentJobRegistry.runningChildJobCount)
            assertTrue(accepted.all { it.state == AgentJobState.PENDING })
            assertFalse(AgentJobRegistry.canStartChildJob)
        } finally { pool.shutdownNow() }
    }

    @Test fun `manual resume cannot reserve the same child twice`() {
        val first = AgentJobRegistry.tryRegisterChild("r", AgentJobOrigin.TOOL, "P", "tool", null, runSessionId = "S", wasResumed = true)
        assertNotNull(first)
        assertNull(AgentJobRegistry.tryRegisterChild("r", AgentJobOrigin.TOOL, "P", "tool", null, runSessionId = "S", wasResumed = true))
        assertEquals(1, AgentJobRegistry.runningChildJobCount)
    }

    @Test fun `cancelled scope whose runner body never starts releases its reservation`() = kotlinx.coroutines.test.runTest {
        val j = child(session = "S")
        val cancelledScopeJob = kotlinx.coroutines.Job().also { it.cancel() }
        val scope = kotlinx.coroutines.CoroutineScope(coroutineContext + cancelledScopeJob)
        var entered = false
        val runner = scope.launch { entered = true }
        AgentJobRegistry.watchRunnerCompletion(j.id, runner)
        runner.join()
        assertFalse(entered)
        assertEquals(AgentJobState.CANCELLED, AgentJobRegistry.job(j.id)?.state)
        assertEquals(0, AgentJobRegistry.runningChildJobCount)
    }

    @Test fun `watcher cancellation stops child and duplicate watcher closure stays idempotent`() {
        val j = child(session = "S")
        val runner = kotlinx.coroutines.Job()
        var stops = 0
        var callbacks = 0
        AgentJobRegistry.markRunning(j.id, "S") { stops++ }
        AgentJobRegistry.followUpDispatcher = { _, _, _ -> callbacks++ }
        AgentJobRegistry.watchRunnerCompletion(j.id, runner)
        AgentJobRegistry.watchRunnerCompletion(j.id, runner)
        runner.cancel()
        assertEquals(AgentJobState.CANCELLED, AgentJobRegistry.job(j.id)?.state)
        assertEquals(1, stops)
        assertEquals(1, callbacks)
    }

    @Test fun `watcher abnormal exit releases slot as failed and never overwrites normal finish`() {
        val j = child(session = "S")
        val runner = kotlinx.coroutines.Job()
        AgentJobRegistry.watchRunnerCompletion(j.id, runner)
        runner.complete()
        assertEquals(AgentJobState.FAILED, AgentJobRegistry.job(j.id)?.state)
        val done = child(session = "done-S")
        val successfulRunner = kotlinx.coroutines.Job()
        AgentJobRegistry.watchRunnerCompletion(done.id, successfulRunner)
        AgentJobRegistry.finish(done.id, AgentJobState.DONE, "answer")
        successfulRunner.complete()
        assertEquals(AgentJobState.DONE, AgentJobRegistry.job(done.id)?.state)
        assertEquals("answer", AgentJobRegistry.job(done.id)?.resultText)
    }

    @Test fun `finish rejects nonterminal state before touching hooks`() {
        val j = child()
        try { AgentJobRegistry.finish(j.id, AgentJobState.RUNNING, null); fail("not terminal") }
        catch (_: IllegalArgumentException) { }
        assertEquals(AgentJobState.PENDING, AgentJobRegistry.job(j.id)?.state)
    }
}
