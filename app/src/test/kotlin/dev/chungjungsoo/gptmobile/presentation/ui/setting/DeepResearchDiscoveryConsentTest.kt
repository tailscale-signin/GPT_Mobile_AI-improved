package dev.chungjungsoo.gptmobile.presentation.ui.setting

import android.app.Application
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.model.DeepResearchSettings
import dev.chungjungsoo.gptmobile.data.model.ResearchIntensity
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
@OptIn(ExperimentalTestApi::class)
class DeepResearchDiscoveryConsentTest {
    @get:Rule val compose = createComposeRule(effectContext = StandardTestDispatcher())

    @Test fun researchPresetsAndReportOptionsFollowTheEditorState() {
        val editable = mutableStateOf(false)
        val initial = DeepResearchSettings(enabled = true)
        var changed: DeepResearchSettings? = null
        compose.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    DeepResearchSettingsCard(initial, editable.value) { changed = it }
                }
            }
        }
        val presets = listOf("Fast", "Brief overview", "Scientific")
        presets.forEach { compose.onNodeWithText(it).assertIsNotEnabled() }
        compose.runOnIdle { editable.value = true }
        presets.forEach { compose.onNodeWithText(it).assertIsEnabled() }
        compose.onNodeWithText("Fast").performClick()
        compose.runOnIdle { assertEquals(ResearchIntensity.QUICK, changed?.intensity) }
    }

    @Test fun discoveryRemainsAvailableThroughExplicitConsentWithoutAPapersToggle() {
        var changed: DeepResearchSettings? = null
        val initial = DeepResearchSettings(enabled = true, academicSources = false)
        val scrollState = ScrollState(0)
        compose.setContent {
            MaterialTheme {
                Column(Modifier.height(400.dp).testTag("research_settings_scroll").verticalScroll(scrollState)) {
                    DeepResearchSettingsCard(initial, true) { changed = it }
                }
            }
        }
        compose.onNodeWithContentDescription("Papers and studies").assertDoesNotExist()
        // Measure the target in pixels and update the known scroll state once.
        // This bypasses nested semantics search and asynchronous scroll animations.
        val viewport = compose.onNodeWithTag("research_settings_scroll").fetchSemanticsNode().boundsInRoot
        val action = compose.onNodeWithText("Allow public study discovery").fetchSemanticsNode()
        val delta = action.positionInRoot.y + action.size.height / 2f - viewport.center.y
        compose.runOnIdle { scrollState.dispatchRawDelta(delta) }
        compose.waitForIdle()
        compose.onNodeWithText("Allow public study discovery").assertIsDisplayed().performClick()
        compose.onNodeWithText("Enable Papers and studies discovery?").assertIsDisplayed()
        assertNull(changed)
        compose.onNodeWithText("Decline").performClick()
        compose.waitForIdle()
        assertNull(changed)
        compose.onNodeWithText("Allow public study discovery").assertIsDisplayed().performClick()
        compose.onNodeWithText("Accept").performClick()
        assertEquals(initial.copy(academicSources = true), changed)
    }
}
