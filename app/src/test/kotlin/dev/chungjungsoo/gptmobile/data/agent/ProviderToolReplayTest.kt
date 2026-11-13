package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.agent.provider.toAnthropicMessages
import dev.chungjungsoo.gptmobile.data.agent.provider.toChatMessages
import dev.chungjungsoo.gptmobile.data.agent.provider.toGeminiContents
import dev.chungjungsoo.gptmobile.data.dto.anthropic.common.ToolUseContent
import dev.chungjungsoo.gptmobile.data.dto.google.common.FunctionCall
import dev.chungjungsoo.gptmobile.data.dto.google.common.Part
import dev.chungjungsoo.gptmobile.data.dto.openai.common.TextContent
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderToolReplayTest {
    @Test fun originalInvocationIsSeparateFromNormalizedExecutionForEveryReplayProvider() {
        for (arguments in listOf(JsonObject(emptyMap()), buildJsonObject { put("action", "") }, buildJsonObject { put("action", "rerun") })) {
            val original = ProviderEvent.ToolCall("opaque call/東京:0", "github_ci", arguments)
            val execution = original.captureProviderInvocation().copy(name = "github_ci__fixture", arguments = buildJsonObject { put("action", "resolved_action") })
            val exchange = AgentToolExchange(listOf(execution), listOf(AgentToolResult(original.callId, ToolResultContent.Text("Retained receipt"), false)))
            val chat = exchange.toChatMessages()
            val wire = chat.first().toolCalls!!.single()
            assertEquals(original.callId, wire.id)
            assertEquals(original.name, wire.function.name)
            assertEquals(original.arguments.toString(), wire.function.arguments)
            assertEquals(original.callId, chat.last().toolCallId)
            assertEquals("Retained receipt", (chat.last().content!!.single() as TextContent).text)
            val anthropic = exchange.toAnthropicMessages(null).first().content.single() as ToolUseContent
            assertEquals(original.callId, anthropic.id)
            assertEquals(original.name, anthropic.name)
            assertEquals(original.arguments, anthropic.input)
            val gemini = exchange.toGeminiContents(null)
            val call = gemini.first().parts.single().functionCall!!
            assertEquals(original.callId, call.id)
            assertEquals(original.name, call.name)
            assertEquals(original.arguments, call.args)
            assertEquals(original.name, gemini.last().parts.single().functionResponse!!.name)
            assertEquals(original.callId, gemini.last().parts.single().functionResponse!!.id)
            assertEquals("github_ci__fixture", execution.name)
            assertEquals(buildJsonObject { put("action", "resolved_action") }, execution.arguments)
        }
    }

    @Test fun retainedProviderPartsKeepOpaqueSignatureAndOriginalResponseIdentity() {
        val original = ProviderEvent.ToolCall("local-id", "github_ci", JsonObject(emptyMap()))
        val execution = original.captureProviderInvocation().copy(name = "github_ci__fixture")
        val exchange = AgentToolExchange(listOf(execution), listOf(AgentToolResult(original.callId, ToolResultContent.Text("Receipt"), false)))
        val parts = listOf(Part(thoughtSignature = "opaque-signature", functionCall = FunctionCall("provider-id", original.name, original.arguments)))
        val replay = exchange.toGeminiContents(parts)
        assertEquals(parts, replay.first().parts)
        assertEquals("provider-id", replay.last().parts.single().functionResponse!!.id)
        assertEquals(original.name, replay.last().parts.single().functionResponse!!.name)
    }

    @Test fun mismatchedFingerprintCannotSubstituteChangedEnvelopeOrCallId() {
        val original = ProviderEvent.ToolCall("original", "github_ci", JsonObject(emptyMap())).captureProviderInvocation()
        for (changed in listOf(
            original.copy(callId = "changed"),
            original.copy(originalInvocation = ProviderToolInvocation("github_write", original.arguments)),
            original.copy(originalInvocation = ProviderToolInvocation(original.name, buildJsonObject { put("action", "write") })),
            original.copy(originalInvocationFingerprint = null),
            original.copy(name = "github_ci__fixture", originalInvocation = null)
        )) {
            assertTrue(runCatching { AgentToolExchange(listOf(changed), emptyList()).toChatMessages() }.isFailure)
        }
    }
}
