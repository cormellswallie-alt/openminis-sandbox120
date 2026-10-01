package com.openminis.app.data.model

import com.openminis.app.data.db.UsageRecord
import com.openminis.app.provider.ModelsDevApi
import org.json.JSONObject

/** Pricing uses an exact catalog channel/model match, never a cross-provider or family price. */
class UsagePriceResolver(
    private val config: ProviderConfig?,
    private val registry: Map<String, ModelsDevApi.ProviderEntry>,
) {
    fun defaults(entry: ModelEntry): TokenPrices {
        val instance = config?.instances?.find { it.id == entry.providerInstanceId } ?: return TokenPrices()
        val customUrl = instance.customBaseURL?.takeIf { it.isNotBlank() }
        val channel = if (customUrl != null) {
            val normalized = normalizeUrl(instance.effectiveBaseURL ?: customUrl)
            registry.values.singleOrNull { it.api?.let(::normalizeUrl) == normalized }
        } else {
            val keys = when (instance.providerType) {
                ProviderType.openAI, ProviderType.openAIResponses -> listOf("openai")
                ProviderType.anthropic -> listOf("anthropic")
                ProviderType.gemini -> listOf("google")
                ProviderType.openRouter -> listOf("openrouter")
                ProviderType.xAI -> listOf("xai")
                else -> listOf(instance.providerType.name.lowercase())
            }
            keys.firstNotNullOfOrNull { registry[it] }
        }
        val dev = channel?.models?.get(entry.baseModel.id) ?: return TokenPrices()
        return TokenPrices(dev.inputCost, dev.outputCost, dev.cacheReadCost, dev.cacheWriteCost)
    }

    fun prices(entry: ModelEntry): TokenPrices = defaults(entry).withOverrides(entry.overrides)

    fun prices(record: UsageRecord): TokenPrices {
        val candidates = config?.modelEntries.orEmpty().filter { entry ->
            entry.baseModel.id == record.modelId &&
                (record.providerInstanceId == null || entry.providerInstanceId == record.providerInstanceId) &&
                (record.providerType == null || config?.instances?.any {
                    it.id == entry.providerInstanceId && it.providerType.name == record.providerType
                } == true)
        }
        // A deleted snapshot instance must never inherit another instance's override.
        // Legacy rows can be priced only when their current attribution is unambiguous.
        return candidates.singleOrNull()?.let(::prices) ?: TokenPrices()
    }

    private fun normalizeUrl(url: String) = url.trim().trimEnd('/').removeSuffix("/v1")
}

/** Stored Android counts are fresh-only; both cache key spellings occur in backups. */
fun readBillableTokens(json: String): BillableTokens? = runCatching {
    val obj = JSONObject(json)
    fun count(key: String, legacy: String = key): Long =
        obj.optLong(key, obj.optLong(legacy, 0L)).coerceAtLeast(0L)
    BillableTokens(
        input = count("inputTokens"),
        output = count("outputTokens"),
        cacheRead = count("cacheReadTokens", "cacheReadInputTokens"),
        cacheWrite = count("cacheCreationTokens", "cacheCreationInputTokens"),
    )
}.getOrNull()

fun priceUsageRecord(record: UsageRecord, resolver: UsagePriceResolver): TokenCost =
    readBillableTokens(record.tokenUsage)?.let { calculateTokenCost(it, resolver.prices(record)) }
        ?: TokenCost(unknownTokens = 1) // Malformed usage must not manufacture a free call.

fun priceUsageRecords(records: List<UsageRecord>, resolver: UsagePriceResolver): TokenCost =
    records.fold(TokenCost()) { total, record -> total + priceUsageRecord(record, resolver) }
