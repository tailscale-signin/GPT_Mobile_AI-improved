package dev.chungjungsoo.gptmobile.data.agent.provider

import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.ToolBatchOversizeException
import dev.chungjungsoo.gptmobile.data.dto.anthropic.response.ContentBlock
import dev.chungjungsoo.gptmobile.data.dto.anthropic.response.ContentBlockType
import dev.chungjungsoo.gptmobile.data.dto.anthropic.response.ContentStartResponseChunk
import dev.chungjungsoo.gptmobile.data.dto.openai.response.OutputItem
import dev.chungjungsoo.gptmobile.data.dto.openai.response.OutputItemAddedEvent
import dev.chungjungsoo.gptmobile.data.dto.openai.response.ResponseErrorEvent
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderAssemblyBoundsTest {
    @Test fun responsesRejectBeforeAllocatingTheNinthStreamedSlot() {
        val assembler = OpenAIResponsesEventAssembler()
        assertEarlyRejection { index ->
            assembler.accept(OutputItemAddedEvent(index, OutputItem("function_call", "item_$index", "call_$index", "read", "{}")))
        }
    }

    @Test fun anthropicRejectsBeforeAllocatingTheNinthStreamedSlot() {
        val assembler = AnthropicEventAssembler()
        assertEarlyRejection { index ->
            assembler.accept(ContentStartResponseChunk(index, ContentBlock(ContentBlockType.TOOL_USE, id = "call_$index", name = "read", input = JsonObject(emptyMap()))))
        }
    }

    @Test fun unknownGatewayOutcomeKeepsItsCodeAcrossResponseAssembly() {
        val failure = OpenAIResponsesEventAssembler().accept(ResponseErrorEvent(message = "Remote result unknown", code = "GATEWAY_OUTCOME_UNKNOWN")).single() as ProviderEvent.Failed
        assertEquals("GATEWAY_OUTCOME_UNKNOWN", failure.errorCode)
    }

    private fun assertEarlyRejection(accept: (Int) -> List<ProviderEvent>) {
        val emitted = mutableListOf<ProviderEvent>()
        var attempted = 0
        val failure = try {
            repeat(129) { index ->
                attempted++
                emitted += accept(index)
            }
            null
        } catch (failure: ToolBatchOversizeException) {
            failure
        }
        assertEquals(9, attempted)
        assertEquals(9, failure?.observedValue)
        assertEquals(8, failure?.allowedValue)
        assertTrue(emitted.none { it is ProviderEvent.ToolCall })
    }
}
