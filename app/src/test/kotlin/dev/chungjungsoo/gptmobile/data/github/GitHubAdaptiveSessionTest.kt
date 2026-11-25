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
import kotlinx.serialization.json.JsonPrimitive
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

    @Test fun completedPrivateLimitCannotConsumeThePublicRepair() = runBlocking {
        val privatePass = session(
            ProviderEvent.RequestConfigured(effectiveOutputTokens = 1024),
            ProviderEvent.Usage(outputTokens = 1024, reasoningTokens = 360, finishReason = "length", invocationId = "private-plan"),
            ProviderEvent.Failed("The response reached its output limit.", transportComplete = true)
        )
        val public = session(ProviderEvent.ThinkingDelta("working"), ProviderEvent.Usage(finishReason = "stop", invocationId = "public-turn"), ProviderEvent.Completed)
        val adaptive = GitHubAdaptiveSession(
            public,
            GitHubAdaptiveSession.INTENT_ROLE,
            "binding",
            open = { _, _ -> privatePass },
            continueWith = { error("Capped private plan cannot direct execution") },
            load = { null },
            save = { error("Capped plan cannot be saved") }
        )
        var repairs = 0
        var saved: JsonObject? = null
        val output = dev.chungjungsoo.gptmobile.data.agent.GitHubOutputContinuationSession(adaptive, saveCheckpoint = { saved = it }) { _, _, _ ->
            repairs++
            session(ProviderEvent.TextDelta("Verified public answer"), ProviderEvent.Completed)
        }.streamRound(emptyList(), emptyList()).toList()
        assertEquals(1, repairs)
        assertTrue(output.filterIsInstance<ProviderEvent.TextDelta>().any { "Verified public answer" in it.text })
        val usage = output.filterIsInstance<ProviderEvent.Usage>().single { it.advisoryRole != null }
        assertEquals("private-plan", usage.invocationId)
        assertEquals(360, usage.reasoningTokens)
        assertEquals(JsonPrimitive(1), saved?.get("no_output_repairs"))
        assertFalse((saved?.get("no_output_observation") as? JsonObject).toString().contains("private-plan"))
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

    @Test fun malformedAdviceFallsBackAndUnexpectedPlanningToolsStillBlock() = runBlocking {
        for (bad in listOf(ProviderEvent.TextDelta(selection.replace("\"A\"", "\"missing\"")), ProviderEvent.ToolCall("mutation", "github_commit", JsonObject(emptyMap())))) {
            var executes = false
            val primary = session(ProviderEvent.TextDelta("original"), ProviderEvent.Completed)
            val wrapper = GitHubAdaptiveSession(primary, "github-design-options", "task", open = { role, _ ->
                if (role.endsWith("options")) session(ProviderEvent.TextDelta(options), ProviderEvent.Completed) else session(bad, ProviderEvent.Completed)
            }, continueWith = {
                executes = true
                session()
            }, load = { null }, save = {})
            val events = wrapper.streamRound(emptyList(), emptyList()).toList()
            if (bad is ProviderEvent.ToolCall) {
                val failure = events.filterIsInstance<ProviderEvent.Failed>().single()
                assertEquals("GITHUB_ADAPTIVE_PLAN_INCOMPLETE", failure.errorCode)
                assertEquals("BLOCKED", failure.taskOutcome)
                assertEquals(JsonPrimitive("planning"), failure.failureDetails?.get("failed_stage"))
                assertEquals(JsonPrimitive("github_decide"), failure.failureDetails?.get("operation"))
                assertNotNull(failure.failureDetails?.get("exception_fingerprint"))
                assertTrue(events.none { it is ProviderEvent.TextDelta })
            } else {
                assertEquals("original", events.filterIsInstance<ProviderEvent.TextDelta>().single().text)
            }
            assertFalse(executes)
        }
        val blockedCases = listOf(
            session(ProviderEvent.TextDelta(options), ProviderEvent.Usage(finishReason = "content_filter", invocationId = "planning-request-7"), ProviderEvent.Completed) to "planning-request-7",
            session(ProviderEvent.TextDelta(options), ProviderEvent.Usage(finishReason = "length")) to null
        )
        for ((privatePass, expectedInvocation) in blockedCases) {
            val primary = session(ProviderEvent.TextDelta("original"), ProviderEvent.Completed)
            val wrapper = GitHubAdaptiveSession(primary, "github-design-options", "task", open = { _, _ -> privatePass }, continueWith = { error("Unexpected execution") }, load = { null }, save = { error("Unexpected save") })
            val events = wrapper.streamRound(emptyList(), emptyList()).toList()
            assertTrue(events.none { it is ProviderEvent.TextDelta })
            val failure = events.filterIsInstance<ProviderEvent.Failed>().single()
            assertEquals(expectedInvocation, failure.invocationId)
            assertEquals(GitHubAdaptiveSession.INCOMPLETE_CODE, failure.errorCode)
        }
    }

    @Test fun semanticPlanRunsOnceWithNoToolsAndAnInvalidPlanFallsBackToTheOriginalSession() = runBlocking {
        val plan = """{"intent":"MIXED","objective":"Explain the earlier result and inspect the change","steps":[{"goal":"Resolve the earlier result","proof":"A pinned source observation"}]}"""
        for (reason in listOf("length", "max_tokens")) {
            val privatePass = session(ProviderEvent.TextDelta(plan), ProviderEvent.Usage(outputTokens = 15, finishReason = reason), ProviderEvent.Completed)
            val wrapper = GitHubAdaptiveSession(
                session(ProviderEvent.TextDelta("original"), ProviderEvent.Completed),
                GitHubAdaptiveSession.INTENT_ROLE,
                "task",
                open = { _, _ -> privatePass },
                continueWith = { error("Truncated advice cannot direct execution") },
                load = { null },
                save = { error("Truncated advice cannot be saved") }
            )
            val events = wrapper.streamRound(emptyList(), emptyList()).toList()
            assertEquals(listOf("original"), events.filterIsInstance<ProviderEvent.TextDelta>().map { it.text })
            val usage = events.filterIsInstance<ProviderEvent.Usage>().single()
            assertEquals(15, usage.outputTokens)
            assertEquals(reason, usage.finishReason)
            assertEquals(GitHubAdaptiveSession.INTENT_ROLE, usage.advisoryRole)
        }
        for (response in listOf(plan, "unstructured answer", plan.replace("MIXED", "AUTHORIZE"))) {
            var saved: JsonObject? = null
            var calls = 0
            val wrapper = GitHubAdaptiveSession(session(ProviderEvent.TextDelta("original"), ProviderEvent.Completed), GitHubAdaptiveSession.INTENT_ROLE, "same-task", open = { _, _ ->
                calls++
                object : AgentProviderSession {
                    override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flowOf(ProviderEvent.TextDelta(response), ProviderEvent.Completed).also {
                        assertTrue(tools.isEmpty() && exchanges.isEmpty())
                    }
                }
            }, continueWith = { instruction ->
                assertTrue(instruction.contains("not evidence"))
                session(ProviderEvent.TextDelta("planned"), ProviderEvent.Completed)
            }, load = { saved }, save = { saved = it })
            repeat(2) {
                val text = wrapper.streamRound(emptyList(), emptyList()).toList().filterIsInstance<ProviderEvent.TextDelta>().single().text
                assertEquals(if (response == plan) "planned" else "original", text)
            }
            assertEquals(response == plan, saved?.containsKey("intent_plan") == true)
            assertEquals(1, calls)
        }
    }

    @Test fun changedBindingDiscardsOldProposalAndCancellationRemainsCancellation() = runBlocking {
        val failedLoad = GitHubAdaptiveSession(session(), "github-design-options", "task", open = { _, _ -> error("Unexpected planning request") }, continueWith = { error("Unexpected execution") }, load = { error("PRIVATE checkpoint detail") }, save = {})
        val failure = failedLoad.streamRound(emptyList(), emptyList()).toList().filterIsInstance<ProviderEvent.Failed>().single()
        assertFalse(failure.message.contains("PRIVATE"))
        assertEquals(JsonPrimitive("planning"), failure.failureDetails?.get("failed_stage"))
        assertNotNull(failure.failureDetails?.get("exception_fingerprint"))

        val saved = Json.parseToJsonElement("""{"binding":"other-task","options":$options,"selection":$selection}""") as JsonObject
        val wrapper = GitHubAdaptiveSession(session(), "github-design-options", "task", open = { _, _ -> throw CancellationException("cancel") }, continueWith = { session() }, load = { saved }, save = {})
        try {
            wrapper.streamRound(emptyList(), emptyList()).toList()
            fail("cancel expected")
        } catch (_: CancellationException) { }
    }
}
