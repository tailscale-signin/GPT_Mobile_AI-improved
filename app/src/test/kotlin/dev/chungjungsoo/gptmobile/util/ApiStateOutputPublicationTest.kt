package dev.chungjungsoo.gptmobile.util

import dev.chungjungsoo.gptmobile.data.diagnostics.FirstOutputPublication
import dev.chungjungsoo.gptmobile.data.diagnostics.OutputLineage
import dev.chungjungsoo.gptmobile.data.diagnostics.OutputOrigin
import dev.chungjungsoo.gptmobile.data.diagnostics.OutputPublicationKind
import dev.chungjungsoo.gptmobile.data.dto.ApiState
import dev.chungjungsoo.gptmobile.data.network.ProviderLimitDecision
import dev.chungjungsoo.gptmobile.data.repository.withGitHubApiDelivery
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ApiStateOutputPublicationTest {
    @Test fun zeroProviderBytesAnd304ErrorCharactersNeverReportAnswerLatency() = runTest {
        val first = mutableListOf<FirstOutputPublication>()
        val error = "e".repeat(304)
        var published = ""
        flowOf(ApiState.ProgressCheckpoint("Waiting"), ApiState.Success(error, OutputLineage(OutputOrigin.SYSTEM_ERROR, "rejected")), ApiState.Error(error, invocationId = "rejected"))
            .collectApiStateUpdates(onUpdate = { content, _, _ -> published = content }, onOutputPublication = { _, _, events -> first += events })
        assertEquals(304, published.length)
        assertEquals(listOf(OutputPublicationKind.FIRST_PROGRESS, OutputPublicationKind.FIRST_SYSTEM_ERROR), first.map { it.kind })
        assertEquals("rejected", first.last().lineage.invocationId)
    }

    @Test fun actualProviderTextKeepsItsInvocationThroughCoalescedPublication() = runTest {
        val first = mutableListOf<FirstOutputPublication>()
        val actual = OutputLineage(OutputOrigin.MODEL_TEXT, "physical")
        var publishedLineage = OutputLineage()
        flowOf(ApiState.Success("Actual ", actual), ApiState.Success("answer", actual), ApiState.Done)
            .collectApiStateUpdates(onUpdate = { _, _, _ -> }, onOutputPublication = { _, lineage, events ->
                publishedLineage = lineage
                first += events
            })
        assertEquals(actual, publishedLineage)
        assertEquals(listOf(OutputPublicationKind.FIRST_VISIBLE_ANSWER), first.map { it.kind })
        assertEquals(actual, first.single().lineage)
    }

    @Test fun unknownAndMixedTextRemainUnlinked() = runTest {
        val first = mutableListOf<FirstOutputPublication>()
        var lineage = OutputLineage(OutputOrigin.MODEL_TEXT, "incorrect")
        flowOf(ApiState.Success("Retained "), ApiState.Success("new", OutputLineage(OutputOrigin.MODEL_TEXT, "new-invocation")), ApiState.Done)
            .collectApiStateUpdates(onUpdate = { _, _, _ -> }, onOutputPublication = { _, owner, events ->
                lineage = owner
                first += events
            })
        assertEquals(OutputLineage(), lineage)
        assertEquals(OutputPublicationKind.FIRST_UNATTRIBUTED_OUTPUT, first.first().kind)
        assertEquals("new-invocation", first.last().lineage.invocationId)
    }

    @Test fun typedRejectedProviderWaitRetainsFailureWithoutInventingAnswerContent() = runTest {
        val limit = ProviderLimitDecision("OPENROUTER", "saved-profile", "selected-model", 100_000, "scope", "PROVIDER_RATE_LIMITED", true, httpStatus = 429)
        val first = mutableListOf<FirstOutputPublication>()
        var published = ""
        val result = flowOf<ApiState>(ApiState.Error("HTTP 429", providerLimit = limit, invocationId = "rejected"), ApiState.Done)
            .withGitHubApiDelivery(true)
            .collectApiStateUpdates(onUpdate = { content, _, _ -> published = content }, onOutputPublication = { _, _, events -> first += events })
        assertEquals("", published)
        assertEquals(limit, (result as ApiStateFlowOutcome.Failed).providerLimit)
        assertFalse(first.any { it.kind == OutputPublicationKind.FIRST_VISIBLE_ANSWER || it.kind == OutputPublicationKind.FIRST_UNATTRIBUTED_OUTPUT })
        assertEquals(setOf(OutputPublicationKind.FIRST_PROGRESS, OutputPublicationKind.FIRST_SYSTEM_ERROR), first.map { it.kind }.toSet())
    }
}
