package dev.chungjungsoo.gptmobile.presentation.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayNameTreatmentTest {
    @Test fun exactThemePrimaryRetainsNormalTextContrastAtItsMinimumOpacity() {
        val primaries = listOf(0xFF6750A4.toInt(), 0xFFD0BCFF.toInt(), 0xFF006A60.toInt(), 0xFF4F6353.toInt(), 0xFF777777.toInt(), 0xFFFFEE00.toInt())
        val backgrounds = listOf(0xFFF7F2FA.toInt(), 0xFF1D1B20.toInt(), 0xFFE8DEF8.toInt(), 0xFF4A4458.toInt(), 0xFF777777.toInt())
        for (primary in primaries) {
            for (background in backgrounds) {
                val treatment = gatewayNameTreatment(primary, background)
                assertTrue(treatment.minimumOpacity in GATEWAY_NAME_MINIMUM_OPACITY..1f)
                assertTrue(gatewayColorContrast(primary, treatment.backgroundArgb, treatment.minimumOpacity) >= 4.5)
            }
        }
    }

    @Test fun aContrastingThemeKeepsTheOriginalSurfaceAndSixSecondCycle() {
        val background = 0xFFFFFFFF.toInt()
        val primary = 0xFF43116B.toInt()
        val treatment = gatewayNameTreatment(primary, background)
        assertEquals(background, treatment.backgroundArgb)
        assertEquals(.86f, treatment.minimumOpacity, 0f)
        assertEquals(6_000, GATEWAY_NAME_FADE_HALF_CYCLE_MS * 2)
    }

    @Test fun steadyOnlineBadgeColorsMeetLightAndDarkTextAndIndicatorContrast() {
        for ((foreground, background) in listOf(0xFF9CE5AE.toInt() to 0xFF123B21.toInt(), 0xFF146C2E.toInt() to 0xFFE3F4E8.toInt())) {
            assertTrue(gatewayColorContrast(foreground, background) >= 4.5)
        }
    }
}
