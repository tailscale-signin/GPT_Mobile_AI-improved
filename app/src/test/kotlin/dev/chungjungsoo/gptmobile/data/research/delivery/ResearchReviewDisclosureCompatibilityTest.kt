package dev.chungjungsoo.gptmobile.data.research.delivery

import dev.chungjungsoo.gptmobile.data.model.ResearchReportDetail
import dev.chungjungsoo.gptmobile.data.research.ResearchSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResearchReviewDisclosureCompatibilityTest {
    private val packet = ResearchDeliveryPacket.build(ResearchSnapshot("test", complete = false), ResearchReportDetail.BRIEF)

    @Test fun legacyUnreviewedDisclosurePreservesEveryByte() {
        val report = "Partial coverage. Independent review is not available.\n\nRetained text.  "
        assertTrue(ResearchReportReviewStatus.hasDisclosure(report))
        assertEquals(report, ResearchReportReviewStatus.disclose(report, packet))
    }

    @Test fun vagueReviewMentionDoesNotBypassDisclosure() {
        val report = "Review is useful."
        assertFalse(ResearchReportReviewStatus.hasDisclosure(report))
        assertTrue(ResearchReportReviewStatus.disclose(report, packet).startsWith("Review status: Unreviewed;"))
    }
}
