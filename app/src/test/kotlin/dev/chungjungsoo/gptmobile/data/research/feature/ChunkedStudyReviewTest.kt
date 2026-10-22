package dev.chungjungsoo.gptmobile.data.research.feature

import dev.chungjungsoo.gptmobile.data.research.researchHash
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChunkedStudyReviewTest {
    @Test fun restartUsesCommittedValidPartsAndRequestsOnlyMissingDomains() = runBlocking {
        val text = "The trial found an uncertain effect. Small sample."
        val passage = EvidencePassage("p", "version", "results", 0, text.length, researchHash(text), text)
        val outcome = ResearchOutcome("outcome", "work", "version", "primary", "Uncertain effect", "Small sample", "[\"p\"]", "EXTRACTED", 1)
        val summary = StudyReviewArtifact("The trial found an uncertain effect.", "Small sample.", "primary", listOf("p"), emptyList(), emptyList())
        val parts = linkedMapOf<String, StudyReviewArtifact>()
        var requests = 0
        suspend fun generate(prompt: String): StudyReviewArtifact {
            requests++
            val kind = if ("in reliability" in prompt) "reliability" else "accuracy"
            val domain = Regex("ONLY domain ([^ ]+) in").find(prompt)?.groupValues?.get(1) ?: return summary
            val row = ReviewDomain(domain, 2, "Reported methods and outcomes", listOf("p"))
            return if (kind == "reliability") summary.copy(reliability = listOf(row)) else summary.copy(accuracy = listOf(row))
        }
        val interrupted = runCatching {
            chunkedStudyReview("packet", outcome, listOf(passage), emptyMap(), generate = { instruction -> if (requests == 4) error("Simulated interruption") else generate(instruction) }, commit = { key, value -> parts[key] = value })
        }
        assertTrue(interrupted.isFailure)
        assertEquals(4, parts.size)
        val before = parts.toMap()
        val result = chunkedStudyReview("packet", outcome, listOf(passage), before, ::generate) { key, value -> parts[key] = value }
        assertEquals(11, requests)
        assertEquals(5, result.reliability.size)
        assertEquals(5, result.accuracy.size)
        assertTrue(before.all { parts[it.key] == it.value })
        assertEquals(result, chunkedStudyReview("packet", outcome, listOf(passage), parts, generate = { error("Completed parts must not regenerate") }, commit = { _, _ -> error("No additional commit") }))
    }
}
