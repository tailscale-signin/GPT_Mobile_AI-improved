package dev.chungjungsoo.gptmobile.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SseUtilsTest {

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
}
