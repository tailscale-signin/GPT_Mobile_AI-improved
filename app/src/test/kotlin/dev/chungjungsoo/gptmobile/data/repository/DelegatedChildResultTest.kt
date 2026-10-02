package dev.chungjungsoo.gptmobile.data.repository

import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DelegatedChildResultTest {
    @Test
    fun `short factual answers are usable instead of counted as empty`() {
        for (answer in listOf("42", "Yes", "OK")) {
            val result = resolveDelegatedChildResult(answer, emptyList(), false, null)
            assertEquals(DelegatedChildStatus.COMPLETED, result.status)
            assertEquals(answer, result.text)
        }
    }

    @Test
    fun `direct text is a completed delegated result`() {
        val result = resolveDelegatedChildResult(
            rawText = "Useful delegated answer.",
            toolFallbacks = emptyList(),
            extractionFailed = false,
            providerFailure = null
        )

        assertEquals(DelegatedChildStatus.COMPLETED, result.status)
        assertEquals("Useful delegated answer.", result.text)
    }

    @Test
    fun `completed tool result rescues an otherwise empty delegate without another generation`() {
        val result = resolveDelegatedChildResult(
            rawText = "",
            toolFallbacks = listOf("Recovered tool result with useful evidence."),
            extractionFailed = false,
            providerFailure = null
        )

        assertEquals(DelegatedChildStatus.COMPLETED, result.status)
        assertEquals("Recovered tool result with useful evidence.", result.text)
    }

    @Test
    fun `usage or reasoning without usable text stays completed empty`() {
        val result = resolveDelegatedChildResult(
            rawText = "",
            toolFallbacks = emptyList(),
            extractionFailed = false,
            providerFailure = null
        )

        assertEquals(DelegatedChildStatus.COMPLETED_EMPTY, result.status)
        assertNull(result.text)
    }

    @Test
    fun `extraction failure is distinct from an empty completion`() {
        val result = resolveDelegatedChildResult(
            rawText = "",
            toolFallbacks = emptyList(),
            extractionFailed = true,
            providerFailure = null
        )

        assertEquals(DelegatedChildStatus.PARSE_FAILED, result.status)
        assertNull(result.text)
    }

    @Test
    fun `usable content survives a trailing provider failure`() {
        val result = resolveDelegatedChildResult(
            rawText = "completed content",
            toolFallbacks = emptyList(),
            extractionFailed = false,
            providerFailure = "stream reset after payload"
        )

        assertEquals(DelegatedChildStatus.COMPLETED, result.status)
        assertEquals("completed content", result.text)
    }

    @Test
    fun `exact cap with unfinished text is treated as likely truncation`() {
        assertTrue(isLikelyDelegatedTruncation("The answer continues with", true))
        assertTrue(isLikelyDelegatedTruncation("Code:\n" + "\u0060\u0060\u0060" + "kotlin\nval x = 1", true))
    }

    @Test
    fun `natural ending or unused cap is not treated as truncation`() {
        assertFalse(isLikelyDelegatedTruncation("The answer is complete.", true))
        assertFalse(isLikelyDelegatedTruncation("The answer continues with", false))
    }

    @Test
    fun `structured completed tool output is extractable and tool errors are ignored`() {
        val json = AgentToolResult(
            callId = "json",
            content = ToolResultContent.Json(buildJsonObject { put("result", "useful") }),
            isError = false
        )
        val failed = AgentToolResult(
            callId = "failed",
            content = ToolResultContent.Text("failed tool output"),
            isError = true
        )

        assertEquals("{\"result\":\"useful\"}", usableDelegatedToolResult(json))
        assertNull(usableDelegatedToolResult(failed))
    }
}
