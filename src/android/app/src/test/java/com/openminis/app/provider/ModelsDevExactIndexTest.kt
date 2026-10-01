package com.openminis.app.provider

import org.junit.Assert.*
import org.junit.Test

class ModelsDevExactIndexTest {
    private fun entry(id: String, tiers: List<String>? = null) = ModelsDevApi.ModelDevEntry(
        id = id, name = null, family = null, contextWindow = null, maxOutputTokens = null,
        reasoning = null, interleavedField = null, inputModalities = null, outputModalities = null,
        reasoningEffortValues = tiers, releaseDate = null, outputCost = null,
    )
    private fun provider(id: String, vararg entries: ModelsDevApi.ModelDevEntry) =
        ModelsDevApi.ProviderEntry(id, null, null, entries.associateBy { it.id })

    @Test fun matchesSortedScanAndPrefersReasoningMetadata() {
        val a = entry("shared")
        val b = entry("shared", listOf("high"))
        val c = entry("shared", listOf("low"))
        val registry = linkedMapOf("z" to provider("z", c), "b" to provider("b", b), "a" to provider("a", a))
        assertSame(b, ModelsDevApi.buildExactIndex(registry)["shared"])
    }
    @Test fun withoutEffortMetadataTheFirstSortedProviderWins() {
        val a = entry("shared")
        assertSame(a, ModelsDevApi.buildExactIndex(mapOf("z" to provider("z", entry("shared")), "a" to provider("a", a)))["shared"])
    }
    @Test fun snapshotsDoNotRetainEntriesFromAnOlderCatalog() {
        assertTrue(ModelsDevApi.buildExactIndex(emptyMap()).isEmpty())
        val old = ModelsDevApi.buildExactIndex(mapOf("a" to provider("a", entry("removed"))))
        val current = ModelsDevApi.buildExactIndex(mapOf("a" to provider("a", entry("added"))))
        assertNotNull(old["removed"])
        assertNull(current["removed"])
        assertNotNull(current["added"])
    }
}
