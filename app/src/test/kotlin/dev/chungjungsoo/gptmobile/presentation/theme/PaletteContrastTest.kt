package dev.chungjungsoo.gptmobile.presentation.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaletteContrastTest {
    @Test
    fun transparentTextMustBeBlendedBeforeCheckingContrast() {
        assertEquals(21.0, contrastRatio(Color.Black, Color.White), 0.001)
        assertEquals(1.0, contrastRatio(Color.Black.copy(alpha = 0f), Color.White), 0.001)
        assertTrue(contrastRatio(Color.Black.copy(alpha = 0.2f), Color.White) < 4.5)
    }
}
