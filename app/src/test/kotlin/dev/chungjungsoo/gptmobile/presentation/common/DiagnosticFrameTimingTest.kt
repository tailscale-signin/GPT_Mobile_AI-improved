package dev.chungjungsoo.gptmobile.presentation.common

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.diagnostics.CombinedOperationOwner
import dev.chungjungsoo.gptmobile.data.diagnostics.UiOperationContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DiagnosticFrameTimingTest {
    @get:Rule val compose = createComposeRule()

    @Test fun invalidLabelsDoNotThrowOrInstallPrivateTelemetry() {
        val original = Modifier.testTag("content")
        listOf("Gateway chats", "", "private\nconversation", "x".repeat(41), "gateway/model?token=private").forEach { label ->
            assertSame(original, original.diagnosticFrameTiming(label))
            assertSame(original, original.diagnosticFrameTiming(CombinedOperationOwner(screen = "Chat", uiContext = UiOperationContext(label))))
        }
    }

    @Test fun componentIdentityChangesKeepTheSubtreeMeasurableAndDrawable() {
        var owner by mutableStateOf(CombinedOperationOwner(screen = "Chat", messageId = 42, revisionId = "selected:0", uiContext = UiOperationContext("Assistant", itemCount = 3)))
        compose.setContent {
            val timing = androidx.compose.runtime.remember(owner) { androidx.compose.ui.Modifier.diagnosticFrameTiming(owner) }
            Box(
                Modifier.then(timing).size(120.dp, 64.dp).testTag("component_content").drawWithContent {
                    drawRect(Color.Magenta)
                    drawContent()
                }
            )
        }
        for (component in listOf("Assistant", "private\nconversation", "Transcript", "")) {
            compose.runOnIdle { owner = owner.copy(revisionId = "selected:1", uiContext = UiOperationContext(component, itemCount = 4)) }
            val content = compose.onNodeWithTag("component_content")
            content.assertIsDisplayed().assertWidthIsEqualTo(120.dp).assertHeightIsEqualTo(64.dp)
            val pixels = content.captureToImage().toPixelMap()
            assertEquals(Color.Magenta, pixels[pixels.width / 2, pixels.height / 2])
        }
    }

    @Test fun gatewayTimingAndRejectedLabelsKeepContentMeasurableAndDrawable() {
        var label by mutableStateOf("GatewayChats")
        compose.setContent {
            Box(
                Modifier
                    .diagnosticFrameTiming(label)
                    .size(120.dp, 64.dp)
                    .testTag("gateway_content")
                    .drawWithContent {
                        drawRect(Color.Magenta)
                        drawContent()
                    }
            )
        }
        listOf("GatewayChats", "Gateway chats", "private\nconversation", "").forEach { screen ->
            compose.runOnIdle { label = screen }
            val content = compose.onNodeWithTag("gateway_content")
            content
                .assertIsDisplayed()
                .assertWidthIsEqualTo(120.dp)
                .assertHeightIsEqualTo(64.dp)
            val pixels = content.captureToImage().toPixelMap()
            assertEquals(Color.Magenta, pixels[pixels.width / 2, pixels.height / 2])
        }
    }
}
