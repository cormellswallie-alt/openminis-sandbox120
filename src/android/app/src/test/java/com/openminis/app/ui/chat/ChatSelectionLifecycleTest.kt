package com.openminis.app.ui.chat

import org.junit.Assert.*
import org.junit.Test

class ChatSelectionLifecycleTest {
    private class Host {
        var active = false
        var visible = false
        var selected = false
        val events = mutableListOf<String>()
        lateinit var gate: ChatSelectionGate

        init {
            gate = ChatSelectionGate(
                setHostActive = {
                    active = it
                    events += "active:$it"
                    if (!it) visible = false
                },
                clearSelection = {
                    events += "clear"
                    selected = false
                    // A pending selection callback runs during cleanup.
                    assertFalse(show())
                },
                hideToolbar = { events += "hide"; visible = false },
            )
        }

        fun show() = gate.showIfActive { visible = true; selected = true }
    }

    @Test fun initiallyInactiveRejectsDisplay() {
        val host = Host()
        assertFalse(host.show())
        assertFalse(host.visible)
        assertFalse(host.selected)
    }

    @Test fun resumedAndAttachedAllowsDisplay() {
        val host = Host()
        host.gate.update(resumed = true, attached = true)
        assertTrue(host.active)
        assertTrue(host.show())
        assertTrue(host.visible)
        assertTrue(host.selected)
    }

    @Test fun pauseClearsBeforeLateDisplayAndResumeAllowsNewSelection() {
        val host = Host()
        host.gate.update(true, true)
        assertTrue(host.show())
        host.events.clear()
        host.gate.update(false, true)
        assertEquals(listOf("active:false", "clear", "hide"), host.events)
        assertFalse(host.active)
        assertFalse(host.visible)
        assertFalse(host.selected)
        assertFalse(host.show())
        host.gate.update(true, true)
        assertFalse(host.visible)
        assertTrue(host.show())
    }

    @Test fun detachRejectsDisplayUntilBothAttachedAndResumed() {
        val host = Host()
        host.gate.update(true, true)
        host.show()
        host.gate.update(true, false)
        assertFalse(host.visible)
        assertFalse(host.selected)
        assertFalse(host.show())
        host.gate.update(false, true)
        assertFalse(host.show())
        host.gate.update(true, false)
        assertFalse(host.show())
        host.gate.update(true, true)
        assertTrue(host.show())
    }

    @Test fun disposalClearsAndCannotBeRevivedByLateUpdates() {
        val host = Host()
        host.gate.update(true, true)
        host.show()
        host.events.clear()
        host.gate.dispose()
        assertEquals(listOf("active:false", "clear", "hide"), host.events)
        assertFalse(host.active)
        assertFalse(host.visible)
        assertFalse(host.selected)
        host.gate.update(true, true)
        assertFalse(host.show())
        host.gate.dispose()
        assertEquals(listOf("active:false", "clear", "hide"), host.events)
    }

    @Test fun newHostAfterDisposalCanDisplayIndependently() {
        val old = Host()
        old.gate.dispose()
        val replacement = Host()
        replacement.gate.update(true, true)
        assertTrue(replacement.show())
        assertFalse(old.show())
    }
}
