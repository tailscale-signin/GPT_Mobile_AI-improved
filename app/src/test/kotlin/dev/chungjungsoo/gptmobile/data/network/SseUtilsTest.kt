package dev.chungjungsoo.gptmobile.data.network

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@Serializable
private data class SampleChunk(val id: String, val content: String? = null)

class SseUtilsTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun extractSseData_withStandardSpace_returnsData() {
        val line = "data: {\"text\":\"hello\"}"
        val result = SseUtils.extractSseData(line)
        assertEquals("{\"text\":\"hello\"}", result)
    }

    @Test
    fun extractSseData_withoutSpace_returnsData() {
        val line = "data:{\"text\":\"hello\"}"
        val result = SseUtils.extractSseData(line)
        assertEquals("{\"text\":\"hello\"}", result)
    }

    @Test
    fun extractSseData_withMultipleSpaces_returnsTrimmedData() {
        val line = "data:    {\"text\":\"hello\"}   "
        val result = SseUtils.extractSseData(line)
        assertEquals("{\"text\":\"hello\"}", result)
    }

    @Test
    fun extractSseData_withDoneSentinel_returnsDone() {
        val line = "data: [DONE]"
        val result = SseUtils.extractSseData(line)
        assertEquals("[DONE]", result)
    }

    @Test
    fun extractSseData_withEmptyPayload_returnsNull() {
        assertNull(SseUtils.extractSseData("data:"))
        assertNull(SseUtils.extractSseData("data: "))
        assertNull(SseUtils.extractSseData("data:    "))
    }

    @Test
    fun extractSseData_withCommentOrOtherEvent_returnsNull() {
        assertNull(SseUtils.extractSseData(": ping"))
        assertNull(SseUtils.extractSseData("event: message"))
        assertNull(SseUtils.extractSseData("id: 123"))
        assertNull(SseUtils.extractSseData(""))
    }

    @Test
    fun isDone_withDoneSentinel_returnsTrue() {
        assertTrue(SseUtils.isDone("[DONE]"))
        assertTrue(SseUtils.isDone("  [DONE]  "))
        assertFalse(SseUtils.isDone("{\"text\":\"done\"}"))
    }

    @Test
    fun safeParseChunk_withValidJson_returnsParsedObject() {
        val raw = "{\"id\":\"chunk-1\",\"content\":\"hello\"}"
        val result = SseUtils.safeParseChunk<SampleChunk>(json, raw)
        assertNotNull(result)
        assertEquals("chunk-1", result?.id)
        assertEquals("hello", result?.content)
    }

    @Test
    fun safeParseChunk_withDoneSentinel_returnsNull() {
        val result = SseUtils.safeParseChunk<SampleChunk>(json, "[DONE]")
        assertNull(result)
    }

    @Test
    fun safeParseChunk_withTruncatedOrMalformedJson_returnsNullGracefully() {
        var errorReported = false
        val truncatedJson = "{\"id\":\"chunk-1\",\"content\":"

        val result = SseUtils.safeParseChunk<SampleChunk>(json, truncatedJson) { error, payload ->
            errorReported = true
            assertEquals(truncatedJson, payload)
        }

        assertNull(result)
        assertTrue(errorReported)
    }
}
