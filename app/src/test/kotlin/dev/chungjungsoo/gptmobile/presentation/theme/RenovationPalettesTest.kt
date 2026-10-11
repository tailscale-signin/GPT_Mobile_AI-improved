package dev.chungjungsoo.gptmobile.presentation.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RenovationPalettesTest {
    @Test
    fun everyPairedPaletteHasReadableTextAndControlBoundaries() {
        RenovationPalettes.names.forEach { family ->
            listOf(false, true).forEach { dark ->
                val scheme = RenovationPalettes.scheme(family, dark)
                val pairs = listOf(
                    scheme.onPrimary to scheme.primary,
                    scheme.onPrimaryContainer to scheme.primaryContainer,
                    scheme.onSurface to scheme.surface,
                    scheme.onSurfaceVariant to scheme.background,
                    scheme.onSurfaceVariant to scheme.surfaceContainerHigh,
                    scheme.onError to scheme.error,
                    scheme.onErrorContainer to scheme.errorContainer
                )
                pairs.forEachIndexed { index, (text, background) ->
                    assertTrue("$family dark=$dark pair=$index", contrastRatio(text, background) >= 4.5)
                }
                assertTrue("$family outline", contrastRatio(scheme.outline, scheme.surface) >= 3.0)
            }
        }
    }

    @Test
    fun choosingAnAccentRetainsIndependentSecondaryAndTertiaryRoles() {
        val original = RenovationPalettes.scheme("Forest", false)
        val changed = RenovationPalettes.withPrimary(original, Color(0xFFFFE600))
        assertEquals(original.secondary, changed.secondary)
        assertEquals(original.tertiary, changed.tertiary)
        assertTrue(contrastRatio(changed.onPrimary, changed.primary) >= 4.5)
    }
}
