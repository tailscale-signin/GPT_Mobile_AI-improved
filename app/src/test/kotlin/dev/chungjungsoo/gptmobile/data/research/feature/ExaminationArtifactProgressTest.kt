package dev.chungjungsoo.gptmobile.data.research.feature

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExaminationArtifactProgressTest {
    private val profile = PlatformV2(uid = "examiner", name = "Examiner", model = "fixed-model", apiUrl = "https://example.org/v1")

    @Test fun savedAttemptClaimsSurviveEveryRestartAndStopBeforeAnotherDispatch() {
        val key = ExaminationArtifactBudget.key(profile, "research-appraisal", "source packet")
        var checkpoint = ExaminationCheckpoint()
        repeat(ExaminationArtifactBudget.MAX_ATTEMPTS) { index ->
            checkpoint = checkpoint.copy(modelAttempts = ExaminationArtifactBudget.claim(checkpoint.modelAttempts, key))
            checkpoint = Json.decodeFromString(Json.encodeToString(checkpoint))
            assertEquals(index + 1, checkpoint.modelAttempts[key])
        }
        val failure = runCatching { ExaminationArtifactBudget.claim(checkpoint.modelAttempts, key) }.exceptionOrNull()
        assertTrue(failure?.message.orEmpty().startsWith(ExaminationArtifactBudget.EXHAUSTED))
        val disposition = examinationFailure(requireNotNull(failure) as Exception, 0, 1000)
        assertEquals(ExaminationStage.WAITING_CAPACITY, disposition.stage)
        assertFalse(disposition.retryable)
    }

    @Test fun partialsCannotMoveToAnotherSliceStageModelOrDestination() {
        val key = ExaminationArtifactBudget.key(profile, "research-appraisal-part", "source slice one")
        val otherKeys = listOf(
            ExaminationArtifactBudget.key(profile, "research-appraisal-part", "source slice two"),
            ExaminationArtifactBudget.key(profile, "research-study-review-part", "source slice one"),
            ExaminationArtifactBudget.key(profile.copy(model = "other-model"), "research-appraisal-part", "source slice one"),
            ExaminationArtifactBudget.key(profile.copy(apiUrl = "https://elsewhere.example/v1"), "research-appraisal-part", "source slice one"),
            ExaminationArtifactBudget.key(profile.copy(uid = "other-examiner"), "research-appraisal-part", "source slice one")
        )
        otherKeys.forEach { assertNotEquals(key, it) }
        assertEquals(key, ExaminationArtifactBudget.key(profile.copy(name = "Renamed profile"), "research-appraisal-part", "source slice one"))
        assertTrue(ExaminationArtifactBudget.storageStage("research-appraisal-part", key).length <= 96)
        assertNotEquals(ExaminationArtifactBudget.storageStage("research-appraisal-part", key), ExaminationArtifactBudget.storageStage("research-appraisal-part", otherKeys.first()))
    }

    @Test fun completeArtifactIdentityAndLegacyCheckpointsRoundTrip() {
        val old = Json.decodeFromString<ExaminationCheckpoint>("""{"nextChunk":2,"unfinishedModelCheckpoint":"legacy.partial"}""")
        assertEquals(2, old.nextChunk)
        assertEquals(null, old.modelProgress)
        assertTrue(old.modelAttempts.isEmpty())
        val progress = ExaminationArtifactProgress("request", "saved.partial", complete = true)
        val restored = Json.decodeFromString<ExaminationCheckpoint>(Json.encodeToString(old.copy(modelProgress = progress)))
        assertEquals(progress, restored.modelProgress)
    }
}
