package com.openminis.app.data.model

import com.openminis.app.data.db.UsageRecord
import com.openminis.app.provider.ModelsDevApi
import java.math.BigDecimal
import org.junit.Assert.*
import org.junit.Test

class UsagePricingTest {
    private fun instance(id: String, url: String? = null) = ProviderInstance(id, id, ProviderType.openAI, ProviderCredential.apiKey, customBaseURL = url)
    private fun entry(instance: String, price: Double? = null, id: String = "m") = ModelEntry(instance, LLMModel(id, id, "OpenAI"), ModelOverrides(inputPricePerMillion = price))
    private fun record(instance: String? = null, model: String? = "m", json: String = """{"inputTokens":1000000}""") =
        UsageRecord(model, model, "openAI", true, json, 0, "s", instance)
    private fun dev(id: String, input: Double) = ModelsDevApi.ModelDevEntry(
        id, null, null, null, null, null, null, null, null, null,
        releaseDate = null, outputCost = 8.0, inputCost = input, cacheReadCost = 0.2, cacheWriteCost = 2.5,
    )
    private fun registry(vararg entries: ModelsDevApi.ModelDevEntry, api: String = "https://api.openai.com/v1") =
        mapOf("openai" to ModelsDevApi.ProviderEntry("openai", "OpenAI", api, entries.associateBy { it.id }))

    @Test fun `same model id in different instances is priced using immutable instance snapshot`() {
        val config = ProviderConfig(instances = mutableListOf(instance("a"), instance("b")), modelEntries = mutableListOf(entry("a", 2.0), entry("b", 9.0)))
        val resolver = UsagePriceResolver(config, emptyMap())
        assertEquals(0, BigDecimal("2").compareTo(priceUsageRecord(record("a"), resolver).knownUsd))
        assertEquals(0, BigDecimal("9").compareTo(priceUsageRecord(record("b"), resolver).knownUsd))
        assertFalse(priceUsageRecord(record(), resolver).isComplete)
        assertFalse(priceUsageRecord(record("deleted"), resolver).isComplete)
    }

    @Test fun `unambiguous legacy usage is priced and ambiguous or orphan usage stays unknown`() {
        val config = ProviderConfig(instances = mutableListOf(instance("a")), modelEntries = mutableListOf(entry("a", 2.0)))
        val resolver = UsagePriceResolver(config, emptyMap())
        assertTrue(priceUsageRecord(record(), resolver).isComplete)
        assertFalse(priceUsageRecord(record(model = null), resolver).isComplete)
        assertFalse(priceUsageRecord(record("a", "removed"), resolver).isComplete)
    }

    @Test fun `exact channel prices and overrides resolve independently`() {
        val config = ProviderConfig(instances = mutableListOf(instance("a")), modelEntries = mutableListOf(entry("a", 0.0)))
        val resolver = UsagePriceResolver(config, registry(dev("m", 2.0)))
        val prices = resolver.prices(config.modelEntries.single())
        assertEquals(TokenPrices(0.0, 8.0, 0.2, 2.5), prices)
    }

    @Test fun `relay and suffixed models never inherit another channel or sibling prices`() {
        val config = ProviderConfig(instances = mutableListOf(instance("relay", "https://relay.example")), modelEntries = mutableListOf(entry("relay")))
        assertEquals(TokenPrices(), UsagePriceResolver(config, registry(dev("m", 2.0))).prices(config.modelEntries.single()))
        val direct = ProviderConfig(instances = mutableListOf(instance("direct")), modelEntries = mutableListOf(entry("direct", id = "m-suffix")))
        assertEquals(TokenPrices(), UsagePriceResolver(direct, registry(dev("m", 2.0))).prices(direct.modelEntries.single()))
        assertEquals(2.0, UsagePriceResolver(config, registry(dev("m", 2.0), api = "https://relay.example/v1")).prices(config.modelEntries.single()).input!!, 0.0)
    }

    @Test fun `both stored cache key spellings use fresh input without subtracting or doubling cache`() {
        val modern = readBillableTokens("""{"inputTokens":100,"outputTokens":20,"cacheReadTokens":300,"cacheCreationTokens":400}""")!!
        val legacy = readBillableTokens("""{"inputTokens":100,"outputTokens":20,"cacheReadInputTokens":300,"cacheCreationInputTokens":400}""")!!
        assertEquals(modern, legacy)
        assertEquals(800L, modern.totalInput)
        assertEquals(100L, modern.input)
        assertEquals(0L, readBillableTokens("""{"inputTokens":-4}""")!!.input)
        assertNull(readBillableTokens("invalid"))
    }

    @Test fun `totals retain unknown requests alongside priced requests`() {
        val config = ProviderConfig(instances = mutableListOf(instance("a")), modelEntries = mutableListOf(entry("a", 2.0)))
        val resolver = UsagePriceResolver(config, emptyMap())
        val total = priceUsageRecords(listOf(record("a"), record("gone")), resolver)
        assertFalse(total.isComplete)
        assertEquals(0, BigDecimal("2").compareTo(total.knownUsd))
        assertEquals(1_000_000L, total.unknownTokens)
        assertFalse(priceUsageRecord(record("a", json = "broken"), resolver).isComplete)
    }
}
