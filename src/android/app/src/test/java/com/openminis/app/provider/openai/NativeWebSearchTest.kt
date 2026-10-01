package com.openminis.app.provider.openai

import com.openminis.app.data.model.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class NativeWebSearchTest {
    private fun body(provider: OpenAIProvider, tools: List<AgentToolDefinition> = emptyList(), temperature: Double? = null) =
        provider.buildResponsesAPIBody(listOf(LLMMessage(LLMMessage.Role.USER, "news")), null, 256, true,
            tools = tools, thinkingLevel = ThinkingLevel.OFF, temperature = temperature)

    @Test fun `search is off by default and app tools retain optional fields`() {
        val p = OpenAIProvider(apiKey = "test", model = LLMModel.gpt4oMini)
        assertFalse(body(p).has("tools"))
        p.nativeWebSearchEnabled = true
        val tool = AgentToolDefinition("shell_execute", "Execute", mapOf("command" to AgentToolParam("string", "Command")))
        val b = body(p, listOf(tool))
        val tools = b.getJSONArray("tools")
        assertEquals(2, tools.length())
        assertEquals("function", tools.getJSONObject(0).getString("type"))
        assertFalse(tools.getJSONObject(0).getBoolean("strict"))
        assertFalse(tools.getJSONObject(0).getJSONObject("parameters").has("required"))
        assertEquals("web_search", tools.getJSONObject(1).getString("type"))
        assertEquals("auto", b.getString("tool_choice"))
    }

    @Test fun `search tool is not duplicated after custom body merge`() {
        val p = OpenAIProvider(apiKey = "test", model = LLMModel.gpt4oMini)
        p.nativeWebSearchEnabled = true
        p.chatExtraBody = mapOf("tools" to org.json.JSONArray().put(JSONObject().put("type", "web_search")))
        assertEquals(1, body(p).getJSONArray("tools").length())
    }

    @Test fun `explicit Responses temperature takes precedence over stored default`() {
        val p = OpenAIProvider(apiKey = "test", model = LLMModel.gpt4oMini)
        p.modelOverrides = ModelOverrides(temperature = 0.8)
        assertEquals(0.2, body(p, temperature = 0.2).getDouble("temperature"), 0.0)
        assertEquals(0.8, body(p).getDouble("temperature"), 0.0)
    }

    @Test fun `search option survives JSON backup and older configs remain off`() {
        val saved = ModelOverrides(nativeWebSearch = true)
        assertFalse(saved.isEmpty)
        assertTrue(Json.decodeFromString<ModelOverrides>(Json.encodeToString(saved)).nativeWebSearch)
        assertFalse(Json.decodeFromString<ModelOverrides>("{}").nativeWebSearch)
    }

    @Test fun `enabled search uses Responses and citation updates are idempotent`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val annotation = """{"type":"url_citation","start_index":0,"end_index":4,"title":"Source","url":"https://example.com/"}"""
            val item = """{"type":"message","id":"m1","content":[{"type":"output_text","text":"News","annotations":[$annotation]}]}"""
            val events = listOf(
                """{"type":"response.output_item.added","item":{"type":"message","id":"m1"}}""",
                """{"type":"response.output_text.delta","delta":"News"}""",
                """{"type":"response.output_text.annotation.added","annotation":$annotation}""",
                """{"type":"response.output_text.done","text":"News"}""",
                """{"type":"response.output_item.done","item":$item}""",
                """{"type":"response.completed","response":{"status":"completed","output":[$item]}}""",
            )
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody(events.joinToString("") { "data: $it\n\n" }))
            val p = OpenAIProvider(apiKey = "test", model = LLMModel.gpt4oMini,
                basePath = server.url("/v1").toString().trimEnd('/'))
            p.nativeWebSearchEnabled = true
            val chunks = p.streamMessageClamped(listOf(LLMMessage(LLMMessage.Role.USER, "news")), null,
                256, null, emptyList(), emptyList(), ThinkingLevel.OFF).toList()
            val text = StringBuilder()
            chunks.filterIsInstance<LLMStreamChunk.Text>().forEach {
                text.setLength((text.length - it.replacePrevious).coerceAtLeast(0)); text.append(it.text)
            }
            assertEquals("[News](https://example.com/)", text.toString())
            assertEquals(1, chunks.filterIsInstance<LLMStreamChunk.Text>().count { it.replacePrevious > 0 })
            assertEquals(1, chunks.filterIsInstance<LLMStreamChunk.Finished>().size)
            val request = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("/v1/responses", request.path)
            assertEquals("Bearer test", request.getHeader("Authorization"))
            assertEquals("web_search", JSONObject(request.body.readUtf8()).getJSONArray("tools").getJSONObject(0).getString("type"))
        } finally { server.shutdown() }
    }
}
