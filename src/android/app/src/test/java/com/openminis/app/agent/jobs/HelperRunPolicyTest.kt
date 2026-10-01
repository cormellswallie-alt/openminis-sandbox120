package com.openminis.app.agent.jobs

import com.openminis.app.data.model.ThinkingLevel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HelperRunPolicyTest {
    @Test fun `provider initialisation lasting more than five seconds can succeed`() = runTest {
        val entry = MutableStateFlow<String?>(null)
        var result: String? = null
        val job = launch { result = HelperRunPolicy.awaitProviderReady(entry) }
        runCurrent()
        advanceTimeBy(30_000L)
        assertTrue(job.isActive)
        entry.value = "resolved-provider"
        job.join()
        assertEquals("resolved-provider", result)
    }

    @Test fun `provider startup remains bounded and blank ids do not indicate readiness`() = runTest {
        val entry = MutableStateFlow<String?>("")
        assertNull(HelperRunPolicy.awaitProviderReady(entry))
        assertEquals(HelperRunPolicy.STARTUP_TIMEOUT_MS, testScheduler.currentTime)
    }

    @Test fun `Stop cancels startup without waiting for the timeout`() = runTest {
        val entry = MutableStateFlow<String?>(null)
        var submitted = false
        val job = launch { HelperRunPolicy.awaitProviderReady(entry); submitted = true }
        runCurrent()
        job.cancel()
        job.join()
        entry.value = "ready-after-stop"
        assertFalse(submitted)
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test fun `explicit OFF wins at each level of inheritance`() {
        assertEquals(ThinkingLevel.OFF, HelperRunPolicy.thinkingOverride(ThinkingLevel.OFF, ThinkingLevel.HIGH, ThinkingLevel.HIGH))
        assertEquals(ThinkingLevel.OFF, HelperRunPolicy.thinkingOverride(null, ThinkingLevel.OFF, ThinkingLevel.HIGH))
        assertEquals(ThinkingLevel.OFF, HelperRunPolicy.thinkingOverride(null, null, ThinkingLevel.OFF))
        assertEquals(ThinkingLevel.LOW, HelperRunPolicy.thinkingOverride(ThinkingLevel.LOW, ThinkingLevel.HIGH, ThinkingLevel.OFF))
        assertNull(HelperRunPolicy.thinkingOverride(null, null, null))
    }

    @Test fun `budget gives one wrap up and one tool stop then bounded grace`() {
        val b = HelperRunPolicy.Budget(10)
        assertEquals(HelperRunPolicy.BudgetAction.CONTINUE, b.nextAction(599_999, true))
        assertEquals(HelperRunPolicy.BudgetAction.REQUEST_WRAP_UP, b.nextAction(600_000, true))
        assertEquals(HelperRunPolicy.BudgetAction.CONTINUE, b.nextAction(600_001, true))
        assertEquals(HelperRunPolicy.BudgetAction.STOP_TOOL, b.nextAction(620_000, true))
        assertEquals(HelperRunPolicy.BudgetAction.CONTINUE, b.nextAction(620_001, true))
        assertEquals(HelperRunPolicy.BudgetAction.CANCEL, b.nextAction(690_000, true))
        assertEquals(HelperRunPolicy.BudgetAction.CONTINUE, b.nextAction(800_000, false))
    }

    @Test fun `wait to background conversion cannot restart budget or grace`() {
        val b = HelperRunPolicy.Budget(10)
        assertEquals(60_000L, b.remainingMs(540_000L))
        // Background watcher attaches after nine minutes. Its deadline stays
        // at minute ten, including time spent retrying the same model request.
        assertEquals(HelperRunPolicy.BudgetAction.REQUEST_WRAP_UP, b.nextAction(600_000, true))
        assertEquals(0L, b.remainingMs(650_000L))
        assertEquals(HelperRunPolicy.BudgetAction.CANCEL, b.nextAction(690_000, true))
    }

    @Test fun `a late watcher does not grant an extra grace period`() {
        assertEquals(HelperRunPolicy.BudgetAction.CANCEL, HelperRunPolicy.Budget(1).nextAction(200_000, true))
    }

    @Test fun `manual resume excludes active completed cancelled and childless runs`() {
        fun payload(status: String) = """{"status":"$status","child_session_id":"child"}"""
        for (s in listOf("running", "interrupted", "failed", "timeout", HelperRunner.NO_DELIVERABLE)) {
            assertTrue(s, HelperRunner.canManuallyResume(payload(s), false))
            assertFalse(s, HelperRunner.canManuallyResume(payload(s), true))
        }
        for (s in listOf("completed", "cancelled", "queued", "rejected")) {
            assertFalse(s, HelperRunner.canManuallyResume(payload(s), false))
        }
        assertFalse(HelperRunner.canManuallyResume("""{"status":"failed"}""", false))
        assertFalse(HelperRunner.canManuallyResume("not json", false))
    }
}
