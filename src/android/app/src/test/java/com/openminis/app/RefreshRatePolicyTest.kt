package com.openminis.app

import org.junit.Assert.assertEquals
import org.junit.Test

class RefreshRatePolicyTest {
    @Test fun modernDisplaysRequestAtMost120Hz() {
        assertEquals(120f, RefreshRatePolicy.preferredRate(true, listOf(60f, 144f)), 0f)
        assertEquals(90f, RefreshRatePolicy.preferredRate(true, listOf(60f, 90f)), 0f)
        assertEquals(60f, RefreshRatePolicy.preferredRate(true, listOf(60f)), 0f)
    }
    @Test fun legacyDisplaysUseAnAdvertisedRate() {
        assertEquals(90f, RefreshRatePolicy.preferredRate(false, listOf(60f, 90f, 144f)), 0f)
        assertEquals(119.88f, RefreshRatePolicy.preferredRate(false, listOf(60f, 119.88f)), 0f)
        assertEquals(0f, RefreshRatePolicy.preferredRate(false, listOf(144f)), 0f)
    }
    @Test fun invalidOrMissingRatesReleaseThePreference() {
        assertEquals(0f, RefreshRatePolicy.preferredRate(true, emptyList()), 0f)
        assertEquals(0f, RefreshRatePolicy.preferredRate(false, listOf(Float.NaN, 0f, -1f)), 0f)
    }
}
