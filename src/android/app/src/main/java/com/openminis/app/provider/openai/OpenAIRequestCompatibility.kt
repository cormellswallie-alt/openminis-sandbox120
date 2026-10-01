package com.openminis.app.provider.openai

import org.json.JSONArray
import org.json.JSONObject

/** Apply only to api.openai.com; compatible vendors keep their own parameter contracts. */
internal object OpenAIRequestCompatibility {
    fun requiresResponses(modelId: String): Boolean = modelId.lowercase().startsWith("gpt-6")

    fun sanitize(body: JSONObject, modelId: String, responses: Boolean) {
        val id = modelId.lowercase()
        val astra = id.startsWith("gpt-6-astra")
        if (requiresResponses(id) && responses) {
            body.remove("prompt_cache_retention")
            if (!body.has("prompt_cache_options")) {
                body.put("prompt_cache_options", JSONObject().put("ttl", "30m"))
            }
        }
        val reasoningModel = id.startsWith("gpt-5") || id.startsWith("gpt-6") ||
            Regex("^o[134](?:-|$)").containsMatchIn(id)
        if (!reasoningModel) return
        val reasoning = body.optJSONObject("reasoning")
        var effort = if (responses) reasoning?.optString("effort", "") ?: ""
            else body.optString("reasoning_effort", "")
        if (astra && effort in setOf("none", "minimal")) {
            effort = "low"
            if (responses) body.put("reasoning", (reasoning ?: JSONObject()).put("effort", effort))
            else body.put("reasoning_effort", effort)
        }
        // The original GPT-5 rejects custom sampling even at minimal effort.
        val originalGpt5 = id == "gpt-5" || id.startsWith("gpt-5-mini") || id.startsWith("gpt-5-nano") ||
            Regex("^gpt-5-\\d{4}-").containsMatchIn(id)
        if (originalGpt5 && effort == "none") {
            effort = "minimal"
        }
        val searchEnabled = body.optJSONArray("tools")?.let { tools ->
            (0 until tools.length()).any { tools.optJSONObject(it)?.optString("type") == "web_search" }
        } == true
        if (originalGpt5 && searchEnabled && effort == "minimal") effort = "low"
        if (effort.isNotEmpty()) {
            if (responses) body.put("reasoning", (reasoning ?: JSONObject()).put("effort", effort))
            else body.put("reasoning_effort", effort)
        }
        if (astra || originalGpt5 || effort != "none") {
            for (key in listOf("temperature", "top_p", "top_logprobs", "logprobs")) body.remove(key)
            body.optJSONArray("include")?.let { include ->
                val allowed = JSONArray()
                for (i in 0 until include.length()) {
                    if (include.optString(i) != "message.output_text.logprobs") allowed.put(include.get(i))
                }
                body.put("include", allowed)
            }
        }
    }
}
