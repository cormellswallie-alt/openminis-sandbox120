package com.openminis.app.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import android.view.View

/** Popups are separate windows; an outgoing navigation entry can remain composed. */
@Composable
internal fun ChatSelectionLifecycle(
    sessionId: String,
    toolbar: MinisMarkdownTextToolbar,
    controller: SelectionController,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val view = LocalView.current
    // Read outside the chat's custom LocalTextToolbar provider: this is the
    // Android toolbar used by the composer and other default selection scopes.
    val systemToolbar = androidx.compose.ui.platform.LocalTextToolbar.current
    DisposableEffect(sessionId, lifecycle, view, toolbar, controller, systemToolbar) {
        val gate = ChatSelectionGate(
            setHostActive = toolbar::setHostActive,
            clearSelection = controller::clearSelection,
            hideToolbar = systemToolbar::hide,
        )
        toolbar.selectionGate = gate
        fun update() {
            if (lifecycle.currentState == Lifecycle.State.DESTROYED) {
                gate.dispose()
                return
            }
            gate.update(
                resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED),
                attached = view.isAttachedToWindow,
            )
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_DESTROY) gate.dispose() else update()
        }
        val attachment = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = update()
            override fun onViewDetachedFromWindow(v: View) {
                gate.update(resumed = false, attached = false)
            }
        }
        lifecycle.addObserver(observer)
        view.addOnAttachStateChangeListener(attachment)
        update()
        onDispose {
            lifecycle.removeObserver(observer)
            view.removeOnAttachStateChangeListener(attachment)
            gate.dispose()
        }
    }
}
