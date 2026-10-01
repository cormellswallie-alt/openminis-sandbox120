package com.openminis.app.sandbox

/** A stopped/restarted PTY must never be modified by an older boot or wait coroutine. */
internal class TerminalLifecycleGate {
    private var generation = 0L
    private var active = false

    @Synchronized fun begin(): Long? {
        if (active) return null
        active = true
        return ++generation
    }
    @Synchronized fun isCurrent(token: Long): Boolean = active && token == generation
    @Synchronized fun finish(token: Long): Boolean {
        if (!isCurrent(token)) return false
        active = false
        return true
    }
    @Synchronized fun token(): Long? = if (active) generation else null
    @Synchronized fun invalidate() { generation++; active = false }
}
