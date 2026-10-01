package com.openminis.app.service

import org.junit.Assert.*
import org.junit.Test

class BackgroundRuntimePolicyTest {
    @Test fun `explicit overlay remains enabled even when live updates are available`() {
        assertFalse(BackgroundRuntimePolicy.suppressOverlay(true,true))
        assertFalse(BackgroundRuntimePolicy.suppressOverlay(true,false))
        assertTrue(BackgroundRuntimePolicy.suppressOverlay(false,true))
        assertFalse(BackgroundRuntimePolicy.suppressOverlay(false,false))
    }
    @Test fun `resident mode is opt in and cannot restart unsafe application initialization`() {
        assertTrue(BackgroundRuntimePolicy.residentShouldRun(true,false))
        assertFalse(BackgroundRuntimePolicy.residentShouldRun(false,false))
        assertFalse(BackgroundRuntimePolicy.residentShouldRun(true,true))
    }
}
