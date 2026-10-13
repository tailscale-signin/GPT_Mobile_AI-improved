package dev.chungjungsoo.gptmobile.presentation.ui.chat

import dev.chungjungsoo.gptmobile.data.agent.ClaimSupportClass
import dev.chungjungsoo.gptmobile.data.agent.ContributionLineage
import dev.chungjungsoo.gptmobile.data.agent.ValidatedCombinedProvenance
import dev.chungjungsoo.gptmobile.data.agent.ValidatedSentenceAttribution
import dev.chungjungsoo.gptmobile.data.database.entity.CombinedModelResponse
import dev.chungjungsoo.gptmobile.data.research.researchHash
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CombinedContributionInspectorTest {
    private val sources = listOf(CombinedModelResponse("a", "Profile A", content = "A claim."))
    private val lineage = ContributionLineage.freeze(listOf("a", "missing"), sources)

    @Test
    fun absentSidecarKeepsEveryPassageUnknownAndPreservesUnicode() {
        val answer = "A 👩🏽‍🔬 finding. Another e\u0301 fact. نهاية."
        val rows = contributionSentenceInventory(answer, lineage, null, "policy")
        assertEquals(answer, rows.joinToString("") { it.text })
        assertTrue(rows.all { it.supportClass == ClaimSupportClass.UNKNOWN && it.spanIds.isEmpty() && it.compared == 0L })
        assertEquals(2, lineage.revisions.size)
    }

    @Test
    fun validatedSharedConflictAndUnknownAreInspectableWithoutExclusiveOwnership() {
        val answer = "A claim. A doubt. Unknown."
        val spans = listOf(0 to 9, 9 to 18, 18 to answer.length)
        val classes = listOf(ClaimSupportClass.SHARED, ClaimSupportClass.CONFLICTING, ClaimSupportClass.UNKNOWN)
        val provenance = ValidatedCombinedProvenance(
            researchHash(answer),
            lineage.corpusDigest,
            ATTRIBUTION_MARKDOWN_PARSER_VERSION,
            "policy",
            spans.mapIndexed { i, (start, end) ->
                ValidatedSentenceAttribution(start, end, classes[i], setOf("a"), lineage.spans.map { it.id }.toSet(), 1, 1, null)
            }
        )
        val rows = contributionSentenceInventory(answer, lineage, provenance, "policy")
        assertEquals(classes, rows.map { it.supportClass })
        assertTrue(rows.all { it.spanIds.isNotEmpty() })
        assertTrue(contributionSentenceInventory(answer, lineage, provenance, "revoked").all { it.supportClass == ClaimSupportClass.UNKNOWN })
        assertTrue(contributionSentenceInventory(answer + " Edited.", lineage, provenance, "policy").all { it.supportClass == ClaimSupportClass.UNKNOWN })
    }

    @Test
    fun evidenceActionsRejectExecutableAndCredentialBearingUrls() {
        assertEquals("https://example.org/page", contributionEvidenceUrl("https://example.org/page"))
        assertNull(contributionEvidenceUrl("javascript:alert(1)"))
        assertNull(contributionEvidenceUrl("file:///private/data"))
        assertNull(contributionEvidenceUrl("https://secret@example.org/page"))
        assertNull(contributionEvidenceUrl("https:relative"))
    }
}
