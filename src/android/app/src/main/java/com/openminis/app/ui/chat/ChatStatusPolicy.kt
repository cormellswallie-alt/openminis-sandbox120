package com.openminis.app.ui.chat

/** Presentation only; execution and recovery remain owned by the ViewModel. */
internal enum class ToolPresentationState { PREPARING, RUNNING, SUCCEEDED, FAILED, TIMED_OUT, STOPPED }

internal fun toolPresentationState(status: ToolBlockStatus?): ToolPresentationState = when (status) {
    null, ToolBlockStatus.STREAMING, ToolBlockStatus.PENDING -> ToolPresentationState.PREPARING
    ToolBlockStatus.RUNNING -> ToolPresentationState.RUNNING
    ToolBlockStatus.SUCCESS -> ToolPresentationState.SUCCEEDED
    ToolBlockStatus.FAILED -> ToolPresentationState.FAILED
    ToolBlockStatus.TIMEOUT -> ToolPresentationState.TIMED_OUT
    ToolBlockStatus.CANCELLED -> ToolPresentationState.STOPPED
}

internal fun compactActionsEnabled(streaming: Boolean, compacting: Boolean, restoring: Boolean): Boolean =
    !streaming && !compacting && !restoring

/** Recovery is available only when the original child exists and has no live run. */
internal enum class HelperCardAction { NONE, START, RESUME, INSPECT_FAILURE }
internal fun helperCardAction(neverStarted: Boolean, finishedStatus: String?, canResume: Boolean = false): HelperCardAction = when {
    neverStarted -> HelperCardAction.START
    canResume && finishedStatus in setOf("interrupted", "failed", "timeout", "no_deliverable") -> HelperCardAction.RESUME
    finishedStatus in setOf("failed", "timeout", "rejected") -> HelperCardAction.INSPECT_FAILURE
    else -> HelperCardAction.NONE
}

internal fun boundedToolPreview(text: String, limit: Int = 600): String {
    require(limit > 0)
    return if (text.length <= limit) text else text.take(limit) + "…"
}
