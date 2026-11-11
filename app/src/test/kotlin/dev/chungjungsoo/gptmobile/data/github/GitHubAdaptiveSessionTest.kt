package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentProviderSession
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolExchange
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GitHubAdaptiveSessionTest {
    private val options = """{"options":[{"id":"A","proposal":"Reuse components","tradeoffs":"Less variation","assumptions":"Source inspection pending"},{"id":"B","proposal":"New layout","tradeoffs":"More tests","assumptions":"Device qualification pending"}]}"""
    private val selection = """{"selected_id":"A","rationale":"Smaller tested change","risks":["Need source inspection"],"tests":["Compose interaction"]}"""
    private fun session(vararg events: ProviderEvent) = object : AgentProviderSession {
        override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flowOf(*events)
    }

    @Test fun optionsSurviveFailedSelectionAndResumeRunsOnlyUnfinishedStage() = runBlocking {
        var saved: JsonObject? = null
        var failSelection = true
        val roles = mutableListOf<String>()
        var executions = 0
        fun wrapped() = GitHubAdaptiveSession(
            session(),
            "github-design-options",
            "task-A",
            open = { role, instruction ->
                roles += role
                assertTrue(instruction.isNotBlank())
                if (role == "github-decision-selection" && failSelection) {
                    session(ProviderEvent.Failed("cooldown", errorCode = "HTTP_429"))
                } else {
                    session(ProviderEvent.TextDelta(if (role.endsWith("selection")) selection else options), ProviderEvent.Completed)
                }
            },
            continueWith = { instruction ->
                assertTrue(instruction.contains("not evidence"))
                executions++
                session(ProviderEvent.TextDelta("Verified result"), ProviderEvent.Completed)
            },
            load = { saved },
            save = { saved = it }
        )
        val failed = wrapped().streamRound(emptyList(), emptyList()).toList()
        assertEquals("HTTP_429", failed.filterIsInstance<ProviderEvent.Failed>().single().errorCode)
        assertNotNull(saved?.get("options"))
        assertEquals(0, executions)
        failSelection = false
        val resumed = wrapped().streamRound(emptyList(), emptyList()).toList()
        assertEquals(listOf("github-design-options", "github-decision-selection", "github-decision-selection"), roles)
        assertEquals(listOf("Verified result"), resumed.filterIsInstance<ProviderEvent.TextDelta>().map { it.text })
        wrapped().streamRound(emptyList(), emptyList()).toList()
        assertEquals(3, roles.size)
    }

    @Test fun invalidSelectionAndToolCallsCannotReachExecution() = runBlocking {
        for (bad in listOf(ProviderEvent.TextDelta(selection.replace("\"A\"", "\"missing\"")), ProviderEvent.ToolCall("mutation", "github_commit", JsonObject(emptyMap())))) {
            var executes = false
            val wrapper = GitHubAdaptiveSession(session(), "github-design-options", "task", open = { role, _ ->
                if (role.endsWith("options")) session(ProviderEvent.TextDelta(options), ProviderEvent.Completed) else session(bad, ProviderEvent.Completed)
            }, continueWith = {
                executes = true
                session()
            }, load = { null }, save = {})
            assertTrue(wrapper.streamRound(emptyList(), emptyList()).toList().any { it is ProviderEvent.Failed })
            assertFalse(executes)
        }
    }

    @Test fun changedBindingDiscardsOldProposalAndCancellationRemainsCancellation() = runBlocking {
        val saved = Json.parseToJsonElement("""{"binding":"other-task","options":$options,"selection":$selection}""") as JsonObject
        val wrapper = GitHubAdaptiveSession(session(), "github-design-options", "task", open = { _, _ -> throw CancellationException("cancel") }, continueWith = { session() }, load = { saved }, save = {})
        try {
            wrapper.streamRound(emptyList(), emptyList()).toList()
            fail("cancel expected")
        } catch (_: CancellationException) { }
    }
}
