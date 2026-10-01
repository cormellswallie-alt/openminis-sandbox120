package com.openminis.app.ui.chat

import org.junit.Assert.*
import org.junit.Test

class ChatStatusPolicyTest {
    @Test fun everyToolOutcomeHasAnExplicitPresentation() {
        assertEquals(ToolPresentationState.PREPARING, toolPresentationState(null))
        assertEquals(ToolPresentationState.PREPARING, toolPresentationState(ToolBlockStatus.STREAMING))
        assertEquals(ToolPresentationState.PREPARING, toolPresentationState(ToolBlockStatus.PENDING))
        assertEquals(ToolPresentationState.RUNNING, toolPresentationState(ToolBlockStatus.RUNNING))
        assertEquals(ToolPresentationState.SUCCEEDED, toolPresentationState(ToolBlockStatus.SUCCESS))
        assertEquals(ToolPresentationState.FAILED, toolPresentationState(ToolBlockStatus.FAILED))
        assertEquals(ToolPresentationState.TIMED_OUT, toolPresentationState(ToolBlockStatus.TIMEOUT))
        assertEquals(ToolPresentationState.STOPPED, toolPresentationState(ToolBlockStatus.CANCELLED))
    }

    @Test fun compactRecoveryNeverMutatesBusyHistory() {
        for (streaming in listOf(false, true)) {
            for (compacting in listOf(false, true)) {
                for (restoring in listOf(false, true)) {
                    assertEquals(!(streaming || compacting || restoring), compactActionsEnabled(streaming, compacting, restoring))
                }
            }
        }
    }

    @Test fun onlyEligibleChildrenOfferResumeAndOtherFailuresKeepDetails() {
        assertEquals(HelperCardAction.START, helperCardAction(true, null))
        assertEquals(HelperCardAction.RESUME, helperCardAction(false, "interrupted", true))
        for (status in listOf("failed", "timeout", "no_deliverable")) {
            assertEquals(HelperCardAction.RESUME, helperCardAction(false, status, true))
            assertNotEquals(HelperCardAction.RESUME, helperCardAction(false, status, false))
        }
        assertEquals(HelperCardAction.INSPECT_FAILURE, helperCardAction(false, "rejected", true))
        for (status in listOf(null, "completed", "cancelled", "unknown", "background")) {
            assertEquals(HelperCardAction.NONE, helperCardAction(false, status))
        }
    }

    @Test fun recoveryClearsSelectionBeforeChangingHistory() {
        val events = mutableListOf<String>()
        val bridge = ChatHistoryMutationBridge()
        bridge.beforeMutation = { events.add("clearSelection") }
        bridge.mutate { events.add("restoreHistory") }
        assertEquals(listOf("clearSelection", "restoreHistory"), events)
        bridge.beforeMutation = {}
        bridge.mutate { events.add("retry") }
        assertEquals("retry", events.last())
    }

    @Test fun collapsedPreviewHasABoundedLayoutBudget() {
        assertEquals("", boundedToolPreview(""))
        assertEquals("short", boundedToolPreview("short"))
        val largeResult = "a".repeat(200_000)
        assertEquals("a".repeat(600) + "…", boundedToolPreview(largeResult))
        assertEquals("abc…", boundedToolPreview("abcdef", 3))
        assertEquals("abc", boundedToolPreview("abc", 3))
    }
}
