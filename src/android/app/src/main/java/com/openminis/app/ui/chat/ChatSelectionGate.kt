package com.openminis.app.ui.chat

/** Main-thread admission shared by lifecycle cleanup and toolbar display. */
internal class ChatSelectionGate(
    private val setHostActive: (Boolean) -> Unit,
    private val clearSelection: () -> Unit,
    private val hideToolbar: () -> Unit,
) {
    private var active = false
    private var destroyed = false

    fun update(resumed: Boolean, attached: Boolean) {
        if (destroyed) return
        active = resumed && attached
        setHostActive(active)
        if (!active) {
            clearSelection()
            hideToolbar()
        }
    }

    fun showIfActive(show: () -> Unit): Boolean {
        if (!active || destroyed) return false
        show()
        return true
    }

    fun dispose() {
        if (destroyed) return
        destroyed = true
        active = false
        setHostActive(false)
        clearSelection()
        hideToolbar()
    }
}
