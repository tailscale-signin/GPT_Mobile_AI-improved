package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class GitHubSavedReadProgressTest {
    private val name = "github_context__selected"
    private val view = "00000000-0000-4000-8000-000000000001"
    private val tool = GitHubOperationTool.definition("github_context", name)
    private fun args(offset: Int, bytes: Int = 1536, id: String = view) = buildJsonObject { put("view_cursor", "$id:$offset:$bytes") }
    private fun recipe(offset: Int, bytes: Int = 1536) = buildJsonObject {
        put("tool", name)
        put("arguments", args(offset, bytes))
    }
    private fun page(id: String, start: Int, end: Int, bytes: Int = 1536, failed: Boolean = false): AgentToolExchange {
        val call = ProviderEvent.ToolCall(id, name, args(start, bytes))
        val result = AgentToolResult(
            id,
            ToolResultContent.Json(
                buildJsonObject {
                    put("schema", "github.operation.v1")
                    put("fragments", JsonArray(listOf(buildJsonObject { put("value", "retained source") })))
                    put("fragment_offset", start)
                    put("fragment_end", end)
                    put("total_fragments", 131)
                    put("recovery_call", recipe(start, bytes))
                    put("continuation_call", recipe(end, bytes))
                }
            ),
            failed
        )
        return AgentToolExchange(listOf(call), listOf(result))
    }
    private fun proofs(exchanges: List<AgentToolExchange>, consumed: Set<String>) = ToolResultDeliveryProof.capture(exchanges.flatMap { it.results }.filter { it.callId in consumed })
    private fun observe(state: GitHubSavedReadProgress, exchanges: List<AgentToolExchange>, consumed: Set<String>) = state.observe(exchanges, proofs(exchanges, consumed))
    private fun automatic(offset: Int, bytes: Int = 1536) = ProviderEvent.ToolCall("saved-result-consume-next", name, args(offset, bytes)).captureProviderInvocation()

    @Test fun nativeRecoveryAdvancesPastTheAlreadyDeliveredIntervalAndRetainsInvocationIdentity() {
        val state = GitHubSavedReadProgress()
        observe(state, listOf(page("delivered", 50, 123)), setOf("delivered"))
        val proposal = automatic(50)
        val next = state.advance(proposal, tool)
        assertEquals(args(123), next.arguments)
        assertEquals(proposal.callId, next.callId)
        assertEquals(proposal.providerInvocation(), next.providerInvocation())
        assertEquals(args(123), state.advance(automatic(59), tool).arguments)
    }

    @Test fun retainedButUndeliveredFailedAndSummaryOnlyEvidenceCannotSkipSource() {
        val proposal = automatic(50)
        for ((exchange, consumed) in listOf(
            page("unread", 50, 123) to emptySet(),
            page("failed", 50, 123, failed = true) to setOf("failed"),
            page("summary", 50, 123).let { exchange ->
                exchange.copy(
                    results = exchange.results.map { result ->
                        result.copy(
                            content = ToolResultContent.Json(
                                buildJsonObject {
                                    put("schema", "github.evidence-summary.v1")
                                    put("previously_delivered", true)
                                }
                            )
                        )
                    }
                )
            } to setOf("summary")
        )) {
            val state = GitHubSavedReadProgress()
            observe(state, listOf(exchange), consumed)
            assertEquals(proposal, state.advance(proposal, tool))
        }
    }

    @Test fun adjacentIntervalsMergeButGapsAreNeverSkipped() {
        val state = GitHubSavedReadProgress()
        observe(state, listOf(page("one", 50, 59), page("two", 59, 66)), setOf("one", "two"))
        assertEquals(args(66), state.advance(automatic(50), tool).arguments)
        val gap = GitHubSavedReadProgress()
        observe(gap, listOf(page("one", 50, 59), page("two", 60, 123)), setOf("one", "two"))
        assertEquals(args(59), gap.advance(automatic(50), tool).arguments)
    }

    @Test fun exactIssuedCursorBudgetConnectionAndExplicitRereadsArePreserved() {
        val state = GitHubSavedReadProgress()
        observe(state, listOf(page("one", 50, 123, bytes = 8192)), setOf("one"))
        assertEquals(automatic(50), state.advance(automatic(50), tool))
        assertEquals(args(123, 8192), state.advance(automatic(50, 8192), tool).arguments)
        val explicit = automatic(50, 8192).copy(callId = "provider-request")
        assertEquals(explicit, state.advance(explicit, tool))
        val otherView = automatic(50, 8192).copy(arguments = args(50, 8192, "another-view"))
        assertEquals(otherView, state.advance(otherView, tool))
        val otherTool = automatic(50, 8192).copy(name = "github_context__another")
        assertEquals(otherTool, state.advance(otherTool, GitHubOperationTool.definition("github_context", otherTool.name)))
    }

    @Test fun aRepeatedModelCursorAdvancesOnlyAfterTwoDistinctProvenDeliveries() {
        val state = GitHubSavedReadProgress()
        val first = page("provider-one", 50, 123, bytes = 8192)
        val next = ProviderEvent.ToolCall("provider-three", name, args(50, 8192)).captureProviderInvocation()
        observe(state, listOf(first), setOf("provider-one"))
        observe(state, listOf(first), setOf("provider-one"))
        assertEquals(next, state.advance(next, tool, repairRepeatedProviderRead = true))
        val second = page("provider-two", 50, 123, bytes = 8192)
        observe(state, listOf(first, second), setOf("provider-one", "provider-two"))
        val advanced = state.advance(next, tool, repairRepeatedProviderRead = true)
        assertEquals(args(123, 8192), advanced.arguments)
        assertEquals(next.providerInvocation(), advanced.providerInvocation())
        assertEquals(next, state.advance(next, tool, repairRepeatedProviderRead = false))
    }

    @Test fun boundedDeliveryHistoryCannotCountOneOldCallAsTwoDeliveries() {
        val state = GitHubSavedReadProgress()
        val first = page("once", 50, 123)
        val others = (1..300).map { page("another-$it", 123, 124) }
        val exchanges = listOf(first) + others
        observe(state, exchanges, exchanges.flatMap { it.calls }.mapTo(mutableSetOf()) { it.callId })
        observe(state, listOf(first), setOf("once"))
        val proposal = automatic(50).copy(callId = "provider-request")
        assertEquals(proposal, state.advance(proposal, tool, repairRepeatedProviderRead = true))
    }

    @Test fun checkpointRestorationReconstructsDeliveredIntervalsAndReplayEvictionDoesNotForgetThem() {
        val retained = listOf(page("one", 50, 123))
        val consumed = setOf("one")
        val restored = GitHubReplayRecovery.State(retained.flatMap { it.calls })
        restored.observeDelivered(retained, proofs(retained, consumed))
        restored.observeDelivered(emptyList(), proofs(retained, consumed))
        assertEquals(args(123), restored.advanceSavedRead(automatic(50), tool).arguments)
    }

    @Test fun deliveryOfAnotherProjectionCannotQualifyTheOriginalInterval() {
        val retained = listOf(page("one", 50, 123))
        val original = retained.single().results.single()
        val summary = original.copy(content = requireNotNull(GitHubReplayRecovery.compact(original.content, 2000)))
        val restored = GitHubSavedReadProgress()
        restored.observe(retained, ToolResultDeliveryProof.capture(listOf(summary)))
        assertEquals(automatic(50), restored.advance(automatic(50), tool))
        restored.observe(retained, ToolResultDeliveryProof.capture(listOf(original)))
        assertEquals(args(123), restored.advance(automatic(50), tool).arguments)
    }
}
