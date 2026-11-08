package dev.chungjungsoo.gptmobile.presentation.ui.home

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayProfileContrastTest {
    @Test fun pulseIsAllowedOnlyWhenItsFaintestFrameKeepsNormalTextContrast() {
        assertTrue(gatewayNamePulsePreservesContrast(Color.Black, Color.White))
        assertTrue(gatewayNamePulsePreservesContrast(Color.White, Color.Black))
        assertFalse(gatewayNamePulsePreservesContrast(Color.LightGray, Color.White))
        assertFalse(gatewayNamePulsePreservesContrast(Color.DarkGray, Color.Black))
    }
}
