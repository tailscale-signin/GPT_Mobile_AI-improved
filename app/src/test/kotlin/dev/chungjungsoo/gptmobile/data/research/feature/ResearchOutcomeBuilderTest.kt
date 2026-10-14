package dev.chungjungsoo.gptmobile.data.research.feature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResearchOutcomeBuilderTest {
    private val version = ResearchWorkVersion("v", "w", "hash", sourceUrl = "https://example.org", retrievedAt = 1, coverage = ArticleCoverage.ABSTRACT_ONLY, authority = "public", publicSource = true)
    private val passage = EvidencePassage("p", "v", "abstract", 0, 27, "hash", "Sleep improved by 3 points.")
    private fun field(name: String, value: String) = StudyField(name, "v", "sleep", name, value, null, Missingness.PRESENT, "[\"p\"]", "validated_model_extraction")

    @Test fun onlyGroundedExtractiveFindingsFromReadableEvidenceBecomeOutcomes() {
        val fields = listOf(field("outcome", "Sleep"), field("effect", "3 points"))
        val result = ResearchOutcomeBuilder.build(version, fields, listOf(passage), 2).single()
        assertEquals("GROUNDED", result.state)
        assertTrue(result.limitations.contains("not reported"))
        assertEquals("v", result.versionId)
        assertTrue(ResearchOutcomeBuilder.build(version.copy(coverage = ArticleCoverage.METADATA_ONLY), fields, listOf(passage), 2).isEmpty())
        assertTrue(ResearchOutcomeBuilder.build(version, listOf(field("outcome", "Sleep"), field("effect", "99 points")), listOf(passage), 2).isEmpty())
        assertTrue(ResearchOutcomeBuilder.build(version, fields, emptyList(), 2).isEmpty())
    }
}
