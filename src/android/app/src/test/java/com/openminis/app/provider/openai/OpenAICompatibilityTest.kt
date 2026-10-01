package com.openminis.app.provider.openai

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OpenAICompatibilityTest {
    @Test fun `GPT6 uses Responses and current prompt cache lifetime`() {
        assertTrue(OpenAIRequestCompatibility.requiresResponses("gpt-6-astra"))
        assertFalse(OpenAIRequestCompatibility.requiresResponses("gpt-4.1"))
        val b = JSONObject().put("prompt_cache_retention", "24h").put("temperature", 0.7)
            .put("top_p", 0.9).put("top_logprobs", 3).put("reasoning", JSONObject().put("effort", "none"))
        OpenAIRequestCompatibility.sanitize(b, "gpt-6-astra", true)
        assertFalse(b.has("temperature")); assertFalse(b.has("top_p")); assertFalse(b.has("top_logprobs"))
        assertFalse(b.has("prompt_cache_retention"))
        assertEquals("30m", b.getJSONObject("prompt_cache_options").getString("ttl"))
        assertEquals("low", b.getJSONObject("reasoning").getString("effort"))
    }

    @Test fun `GPT51 non reasoning retains sampling while reasoning removes it`() {
        val b = JSONObject().put("temperature", 0.4).put("top_p", 0.8)
            .put("reasoning", JSONObject().put("effort", "none"))
        OpenAIRequestCompatibility.sanitize(b, "gpt-5.1", true)
        assertTrue(b.has("temperature")); assertTrue(b.has("top_p"))
        b.getJSONObject("reasoning").put("effort", "high")
        b.put("include", JSONArray().put("reasoning.encrypted_content").put("message.output_text.logprobs"))
        OpenAIRequestCompatibility.sanitize(b, "gpt-5.1", true)
        assertFalse(b.has("temperature")); assertFalse(b.has("top_p"))
        assertEquals("reasoning.encrypted_content", b.getJSONArray("include").getString(0))
        assertEquals(1, b.getJSONArray("include").length())
    }

    @Test fun `non reasoning model sampling stays intact`() {
        val b = JSONObject().put("temperature", 0.5).put("top_p", 0.9)
        OpenAIRequestCompatibility.sanitize(b, "gpt-4.1", true)
        assertEquals(0.5, b.getDouble("temperature"), 0.0)
    }

    @Test fun `citation links escape labels and handle missing or invalid indices`() {
        val c = WebSearchCitations()
        c.add(JSONObject().put("type", "url_citation").put("url", "https://example.com/a(b)")
            .put("title", "[新闻]\n来源"))
        assertEquals("Answer\n\n- [\\[新闻\\] 来源](https://example.com/a%28b%29)\n", c.renderInline("Answer"))
        c.add(JSONObject().put("type", "url_citation").put("url", "javascript:alert(1)").put("title", "bad"))
        assertFalse(c.renderInline("Answer").contains("javascript:"))
    }

    @Test fun `citations replace private markers and reset between messages`() {
        val c = WebSearchCitations()
        val text = "Result \uE200cite\uE201"
        val a = JSONObject().put("type", "url_citation").put("url", "https://example.com/")
            .put("title", "来源").put("start_index", 7).put("end_index", text.length)
        c.add(a); c.add(a)
        assertEquals("Result [来源](https://example.com/)", c.renderInline(text))
        assertEquals(c.renderInline(text), c.renderInline(text))
        c.resetMessage()
        assertFalse(c.hasPending); assertEquals(text, c.renderInline(text))
    }
}
