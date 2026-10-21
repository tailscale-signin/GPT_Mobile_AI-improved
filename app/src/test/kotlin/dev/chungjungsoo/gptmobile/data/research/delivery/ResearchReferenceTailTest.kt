package dev.chungjungsoo.gptmobile.data.research.delivery

import dev.chungjungsoo.gptmobile.data.model.ResearchReportDetail
import dev.chungjungsoo.gptmobile.data.research.ResearchSnapshot
import dev.chungjungsoo.gptmobile.data.research.ResearchSource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResearchReferenceTailTest {
    private val passage = "A synthetic randomized trial enrolled 20 participants. Follow-up was short and safety remains uncertain."
    private val source = ResearchSource(
        "S1",
        "https://example.org/study",
        "Synthetic trial registry of 999 participants",
        "Read",
        passage,
        doi = "10.1234/panel-study",
        publicationYear = 2024,
        bibliographyJson = """{"authors":["Ada Example","Ben Sample"],"journal":"Synthetic Trials","year":2024}"""
    )
    private val packet = ResearchDeliveryPacket.build(ResearchSnapshot("synthetic trial", sources = listOf(source)), ResearchReportDetail.BRIEF)
    private val findings = "Partial coverage. Independent review is not available.\n\n$passage [S1]"
    private val report = "$findings\n\n## References\n\n[S1] ${source.url}"

    @Test
    fun numericalFindingAfterReferencesRemainsInsideScientificValidation() {
        val text = "$report\n\nResearchers observed improved recovery in 999 patients. [S1]"
        val result = ResearchDeliveryValidator.validate(text, packet)
        assertTrue(result.issues.toString(), "NUMERICAL_CLAIM_NOT_IN_CITED_PASSAGE" in result.issues)
        assertTrue(ResearchReportRepair.blocks(text, packet).last().issues.contains("NUMERICAL_CLAIM_NOT_IN_CITED_PASSAGE"))
    }

    @Test
    fun uncitedFindingAfterReferencesCannotHideUnderTheHeading() {
        val text = "$report\n\nPatients experienced substantially improved outcomes after treatment."
        assertTrue(ResearchDeliveryValidator.validate(text, packet).issues.contains("UNSOURCED_SCIENTIFIC_PARAGRAPH"))
    }

    @Test
    fun registeredReferencePrefixCannotHideAddedFactualProseOnTheSameLine() {
        val text = "$report Researchers observed improved recovery in 999 patients. [S1]"
        assertTrue(ResearchDeliveryValidator.validate(text, packet).issues.contains("NUMERICAL_CLAIM_NOT_IN_CITED_PASSAGE"))
    }

    @Test
    fun exactRegisteredBibliographyAndCanonicalDoiRemainCompatible() {
        val reference = "- [S1] Ada Example; Ben Sample. [${source.title}](https://doi.org/${source.doi}). Synthetic Trials (2024). DOI: ${source.doi}"
        val text = "$findings\n\n## References\n\n$reference"
        val result = ResearchDeliveryValidator.validate(text, packet)
        assertTrue(result.issues.toString(), result.accepted)
        assertTrue(ResearchReportRepair.blocks(text, packet).last().issues.isEmpty())
        assertFalse(ResearchReportReferences.body(text, packet).contains("999 participants"))
    }

    @Test
    fun referencesAloneStillDoNotRepresentTheLeadingFinding() {
        val text = "Partial coverage. Independent review is not available.\n\n## References\n\n[S1] ${source.url}"
        assertTrue(ResearchDeliveryValidator.validate(text, packet).issues.contains("OMITTED_LEADING_STUDY:S1"))
    }

    @Test
    fun rendererIncludesARegisteredSourceCitedByProseAfterReferences() {
        val second = source.copy(id = "S2", url = "https://example.org/second-study", canonicalUrl = "https://example.org/second-study", doi = "", equivalenceGroup = "second")
        val both = ResearchDeliveryPacket.build(packet.snapshot.copy(sources = listOf(source, second)), ResearchReportDetail.BRIEF)
        val text = "$report\n\n$passage [S2]"
        val rendered = ResearchReportReferences.render(text, both)
        assertTrue(rendered, rendered.contains("[S2] ${second.url}"))
        assertTrue(rendered.contains("$passage [S2]"))
    }

    @Test
    fun factualHeadingCannotHideAnUnsupportedQuantity() {
        val text = "Partial coverage. Independent review is not available.\n\n## Study improved 999% [S1]\n\n## References\n\n[S1] ${source.url}"
        assertTrue(ResearchDeliveryValidator.validate(text, packet).issues.contains("NUMERICAL_CLAIM_NOT_IN_CITED_PASSAGE"))
    }

    @Test
    fun factualHeadingCitationDoesNotSupportFollowingUncitedProse() {
        val text = "Partial coverage. Independent review is not available.\n\n## Study observed short follow-up [S1]\nPatients experienced substantially improved outcomes after treatment.\n\n## References\n\n[S1] ${source.url}"
        val result = ResearchDeliveryValidator.validate(text, packet)
        assertTrue(result.issues.contains("UNSOURCED_SCIENTIFIC_PARAGRAPH"))
        assertTrue(result.repairHints.any { it.contains("Patients experienced") })
    }

    @Test
    fun numberedNavigationHeadingsRemainCompatibleWithoutSupplyingEvidence() {
        val text = "Partial coverage. Independent review is not available.\n\n## 1. Leading studies\n$passage [S1]\n\n## 2. Safety and limitations\nSafety is not established.\n\n## References\n\n[S1] ${source.url}"
        val result = ResearchDeliveryValidator.validate(text, packet)
        assertTrue(result.issues.toString(), result.accepted)
        val titleOnly = text.replace("$passage [S1]", "").replace("Leading studies", "Leading studies [S1]")
        assertTrue(ResearchDeliveryValidator.validate(titleOnly, packet).issues.contains("OMITTED_LEADING_STUDY:S1"))
    }
}
