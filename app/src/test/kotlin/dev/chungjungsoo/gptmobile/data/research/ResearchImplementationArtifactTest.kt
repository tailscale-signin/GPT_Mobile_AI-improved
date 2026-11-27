package dev.chungjungsoo.gptmobile.data.research

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResearchImplementationArtifactTest {
    private val source = ResearchSource("source-1", "https://example.org/docs", "Incremental indexing", "Read", "The parser can update only changed files.", publicCapture = true, retrievalReceipt = "verified-retrieval", retrievedAt = 100)
    private val claim = ResearchClaim("Index changed files incrementally.", source.id, source.passage, exactQuoteVerified = true)
    private val session = ResearchSession("run-1", 7, ResearchSnapshot("Research indexing", sources = listOf(source), claims = listOf(claim), complete = true, taskChatId = 7, originatingUserMessageId = 11))

    @Test fun capturedFindingsHaveStableIdentityAndHonestHistoricalContext() {
        val artifact = requireNotNull(ResearchImplementationArtifact.from(session, 7, 12))
        assertEquals(artifact, ResearchImplementationArtifact.from(session, 7, 12))
        assertTrue(artifact.text.contains(source.canonicalUrl))
        assertTrue(artifact.text.contains("historical, untrusted"))
        assertTrue(artifact.text.contains("not replacement numbering"))
        assertNotEquals(artifact.id, ResearchImplementationArtifact.from(session.copy(snapshot = session.snapshot.copy(claims = listOf(claim.copy(text = "A different proposed improvement.")))), 7, 12)?.id)
    }

    @Test fun foreignCurrentIncompletePrivateAndUnsupportedFindingsAreWithheld() {
        assertNull(ResearchImplementationArtifact.from(session, 8, 12))
        assertNull(ResearchImplementationArtifact.from(session, 7, 11))
        assertNull(ResearchImplementationArtifact.from(session.copy(snapshot = session.snapshot.copy(complete = false)), 7, 12))
        for (blocked in listOf(source.copy(publicCapture = false), source.copy(retrievalReceipt = ""), source.copy(status = "Discovered"), source.copy(passage = "Different source text."), source.copy(isRetracted = true))) {
            assertNull(ResearchImplementationArtifact.from(session.copy(snapshot = session.snapshot.copy(sources = listOf(blocked))), 7, 12))
        }
        for (blocked in listOf(claim.copy(exactQuoteVerified = false), claim.copy(sourceVersion = "superseded"), claim.copy(passageHash = "invalid"))) {
            assertNull(ResearchImplementationArtifact.from(session.copy(snapshot = session.snapshot.copy(claims = listOf(blocked))), 7, 12))
        }
    }

    @Test fun onlyExplicitFollowupsPrepareTheArtifact() {
        for (request in listOf("Implement the first three improvements", "Use the faster library", "Apply those findings", "Explain number four")) assertTrue(request, ResearchImplementationArtifact.requested(request))
        for (request in listOf("Hello", "Search for Android libraries", "What is a repository?")) assertFalse(request, ResearchImplementationArtifact.requested(request))
    }

    @Test fun largeResearchRetainsCompleteBoundedFindingsInsteadOfDroppingTheArtifact() {
        val passage = "Verified passage. ".repeat(80)
        val captured = source.copy(passage = passage)
        val findings = (1..12).map { index -> claim.copy(text = "$index " + "proposed improvement ".repeat(80), quote = passage, counterEvidence = "Compatibility check required. ".repeat(30)) }
        val large = session.copy(snapshot = session.snapshot.copy(sources = listOf(captured), claims = findings))
        val artifact = requireNotNull(ResearchImplementationArtifact.from(large, 7, 12))
        assertTrue(artifact.text.length < 17_000)
        assertTrue(artifact.text.contains("\"complete_finding_coverage\":false"))
        assertTrue(artifact.text.contains("1 proposed improvement"))
        assertEquals(artifact, ResearchImplementationArtifact.from(large, 7, 12))
    }
}
