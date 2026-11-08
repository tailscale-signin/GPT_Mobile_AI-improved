package dev.chungjungsoo.gptmobile.presentation.ui.home

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayAvailability
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayAvailabilityStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GatewayProfileAvailabilityTest {
    @get:Rule val compose = createComposeRule()
    private val profile = PlatformV2(uid = "saved", name = "A long saved gateway profile name", compatibleType = ClientType.LLAMA, model = "selected-model", apiUrl = "http://127.0.0.1/v1")

    @Test fun largeTextWrapsTheFullNameAndKeepsTheOnlineBadgeVisible() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale = 2f)) {
                MaterialTheme {
                    Box(Modifier.width(280.dp)) {
                        SelectPlatformProfileCard(profile, false, null, {}, {}, {}, GatewayAvailability("route", GatewayAvailabilityStatus.ONLINE, "Selected model is ready", 0))
                    }
                }
            }
        }
        compose.onNodeWithText(profile.name, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Online", useUnmergedTree = true).assertIsDisplayed()
        val dot = compose.onNodeWithTag("gateway_online_dot_saved", useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode()
        val online = compose.onNodeWithText("Online", useUnmergedTree = true).fetchSemanticsNode()
        assertTrue(dot.boundsInRoot.right <= online.boundsInRoot.left)
        assertTrue(dot.boundsInRoot.center.y in online.boundsInRoot.top..online.boundsInRoot.bottom)
        assertFalse(dot.config.contains(SemanticsProperties.ContentDescription))
        assertFalse(dot.config.contains(SemanticsProperties.Text))
        val layout = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(profile.name, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layout) }
        assertTrue(layout.single().lineCount > 1)
        assertFalse(layout.single().hasVisualOverflow)
    }

    @Test fun offlineObservationsNeverDisableOrSilentlyChangeTheSavedSelection() {
        var selected by mutableStateOf(false)
        val toggled = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                SelectPlatformProfileCard(profile, selected, null, {
                    toggled += it
                    selected = !selected
                }, {}, {}, GatewayAvailability("route", GatewayAvailabilityStatus.OFFLINE, "Gateway is unreachable.", 0))
            }
        }
        compose.onNodeWithText("Offline", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("profile_select_saved").performClick().assertIsOn()
        compose.runOnIdle {
            assertTrue(selected)
            assertEquals(listOf(profile.uid), toggled)
        }
    }

    @Test fun nameColorRetainsNormalTextContrastAtTheDimmestAnimationPoint() {
        for ((background, fallback) in listOf(Color.White to Color.Black, Color.Black to Color.White)) {
            val color = gatewayProfileNameColor(Color(0xFF999999), background, fallback)
            val foreground = color.copy(alpha = .86f).compositeOver(background).luminance()
            val surface = background.luminance()
            assertTrue((maxOf(foreground, surface) + .05f) / (minOf(foreground, surface) + .05f) >= 4.5f)
        }
    }
}
