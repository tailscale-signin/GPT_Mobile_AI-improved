package dev.chungjungsoo.gptmobile.presentation.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnswerFontPolicyTest {
    private fun scale(length: Int, width: Float = 360f, height: Float = 720f, fontScale: Float = 1f) = adaptiveAnswerScale(length, width, height, 16f, fontScale)

    @Test fun shortAnswersAreLargerAndLongAnswersUseRegularSize() {
        assertTrue(scale(40) > 1.4f)
        assertEquals(1f, scale(1200), 0.001f)
        assertEquals(1f, scale(40000), 0.001f)
        assertEquals(1f, scale(0), 0.001f)
    }

    @Test fun generationOnlyShrinksTowardTheRegularFloor() {
        val scales = (1..1500).map { scale(it) }
        assertTrue(scales.zipWithNext().all { (a, b) -> b <= a })
        assertTrue(scales.all { it in 1f..1.65f })
    }

    @Test fun smallPanesAndLargeSystemFontsDoNotOverEnlarge() {
        assertTrue(scale(100, 240f, 360f) <= scale(100))
        assertTrue(scale(100, fontScale = 2f) <= scale(100))
        assertEquals(1f, scale(100, 120f, 180f, 2f), 0.001f)
        assertEquals(1f, scale(100, Float.NaN), 0.001f)
        assertEquals(1f, adaptiveAnswerScale(100, 360f, 720f, 16f, 1f, 30), 0.001f)
    }

    @Test fun nearbyStreamingDeltasUseSameSizeBucket() {
        assertEquals(scale(165), scale(175), 0.001f)
    }
}
