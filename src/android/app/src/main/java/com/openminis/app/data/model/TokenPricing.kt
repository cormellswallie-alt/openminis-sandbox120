package com.openminis.app.data.model

import java.math.BigDecimal
import java.util.Locale
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** All prices are USD per 1,000,000 tokens. null means unknown/inherit, never free. */
data class TokenPrices(
    val input: Double? = null,
    val output: Double? = null,
    val cacheRead: Double? = null,
    val cacheWrite: Double? = null,
) {
    fun withOverrides(overrides: ModelOverrides) = TokenPrices(
        validTokenPrice(overrides.inputPricePerMillion) ?: validTokenPrice(input),
        validTokenPrice(overrides.outputPricePerMillion) ?: validTokenPrice(output),
        validTokenPrice(overrides.cacheReadPricePerMillion) ?: validTokenPrice(cacheRead),
        validTokenPrice(overrides.cacheWritePricePerMillion) ?: validTokenPrice(cacheWrite),
    )
}

fun validTokenPrice(value: Double?): Double? = value?.takeIf { it.isFinite() && it >= 0.0 }

/** Empty clears an override; every other input must be a finite non-negative number. */
data class TokenPriceInput(val value: Double?, val isValid: Boolean)
fun parseTokenPriceInput(text: String): TokenPriceInput {
    if (text.isBlank()) return TokenPriceInput(null, true)
    val value = validTokenPrice(text.trim().toDoubleOrNull())
    return TokenPriceInput(value, value != null)
}

/** Normalizes invalid prices in older/imported config without discarding other overrides. */
object NullableTokenPriceSerializer : KSerializer<Double?> {
    private val delegate = Double.serializer().nullable
    override val descriptor: SerialDescriptor = delegate.descriptor
    override fun deserialize(decoder: Decoder): Double? = validTokenPrice(delegate.deserialize(decoder))
    override fun serialize(encoder: Encoder, value: Double?) = delegate.serialize(encoder, validTokenPrice(value))
}

/** Provider adapters already split fresh input from cached input. Never subtract cache again. */
data class BillableTokens(
    val input: Long = 0,
    val output: Long = 0,
    val cacheRead: Long = 0,
    val cacheWrite: Long = 0,
) {
    val totalInput: Long get() = input + cacheRead + cacheWrite
}

/** Decimal arithmetic avoids overflow even for large finite user prices. */
data class TokenCost(
    val knownUsd: BigDecimal = BigDecimal.ZERO,
    val unknownTokens: Long = 0,
) {
    val isComplete: Boolean get() = unknownTokens == 0L
    operator fun plus(other: TokenCost) = TokenCost(knownUsd + other.knownUsd, unknownTokens + other.unknownTokens)
}

fun calculateTokenCost(tokens: BillableTokens, prices: TokenPrices): TokenCost {
    var cost = TokenCost()
    for ((count, price) in listOf(
        tokens.input to prices.input,
        tokens.output to prices.output,
        tokens.cacheRead to prices.cacheRead,
        tokens.cacheWrite to prices.cacheWrite,
    )) {
        if (count <= 0) continue // Unused categories do not require a price.
        val valid = validTokenPrice(price)
        cost += if (valid == null) TokenCost(unknownTokens = count)
        else TokenCost(BigDecimal.valueOf(valid).multiply(BigDecimal.valueOf(count)).movePointLeft(6))
    }
    return cost
}

fun formatTokenCostUsd(cost: TokenCost): String = String.format(Locale.US, "USD %.6f", cost.knownUsd)
