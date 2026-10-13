package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.database.entity.CombinedModelResponse
import dev.chungjungsoo.gptmobile.data.research.researchHash
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CombinedClaimInventoryTest {
    private fun source(text: String, uid: String = "a", status: String = "COMPLETED") = CombinedModelResponse(uid, uid, content = text, completionStatus = status)
    private fun link(text: String, start: Int = 0, end: Int = text.length) = CombinedCoverageLink(start, end, researchHash(text.substring(start, end)))

    @Test fun `complete inventory reconstructs more than 256 sections with exact UTF8 and partial source lineage`() {
        val content = (1..700).joinToString("\n\n") { "$it 😀 e\u0301 العربية not before 12 BCE: -5.2%\r" }
        val sources = listOf(source(content), source("partial tail " + "x😀".repeat(3000), "b", "FAILED"))
        val inventory = CombinedClaimInventory.freeze(sources)
        assertTrue(inventory.sections.size > 256)
        val pages = generateSequence(0) { it + 128 }.takeWhile { it < inventory.sections.size }.flatMap { inventory.page(it, 128) }.toList()
        assertEquals(inventory.sections, pages)
        sources.forEach { source ->
            val sections = pages.filter { it.profileUid == source.platformUid }
            assertEquals(source.content, sections.joinToString("") { inventory.resolve(it, sources).orEmpty() })
            assertEquals(source.content.toByteArray().size.toLong(), sections.last().endUtf8)
            assertTrue(sections.zipWithNext().all { (a, b) -> a.endUtf16 == b.startUtf16 && a.endUtf8 == b.startUtf8 })
        }
    }

    @Test fun `a modern only answer cannot account for an omitted earlier source tail`() {
        val sources = listOf(source("Modern history.\nAncient history, including 12 BCE."))
        val inventory = CombinedClaimInventory.freeze(sources)
        val answer = "Modern history."
        val first = inventory.sections.first()
        val decision = CombinedClaimDecision("claim", first.id, link(sources.single().content, first.startUtf16, first.endUtf16), CombinedClaimDisposition.INCLUDED, listOf(link(answer)))
        val result = CombinedCoverageValidator.validate(inventory, sources, answer, CombinedCoverageProposal(inventory.corpusDigest, researchHash(answer), "p", listOf(decision)), "p")
        assertFalse(result.structurallyAccounted)
        assertEquals(setOf(inventory.sections.last().id), result.missingSectionIds)
        org.junit.Assert.assertNull(result.includedFraction)
    }

    @Test fun `exclusions cannot inflate included coverage and require an independently granted policy reason`() {
        val text = "-5% in 12 BCE, except in winter."
        val sources = listOf(source(text))
        val inventory = CombinedClaimInventory.freeze(sources)
        val section = inventory.sections.single()
        val decision = CombinedClaimDecision("claim", section.id, link(text), CombinedClaimDisposition.EXCLUDED_IRRELEVANT, reason = "User requested a different era")
        val proposal = CombinedCoverageProposal(inventory.corpusDigest, researchHash("Short answer"), "p", listOf(decision))
        val excluded = CombinedCoverageValidator.validate(inventory, sources, "Short answer", proposal, "p")
        assertTrue(excluded.structurallyAccounted)
        assertEquals(0.0, requireNotNull(excluded.includedFraction), 0.0)
        assertEquals(1, excluded.excludedClaims)
        val withheld = proposal.copy(decisions = listOf(decision.copy(disposition = CombinedClaimDisposition.WITHHELD_BY_POLICY)))
        assertFalse(CombinedCoverageValidator.validate(inventory, sources, "Short answer", withheld, "p").structurallyAccounted)
        assertTrue(CombinedCoverageValidator.validate(inventory, sources, "Short answer", withheld, "p", setOf(section.id)).structurallyAccounted)
    }

    @Test fun `omitted numeric signs invalid output ranges duplicate claims and stale answers invalidate audit`() {
        val text = "-5% in 12 BCE."
        val sources = listOf(source(text))
        val inventory = CombinedClaimInventory.freeze(sources)
        val claim = CombinedClaimDecision("c", inventory.sections.single().id, link(text), CombinedClaimDisposition.INCLUDED, listOf(link(text)))
        val proposal = CombinedCoverageProposal(inventory.corpusDigest, researchHash(text), "p", listOf(claim))
        assertTrue(CombinedCoverageValidator.validate(inventory, sources, text, proposal, "p").structurallyAccounted)
        val mutations = listOf(
            proposal.copy(decisions = listOf(claim.copy(source = link(text, 1)))),
            proposal.copy(decisions = listOf(claim.copy(output = listOf(link(text).copy(endUtf16 = text.length + 1))))),
            proposal.copy(decisions = listOf(claim, claim)),
            proposal.copy(answerDigest = researchHash("changed")),
            proposal.copy(policyRevision = "old")
        )
        mutations.forEach { assertFalse(CombinedCoverageValidator.validate(inventory, sources, text, it, "p").structurallyAccounted) }
        val corrupt = inventory.copy(sections = listOf(inventory.sections.single().copy(startUtf16 = -1)))
        assertFalse(CombinedCoverageValidator.validate(corrupt, sources, text, proposal, "p").structurallyAccounted)
    }
}
