package dev.chungjungsoo.gptmobile.data.agent.provider

import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.dto.anthropic.response.MessageResponseChunk
import dev.chungjungsoo.gptmobile.data.dto.google.response.GenerateContentResponse
import dev.chungjungsoo.gptmobile.data.dto.openai.response.ChatCompletionChunk
import dev.chungjungsoo.gptmobile.data.dto.openai.response.ChatFunctionDelta
import dev.chungjungsoo.gptmobile.data.dto.openai.response.ChatToolCallDelta
import dev.chungjungsoo.gptmobile.data.dto.openai.response.ResponsesStreamEvent
import dev.chungjungsoo.gptmobile.data.network.NetworkClient
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderEventAssemblerTest {

    @Test
    fun `compatible stop completes location call and assigns a replayable id when omitted`() {
        val assembler = ChatCompletionsEventAssembler()
        val events = assembler.accept(
            null,
            null,
            listOf(ChatToolCallDelta(function = ChatFunctionDelta("device_location", "{}"))),
            "stop"
        )
        val call = events.single() as ProviderEvent.ToolCall
        assertEquals("device_location", call.name)
        assertEquals("{}", call.arguments.toString())
        assertTrue(call.callId.isNotBlank())
        assertTrue(assembler.finish().isEmpty())
    }

    @Test
    fun `complete messages keep distinct calls without streaming indices`() {
        val fixture = """{"choices":[{"message":{"tool_calls":[{"id":"a","function":{"name":"device_location","arguments":"{}"}},{"id":"b","function":{"name":"weather","arguments":"{\"city\":\"Tokyo\"}"}}]},"finish_reason":"stop"}]}"""
        val choice = NetworkClient.openAIJson.decodeFromString<ChatCompletionChunk>(fixture).choices!!.single()
        val events = ChatCompletionsEventAssembler().accept(null, null, choice.effectiveDelta.toolCalls, choice.finishReason)
        assertEquals(listOf("a", "b"), events.filterIsInstance<ProviderEvent.ToolCall>().map { it.callId })
        assertEquals(listOf("device_location", "weather"), events.filterIsInstance<ProviderEvent.ToolCall>().map { it.name })
    }

    @Test
    fun `truncated blocked malformed or unnamed calls never execute`() {
        listOf(
            Triple("device_location", "{}", "length"),
            Triple("device_location", "{}", "content_filter"),
            Triple("device_location", "{", "stop"),
            Triple("device_location", "[]", "tool_calls"),
            Triple(null, "{}", "stop")
        ).forEach { (name, args, reason) ->
            val assembler = ChatCompletionsEventAssembler()
            val events = assembler.accept(null, null, listOf(ChatToolCallDelta(function = ChatFunctionDelta(name, args))), reason)
            assertTrue(events.single() is ProviderEvent.Failed)
            assertTrue(assembler.finish().isEmpty())
        }
    }

    @Test
    fun `pending calls require a clean protocol end and prose is never executable`() {
        val calls = listOf(ChatToolCallDelta(function = ChatFunctionDelta("device_location", "{}")))
        val completed = ChatCompletionsEventAssembler()
        assertTrue(completed.accept(null, null, calls, null).isEmpty())
        assertTrue(completed.finish().single() is ProviderEvent.ToolCall)
        val interrupted = ChatCompletionsEventAssembler()
        interrupted.accept(null, null, calls, null)
        assertTrue(interrupted.discardIncomplete().single() is ProviderEvent.Failed)
        assertTrue(interrupted.finish().isEmpty())
        val prose = "Testing device_location() {\"permission_status\":\"granted\"}"
        assertEquals(listOf(ProviderEvent.TextDelta(prose)), interrupted.accept(prose, null, null, "stop"))
    }

    @Test
    fun `responses assembler preserves function call id and completed arguments`() {
        val assembler = OpenAIResponsesEventAssembler()
        val events = listOf(
            """{"type":"response.output_item.added","output_index":0,"item":{"type":"function_call","id":"fc_1","call_id":"call_exact_1","name":"weather","arguments":""}}""",
            """{"type":"response.function_call_arguments.delta","item_id":"fc_1","output_index":0,"delta":"{\"city\":"}""",
            """{"type":"response.function_call_arguments.done","item_id":"fc_1","output_index":0,"arguments":"{\"city\":\"Tokyo\"}"}"""
        ).flatMap { fixture ->
            assembler.accept(NetworkClient.openAIJson.decodeFromString<ResponsesStreamEvent>(fixture))
        }

        assertEquals(
            listOf(
                ProviderEvent.ToolCall(
                    callId = "call_exact_1",
                    name = "weather",
                    arguments = buildJsonObject { put("city", "Tokyo") }
                )
            ),
            events
        )
    }

    @Test
    fun `chat completions assembler joins indexed tool call deltas`() {
        val assembler = ChatCompletionsEventAssembler()
        val fixtures = listOf(
            """{"id":"chat_1","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_exact_2","type":"function","function":{"name":"search","arguments":"{\"query\":"}}]},"finish_reason":null}]}""",
            """{"id":"chat_1","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"agents\"}"}}]},"finish_reason":"tool_calls"}]}"""
        )
        val events = fixtures.flatMap { fixture ->
            val chunk = NetworkClient.openAIJson.decodeFromString<ChatCompletionChunk>(fixture)
            val choice = chunk.choices.orEmpty().first()
            assembler.accept(
                content = choice.delta.content,
                reasoning = null,
                toolCalls = choice.delta.toolCalls,
                finishReason = choice.finishReason
            )
        }

        assertEquals(
            listOf(
                ProviderEvent.ToolCall(
                    callId = "call_exact_2",
                    name = "search",
                    arguments = buildJsonObject { put("query", "agents") }
                )
            ),
            events
        )
    }

    @Test
    fun `anthropic assembler joins tool use input json deltas`() {
        val assembler = AnthropicEventAssembler()
        val events = listOf(
            """{"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"call_exact_3","name":"lookup","input":{}}}""",
            """{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"id\":"}}""",
            """{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"7}"}}""",
            """{"type":"content_block_stop","index":1}"""
        ).flatMap { fixture ->
            assembler.accept(NetworkClient.json.decodeFromString<MessageResponseChunk>(fixture))
        }

        assertEquals(
            listOf(
                ProviderEvent.ToolCall(
                    callId = "call_exact_3",
                    name = "lookup",
                    arguments = buildJsonObject { put("id", 7) }
                )
            ),
            events
        )
    }

    @Test
    fun `gemini mapper preserves function call id and structured args`() {
        val fixture = """{"candidates":[{"index":0,"content":{"role":"model","parts":[{"functionCall":{"id":"call_exact_4","name":"read_url","args":{"url":"https://example.com"}}}]}}]}"""
        val response = NetworkClient.json.decodeFromString<GenerateContentResponse>(fixture)

        assertEquals(
            listOf(
                ProviderEvent.ToolCall(
                    callId = "call_exact_4",
                    name = "read_url",
                    arguments = buildJsonObject { put("url", "https://example.com") }
                )
            ),
            GeminiEventMapper.accept(response)
        )
    }

    @Test
    fun `gemini mapper accepts function calls without provider ids`() {
        val fixture = """{"candidates":[{"index":0,"content":{"role":"model","parts":[{"functionCall":{"name":"read_url","args":{"url":"https://example.com"}}}]}}]}"""
        val response = NetworkClient.json.decodeFromString<GenerateContentResponse>(fixture)

        val event = GeminiEventMapper.accept(response).single() as ProviderEvent.ToolCall

        assertTrue(event.callId.isNotBlank())
        assertEquals("read_url", event.name)
        assertEquals(buildJsonObject { put("url", "https://example.com") }, event.arguments)
    }

    @Test
    fun `Responses completion forwards provider token usage`() {
        val fixture = """{"type":"response.completed","response":{"id":"r1","status":"completed","usage":{"input_tokens":100,"output_tokens":42,"total_tokens":142}}}"""
        val events = OpenAIResponsesEventAssembler().accept(NetworkClient.openAIJson.decodeFromString<ResponsesStreamEvent>(fixture))
        assertEquals(ProviderEvent.Usage(100, 42, 142), events.filterIsInstance<ProviderEvent.Usage>().single())
        assertEquals(ProviderEvent.Completed, events.last())
    }

    @Test
    fun `Claude usage includes cached input and cumulative output`() {
        val assembler = AnthropicEventAssembler()
        val start = """{"type":"message_start","message":{"id":"m1","model":"claude","content":[],"usage":{"input_tokens":20,"cache_read_input_tokens":30,"cache_creation_input_tokens":10,"output_tokens":1}}}"""
        val delta = """{"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},"usage":{"output_tokens":25}}"""
        assembler.accept(NetworkClient.json.decodeFromString<MessageResponseChunk>(start))
        val events = assembler.accept(NetworkClient.json.decodeFromString<MessageResponseChunk>(delta))
        assertEquals(ProviderEvent.Usage(60, 25, 85), events.single())
    }

    @Test
    fun `Gemini usage includes reasoning output without inventing unknown usage`() {
        val fixture = """{"usageMetadata":{"promptTokenCount":12,"candidatesTokenCount":8,"thoughtsTokenCount":6,"totalTokenCount":26}}"""
        val events = GeminiEventMapper.accept(NetworkClient.json.decodeFromString<GenerateContentResponse>(fixture))
        assertEquals(ProviderEvent.Usage(12, 14, 26), events.filterIsInstance<ProviderEvent.Usage>().single())
        assertTrue(GeminiEventMapper.accept(GenerateContentResponse()).filterIsInstance<ProviderEvent.Usage>().isEmpty())
    }
}
