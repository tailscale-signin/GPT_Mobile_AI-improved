package dev.chungjungsoo.gptmobile.presentation.ui.research

import dev.chungjungsoo.gptmobile.data.research.feature.ExaminationJob
import dev.chungjungsoo.gptmobile.data.research.feature.ExaminationOrigin
import dev.chungjungsoo.gptmobile.data.research.feature.ExaminationStage
import org.junit.Assert.assertEquals
import org.junit.Test

class ExaminationPrimaryActionTest {
    private fun job(id: String, stage: ExaminationStage, time: Long) = ExaminationJob(id, id, "work", "version", ExaminationOrigin.USER_EXAMINE, 7, "profile", "public", "policy", stage, updatedAt = time, queuedAt = time)

    @Test fun newerInterruptedAttemptResumesDespiteAnOlderCompletedAssessment() {
        val old = job("old", ExaminationStage.COMPLETE_FULL, 1).copy(updatedAt = 30)
        val current = job("current", ExaminationStage.PAUSED, 2)
        assertEquals("current", examinationPrimarySelection(listOf(current, old), "profile", "version").resumeJobId)
        assertEquals(ExaminationPrimaryAction.RESUME, examinationPrimarySelection(listOf(current, old), "profile", "version").action)
        assertEquals(ExaminationPrimaryAction.EXAMINE_AGAIN, examinationPrimarySelection(listOf(old, current.copy(stage = ExaminationStage.COMPLETE_PARTIAL)), "profile", "version").action)
    }

    @Test fun otherProfilesAndVersionsDoNotChangeTheDisplayedAction() {
        val completed = job("done", ExaminationStage.COMPLETE_FULL, 1)
        assertEquals(ExaminationPrimaryAction.EXAMINE, examinationPrimarySelection(listOf(completed.copy(profileUid = "other"), completed.copy(requestedVersionId = "other")), "profile", "version").action)
    }

    @Test fun legacyUnboundJobsRequireMatchingCapturedVersionEvidence() {
        val legacy = job("legacy", ExaminationStage.COMPLETE_FULL, 1).copy(requestedVersionId = null)
        assertEquals(ExaminationPrimaryAction.EXAMINE, examinationPrimarySelection(listOf(legacy), "profile", "version").action)
        assertEquals(ExaminationPrimaryAction.EXAMINE_AGAIN, examinationPrimarySelection(listOf(legacy.copy(checkpointJson = "{\"versionId\":\"version\",\"unrelatedEvidence\":[{\"text\":\"retained\"}]}")), "profile", "version").action)
    }

    @Test fun capturedVersionCanResumeButAnAutomaticJobCannotBecomeAManualCheckpoint() {
        val current = job("saved", ExaminationStage.PAUSED, 1).copy(requestedVersionId = "metadata", checkpointJson = "{\"versionId\":\"version\"}")
        assertEquals("saved", examinationPrimarySelection(listOf(current), "profile", "version").resumeJobId)
        assertEquals(ExaminationPrimaryAction.EXAMINE, examinationPrimarySelection(listOf(current.copy(origin = ExaminationOrigin.AUTHORIZED_RESEARCH)), "profile", "version").action)
    }
}
