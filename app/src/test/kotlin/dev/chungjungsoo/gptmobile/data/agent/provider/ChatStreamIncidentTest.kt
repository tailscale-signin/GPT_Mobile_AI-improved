package dev.chungjungsoo.gptmobile.data.agent.provider

import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.ToolBatchOversizeException
import dev.chungjungsoo.gptmobile.data.dto.openai.response.ChatFunctionDelta
import dev.chungjungsoo.gptmobile.data.dto.openai.response.ChatToolCallDelta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatStreamIncidentTest {
    @Test fun hundredsOfFragmentsAreOneCallAndLateIdIsPreserved() {
        val assembler = ChatCompletionsEventAssembler()
        assembler.accept(null, null, listOf(ChatToolCallDelta(0, function = ChatFunctionDelta("read", "{\"query\":\""))), null)
        repeat(200) { assembler.accept(null, null, listOf(ChatToolCallDelta(0, function = ChatFunctionDelta(arguments = "x"))), null) }
        val events = assembler.accept(null, null, listOf(ChatToolCallDelta(0, "late-id", function = ChatFunctionDelta("read", "\"}"))), "tool_calls")
        val call = events.filterIsInstance<ProviderEvent.ToolCall>().single()
        assertEquals("late-id", call.callId)
        assertEquals("read", call.name)
        assertEquals(200, (call.arguments["query"] as kotlinx.serialization.json.JsonPrimitive).content.length)
        assertTrue(assembler.finish().isEmpty())
    }

    @Test fun choiceAndToolIndicesAreIndependentAndPartialChoicesAreNotFlushed() {
        val assembler = ChatCompletionsEventAssembler()
        assembler.accept(null, null, listOf(ChatToolCallDelta(0, "one", function = ChatFunctionDelta("read", "{"))), null, 0)
        val second = assembler.accept(null, null, listOf(ChatToolCallDelta(0, "two", function = ChatFunctionDelta("other", "{}"))), "stop", 1)
        assertEquals("two", second.filterIsInstance<ProviderEvent.ToolCall>().single().callId)
        val first = assembler.accept(null, null, listOf(ChatToolCallDelta(0, function = ChatFunctionDelta(arguments = "}"))), "stop", 0)
        assertEquals("one", first.filterIsInstance<ProviderEvent.ToolCall>().single().callId)
    }

    @Test fun malformedAndTruncatedArgumentsNeverBecomeExecutableCalls() {
        for (reason in listOf("stop", "length")) {
            val events = ChatCompletionsEventAssembler().accept(null, null, listOf(ChatToolCallDelta(0, "id", function = ChatFunctionDelta("read", "{broken"))), reason)
            assertTrue(events.any { it is ProviderEvent.Failed })
            assertFalse(events.any { it is ProviderEvent.ToolCall })
        }
    }

    @Test fun overflowRetainsOnlyBoundedSanitizedShapeAndDispatchesNothing() {
        val assembler = ChatCompletionsEventAssembler()
        try {
            repeat(129) { assembler.accept(null, null, listOf(ChatToolCallDelta(it, "private-id-$it", function = ChatFunctionDelta("private-tool", "{}"))), null) }
            throw AssertionError("Expected bounded stream rejection")
        } catch (failure: ToolBatchOversizeException) {
            assertEquals("STREAM_TOOL_SLOTS", failure.threshold)
            assertEquals(129, failure.observedValue)
            assertEquals(128, failure.completeCallIds)
            assertTrue(failure.sanitizedStreamShape.orEmpty().length < 1500)
            assertFalse(failure.message.orEmpty().contains("private-id"))
            assertFalse(failure.message.orEmpty().contains("private-tool"))
        }
    }

    @Test(expected = IllegalStateException::class)
    fun changingCallIdentityFailsBeforeDispatch() {
        val assembler = ChatCompletionsEventAssembler()
        assembler.accept(null, null, listOf(ChatToolCallDelta(0, "first", function = ChatFunctionDelta("read", "{"))), null)
        assembler.accept(null, null, listOf(ChatToolCallDelta(0, "different", function = ChatFunctionDelta(arguments = "}"))), "stop")
    }
}
