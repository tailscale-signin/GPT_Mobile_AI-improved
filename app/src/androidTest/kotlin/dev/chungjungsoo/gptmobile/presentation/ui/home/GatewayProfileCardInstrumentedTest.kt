package dev.chungjungsoo.gptmobile.presentation.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayAvailability
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayAvailabilityStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class GatewayProfileCardInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun onlineStatusRemainsReadableAndDoesNotChangeSelectionAtLargeText() = verify(false, false)

    @Test fun onlineStatusRemainsReadableAndDoesNotChangeSelectionInDarkRtl() = verify(true, true)

    private fun verify(dark: Boolean, rtl: Boolean) {
        val profile = PlatformV2(uid = "online-profile", name = "Private gateway with a long profile name", compatibleType = ClientType.LLAMA, model = "exact-model")
        var selected by mutableStateOf(false)
        var availability by mutableStateOf(GatewayAvailability(status = GatewayAvailabilityStatus.ONLINE))
        var toggles = 0
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(
                LocalDensity provides Density(density, fontScale = 2f),
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr
            ) {
                MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                    Box(Modifier.width(320.dp)) {
                        SelectPlatformProfileCard(
                            profile = profile,
                            selected = selected,
                            insight = null,
                            onToggle = { uid ->
                                assertEquals(profile.uid, uid)
                                selected = !selected
                                toggles++
                            },
                            onFavorite = {},
                            onInsights = {},
                            gatewayAvailability = availability,
                            animateGateway = true
                        )
                    }
                }
            }
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.onNodeWithTag("profile_select_${profile.uid}").assertIsOff()
        compose.onNodeWithText(context.getString(R.string.gateway_availability_online)).assertIsDisplayed()
        val row = compose.onNodeWithTag("profile_select_${profile.uid}").fetchSemanticsNode().boundsInRoot
        val dot = compose.onNodeWithTag("gateway_online_dot", useUnmergedTree = true).fetchSemanticsNode()
        assertTrue(dot.boundsInRoot.left >= row.left && dot.boundsInRoot.right <= row.right)
        assertTrue(dot.boundsInRoot.top >= row.top && dot.boundsInRoot.bottom <= row.bottom)
        assertFalse(dot.config.contains(SemanticsProperties.ContentDescription))
        compose.onNodeWithTag("profile_select_${profile.uid}").performClick().assertIsOn()
        compose.runOnIdle { availability = availability.copy(status = GatewayAvailabilityStatus.CHECKING) }
        compose.onNodeWithText(context.getString(R.string.gateway_availability_checking)).assertIsDisplayed()
        compose.onNodeWithTag("profile_select_${profile.uid}").assertIsOn()
        compose.runOnIdle { assertEquals(1, toggles) }
    }
}
