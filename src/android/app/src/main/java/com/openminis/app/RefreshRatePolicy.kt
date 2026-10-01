package com.openminis.app

/** Only rates available at the current resolution participate in the request. */
internal object RefreshRatePolicy {
    const val TARGET_HZ = 120f

    fun preferredRate(supportsArbitraryRate: Boolean, availableRates: List<Float>): Float {
        val valid = availableRates.filter { it.isFinite() && it > 0f }
        if (valid.isEmpty()) return 0f
        if (supportsArbitraryRate) return minOf(TARGET_HZ, valid.maxOrNull()!!)
        // Legacy devices require an advertised rate. Allow rounded 120 Hz modes.
        return valid.filter { it <= TARGET_HZ + 0.5f }.maxOrNull() ?: 0f
    }
}
