package com.openminis.app.ui.chat

import com.openminis.app.agent.jobs.HelperRunner
import org.junit.Assert.*
import org.junit.Test

class HelperCardResumeTest {
    private fun payload(status: String, child: String = "child-1") =
        """{"status":"$status","child_session_id":"$child"}"""

    @Test fun interruptedAndUnsuccessfulRunsWithChildOfferResume() {
        for (status in listOf("interrupted", "failed", "timeout", "no_deliverable")) {
            val eligible = HelperRunner.canManuallyResume(payload(status), false)
            assertTrue(status, eligible)
            assertEquals(HelperCardAction.RESUME, helperCardAction(false, status, eligible))
        }
    }

    @Test fun lostRunningChildIsRecoverableButLiveDuplicateIsExcluded() {
        assertTrue(HelperRunner.canManuallyResume(payload("running"), false))
        for (status in listOf("running", "interrupted", "failed", "timeout", "no_deliverable")) {
            assertFalse(status, HelperRunner.canManuallyResume(payload(status), true))
            assertNotEquals(HelperCardAction.RESUME, helperCardAction(false, status, false))
        }
    }

    @Test fun successAndUserCancellationCannotResume() {
        for (status in listOf("completed", "cancelled", "rejected", "queued")) {
            assertFalse(status, HelperRunner.canManuallyResume(payload(status), false))
            assertNotEquals(HelperCardAction.RESUME, helperCardAction(false, status, true))
        }
    }

    @Test fun missingChildAndMalformedPayloadCannotResume() {
        for (status in listOf("interrupted", "failed", "timeout", "no_deliverable")) {
            val eligible = HelperRunner.canManuallyResume(payload(status, ""), false)
            assertFalse(eligible)
            assertNotEquals(HelperCardAction.RESUME, helperCardAction(false, status, eligible))
        }
        assertFalse(HelperRunner.canManuallyResume("invalid", false))
    }

    @Test fun neverStartedUsesStartAndFailureWithoutChildUsesDetails() {
        assertEquals(HelperCardAction.START, helperCardAction(true, null, false))
        assertEquals(HelperCardAction.INSPECT_FAILURE, helperCardAction(false, "failed", false))
        assertEquals(HelperCardAction.INSPECT_FAILURE, helperCardAction(false, "timeout", false))
    }
}
