package com.openminis.app.agent.jobs

import com.openminis.app.data.model.LLMError
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HelperRequestRetryTest {
    @Test fun `network failure has three bounded retries with exact backoff`() = runTest {
        var calls = 0
        val retryTimes = mutableListOf<Long>()
        val failure = LLMError.NetworkError(IOException("connection reset"))
        try {
            HelperRequestRetry.execute<Unit>(beforeRetry = { _, _, _ -> retryTimes += testScheduler.currentTime }) {
                calls++
                throw failure
            }
            fail("must surface exhaustion")
        } catch (error: LLMError.NetworkError) { assertSame(failure, error) }
        assertEquals(4, calls)
        assertEquals(listOf(0L, 1_000L, 3_000L), retryTimes)
        assertEquals(7_000L, testScheduler.currentTime)
    }

    @Test fun `retry after a completed tool batch preserves its result and executes effects once`() = runTest {
        var writes = 0
        val history = mutableListOf<String>()
        // An earlier, committed request already dispatched its tool batch.
        HelperRequestRetry.execute { attempt ->
            attempt.markToolDispatched()
            writes++
            history += "tool_result: wrote file"
        }
        var requests = 0
        val uncommitted = mutableListOf<String>()
        val answer = HelperRequestRetry.execute(beforeRetry = { _, _, _ -> uncommitted.clear() }) {
            requests++
            assertEquals(listOf("tool_result: wrote file"), history)
            uncommitted += "partial"
            if (requests == 1) throw LLMError.TransientError("stream dropped")
            "done"
        }
        assertEquals("done", answer)
        assertEquals(2, requests)
        assertEquals(1, writes)
        assertEquals(listOf("partial"), uncommitted)
    }

    @Test fun `failure after dispatch including unknown tool outcome cannot replay a side effect`() = runTest {
        var writes = 0
        var retryCallbacks = 0
        try {
            HelperRequestRetry.execute<Unit>(beforeRetry = { _, _, _ -> retryCallbacks++ }) { attempt ->
                attempt.markToolDispatched()
                writes++
                throw IOException("tool reply lost after write")
            }
            fail("must not replay")
        } catch (_: IOException) { }
        assertEquals(1, writes)
        assertEquals(0, retryCallbacks)
    }

    @Test fun `user Stop during backoff prevents a second request`() = runTest {
        var calls = 0
        val countdown = CompletableDeferred<Unit>()
        val job = launch {
            HelperRequestRetry.execute<Unit>(beforeRetry = { _, _, _ -> countdown.complete(Unit) }) {
                calls++
                throw IOException("offline")
            }
        }
        countdown.await()
        job.cancel()
        job.join()
        advanceTimeBy(30_000L)
        assertEquals(1, calls)
    }

    @Test fun `cancellation provider auth quota and programming errors never retry`() = runTest {
        val failures = listOf(
            CancellationException("stop"), LLMError.Cancelled(), LLMError.InvalidApiKey(),
            LLMError.RateLimited(), LLMError.ProviderError("quota exhausted", 429),
            LLMError.DecodingError(IllegalArgumentException("bad json")), IllegalStateException("bug"),
        )
        for (failure in failures) {
            var calls = 0
            try {
                HelperRequestRetry.execute<Unit> { calls++; throw failure }
                fail("must surface $failure")
            } catch (error: Exception) { assertSame(failure, error) }
            assertEquals(failure.toString(), 1, calls)
        }
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test fun `budget prevents a backoff that would consume all remaining time`() = runTest {
        var calls = 0
        try {
            HelperRequestRetry.execute<Unit>(remainingBudgetMs = { 1_000L }) { calls++; throw IOException("offline") }
            fail("budget exhausted")
        } catch (_: IOException) { }
        assertEquals(1, calls)
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test fun `budget exhausted during backoff prevents another request`() = runTest {
        var calls = 0
        var remaining = 10_000L
        try {
            HelperRequestRetry.execute<Unit>(remainingBudgetMs = { remaining }, beforeRetry = { _, _, _ -> remaining = 0 }) {
                calls++; throw IOException("offline")
            }
            fail("budget exhausted")
        } catch (_: IOException) { }
        assertEquals(1, calls)
    }

    @Test fun `healthy slow requests are not killed by a short local timeout`() = runTest {
        var result = ""
        val job = launch { result = HelperRequestRetry.execute { delay(180_000L); "answer" } }
        runCurrent()
        advanceTimeBy(120_000L)
        assertTrue(job.isActive)
        job.join()
        assertEquals("answer", result)
    }
}
