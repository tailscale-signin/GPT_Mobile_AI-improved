package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.database.entity.CombinedModelResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContributionLineageTest {
    private fun source(uid: String, body: String, status: String = "COMPLETED", local: Boolean = false) =
        CombinedModelResponse(uid, uid, content = body, completionStatus = status, localOnly = local)

    @Test fun exactDedupRetainsEverySelectedOriginAndExcludesUnselectedReviewer() {
        val sources = listOf(source("primary", "Shared fact.\nPrimary detail."), source("other", "Shared fact.\nOther detail."), source("reviewer", "Reviewed all facts."))
        val lineage = ContributionLineage.freeze(listOf("primary", "other"), sources)
        assertEquals(setOf("primary", "other"), lineage.spans.map { it.profileUid }.toSet())
        assertEquals(2, lineage.identicalOccurrences.values.single().size)
        assertFalse(lineage.semanticSupportVerified)
        assertFalse(lineage.comparisonComplete)
    }

    @Test fun missingAndFailedCompetitorsRemainUnknownInsteadOfExclusive() {
        val lineage = ContributionLineage.freeze(listOf("primary", "missing", "failed"), listOf(source("primary", "A claim."), source("failed", "Provider failed; retry", "FAILED")))
        assertEquals(listOf(ContributionAvailability.RETAINED, ContributionAvailability.MISSING, ContributionAvailability.PARTIAL), lineage.revisions.map { it.availability })
        assertEquals(setOf("primary"), lineage.spans.map { it.profileUid }.toSet())
        assertFalse(lineage.comparisonComplete)
    }

    @Test fun exactUnicodeLocatorsRejectRevisionsAndKeepLocalOnlyRestrictions() {
        val body = "x".repeat(1599) + "👩🏽‍💻 é שלום\nLast source."
        val sources = listOf(source("local", body, local = true))
        val lineage = ContributionLineage.freeze(listOf("local"), sources)
        val reconstructed = lineage.spans.joinToString("") { requireNotNull(lineage.resolve(it.id, sources, allowLocalOnly = true)) }
        assertEquals(body, reconstructed)
        assertTrue(lineage.spans.all { !Character.isHighSurrogate(body[it.endUtf16 - 1]) })
        assertEquals(body.toByteArray(Charsets.UTF_8).size.toLong(), lineage.spans.last().endUtf8)
        assertNull(lineage.resolve(lineage.spans.first().id, sources, allowLocalOnly = false))
        assertNull(lineage.resolve(lineage.spans.first().id, listOf(source("local", body + " changed", local = true)), allowLocalOnly = true))
    }
}
