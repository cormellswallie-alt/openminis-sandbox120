package com.openminis.app.provider.openai

import android.content.Context
import com.openminis.app.data.ThinkingDefaults
import com.openminis.app.data.model.*
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.provider.ProviderFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

internal class WebSearchProbeEvidence {
    private val calls = linkedMapOf<String, JSONObject>()
    fun observe(item: JSONObject) {
        if (item.optString("type") != "web_search_call") return
        val id = item.optString("id", "").ifBlank { item.toString() }
        calls[id] = item
    }
    val completedCalls: Int get() = calls.values.count { it.optString("status") == "completed" }
    val queries: List<String> get() = calls.values.flatMap { call ->
        val action = call.optJSONObject("action") ?: return@flatMap emptyList<String>()
        val values = action.optJSONArray("queries")
        if (values != null) (0 until values.length()).map { values.optString(it) }.filter { it.isNotBlank() }
        else listOfNotNull(action.optString("query", "").takeIf { it.isNotBlank() })
    }.distinct()
}

internal data class NativeWebSearchProbeResult(
    val verified: Boolean,
    val completedCalls: Int,
    val queries: List<String>,
    val reply: String,
)

internal object NativeWebSearchProbe {
    suspend fun run(context: Context, entry: ModelEntry, repository: ProviderRepository): NativeWebSearchProbeResult =
        withContext(Dispatchers.IO) {
            withTimeout(90_000) {
                val instance = repository.instance(entry.providerInstanceId)
                    ?: error("Provider not found")
                require(instance.providerType in setOf(ProviderType.openAI, ProviderType.openAIResponses) &&
                    instance.credentialType == ProviderCredential.apiKey && !instance.azureMode) {
                    "Native web search requires an OpenAI Responses API endpoint with API key authentication"
                }
                val key = repository.usableApiKey(instance) ?: throw LLMError.InvalidApiKey()
                val provider = ProviderFactory.create(instance, key, entry.model, context,
                    overrides = entry.overrides.copy(nativeWebSearch = true)) as OpenAIProvider
                provider.nativeWebSearchEnabled = true
                // This fresh probe offers only hosted search, so required guarantees search.
                provider.chatExtraBody = mapOf("tool_choice" to "required")
                val evidence = WebSearchProbeEvidence()
                provider.onNativeWebSearchCall = evidence::observe
                val response = provider.sendMessage(
                    messages = listOf(LLMMessage(LLMMessage.Role.USER,
                        "Search the web for the current OpenAI API web search documentation. " +
                            "Return its title, one short sentence describing it, and its source URL. Use web search.")),
                    systemPrompt = null,
                    maxTokens = 2048,
                    thinkingLevel = ThinkingDefaults.load(context).takeIf { it.isEnabled } ?: ThinkingLevel.LOW,
                )
                NativeWebSearchProbeResult(evidence.completedCalls > 0, evidence.completedCalls,
                    evidence.queries, response.text)
            }
        }
}
