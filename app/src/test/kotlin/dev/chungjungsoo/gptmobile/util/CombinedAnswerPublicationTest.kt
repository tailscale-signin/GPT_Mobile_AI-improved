package dev.chungjungsoo.gptmobile.util

import dev.chungjungsoo.gptmobile.data.agent.CombinedPassageLink
import dev.chungjungsoo.gptmobile.data.agent.CombinedPassageRecord
import dev.chungjungsoo.gptmobile.data.agent.CombinedQualityReport
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItemType
import dev.chungjungsoo.gptmobile.data.dto.ApiState
import dev.chungjungsoo.gptmobile.data.research.researchHash
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CombinedAnswerPublicationTest {
    private val body = "The saved answer retains its exact contributor attribution."
    private val report = CombinedQualityReport(
        revisionId = "accepted-revision",
        answerDigest = researchHash(body),
        complete = true,
        passages = listOf(CombinedPassageRecord(0, body.length, researchHash(body), setOf("profile"), links = listOf(CombinedPassageLink(0, body.length, researchHash(body), setOf("profile"), setOf("claim")))))
    )

    @Test fun finalBodyIsNotPublishedUntilTheCompleteBundleIsDurable() = runBlocking {
        var saved = false
        val events = mutableListOf<String>()
        val result = flowOf(ApiState.Thinking("Preparing"), ApiState.CombinedAnswer(body, report), ApiState.Done).collectApiStateUpdates(
            onUpdate = { text, _, timeline ->
                if (text.isNotEmpty()) {
                    assertTrue(saved)
                    assertEquals(body, text)
                    assertEquals(report, timeline.single { it.combinedQuality != null }.combinedQuality)
                    events += "visible"
                }
            },
            onCombinedAnswer = { text, thoughts, timeline ->
                assertEquals(body, text)
                assertEquals("Preparing", thoughts)
                assertEquals(body, timeline.filter { it.type == AssistantTimelineItemType.TEXT }.joinToString("") { it.content })
                assertEquals(report, timeline.single { it.combinedQuality != null }.combinedQuality)
                events += "committed"
                saved = true
                true
            }
        )
        assertEquals(ApiStateFlowOutcome.Completed, result)
        assertEquals("committed", events.first())
        assertTrue(events.contains("visible"))
    }

    @Test fun failedCommitNeverLeaksTheFinalBundleThroughTheFinallyFlush() = runBlocking {
        var visible = ""
        val failure = runCatching {
            flowOf(ApiState.Success("Provisional draft"), ApiState.CombinedAnswer(body, report), ApiState.Done).collectApiStateUpdates(
                onUpdate = { text, _, timeline ->
                    visible = text
                    assertTrue(timeline.none { it.combinedQuality == report })
                },
                onCombinedAnswer = { _, _, _ -> false }
            )
        }
        assertTrue(failure.isFailure)
        assertEquals("Provisional draft", visible)
    }

    @Test fun mismatchedBodyOrLinkCannotReachPersistenceOrTheRenderer() = runBlocking {
        for (invalid in listOf(report.copy(answerDigest = researchHash("other")), report.copy(passages = listOf(report.passages.single().copy(links = listOf(report.passages.single().links.single().copy(end = body.length + 1))))))) {
            var persisted = false
            var exposed = false
            val result = runCatching {
                flowOf(ApiState.CombinedAnswer(body, invalid)).collectApiStateUpdates(
                    onUpdate = { text, _, _ -> exposed = exposed || text.isNotEmpty() },
                    onCombinedAnswer = { _, _, _ ->
                        persisted = true
                        true
                    }
                )
            }
            assertTrue(result.isFailure)
            assertFalse(persisted)
            assertFalse(exposed)
        }
    }
}
