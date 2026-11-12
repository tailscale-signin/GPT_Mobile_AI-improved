package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.agent.provider.ChatCompletionsEventAssembler
import dev.chungjungsoo.gptmobile.data.diagnostics.OutputLineage
import dev.chungjungsoo.gptmobile.data.diagnostics.OutputOrigin
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProviderOutputLineageTest {
    @Test fun providerDecoderAndLedgerSupplySeparateOriginAndInvocationEvidence() {
        val decoded = ChatCompletionsEventAssembler().accept("Provider answer", null, null, null).single() as ProviderEvent.TextDelta
        assertEquals(OutputOrigin.MODEL_TEXT, decoded.lineage.origin)
        assertNull(decoded.lineage.invocationId)
        val bound = decoded.withInvocationLineage("physical-invocation") as ProviderEvent.TextDelta
        assertEquals(OutputLineage(OutputOrigin.MODEL_TEXT, "physical-invocation"), bound.lineage)
    }

    @Test fun arbitraryAndSystemTextCannotBorrowTheCurrentInvocation() {
        for (origin in listOf(OutputOrigin.UNKNOWN, OutputOrigin.SYSTEM_ERROR, OutputOrigin.APPLICATION_REPORT, OutputOrigin.RETAINED_CONTENT)) {
            val event = ProviderEvent.TextDelta("Visible text", OutputLineage(origin))
            assertEquals(event, event.withInvocationLineage("unrelated-invocation"))
        }
    }

    @Test fun alreadyLinkedTextKeepsItsOriginalInvocationAndFailureGetsItsActualOwner() {
        val text = ProviderEvent.TextDelta("Retained provider text", OutputLineage(OutputOrigin.MODEL_TEXT, "original"))
        assertEquals(text, text.withInvocationLineage("later"))
        assertEquals("actual", (ProviderEvent.Failed("HTTP 429").withInvocationLineage("actual") as ProviderEvent.Failed).invocationId)
        assertEquals("actual", (ProviderEvent.Usage(outputTokens = 1536).withInvocationLineage("actual") as ProviderEvent.Usage).invocationId)
        val usage = ProviderEvent.Usage(outputTokens = 1133, invocationId = "primary")
        assertEquals(usage, usage.withInvocationLineage("reviewer"))
    }

    @Test fun markupBufferPreservesIdentityAcrossSplitProviderFragments() = runTest {
        val lineage = OutputLineage(OutputOrigin.MODEL_TEXT, "physical")
        val events = flowOf(ProviderEvent.TextDelta("An ans", lineage), ProviderEvent.TextDelta("wer.", lineage), ProviderEvent.Completed)
            .withAssistantTextMarkupGuard(validateSearchClaims = false).toList().filterIsInstance<ProviderEvent.TextDelta>()
        assertEquals("An answer.", events.joinToString("") { it.text })
        assertEquals(setOf(lineage), events.map { it.lineage }.toSet())
    }
}
