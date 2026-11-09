package dev.chungjungsoo.gptmobile.data.accounting

import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.context.RequestDispatchContext
import dev.chungjungsoo.gptmobile.data.diagnostics.OutputLineage
import dev.chungjungsoo.gptmobile.data.diagnostics.OutputOrigin
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class InvocationOutputOriginTest {
    @Test fun applicationAndFailureTextDoesNotCreateProviderBytesOrFirstTokenTiming() {
        for (origin in listOf(OutputOrigin.SYSTEM_ERROR, OutputOrigin.PROGRESS_NOTICE, OutputOrigin.APPLICATION_REPORT, OutputOrigin.RETAINED_CONTENT)) {
            val round = round()
            round.observe(ProviderEvent.TextDelta("e".repeat(304), OutputLineage(origin)))
            assertEquals(0L, round.visibleBytes)
            assertEquals(0L, round.generatedBytes)
            assertNull(round.first)
            assertFalse(round.hasVisibleAnswer)
        }
    }

    @Test fun unknownOutputKeepsConservativeAccountingWithoutInventingProviderLatency() {
        val round = round()
        round.observe(ProviderEvent.TextDelta("Unknown"))
        assertEquals(7L, round.generatedBytes)
        assertNull(round.first)
    }

    @Test fun decodedProviderOutputHasPhysicalFirstTokenAndByteMeasurements() {
        val round = round()
        round.observe(ProviderEvent.TextDelta("Provider", OutputLineage(OutputOrigin.MODEL_TEXT, "physical")))
        assertEquals(8L, round.generatedBytes)
        assertNotNull(round.first)
    }

    private fun round(): InvocationRoundAccounting = InvocationRoundAccounting(
        ModelInvocation("physical", "run", "turn", "OPENROUTER", "selected", "primary", 100, 500),
        null,
        RequestDispatchContext(emptySet(), "run", "physical"),
        null,
        mockk(relaxed = true),
        false,
        MutableStateFlow(emptyMap()),
        null,
        "turn"
    )
}
