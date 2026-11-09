package dev.chungjungsoo.gptmobile.presentation.common

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class DiagnosticFrameTimingTest {
    @get:Rule val compose = createComposeRule()

    @Test fun invalidLabelsDoNotThrowOrInstallPrivateTelemetry() {
        val original = Modifier.testTag("content")
        listOf("Gateway chats", "", "private\nconversation", "x".repeat(41), "gateway/model?token=private").forEach { label ->
            assertSame(original, original.diagnosticFrameTiming(label))
        }
    }

    @Test fun gatewayTimingAndRejectedLabelsKeepContentMeasurableAndDrawable() {
        var label by mutableStateOf("GatewayChats")
        val draws = AtomicInteger()
        compose.setContent {
            Box(
                Modifier
                    .diagnosticFrameTiming(label)
                    .size(120.dp, 64.dp)
                    .testTag("gateway_content")
                    .drawWithContent {
                        draws.incrementAndGet()
                        drawContent()
                    }
            )
        }
        listOf("GatewayChats", "Gateway chats", "private\nconversation", "").forEach { screen ->
            compose.runOnIdle { label = screen }
            compose.onNodeWithTag("gateway_content")
                .assertIsDisplayed()
                .assertWidthIsEqualTo(120.dp)
                .assertHeightIsEqualTo(64.dp)
        }
        compose.runOnIdle { assertTrue(draws.get() > 0) }
    }
}
