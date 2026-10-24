package dev.chungjungsoo.gptmobile.data.research.delivery

import dev.chungjungsoo.gptmobile.data.model.DeepResearchSettings
import dev.chungjungsoo.gptmobile.data.model.ResearchContentScope
import dev.chungjungsoo.gptmobile.data.model.ResearchReportDetail
import dev.chungjungsoo.gptmobile.data.model.ResearchReportStructure
import dev.chungjungsoo.gptmobile.data.research.ResearchSnapshot
import dev.chungjungsoo.gptmobile.data.research.ResearchSource
import org.junit.Assert.assertEquals
import org.junit.Test

class ResearchReportStructureTest {
    @Test fun exactInhalationTaskUsesScientificStructureWhileWebOnlyScopeStaysAccepted() {
        val settings = DeepResearchSettings(contentScope = ResearchContentScope.WEB_ONLY)
        val source = ResearchSource("S1", "https://example.org/evidence", "Documented evidence", "Read", "Captured text")
        val snapshot = ResearchSnapshot("benefits of hydrogen inhalation", sources = listOf(source), reportStructure = settings.reportStructure.name)
        val packet = ResearchDeliveryPacket(snapshot, ResearchReportDetail.DETAILED, listOf(source), emptyList(), emptyList(), emptyMap())
        assertEquals(ResearchReportContract.SCIENTIFIC, packet.contract)
        assertEquals(ResearchContentScope.WEB_ONLY, settings.contentScope)
    }

    @Test fun evidenceMetadataSelectsStructureAndExplicitChoiceWinsWithoutResettingCollection() {
        val source = ResearchSource("S1", "https://example.org/study", "Study", pmid = "1234")
        assertEquals(ResearchReportContract.SCIENTIFIC, ResearchReportContract.forTask("Compare the findings", sources = listOf(source)))
        assertEquals(ResearchReportContract.GENERAL, ResearchReportContract.forTask("Compare the findings", sources = listOf(source), overrideStructure = "GENERAL"))
        val automatic = DeepResearchSettings()
        val explicit = automatic.copy(reportStructure = ResearchReportStructure.SCIENTIFIC)
        assertEquals(automatic.collectionPolicyKey(), explicit.collectionPolicyKey())
        val snapshot = ResearchSnapshot("Compare the findings", reportStructure = explicit.reportStructure.name)
        assertEquals("SCIENTIFIC", ResearchSnapshot.parse(snapshot.json())?.reportStructure)
    }

    @Test fun legacySnapshotDefaultsToAutomaticStructureAndTechnicalTaskStaysTechnical() {
        val old = ResearchSnapshot("Android runtime architecture").json().let { kotlinx.serialization.json.JsonObject(it - "reportStructure") }
        assertEquals("AUTO", ResearchSnapshot.parse(old)?.reportStructure)
        assertEquals(ResearchReportContract.TECHNICAL, ResearchReportContract.forTask("Android runtime architecture"))
    }
}
