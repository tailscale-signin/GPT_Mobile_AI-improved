package dev.chungjungsoo.gptmobile.data.ollama

import dev.chungjungsoo.gptmobile.data.dto.openai.request.ChatCompletionRequest
import dev.chungjungsoo.gptmobile.data.dto.openai.request.ChatMessage
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OllamaOptionsTest {

    private val json = Json {
        encodeDefaults = false
        explicitNulls = false
    }

    @Test
    fun `default OllamaOptions values match specified defaults`() {
        val options = OllamaOptions.createDefault()

        assertEquals(999, options.numGpu)
        assertEquals(8192, options.numCtx)
        assertEquals(512, options.numBatch)
        assertEquals(10, options.numThread)
        assertEquals(0.2f, options.temperature ?: 0f, 0.001f)
        assertEquals(0.15f, options.topP ?: 0f, 0.001f)
        assertEquals(30, options.topK)
        assertEquals(1.1f, options.repeatPenalty ?: 0f, 0.001f)
        assertEquals(42, options.seed)
        assertEquals(listOf("```end", "delimiter", "You"), options.stop)
    }

    @Test
    fun `standard request does not serialize options when null`() {
        val request = ChatCompletionRequest(
            model = "gpt-oss",
            messages = listOf(ChatMessage(role = "user", content = "Hello"))
        )

        val encoded = json.encodeToString(request)

        assertFalse(encoded.contains("\"options\""))
    }

    @Test
    fun `request serializes ollama options payload when provided`() {
        val options = OllamaOptions.createDefault()
        val request = ChatCompletionRequest(
            model = "gpt-oss",
            messages = listOf(ChatMessage(role = "user", content = "Hello")),
            options = options
        )

        val encoded = json.encodeToString(request)

        assertTrue(encoded.contains("\"options\":{"))
        assertTrue(encoded.contains("\"num_gpu\":999"))
        assertTrue(encoded.contains("\"num_ctx\":8192"))
        assertTrue(encoded.contains("\"num_batch\":512"))
        assertTrue(encoded.contains("\"num_thread\":10"))
        assertTrue(encoded.contains("\"temperature\":0.2"))
        assertTrue(encoded.contains("\"top_p\":0.15"))
        assertTrue(encoded.contains("\"top_k\":30"))
        assertTrue(encoded.contains("\"repeat_penalty\":1.1"))
        assertTrue(encoded.contains("\"seed\":42"))
        assertTrue(encoded.contains("\"stop\":[\"```end\",\"delimiter\",\"You\"]"))
    }
}
