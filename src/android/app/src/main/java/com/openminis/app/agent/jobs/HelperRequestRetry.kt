package com.openminis.app.agent.jobs

import com.openminis.app.data.model.LLMError
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.io.IOException
import java.util.concurrent.CancellationException

/**
 * Retries one model request, never a whole agent turn or a tool batch.
 * The caller must restore only the failed attempt's uncommitted stream deltas
 * in [beforeRetry]; persisted tool results from earlier requests stay intact.
 * Do not combine this with the chat loop's existing automatic retry counter.
 */
object HelperRequestRetry {
    val delaysMs: List<Long> = listOf(1_000L, 2_000L, 4_000L)

    /** One checkpoint per request attempt, including retries. */
    class Attempt internal constructor(val number: Int) {
        @Volatile private var toolExecuted = false

        /** Call BEFORE dispatching any tool, including a tool with an unknown outcome. */
        fun markToolDispatched() { toolExecuted = true }
        internal val safeToRetry: Boolean get() = !toolExecuted
    }

    fun isRetryable(error: Throwable): Boolean = when (error) {
        is CancellationException, is LLMError.Cancelled -> false
        is LLMError -> error.isRetryable
        is IOException -> true
        else -> false
    }

    /**
     * Four attempts at most. There is no short request timeout: providers own
     * network timeouts and the helper owns its total budget. [remainingBudgetMs]
     * may prevent another attempt, but never cancels a healthy in-flight request.
     * User Stop cancels the coroutine, including a pending backoff.
     */
    suspend fun <T> execute(
        remainingBudgetMs: () -> Long = { Long.MAX_VALUE },
        beforeRetry: suspend (attempt: Int, delayMs: Long, error: Throwable) -> Unit = { _, _, _ -> },
        request: suspend (Attempt) -> T,
    ): T {
        var number = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val attempt = Attempt(number)
            try {
                return request(attempt)
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                if (!attempt.safeToRetry || !isRetryable(error) || number >= delaysMs.size) throw error
                val pause = delaysMs[number]
                if (remainingBudgetMs() <= pause) throw error
                number++
                beforeRetry(number, pause, error)
                currentCoroutineContext().ensureActive()
                delay(pause)
                currentCoroutineContext().ensureActive()
                if (remainingBudgetMs() <= 0) throw error
            }
        }
    }
}
