package dev.chungjungsoo.gptmobile.presentation.ui.chat

import dev.chungjungsoo.gptmobile.data.accounting.ModelInvocation
import dev.chungjungsoo.gptmobile.data.accounting.compareModelTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenComparisonRowsTest {
    @Test
    fun `same exact model collapses into one token comparison row`() {
        val rows = compareModelTokens(
            listOf(
                invocation("one", "Qwen3.5-9B", "primary", 100, 20, 10, "profile-a"),
                invocation("two", " Qwen3.5-9B ", "tool", 50, 30, 20, "profile-a", estimated = true),
                invocation("three", "Qwen3.5-9B", "delegate", 25, 5, 30, "profile-b")
            )
        )

        assertEquals(1, rows.size)
        assertEquals("Qwen3.5-9B", rows[0].model)
        assertEquals(175L, rows[0].inputTokens)
        assertEquals(55L, rows[0].outputTokens)
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
