package com.openminis.app.provider.openai

import com.openminis.app.data.ThinkingControlPolicy
import com.openminis.app.data.model.ThinkingLevel
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ModelControlsTest {
    @Test fun `slider levels include off once and follow model order`() {
        assertEquals(listOf(ThinkingLevel.OFF, ThinkingLevel.LOW, ThinkingLevel.HIGH),
            ThinkingControlPolicy.levels(listOf(ThinkingLevel.HIGH, ThinkingLevel.OFF, ThinkingLevel.LOW, ThinkingLevel.HIGH)))
        assertEquals(listOf(ThinkingLevel.OFF), ThinkingControlPolicy.levels(emptyList()))
    }
    @Test fun `session and group defaults override global including explicit off`() {
        assertEquals(ThinkingLevel.OFF, ThinkingControlPolicy.resolveDefault(ThinkingLevel.OFF, ThinkingLevel.HIGH, ThinkingLevel.MAX))
        assertEquals(ThinkingLevel.HIGH, ThinkingControlPolicy.resolveDefault(null, ThinkingLevel.HIGH, ThinkingLevel.MAX))
        assertEquals(ThinkingLevel.MAX, ThinkingControlPolicy.resolveDefault(null, null, ThinkingLevel.MAX))
    }
    @Test fun `normal text never proves web search capability`() {
        val evidence = WebSearchProbeEvidence()
        evidence.observe(JSONObject().put("type", "message").put("status", "completed"))
        assertEquals(0, evidence.completedCalls)
    }
    @Test fun `search proof requires completed status and deduplicates final events`() {
        val e = WebSearchProbeEvidence()
        val call = JSONObject().put("type", "web_search_call").put("id", "ws_1").put("status", "in_progress")
            .put("action", JSONObject().put("query", "OpenAI web search"))
        e.observe(call); assertEquals(0, e.completedCalls)
        val complete = JSONObject(call.toString()).put("status", "completed")
        e.observe(complete); e.observe(complete)
        assertEquals(1, e.completedCalls)
        assertEquals(listOf("OpenAI web search"), e.queries)
    }
}
