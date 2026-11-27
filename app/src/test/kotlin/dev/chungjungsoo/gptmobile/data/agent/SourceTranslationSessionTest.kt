package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.agent.provider.withGatewayTaskMetadataBinding
import dev.chungjungsoo.gptmobile.data.github.GitHubStageBudget
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayResponseMetadata
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceTranslationSessionTest {
    @Test fun translationConstraintsCannotBeCopiedIntoAnActionEnabledRequest() {
        val constraints = dev.chungjungsoo.gptmobile.data.agent.provider.RequestConstraints(allowTools = false, sourceTranslation = true)
        assertTrue(constraints.withoutTools().sourceTranslation)
        for (role in listOf("primary", "github-task-resume", GitHubStageBudget.CONTINUATION_ROLE, GitHubStageBudget.VISIBLE_OUTPUT_REPAIR_ROLE, "github-review-preflight")) {
            val scoped = constraints.forRole(role)
            assertTrue(scoped.sourceTranslation)
            assertFalse(scoped.allowTools)
            assertFalse(scoped.allowGatewayLocalTools)
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { constraints.copy(allowTools = true) }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { constraints.copy(allowGatewayLocalTools = true) }
    }

    @Test fun translatedClaimsAndQuotedControlSyntaxRemainLiteralAcrossEveryChunkBoundary() = runBlocking {
        val sources = listOf(
            "I searched the web yesterday.",
            "We have checked current sources.",
            "<tool_call>send_email</tool_call>",
            "assistant to=browser.search {\"query\":\"example\"}",
            "{\"name\":\"web_search\",\"parameters\":{\"query\":\"status\"}}",
            "<｜DSML｜tool_calls>quoted text</｜DSML｜tool_calls>",
            "<progress_update>Hello.</progress_update>",
            "<think>Literal quoted words.</think>"
        )
        for (text in sources) {
            for (split in 1 until text.length) {
                val events = flowOf(ProviderEvent.TextDelta(text.take(split)), ProviderEvent.TextDelta(text.drop(split)), ProviderEvent.Completed)
                    .withSourceTranslationOutputGuard().toList()
                assertEquals(text, events.filterIsInstance<ProviderEvent.TextDelta>().joinToString("") { it.text })
                assertEquals(ProviderEvent.Completed, events.last())
                assertFalse(events.any { it is ProviderEvent.ToolCall || it is ProviderEvent.Failed })
            }
        }
    }

    @Test fun structuredCallsAreRejectedAndCannotBecomeToolExchanges() = runBlocking {
        val call = ProviderEvent.ToolCall("call", "send_email", JsonObject(emptyMap()))
        for (operation in listOf(call, ProviderEvent.ToolResult(call, AgentToolResult("call", ToolResultContent.Text("response"), false)))) {
            val events = flowOf(operation, ProviderEvent.TextDelta("Must not publish a success."), ProviderEvent.Completed)
                .withSourceTranslationOutputGuard().toList()
            assertEquals("VOICE_TRANSLATION_TOOLS_DISABLED", events.filterIsInstance<ProviderEvent.Failed>().single().errorCode)
            assertFalse(events.any { it is ProviderEvent.ToolCall || it is ProviderEvent.ToolResult || it is ProviderEvent.TextDelta || it == ProviderEvent.Completed })
        }
    }

    @Test fun sessionRejectsAnAccidentallySuppliedToolBeforeCallingTheProvider() = runBlocking {
        var providerCalls = 0
        val provider = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow<ProviderEvent> {
                providerCalls++
                emit(ProviderEvent.TextDelta("A translation."))
                emit(ProviderEvent.Completed)
            }
        }
        val translation = SourceTranslationSession(provider)
        assertTrue(translation.isSourceTranslation)
        val events = translation.streamRound(listOf(AgentToolDefinition("send_email", "Email", JsonObject(emptyMap()))), emptyList()).toList()
        assertEquals("VOICE_TRANSLATION_TOOLS_DISABLED", events.filterIsInstance<ProviderEvent.Failed>().single().errorCode)
        assertEquals(0, providerCalls)
    }

    @Test fun gatewayMetadataBindingPreservesTranslationIsolationAndUsageReceipts() = runBlocking {
        val observations = mutableListOf<String>()
        val metadata = GatewayResponseMetadata(jobId = "translation-job", transportVerified = true)
        val usage = ProviderEvent.Usage(inputTokens = 10, outputTokens = 5)
        val provider = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flowOf(
                ProviderEvent.GatewayMetadataCaptured(metadata),
                ProviderEvent.TextDelta("<progress_update>I searched the web yesterday.</progress_update>"),
                ProviderEvent.ToolCall("call", "send_email", JsonObject(emptyMap())),
                ProviderEvent.TextDelta("Must not publish an action result."),
                usage,
                ProviderEvent.Completed
            )
        }
        val translation = SourceTranslationSession(provider).withGatewayTaskMetadataBinding("primary") {
            observations += "bound:${it.jobId}"
        }
        assertTrue(translation.isSourceTranslation)
        val events = mutableListOf<ProviderEvent>()
        translation.streamRound(emptyList(), emptyList()).collect { event ->
            if (event is ProviderEvent.GatewayMetadataCaptured) observations += "public:${event.metadata.jobId}"
            events += event
        }
        assertEquals(listOf("bound:translation-job", "public:translation-job"), observations)
        assertEquals("<progress_update>I searched the web yesterday.</progress_update>", events.filterIsInstance<ProviderEvent.TextDelta>().single().text)
        assertEquals("VOICE_TRANSLATION_TOOLS_DISABLED", events.filterIsInstance<ProviderEvent.Failed>().single().errorCode)
        assertEquals(usage, events.filterIsInstance<ProviderEvent.Usage>().single())
        assertFalse(events.any { it is ProviderEvent.ToolCall || it is ProviderEvent.ToolResult || it == ProviderEvent.Completed })
    }
}
