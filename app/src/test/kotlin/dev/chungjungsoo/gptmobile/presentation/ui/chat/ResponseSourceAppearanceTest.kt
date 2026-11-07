package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import dev.chungjungsoo.gptmobile.data.database.entity.ResponseSource
import dev.chungjungsoo.gptmobile.data.database.entity.ResponseTransport
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponseSourceAppearanceTest {
    @Test fun gatewayAccentMeetsNormalTextContrastOnLightDarkAndTintedSurfaces() {
        val accents = listOf(Color(0xff6750a4), Color(0xffd0bcff), Color.Yellow, Color.Cyan, Color.Red)
        val backgrounds = listOf(Color.White, Color.Black, Color(0xff777777), Color(0xff121212))
        for (background in backgrounds) {
            for (accent in accents) {
                val surface = accent.copy(alpha = 0.138f).compositeOver(background)
                assertTrue(ProfileAttributionPalette.contrast(ResponseSourceAppearance.gatewayForeground(accent, surface), surface) >= 4.5)
            }
        }
    }

    @Test fun gatewaySemanticsRequireProofCapturedForThatResponse() {
        assertFalse(ResponseSourceAppearance.isGateway(null))
        assertFalse(ResponseSourceAppearance.isGateway(ResponseSource(executionLocation = "gateway-host")))
        assertFalse(ResponseSourceAppearance.isGateway(ResponseSource(ResponseTransport.GATEWAY)))
        assertFalse(ResponseSourceAppearance.isGateway(ResponseSource(ResponseTransport.GATEWAY, executionLocation = "gateway-host", profileUid = "gateway-profile")))
        assertTrue(ResponseSourceAppearance.isGateway(ResponseSource(ResponseTransport.GATEWAY, gatewayVerified = true)))
    }

    @Test fun directAndUnknownTransportsCannotClaimTheGatewayAccent() {
        for (transport in listOf(ResponseTransport.DIRECT, ResponseTransport.UNKNOWN)) {
            for (proof in listOf(false, true)) {
                assertFalse(ResponseSourceAppearance.isGateway(ResponseSource(transport, executionLocation = "gateway-host", profileUid = "gateway-profile", gatewayVerified = proof)))
            }
        }
    }
}
