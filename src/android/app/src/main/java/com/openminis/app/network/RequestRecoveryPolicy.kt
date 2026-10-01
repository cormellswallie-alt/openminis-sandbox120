package com.openminis.app.network

import com.openminis.app.data.model.LLMError
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** Retries only a model request; completed tool results stay in the conversation. */
object RequestRecoveryPolicy {
    const val NETWORK_WAIT_MS = 60_000L

    fun isRetryable(error: Throwable): Boolean {
        var cause: Throwable? = error
        val visited = HashSet<Throwable>()
        while (cause != null && visited.add(cause)) {
            if (cause is CancellationException || cause is LLMError.Cancelled) return false
            if (cause is LLMError) return cause.isRetryable
            if (cause is IOException) return true
            cause = cause.cause
        }
        return false
    }

    /** A cancellable wait preserves the remaining retry budget while offline. */
    suspend fun awaitConnected(connected: Flow<Boolean>, timeoutMs: Long = NETWORK_WAIT_MS): Boolean =
        withTimeoutOrNull(timeoutMs) { connected.first { it }; true } ?: false
}
