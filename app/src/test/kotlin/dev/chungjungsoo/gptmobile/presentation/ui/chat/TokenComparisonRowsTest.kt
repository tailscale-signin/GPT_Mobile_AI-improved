package dev.chungjungsoo.gptmobile.presentation.ui.chat

import dev.chungjungsoo.gptmobile.data.accounting.ModelInvocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenComparisonRowsTest {
    @Test
    fun `same model collapses into one token comparison row`() {
        val rows = tokenComparisonRows(
            listOf(
                invocation("one", "Qwen3.5-9B", "primary", 100, 20, 10, "profile-a"),
                invocation("two", " qwen3.5-9b ", "tool", 50, 30, 20, "profile-a", estimated = true),
                invocation("three", "Qwen3.5-9B", "delegate", 25, 5, 30, "profile-b")
            )
        )

        assertEquals(1, rows.size)
        assertEquals("Qwen3.5-9B", rows[0].model)
        assertEquals(175, rows[0].inputTokens)
        assertEquals(55, rows[0].outputTokens)
        assertEquals(3, rows[0].requestCount)
        assertTrue(rows[0].estimated)
    }

    private fun invocation(
        id: String,
        model: String,
        kind: String,
        input: Int,
        output: Int,
        startedAt: Long,
        profileUid: String?,
        estimated: Boolean = false
    ) = ModelInvocation(
        id = id,
        parentRunId = "run",
        turnKey = "turn",
        provider = "LLAMA",
        model = model,
        kind = kind,
        inputTokens = input,
        outputTokens = output,
        estimated = estimated,
        startedAt = startedAt,
        profileUid = profileUid
    )
}
