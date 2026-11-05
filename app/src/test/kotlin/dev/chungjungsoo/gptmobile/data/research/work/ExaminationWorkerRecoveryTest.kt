package dev.chungjungsoo.gptmobile.data.research.work

import dev.chungjungsoo.gptmobile.data.research.feature.ExaminationJob
import dev.chungjungsoo.gptmobile.data.research.feature.ExaminationOrigin
import dev.chungjungsoo.gptmobile.data.research.feature.ExaminationStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExaminationWorkerRecoveryTest {
    private fun paused(origin: String, reason: String = "Saved progress retained") = ExaminationJob("job", "dedup", "work", "version", ExaminationOrigin.AUTHORIZED_RESEARCH, 1, "examiner", "public", "policy", ExaminationStage.PAUSED, terminalReason = reason, updatedAt = 1, cancellationOrigin = origin)

    @Test fun elapsedDeadlinesAndStalledOwnershipCannotBecomeAutomaticRetryLoops() {
        listOf("PHASE_DEADLINE", "ATTEMPT_DEADLINE", "QUEUE_DEADLINE", "WATCHDOG_EXPIRED", "OWNERSHIP_CHANGED", "PARENT_CANCELLED").forEach { origin ->
            assertEquals(origin, "EXECUTION_PAUSED", examinationAutomaticWaitReason(paused(origin)))
        }
    }

    @Test fun userPauseWinsOverForegroundRecoveryAndPermissionChangesWaitForConfiguration() {
        assertEquals("USER_PAUSED", examinationAutomaticWaitReason(paused("USER_PAUSE", "foreground_required")))
        assertEquals("WAITING_FOR_CONFIGURATION", examinationAutomaticWaitReason(paused("POLICY_CHANGED", "source_scope changed")))
        assertEquals("WAITING_FOR_CONFIGURATION", examinationAutomaticWaitReason(paused("PERMISSION_REVOKED")))
        assertNull(examinationAutomaticWaitReason(paused("POLICY_CHANGED", "foreground_required")))
        assertNull(examinationAutomaticWaitReason(paused("PROCESS_RECOVERY")))
        assertNull(examinationAutomaticWaitReason(paused("WAITING_FOR_CONFIGURATION")))
    }

    @Test fun networkWaitKeepsItsExistingBoundedRetryPolicy() {
        assertNull(examinationAutomaticWaitReason(paused("unused").copy(stage = ExaminationStage.WAITING_NETWORK)))
        assertFalse(examinationConfigurationBlocked(java.io.IOException("Connection reset")))
        assertTrue(examinationConfigurationBlocked(IllegalStateException("RESEARCH_TOOL_NOT_READY: selected gateway has no reader")))
        assertTrue(examinationConfigurationBlocked(IllegalStateException("Examination permission changed")))
    }
}
