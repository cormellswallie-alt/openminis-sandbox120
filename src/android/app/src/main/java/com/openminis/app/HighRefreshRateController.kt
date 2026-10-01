package com.openminis.app

import android.app.Activity
import android.app.Application
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import java.util.Collections
import java.util.WeakHashMap

/** Requests fast UI pacing only for resumed windows; Android retains power/thermal control. */
internal class HighRefreshRateController(application: Application) :
    Application.ActivityLifecycleCallbacks, DisplayManager.DisplayListener {
    private val displays = application.getSystemService(DisplayManager::class.java)
    private val resumed = Collections.newSetFromMap(WeakHashMap<Activity, Boolean>())
    private val handler = Handler(Looper.getMainLooper())

    override fun onActivityResumed(activity: Activity) {
        if (resumed.isEmpty()) displays?.registerDisplayListener(this, handler)
        resumed.add(activity)
        apply(activity)
    }

    override fun onActivityPaused(activity: Activity) {
        resumed.remove(activity)
        if (resumed.isEmpty()) displays?.unregisterDisplayListener(this)
        val window = activity.window ?: return
        val attrs = window.attributes
        if (attrs.preferredRefreshRate != 0f || attrs.preferredDisplayModeId != 0) {
            attrs.preferredRefreshRate = 0f
            attrs.preferredDisplayModeId = 0
            window.attributes = attrs
        }
    }

    @Suppress("DEPRECATION")
    private fun apply(activity: Activity) {
        val display: Display = if (Build.VERSION.SDK_INT >= 30) {
            activity.display ?: return
        } else {
            activity.windowManager.defaultDisplay
        }
        val current = display.mode
        val rates = display.supportedModes.asSequence()
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            .map { it.refreshRate }.toList()
        val preferred = RefreshRatePolicy.preferredRate(Build.VERSION.SDK_INT >= 34, rates)
        val modeId = if (Build.VERSION.SDK_INT >= 34 || preferred == 0f) 0 else {
            display.supportedModes.firstOrNull {
                it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight &&
                    it.refreshRate == preferred
            }?.modeId ?: 0
        }
        val window = activity.window ?: return
        val attrs = window.attributes
        if (attrs.preferredRefreshRate == preferred && attrs.preferredDisplayModeId == modeId) return
        attrs.preferredRefreshRate = preferred
        attrs.preferredDisplayModeId = modeId
        window.attributes = attrs
    }

    override fun onDisplayChanged(displayId: Int) {
        resumed.toList().forEach { activity ->
            @Suppress("DEPRECATION")
            val id = if (Build.VERSION.SDK_INT >= 30) activity.display?.displayId
                else activity.windowManager.defaultDisplay.displayId
            if (id == displayId) apply(activity)
        }
    }
    override fun onDisplayAdded(displayId: Int) = Unit
    override fun onDisplayRemoved(displayId: Int) = Unit
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) { resumed.remove(activity) }
}
