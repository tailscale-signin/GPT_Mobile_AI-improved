package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.database.entity.CombinedModelResponse
import dev.chungjungsoo.gptmobile.data.research.researchHash
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CombinedProvenanceValidatorTest {
    private val answer = "The bridge opened in 1984."
    private val sources = listOf(
        CombinedModelResponse("a", "A", content = answer),
        CombinedModelResponse("b", "B", content = "Construction used steel.")
    )
    private val lineage = ContributionLineage.freeze(listOf("a", "b"), sources)
    private val qualification = AttributionVerifierQualification("unit-fixture", "a".repeat(64), 100, 99, true)

    private fun comparison(uid: String, supports: Boolean = false, conflicts: Boolean = false) = ProfileClaimComparison(
        uid,
        lineage.spans.filter { it.profileUid == uid }.map { it.id },
        lineage.spans.filter { supports && it.profileUid == uid }.map { it.id },
        lineage.spans.filter { conflicts && it.profileUid == uid }.map { it.id }
    )

    private fun sidecar(comparisons: List<ProfileClaimComparison> = listOf(comparison("a", true), comparison("b"))) = CombinedProvenanceSidecar(
        answerDigest = researchHash(answer),
        corpusDigest = lineage.corpusDigest,
        parserVersion = "parser",
        policyRevision = "policy",
        verifierRevision = "unit-fixture",
        sentences = listOf(
            SentenceAttributionProposal(0, answer.length, researchHash(answer), listOf(AttributionClaim(0, answer.length, researchHash(answer), comparisons)))
        )
    )

    private fun validate(proposal: CombinedProvenanceSidecar = sidecar(), authorized: Set<String> = setOf("a", "b")) =
        CombinedProvenanceValidator.validate(answer, sources, lineage, proposal, "parser", "policy", qualification, true, authorized, fixtureReceipts(proposal))

    private fun fixtureReceipts(proposal: CombinedProvenanceSidecar) = proposal.sentences.flatMap { it.claims }.map {
        VerifiedComparisonBatch(proposal.answerDigest, lineage.corpusDigest, "policy", "unit-fixture", it.startUtf16, it.endUtf16, it.textDigest, lineage.spans.map { span -> span.id }.toSet(), setOf("00000000-0000-0000-0000-000000000001"), true)
    }

    @Test
    fun `model claims of complete comparison cannot replace actual dispatch receipts`() {
        val proposal = sidecar()
        val receipts = fixtureReceipts(proposal)
        for (incomplete in listOf(emptyList(), receipts.map { it.copy(completed = false) }, receipts.map { it.copy(deliveredSpanIds = lineage.spans.filter { span -> span.profileUid == "a" }.map { span -> span.id }.toSet()) }, receipts.map { it.copy(policyRevision = "old") })) {
            val result = CombinedProvenanceValidator.validate(answer, sources, lineage, proposal, "parser", "policy", qualification, true, setOf("a", "b"), incomplete)
            assertEquals(ClaimSupportClass.UNKNOWN, result.sentences.single().supportClass)
            assertTrue(result.sentences.single().comparedSpans < result.sentences.single().expectedSpans)
        }
    }

    @Test
    fun `omitted numeric signs and percentage qualifiers cannot receive exclusive colors`() {
        for (text in listOf("-5 degrees.", "≤5 cases.", "5%.")) {
            val start = text.indexOf('5')
            val proposal = sidecar().copy(
                answerDigest = researchHash(text),
                sentences = listOf(
                    SentenceAttributionProposal(
                        0,
                        text.length,
                        researchHash(text),
                        listOf(
                            AttributionClaim(start, start + 1, researchHash("5"), listOf(comparison("a", true), comparison("b")))
                        )
                    )
                )
            )
            val result = CombinedProvenanceValidator.validate(text, sources, lineage, proposal, "parser", "policy", qualification, true, setOf("a", "b"))
            assertEquals(ClaimSupportClass.UNKNOWN, result.sentences.single().supportClass)
        }
    }

    @Test
    fun `qualification precision is exact even for large reported counts`() {
        val emitted = 1_000_000_000_000_000_000L
        assertFalse(qualification.copy(emittedExclusiveLabels = emitted, correctExclusiveLabels = emitted / 100 * 99 - 1).qualified)
        assertTrue(qualification.copy(emittedExclusiveLabels = emitted, correctExclusiveLabels = emitted / 100 * 99).qualified)
    }

    @Test
    fun `qualified complete comparison can label a sole supporter but shared paraphrase stays default`() {
        assertEquals("a", validate().sentences.single().exclusiveProfileUid)
        val shared = validate(sidecar(listOf(comparison("a", true), comparison("b", true)))).sentences.single()
        assertEquals(ClaimSupportClass.SHARED, shared.supportClass)
        assertEquals(setOf("a", "b"), shared.supporterUids)
        assertNull(shared.exclusiveProfileUid)
    }

    @Test
    fun `negation or quantity contradiction is not corroboration`() {
        val result = validate(sidecar(listOf(comparison("a", true), comparison("b", conflicts = true)))).sentences.single()
        assertEquals(ClaimSupportClass.CONFLICTING, result.supportClass)
        assertNull(result.exclusiveProfileUid)
    }

    @Test
    fun `missing late corpus chunk and fabricated identifiers cannot establish exclusivity`() {
        val omitted = comparison("b").copy(comparedSpanIds = emptyList())
        val invented = comparison("a", true).copy(supportSpanIds = listOf("invented"))
        for (proposal in listOf(sidecar(listOf(comparison("a", true), omitted)), sidecar(listOf(invented, comparison("b"))))) {
            assertEquals(ClaimSupportClass.UNKNOWN, validate(proposal).sentences.single().supportClass)
        }
    }

    @Test
    fun `changed answer parser corpus or permissions invalidate all foreground overrides`() {
        for (proposal in listOf(sidecar().copy(answerDigest = "stale"), sidecar().copy(parserVersion = "stale"), sidecar().copy(policyRevision = "stale"), sidecar().copy(corpusDigest = "stale"))) {
            assertTrue(validate(proposal).sentences.isEmpty())
        }
        val revoked = validate(authorized = setOf("b")).sentences.single()
        assertEquals(ClaimSupportClass.UNKNOWN, revoked.supportClass)
        assertTrue(revoked.supportSpanIds.isEmpty())
    }

    @Test
    fun `mixed sentence and omitted qualifier stay default`() {
        val split = answer.indexOf("1984")
        val whole = sidecar().sentences.single()
        val first = AttributionClaim(0, split, researchHash(answer.substring(0, split)), listOf(comparison("a", true), comparison("b", true)))
        val second = AttributionClaim(split, answer.length, researchHash(answer.substring(split)), listOf(comparison("a", true), comparison("b")))
        assertEquals(ClaimSupportClass.MIXED, validate(sidecar().copy(sentences = listOf(whole.copy(claims = listOf(first, second))))).sentences.single().supportClass)
        assertEquals(ClaimSupportClass.UNKNOWN, validate(sidecar().copy(sentences = listOf(whole.copy(claims = listOf(first))))).sentences.single().supportClass)
    }

    @Test
    fun `all abstaining or unevaluated verifier cannot qualify`() {
        assertFalse(qualification.copy(emittedExclusiveLabels = 0, correctExclusiveLabels = 0).qualified)
        assertFalse(qualification.copy(correctExclusiveLabels = 98).qualified)
        assertFalse(qualification.copy(heldOutHumanLabels = false).qualified)
        val result = CombinedProvenanceValidator.validate(answer, sources, lineage, sidecar(), "parser", "policy", null, true, setOf("a", "b"))
        assertNull(result.sentences.single().exclusiveProfileUid)
    }

    @Test
    fun `overlapping sentences and incomplete competing contributions cannot be colored`() {
        assertTrue(validate(sidecar().copy(sentences = sidecar().sentences + sidecar().sentences)).sentences.isEmpty())
        val partial = sources.map { if (it.platformUid == "b") it.copy(completionStatus = "FAILED") else it }
        val partialLineage = ContributionLineage.freeze(listOf("a", "b"), partial)
        val result = CombinedProvenanceValidator.validate(answer, partial, partialLineage, sidecar().copy(corpusDigest = partialLineage.corpusDigest), "parser", "policy", qualification, true, setOf("a", "b"))
        assertNull(result.sentences.single().exclusiveProfileUid)
    }
}
