package com.openminis.app.agent.jobs

import com.openminis.app.data.model.SubAgentDefinition
import com.openminis.app.tools.AgentTools
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class HelperToolContractTest {
    private fun definition(names: List<String> = listOf(SubAgentDefinition.BUILT_IN_NAME)) =
        AgentTools.makeAgentTools(rosterNames = names).single { it.name == HelperRunner.TOOL_NAME }

    @Test fun `background payload recommends only the offered current control tool`() {
        for (converted in listOf(false, true)) {
            val note = JSONObject(HelperRunner.backgroundStartJson("J", "C", "model", HelperModelTier.PRIMARY, 10, converted)).getString("note")
            assertTrue(note.contains("subagent_task with action=status"))
            assertFalse(note.contains("agent_status"))
        }
    }

    @Test fun `empty roster still emits a usable agent enum`() {
        assertEquals(listOf(SubAgentDefinition.BUILT_IN_NAME), definition(emptyList()).parameters.getValue("agent").enumValues)
    }

    @Test fun `schema documents the shared workspace and bounded backlog`() {
        val tool = definition()
        assertTrue(tool.description.contains("Workspace files are shared"))
        assertTrue(tool.description.contains("Only 10 run at once and at most 10 more"))
        assertTrue(tool.description.contains("status=rejected"))
        assertTrue(tool.description.contains("Never blindly re-delegate"))
        assertFalse(tool.parameters.getValue("action").description.contains("`job_id`/`child_session_id`"))
        assertTrue(tool.parameters.getValue("max_minutes").description.contains("90 seconds total grace"))
    }

    @Test fun `helpers cannot schedule or delegate and know files and tool effects survive request failure`() {
        val cfg = HelperConfig("P", "T", "J", 200, "work", HelperModelTier.PRIMARY)
        val prompt = HelperRunner.identitySection(cfg)
        assertTrue(prompt.contains("workspace and files are shared"))
        assertTrue(prompt.contains("do not repeat a write, message, install or other side effect"))
        assertTrue(prompt.contains("Do not create scheduled tasks or delegate further"))
        assertFalse(AgentTools.makeAgentTools(isHelper = true).any { it.name == HelperRunner.TOOL_NAME })
    }

    @Test fun `resume notice describes uncertain live state without telling child to replay commands`() {
        val notice = HelperRunner.resumeNotice()
        assertTrue(notice.contains("live state may have been lost"))
        assertTrue(notice.contains("Inspect current state"))
        assertTrue(notice.contains("do not repeat completed writes"))
        assertFalse(notice.contains("re-run commands"))
    }

    @Test fun `empty failure and stop results never encourage automatic redelegation`() {
        for (status in listOf("failed", "timeout", "interrupted", "cancelled")) {
            assertFalse(HelperRunner.emptyResultNote(status).contains("re-delegate"))
        }
        assertTrue(HelperRunner.emptyResultNote("cancelled").contains("wait for the user's instruction"))
        assertTrue(HelperRunner.emptyResultNote("failed").contains("completed tool results"))
    }
}
