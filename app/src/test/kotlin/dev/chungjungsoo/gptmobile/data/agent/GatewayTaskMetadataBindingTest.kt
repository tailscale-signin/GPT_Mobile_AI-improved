package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.agent.provider.withGatewayTaskMetadataBinding
import dev.chungjungsoo.gptmobile.data.github.GitHubStageBudget
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayResponseMetadata
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayTaskMetadataBindingTest {
    private val metadata = GatewayResponseMetadata(jobId = "canonical-job", transportVerified = true)
    private fun session(vararg events: ProviderEvent) = object : AgentProviderSession {
        override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> = flowOf(*events)
    }

    @Test fun taskIdentityIsBoundBeforeAContinuationCanOutrunBufferedPublicMetadata() = runBlocking {
        withTimeout(5000) {
            var boundJob: String? = null
            var publiclyObservedJob: String? = null
            val reopened = CompletableDeferred<Unit>()
            val initial = session(
                ProviderEvent.GatewayMetadataCaptured(metadata),
                ProviderEvent.TextDelta("Saved prefix."),
                ProviderEvent.Usage(finishReason = "length"),
                ProviderEvent.Completed
            ).withGatewayTaskMetadataBinding("primary") { boundJob = it.jobId }
            val output = GitHubOutputContinuationSession(initial) { draft, _, _ ->
                assertEquals("Saved prefix.", draft)
                assertEquals("canonical-job", boundJob)
                assertNull(publiclyObservedJob)
                reopened.complete(Unit)
                session(ProviderEvent.TextDelta(" Finished."), ProviderEvent.Completed)
            }
            // GitHubReviewedSession has this dispatcher boundary. A slow public
            // collector must not own the job identity used by the inner reopen.
            output.streamRound(emptyList(), emptyList()).flowOn(Dispatchers.Default).collect { event ->
                if (event is ProviderEvent.GatewayMetadataCaptured) {
                    reopened.await()
                    publiclyObservedJob = event.metadata.jobId
                }
            }
            assertEquals("canonical-job", publiclyObservedJob)
        }
    }

    @Test fun onlyTaskAndSynthesisRolesCanBindTransportVerifiedJobs() = runBlocking {
        val bound = mutableListOf<String?>()
        val source = session(ProviderEvent.GatewayMetadataCaptured(metadata))
        for (role in listOf("primary", "github-task-resume", "github-execution-continuation", GitHubStageBudget.CONTINUATION_ROLE, GitHubStageBudget.VISIBLE_OUTPUT_REPAIR_ROLE, "synthesis")) {
            source.withGatewayTaskMetadataBinding(role) { bound += it.jobId }.streamRound(emptyList(), emptyList()).toList()
        }
        assertEquals(List(6) { "canonical-job" }, bound)
        for (role in listOf("reviewer", "github-review-preflight", "github-claim-extraction", "github-progress-summary", "answer_contract_rewrite")) {
            assertSame(source, source.withGatewayTaskMetadataBinding(role) { error("A helper must not replace task identity") })
        }
        for (untrusted in listOf(metadata.copy(transportVerified = false), metadata.copy(jobId = null))) {
            val events = session(ProviderEvent.GatewayMetadataCaptured(untrusted)).withGatewayTaskMetadataBinding("primary") { error("Body metadata is not a verified job binding") }
                .streamRound(emptyList(), emptyList()).toList()
            assertEquals(ProviderEvent.GatewayMetadataCaptured(untrusted), events.single())
        }
    }

    @Test fun aLostOwnershipFenceStopsBeforeMetadataOrLaterContentCanBePublished() = runBlocking {
        val observed = mutableListOf<ProviderEvent>()
        val expected = IllegalStateException("Saved run ownership changed")
        val failure = runCatching {
            session(ProviderEvent.GatewayMetadataCaptured(metadata), ProviderEvent.TextDelta("later text"))
                .withGatewayTaskMetadataBinding("primary") { throw expected }
                .streamRound(emptyList(), emptyList()).collect { observed += it }
        }.exceptionOrNull()
        assertSame(expected, failure)
        assertTrue(observed.isEmpty())
    }
}
