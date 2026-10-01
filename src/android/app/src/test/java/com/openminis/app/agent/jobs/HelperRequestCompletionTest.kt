package com.openminis.app.agent.jobs

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class HelperRequestCompletionTest {
    @Test fun queuedIdleDoesNotCompleteAcceptedRequest() = runTest {
        val request = HelperRequestCompletion()
        yield()
        assertEquals(HelperRequestCompletion.Result.PENDING, request.result.value)
        val runner = launch(start = CoroutineStart.LAZY) { delay(10) }
        request.bind(runner)
        runner.start()
        runner.join()
        assertEquals(HelperRequestCompletion.Result.COMPLETE, request.result.value)
    }

    @Test fun compactHandoffDoesNotCompleteBeforeRequest() = runTest {
        val request = HelperRequestCompletion()
        val stream = launch(start = CoroutineStart.LAZY) { delay(100) }
        val compact = launch(start = CoroutineStart.LAZY) { request.bind(stream); stream.start() }
        request.bind(compact)
        compact.start()
        compact.join()
        assertEquals(HelperRequestCompletion.Result.PENDING, request.result.value)
        stream.join()
        assertEquals(HelperRequestCompletion.Result.COMPLETE, request.result.value)
    }

    @Test fun shortRequestCompletedBeforeObservationStillCompletes() = runTest {
        val runner = launch { }
        runner.join()
        val request = HelperRequestCompletion()
        request.bind(runner)
        assertEquals(HelperRequestCompletion.Result.COMPLETE, request.result.value)
    }

    @Test fun cancelledBeforeLaunchCannotStartWork() = runTest {
        var sideEffects = 0
        val request = HelperRequestCompletion()
        request.cancel()
        val runner = launch(start = CoroutineStart.LAZY) { sideEffects++ }
        request.bind(runner)
        runner.start()
        runner.join()
        assertEquals(0, sideEffects)
        assertEquals(HelperRequestCompletion.Result.CANCELLED, request.result.value)
    }

    @Test fun cancelledQueuedRequestCannotRunOnLateDelivery() = runTest {
        val request = HelperRequestCompletion()
        request.cancel()
        var tools = 0
        val delivery = launch(start = CoroutineStart.LAZY) { tools++ }
        request.bind(delivery)
        delivery.start()
        delivery.join()
        assertEquals(0, tools)
    }

    @Test fun failedRunnerClosesWithoutInventedSuccess() = runTest {
        val request = HelperRequestCompletion()
        val runner = Job()
        request.bind(runner)
        runner.completeExceptionally(IllegalStateException("setup failed"))
        assertEquals(HelperRequestCompletion.Result.FAILED, request.result.value)
        request.finish(HelperRequestCompletion.Result.COMPLETE)
        assertEquals(HelperRequestCompletion.Result.FAILED, request.result.value)
    }

    @Test fun bindingNeverEnteredCancelledScopeCloses() = runTest {
        val scope = CoroutineScope(Job().also { it.cancel() })
        val runner = scope.launch { error("must not enter") }
        val request = HelperRequestCompletion()
        request.bind(runner)
        runner.join()
        assertEquals(HelperRequestCompletion.Result.CANCELLED, request.result.value)
    }

    @Test fun compactToQueueStaysPendingUntilDelivery() = runTest {
        val request = HelperRequestCompletion()
        val compact = launch(start = CoroutineStart.LAZY) { request.parkForDelivery() }
        request.bind(compact)
        compact.start()
        compact.join()
        assertEquals(HelperRequestCompletion.Result.PENDING, request.result.value)
        val delivery = launch(start = CoroutineStart.LAZY) { delay(10) }
        request.bind(delivery)
        delivery.start()
        delivery.join()
        assertEquals(HelperRequestCompletion.Result.COMPLETE, request.result.value)
    }

    @Test fun requestIdsIncreaseAcrossResumedRuns() {
        val first = HelperRequestCompletion()
        assertTrue(HelperRequestCompletion().id > first.id)
    }
}
