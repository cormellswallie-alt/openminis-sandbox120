package com.openminis.app.data.model

import org.json.JSONObject

/** The shared provider JSON path is hand-written; keep price read/write symmetric. */
fun writeTokenPriceOverrides(json: JSONObject, overrides: ModelOverrides) {
    for ((key, value) in listOf(
        "inputPricePerMillion" to overrides.inputPricePerMillion,
        "outputPricePerMillion" to overrides.outputPricePerMillion,
        "cacheReadPricePerMillion" to overrides.cacheReadPricePerMillion,
        "cacheWritePricePerMillion" to overrides.cacheWritePricePerMillion,
    )) validTokenPrice(value)?.let { json.put(key, it) }
}

fun readTokenPriceOverrides(json: JSONObject, existing: ModelOverrides = ModelOverrides()): ModelOverrides = existing.copy(
    inputPricePerMillion = validTokenPrice(json.optDouble("inputPricePerMillion", Double.NaN)),
    outputPricePerMillion = validTokenPrice(json.optDouble("outputPricePerMillion", Double.NaN)),
    cacheReadPricePerMillion = validTokenPrice(json.optDouble("cacheReadPricePerMillion", Double.NaN)),
    cacheWritePricePerMillion = validTokenPrice(json.optDouble("cacheWritePricePerMillion", Double.NaN)),
)
