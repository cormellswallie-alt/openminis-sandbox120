package com.openminis.app.agent.jobs

import com.openminis.app.data.model.ThinkingLevel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** Shared policy for fresh, wait-mode, background and manually resumed helpers. */
object HelperRunPolicy {
    // Session/provider loading includes Room and provider configuration I/O.
    // Five seconds cannot distinguish a slow startup from a failed one.
    const val STARTUP_TIMEOUT_MS = 120_000L

    suspend fun awaitProviderReady(
        activeEntryId: StateFlow<String?>,
        timeoutMs: Long = STARTUP_TIMEOUT_MS,
    ): String? {
        require(timeoutMs > 0)
        currentCoroutineContext().ensureActive()
        return withTimeoutOrNull(timeoutMs) { activeEntryId.first { !it.isNullOrBlank() } }
    }

    /** OFF is an explicit override; null alone means inherit. Persist any non-null result. */
    fun thinkingOverride(
        definition: ThinkingLevel?,
        pinnedGroup: ThinkingLevel?,
        parent: ThinkingLevel?,
    ): ThinkingLevel? = definition ?: pinnedGroup ?: parent

    enum class BudgetAction { CONTINUE, REQUEST_WRAP_UP, STOP_TOOL, CANCEL }

    /**
     * Time since the accepted submission, from a monotonic clock. Do not reset
     * it when wait-mode becomes background, when a request retries, or when a
     * progress watcher is installed. A manual resume gets a fresh budget.
     */
    class Budget(minutes: Int) {
        val limitMs = minutes.coerceIn(1, HelperRunner.MAX_MINUTES) * 60_000L
        private var wrapUpRequested = false
        private var toolStopRequested = false

        fun remainingMs(elapsedMs: Long): Long = (limitMs - elapsedMs).coerceAtLeast(0L)

        fun nextAction(elapsedMs: Long, active: Boolean): BudgetAction {
            if (!active || elapsedMs < limitMs) return BudgetAction.CONTINUE
            // Grace is anchored to the original deadline even if the watcher
            // was delayed. A flowing long request is bounded only by this budget.
            val overdue = elapsedMs - limitMs
            if (overdue >= HelperRunner.WRAP_UP_GRACE_MS) return BudgetAction.CANCEL
            if (!wrapUpRequested) {
                wrapUpRequested = true
                return BudgetAction.REQUEST_WRAP_UP
            }
            if (!toolStopRequested && overdue >= HelperRunner.WRAP_UP_TOOL_PATIENCE_MS) {
                toolStopRequested = true
                return BudgetAction.STOP_TOOL
            }
            return BudgetAction.CONTINUE
        }
    }
}
