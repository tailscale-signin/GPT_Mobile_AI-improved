package dev.chungjungsoo.gptmobile.presentation.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaletteContrastTest {
    @Test
    fun neutralMaterialSurfacesFollowTheThemeAccent() {
        val base = androidx.compose.material3.darkColorScheme(
            primary = Color(0xFF00FFDE),
            surface = Color(0xFF202020),
            background = Color(0xFF101010)
        )
        val result = tintNeutralSurfaces(base)
        assertTrue(result.surface.green > result.surface.red)
        assertTrue(result.background.green > result.background.red)
        assertEquals(base.primary, result.primary)
        assertTrue(contrastRatio(result.onSurface, result.surface) >= 4.5)
    }

    @Test
    fun chromaticCustomSurfacesArePreserved() {
        val base = androidx.compose.material3.darkColorScheme(surface = Color(0xFF12383F))
        assertEquals(base.surface, tintNeutralSurfaces(base).surface)
    }

    @Test
    fun transparentTextMustBeBlendedBeforeCheckingContrast() {
        assertEquals(21.0, contrastRatio(Color.Black, Color.White), 0.001)
        assertEquals(1.0, contrastRatio(Color.Black.copy(alpha = 0f), Color.White), 0.001)
        assertTrue(contrastRatio(Color.Black.copy(alpha = 0.2f), Color.White) < 4.5)
    }
}
