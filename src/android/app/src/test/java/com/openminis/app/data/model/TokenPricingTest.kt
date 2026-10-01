package com.openminis.app.data.model

import java.math.BigDecimal
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TokenPricingTest {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    @Test fun `cache read and write are billed once separately from fresh input`() {
        val cost = calculateTokenCost(BillableTokens(100_000, 200_000, 300_000, 400_000), TokenPrices(2.0, 8.0, 0.2, 2.5))
        assertTrue(cost.isComplete)
        assertEquals(0, BigDecimal("2.86").compareTo(cost.knownUsd))
    }

    @Test fun `cached only request needs no fresh input price`() {
        val cost = calculateTokenCost(BillableTokens(cacheRead = 1_000_000), TokenPrices(cacheRead = 0.15))
        assertTrue(cost.isComplete)
        assertEquals(0, BigDecimal("0.15").compareTo(cost.knownUsd))
    }

    @Test fun `missing prices keep known subtotal and unknown total`() {
        val cost = calculateTokenCost(BillableTokens(input = 1_000_000, cacheWrite = 50), TokenPrices(input = 2.0))
        assertFalse(cost.isComplete)
        assertEquals(50L, cost.unknownTokens)
        assertEquals(0, BigDecimal("2").compareTo(cost.knownUsd))
    }

    @Test fun `explicit zero is free but absent price is unknown`() {
        assertTrue(calculateTokenCost(BillableTokens(output = 12), TokenPrices(output = 0.0)).isComplete)
        assertFalse(calculateTokenCost(BillableTokens(output = 12), TokenPrices()).isComplete)
        assertTrue(calculateTokenCost(BillableTokens(), TokenPrices()).isComplete)
    }

    @Test fun `cached tokens never fall back to fresh input price`() {
        assertFalse(calculateTokenCost(BillableTokens(cacheRead = 10), TokenPrices(input = 2.0)).isComplete)
        assertFalse(calculateTokenCost(BillableTokens(cacheWrite = 10), TokenPrices(input = 2.0)).isComplete)
    }

    @Test fun `invalid prices and numeric overflow are rejected`() {
        for (text in listOf("-1", "NaN", "Infinity", "-Infinity", "1e309", "abc", "1,2")) {
            assertFalse(text, parseTokenPriceInput(text).isValid)
        }
        for (text in listOf("", "  ", "0", " 1.25 ", "1e-4")) assertTrue(text, parseTokenPriceInput(text).isValid)
        assertNull(parseTokenPriceInput("  ").value)
        for (price in listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertFalse(calculateTokenCost(BillableTokens(input = 1), TokenPrices(input = price)).isComplete)
        }
    }

    @Test fun `large finite prices never overflow the cost to infinity`() {
        val cost = calculateTokenCost(BillableTokens(input = Long.MAX_VALUE), TokenPrices(input = Double.MAX_VALUE))
        assertTrue(cost.isComplete)
        assertTrue(cost.knownUsd > BigDecimal.ZERO)
        assertFalse(formatTokenCostUsd(cost).contains("Infinity"))
    }

    @Test fun `old overrides and provider backups decode without synthesizing zero prices`() {
        val overrides = json.decodeFromString<ModelOverrides>("""{"displayName":"Legacy"}""")
        assertNull(overrides.inputPricePerMillion)
        assertNull(overrides.outputPricePerMillion)
        assertNull(overrides.cacheReadPricePerMillion)
        assertNull(overrides.cacheWritePricePerMillion)
        assertTrue(json.decodeFromString<ModelOverrides>("{}").isEmpty)
        val config = json.decodeFromString<ProviderConfig>("""{"modelEntries":[{"providerInstanceId":"i","model":{"id":"m","displayName":"M","provider":"Custom"}}]}""")
        assertTrue(config.modelEntries.single().overrides.isEmpty)
    }

    @Test fun `each price alone survives backup and prevents override dropping`() {
        val overrides = listOf(
            ModelOverrides(inputPricePerMillion = 0.0), ModelOverrides(outputPricePerMillion = 1.5),
            ModelOverrides(cacheReadPricePerMillion = 0.2), ModelOverrides(cacheWritePricePerMillion = 3.0),
        )
        for (value in overrides) {
            assertFalse(value.isEmpty)
            val config = ProviderConfig(modelEntries = mutableListOf(ModelEntry("i", LLMModel("m", "M", "Custom"), value)))
            val back = json.decodeFromString<ProviderConfig>(json.encodeToString(config))
            assertEquals(value, back.modelEntries.single().overrides)
        }
    }

    @Test fun `invalid persisted prices become inherited without dropping other settings`() {
        val back = json.decodeFromString<ModelOverrides>("""{"displayName":"Keep","inputPricePerMillion":-1,"outputPricePerMillion":null,"cacheReadPricePerMillion":0}""")
        assertEquals("Keep", back.displayName)
        assertNull(back.inputPricePerMillion)
        assertNull(back.outputPricePerMillion)
        assertEquals(0.0, back.cacheReadPricePerMillion!!, 0.0)
        val special = Json { allowSpecialFloatingPointValues = true }.decodeFromString<ModelOverrides>("""{"inputPricePerMillion":NaN,"outputPricePerMillion":Infinity}""")
        assertNull(special.inputPricePerMillion)
        assertNull(special.outputPricePerMillion)
    }

    @Test fun `provider share round trip preserves prices and other overrides`() {
        val original = ModelOverrides(displayName = "Keep", inputPricePerMillion = 2.0, outputPricePerMillion = 8.0, cacheReadPricePerMillion = 0.0, cacheWritePricePerMillion = 2.5)
        val wire = JSONObject()
        writeTokenPriceOverrides(wire, original)
        val back = readTokenPriceOverrides(JSONObject(wire.toString()), ModelOverrides(displayName = "Keep"))
        assertEquals(original, back)
        assertEquals(ModelOverrides(displayName = "Keep"), readTokenPriceOverrides(JSONObject("{}"), ModelOverrides(displayName = "Keep")))
        assertNull(readTokenPriceOverrides(JSONObject("""{"inputPricePerMillion":-1,"outputPricePerMillion":"Infinity"}""")).inputPricePerMillion)
    }

    @Test fun `reset inherits current defaults while zero override stays pinned`() {
        val defaults = TokenPrices(2.0, 8.0, 0.2, 2.5)
        assertEquals(0.0, defaults.withOverrides(ModelOverrides(inputPricePerMillion = 0.0)).input!!, 0.0)
        assertEquals(defaults, defaults.withOverrides(ModelOverrides()))
        assertEquals(5.0, defaults.copy(input = 5.0).withOverrides(ModelOverrides()).input!!, 0.0)
    }
}
