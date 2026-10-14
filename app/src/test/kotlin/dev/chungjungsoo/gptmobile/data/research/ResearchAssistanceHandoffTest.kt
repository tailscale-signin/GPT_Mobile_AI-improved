package dev.chungjungsoo.gptmobile.data.research

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResearchAssistanceHandoffTest {
    @Test fun onlyLocallyProducedVerifiedClaimsAreForwardedWithOriginalEvidence() {
        val quote = "The documented widget can process two requests in parallel."
        val source = ResearchSource("S1", "https://example.org/widget", "Widget", status = "Read", passage = quote)
        val claim = ResearchClaim("Two requests", "S1", quote, verdict = "Supported", questionId = "Q1", relevance = "Relevant", exactQuoteVerified = true, extractedBy = "known-model", extractionInvocationId = "local")
        val event = ResearchModelEvent("event", "EXTRACT", "profile", "local model", true, "SUCCESS", 1, invocationId = "local")
        val snapshot = ResearchSnapshot("task", sources = listOf(source), claims = listOf(claim), modelEvents = listOf(event))
        val restored = ResearchSnapshot.parse(snapshot.json())!!
        val packets = ResearchAssistanceHandoff.packets(restored, "root")
        assertEquals(1, packets.size)
        assertTrue(packets.single().toString().contains(quote))
        assertTrue(ResearchAssistanceHandoff.withinBudget(packets, 1).isEmpty())
        assertEquals(packets, ResearchAssistanceHandoff.withinBudget(packets, 10000))
        assertTrue(ResearchAssistanceHandoff.packets(snapshot.copy(claims = listOf(claim.copy(exactQuoteVerified = false))), "root").isEmpty())
        assertTrue(ResearchAssistanceHandoff.packets(snapshot.copy(modelEvents = listOf(event.copy(local = false))), "root").isEmpty())
    }
}
