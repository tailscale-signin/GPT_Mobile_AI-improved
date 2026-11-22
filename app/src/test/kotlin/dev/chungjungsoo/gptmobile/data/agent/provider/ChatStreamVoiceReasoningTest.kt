package dev.chungjungsoo.gptmobile.data.agent.provider

import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.dto.openai.response.ChatFunctionDelta
import dev.chungjungsoo.gptmobile.data.dto.openai.response.ChatToolCallDelta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatStreamVoiceReasoningTest {
    @Test fun compatibleRoutesSeparateLeadingReasoningBeforeAnySpokenFragment() {
        val assembler = ChatCompletionsEventAssembler()
        assembler.observeModel("xiaomi/mimo")
        val thinking = "<think>The user is asking. Let me think.</think>"
        val before = thinking.map { assembler.accept(it.toString(), null, null, null) }.flatten()
        assertTrue(before.none { it is ProviderEvent.TextDelta })
        val answer = assembler.accept("Here is the answer.", null, null, "stop")
        assertEquals("Here is the answer.", answer.filterIsInstance<ProviderEvent.TextDelta>().joinToString("") { it.text })
        assertEquals("xiaomi/mimo", answer.filterIsInstance<ProviderEvent.TextDelta>().single().lineage.reportedModelId)
        assertEquals("The user is asking. Let me think.", before.filterIsInstance<ProviderEvent.ThinkingDelta>().joinToString("") { it.text })
    }

    @Test fun truncationAndMissingDoneNeverPromoteUnclosedReasoning() {
        for (finish in listOf("length", "stop", null)) {
            val assembler = ChatCompletionsEventAssembler()
            val states = assembler.accept("<think>unfinished plan", null, null, finish) + assembler.discardIncomplete()
            assertTrue(states.none { it is ProviderEvent.TextDelta })
            assertEquals("unfinished plan", states.filterIsInstance<ProviderEvent.ThinkingDelta>().joinToString("") { it.text })
        }
    }

    @Test fun parserStateIsIsolatedAcrossChoices() {
        val assembler = ChatCompletionsEventAssembler()
        val states = assembler.accept("<think>plan", null, null, null, choiceIndex = 0) +
            assembler.accept("Second answer", null, null, "stop", choiceIndex = 1) +
            assembler.accept("</think>First answer", null, null, "stop", choiceIndex = 0)
        assertEquals(listOf("Second answer", "First answer"), states.filterIsInstance<ProviderEvent.TextDelta>().map { it.text })
        assertEquals("plan", states.filterIsInstance<ProviderEvent.ThinkingDelta>().single().text)
    }

    @Test fun toolArgumentsAndLiteralCodeKeepTheirExactContent() {
        val assembler = ChatCompletionsEventAssembler()
        val code = "```xml\n<think>literal</think>\n```"
        val arguments = "{\"text\":\"<think>literal</think>\"}"
        val states = assembler.accept(code, null, listOf(ChatToolCallDelta(0, "call-1", function = ChatFunctionDelta("write", arguments))), "tool_calls")
        assertEquals(code, states.filterIsInstance<ProviderEvent.TextDelta>().single().text)
        assertEquals("write", states.filterIsInstance<ProviderEvent.ToolCall>().single().name)
        assertEquals(arguments, states.filterIsInstance<ProviderEvent.ToolCall>().single().arguments.toString())
        assertTrue(states.none { it is ProviderEvent.ThinkingDelta })
    }

    @Test fun typedReasoningAndAnswerRemainSeparate() {
        val states = ChatCompletionsEventAssembler().accept("Answer", "Typed planning", null, "stop")
        assertEquals("Typed planning", states.filterIsInstance<ProviderEvent.ThinkingDelta>().single().text)
        assertEquals("Answer", states.filterIsInstance<ProviderEvent.TextDelta>().single().text)
    }
}
