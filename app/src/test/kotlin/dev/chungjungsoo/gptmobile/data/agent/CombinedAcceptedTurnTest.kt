package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.model.ConversationCombinedSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class CombinedAcceptedTurnTest {
    private fun requested() = CombinedPolicyResolver.accept(
        ConversationCombinedSettings(),
        listOf("a"),
        mapOf("a" to "model"),
        "Compare findings",
        requestedTemperatures = mapOf("a" to null)
    )

    @Test fun capturedProviderDefaultCannotBecomeALaterProfileTemperature() {
        assertNull(requested().temperatureFor("a", 0.9f))
        assertEquals(0.5f, requested().copy(requestedTemperatures = emptyMap()).temperatureFor("a", 0.5f))
    }

    @Test fun contributorAndResearchContinuationsCannotBecomeFinalizationWorkers() {
        val context = CombinedExecutionContext(requested(), rootBudgetId = "root", profileUid = "a")
        assertEquals(CombinedRunRole.CONTRIBUTOR, context.forInvocationKind("output_limit_continuation", "a").role)
        assertEquals(CombinedRunRole.RESEARCH, context.copy(role = CombinedRunRole.RESEARCH).forInvocationKind("output_limit_continuation", "a").role)
        assertEquals(CombinedRunRole.CONTINUATION, context.copy(role = CombinedRunRole.REPAIR).forInvocationKind("combined_repair_continuation", "a").role)
    }

    @Test fun revisionLineageIsAbsentFromUnchangedLegacyEncoding() {
        val context = CombinedExecutionContext(requested(), rootBudgetId = "root", profileUid = "a")
        val accepted = CombinedAcceptedTurn(context = context, tools = dev.chungjungsoo.gptmobile.data.model.ChatMcpToolConfig())
        val encoded = accepted.encode()
        assertFalse(encoded.contains("revisionOf"))
        assertEquals(encoded, requireNotNull(CombinedAcceptedTurn.decode(encoded)).encode())
    }
}
