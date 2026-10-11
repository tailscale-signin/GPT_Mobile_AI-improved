package dev.chungjungsoo.gptmobile.data.agent

import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CombinedCoverageTest {
    @Test fun coverageNeverTruncatesAReferenceIntoAnInventedUrl() {
        val url = "https://example.com/" + "a".repeat(300)
        val coverage = CombinedCoverage("""{"contributions":[{"response":"Source $url"}]}""")
        val manifest = coverage.manifest("").toString()
        assertTrue(manifest.contains("\"urls\":[]"))
        assertTrue(manifest.contains("\"omittedSourceReferenceCount\":1"))
    }

    @Test fun cancellationPreservesShortUncommittedTextAndPropagates() = runBlocking {
        val saved = mutableListOf<String>()
        val cancellation = kotlinx.coroutines.CancellationException("stop")
        val initial = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                emit(ProviderEvent.TextDelta("Latest fact."))
                throw cancellation
            }
        }
        val wrapper = OutputLimitRecoverySession(initial, saveProgress = { draft, _, _, reason -> saved += "$reason:$draft" }) { _, _ -> error("Do not retry cancellation") }
        try {
            wrapper.streamRound(emptyList(), emptyList()).toList()
            org.junit.Assert.fail("Cancellation must propagate")
        } catch (caught: kotlinx.coroutines.CancellationException) {
            assertTrue(caught === cancellation)
        }
        assertEquals("CANCELLED:Latest fact.", saved.last())
    }

    @Test fun sourcesAndConflictsRemainCandidatesUntilVerified() {
        val coverage = CombinedCoverage("""{"contributions":[{"response":"Date 1900 https://a.example/source"},{"response":"Conflicting date 1901 https://b.example/source"}]}""")
        val manifest = coverage.manifest("Date 1900 https://a.example/source").toString()
        assertTrue(manifest.contains("EXACT_TEXT_PRESENT"))
        assertTrue(manifest.contains("UNVERIFIED_OR_PENDING"))
        assertTrue(coverage.pendingOutline("Date 1900 https://a.example/source").contains("1901"))
        assertTrue(coverage.continuationContext("x".repeat(100_000)).length < 21_000)
    }

    @Test fun savedDraftResumesWritingAndSavesTerminalProgress() = runBlocking {
        val saved = mutableListOf<String>()
        val initial = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow<ProviderEvent> { error("Must not restart the original request") }
        }
        val wrapper = OutputLimitRecoverySession(initial, initialDraft = "Committed. ", effectiveOutputCap = 4096, saveProgress = { draft, _, cap, reason ->
            assertEquals(4096, cap)
            saved += "$reason:$draft"
        }) { draft, _ ->
            assertEquals("Committed. ", draft)
            object : AgentProviderSession {
                override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                    assertTrue(tools.isEmpty())
                    emit(ProviderEvent.TextDelta("Remaining."))
                    emit(ProviderEvent.Completed)
                }
            }
        }
        val events = wrapper.streamRound(emptyList(), emptyList()).toList()
        assertEquals("Committed. Remaining.", events.filterIsInstance<ProviderEvent.TextDelta>().joinToString("") { it.text })
        assertEquals("COMPLETED:Committed. Remaining.", saved.last())
    }
}
