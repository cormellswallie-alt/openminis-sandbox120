package com.openminis.app.network

import com.openminis.app.data.model.LLMError
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RequestRecoveryPolicyTest {
    @Test fun retriesOnlyTransientRequestFailures() {
        assertTrue(RequestRecoveryPolicy.isRetryable(LLMError.NetworkError(IOException("reset"))))
        assertTrue(RequestRecoveryPolicy.isRetryable(LLMError.TransientError("busy", 503)))
        assertTrue(RequestRecoveryPolicy.isRetryable(IOException("socket closed")))
        assertFalse(RequestRecoveryPolicy.isRetryable(LLMError.InvalidApiKey()))
        assertFalse(RequestRecoveryPolicy.isRetryable(LLMError.ProviderError("bad input", 400)))
        assertFalse(RequestRecoveryPolicy.isRetryable(LLMError.Cancelled()))
        assertFalse(RequestRecoveryPolicy.isRetryable(CancellationException("user stopped")))
        assertFalse(RequestRecoveryPolicy.isRetryable(LLMError.DecodingError(IOException("invalid payload"))))
    }

    @Test fun networkRecoveryWakesImmediately() = runTest {
        val network = MutableStateFlow(false)
        val wait = async { RequestRecoveryPolicy.awaitConnected(network) }
        runCurrent()
        advanceTimeBy(10_000)
        assertFalse(wait.isCompleted)
        network.value = true
        runCurrent()
        assertTrue(wait.await())
    }

    @Test fun offlineWaitHasAnAbsoluteLimit() = runTest {
        val wait = async { RequestRecoveryPolicy.awaitConnected(MutableStateFlow(false)) }
        runCurrent()
        advanceTimeBy(RequestRecoveryPolicy.NETWORK_WAIT_MS)
        runCurrent()
        assertFalse(wait.await())
    }

    @Test fun stoppingTheUserRequestCancelsNetworkWait() = runTest {
        val wait = async { RequestRecoveryPolicy.awaitConnected(MutableStateFlow(false)) }
        runCurrent()
        wait.cancel()
        runCurrent()
        assertTrue(wait.isCancelled)
    }

    @Test fun alreadyConnectedDoesNotDelay() = runTest {
        assertTrue(RequestRecoveryPolicy.awaitConnected(MutableStateFlow(true)))
    }
}
