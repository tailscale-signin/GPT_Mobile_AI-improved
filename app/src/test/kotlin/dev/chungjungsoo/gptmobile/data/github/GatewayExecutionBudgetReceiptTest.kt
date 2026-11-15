package dev.chungjungsoo.gptmobile.data.github

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayExecutionBudgetReceiptTest {
    private val receipt = GatewayExecutionBudgetReceipt(
        schema = "gateway.execution-budget.v1",
        receiptId = "a".repeat(64),
        bindingId = "binding",
        modelId = "selected",
        contextTokens = 4096,
        inputTokens = 2048,
        outputTokens = 1024,
        safetyTokens = 128,
        verificationTokens = 128,
        finalTokens = 256,
        recoveryTokens = 128,
        remainingTokens = 384,
        countMode = "SELECTED_TOKENIZER"
    )

    @Test
    fun acceptsMatchingAuthoritativeAllocation() {
        assertTrue(receipt.matches("binding", "selected"))
    }

    @Test
    fun rejectsChangedModelBindingAndOverallocatedContext() {
        assertFalse(receipt.matches("other", "selected"))
        assertFalse(receipt.matches("binding", "other"))
        assertFalse(receipt.copy(outputTokens = 8192).matches("binding", "selected"))
        assertFalse(receipt.copy(inputTokens = Int.MAX_VALUE, remainingTokens = Int.MAX_VALUE).matches("binding", "selected"))
        assertFalse(receipt.copy(remainingTokens = -1).matches("binding", "selected"))
    }
}
