package dev.chungjungsoo.gptmobile.data.research.feature

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ExaminationOutputBudgetTest {
    @Test fun scientificAppraisalRejectsTheLoggedVerificationClampBeforeDispatch() {
        assertThrows(IllegalStateException::class.java) { ExaminationOutputBudget.requireCapacity("research-appraisal", 1024) }
        ExaminationOutputBudget.requireCapacity("research-appraisal", 6144)
        ExaminationOutputBudget.requireCapacity("research-appraisal-part", 1024)
        ExaminationOutputBudget.requireCapacity("research-study-review", 4096)
        assertEquals(ExaminationStage.WAITING_CAPACITY, examinationFailure(IllegalStateException(ExaminationOutputBudget.INSUFFICIENT), 0).stage)
        assertFalse(examinationFailure(IllegalStateException(ExaminationOutputBudget.INSUFFICIENT), 0).retryable)
    }

    @Test fun splitAndRestoredCheckpointsRetainExactOffsetsAndCompletedChunks() {
        val source = "a".repeat(511) + "\uD83D\uDE00" + "b".repeat(511)
        val split = ExaminationSlice(0, source.length).split(source)
        assertEquals(source, split.joinToString("") { it.text(source).second })
        assertFalse(split[0].text(source).second.last().isHighSurrogate())
        assertTrue(split.all { it.compact })
        val saved = ExaminationCheckpoint(nextChunk = 1, chunkCount = 3, appraisalSlices = listOf(ExaminationSlice(0, 10)) + split, pendingAppraisalIds = listOf("committed"))
        assertEquals(saved, Json.decodeFromString<ExaminationCheckpoint>(Json.encodeToString(saved)))
        assertThrows(IllegalStateException::class.java) { ExaminationSlice(0, 100, true).split(source) }
    }
}
