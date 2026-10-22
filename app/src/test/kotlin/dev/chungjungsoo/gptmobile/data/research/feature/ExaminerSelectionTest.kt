package dev.chungjungsoo.gptmobile.data.research.feature

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.model.ChatMcpToolConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExaminerSelectionTest {
    private val available = PlatformV2(uid = "ready", name = "Examiner", enabled = true)

    @Test fun missingAndDisabledSelectionsNeverFallBackToAnotherProfile() {
        val missing = AppFeatureSettings(researchFeature = ResearchFeatureSettings(crawlerProfileUid = "removed"))
        val selection = ResearchProfileResolver.examinerSelection(missing, listOf(available), 1, ChatMcpToolConfig())
        assertEquals(ExaminerSelectionStatus.MISSING, selection.status)
        assertNull(selection.profile)
        assertTrue(examinerSelectionIssue(selection)!!.contains("missing"))
        val disabled = ResearchProfileResolver.selection(available.uid, listOf(available.copy(enabled = false)))
        assertEquals(ExaminerSelectionStatus.DISABLED, disabled.status)
        assertNull(disabled.profile)
        assertEquals(ExaminerSelectionStatus.AMBIGUOUS, ResearchProfileResolver.selection(available.uid, listOf(available, available)).status)
        assertEquals(ExaminerSelectionStatus.UNSELECTED, ResearchProfileResolver.selection("", listOf(available)).status)
        assertTrue(examinerSelectionIssue(ResearchProfileResolver.selection("", listOf(available)))!!.contains("Choose Examiner"))
        assertNull(examinerSelectionIssue(ResearchProfileResolver.selection(available.uid, listOf(available))))
    }

    @Test fun waitingLabelsDescribeSourceAccessWithoutImplyingScientificRejection() {
        assertEquals("Source access blocked", dev.chungjungsoo.gptmobile.presentation.ui.research.examinationStageLabel(ExaminationStage.BLOCKED_SOURCE))
        val label = dev.chungjungsoo.gptmobile.presentation.ui.research.examinationStageLabel(ExaminationStage.WAITING_NETWORK)
        assertTrue(label.contains("network"))
        assertFalse(label.contains("unreliable"))
    }
}
