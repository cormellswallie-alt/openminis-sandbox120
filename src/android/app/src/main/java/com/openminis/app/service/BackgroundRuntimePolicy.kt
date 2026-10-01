package com.openminis.app.service

/** Explicit user choices are authoritative; system capability alone must not hide an opted-in overlay. */
internal object BackgroundRuntimePolicy {
    fun suppressOverlay(overlayEnabled: Boolean, islandActive: Boolean): Boolean =
        !overlayEnabled && islandActive

    fun residentShouldRun(enabled: Boolean, safeMode: Boolean): Boolean = enabled && !safeMode
}
