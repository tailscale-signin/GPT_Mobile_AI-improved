package dev.chungjungsoo.gptmobile.presentation.ui.setting

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.model.ConversationDelegationSettings
import dev.chungjungsoo.gptmobile.data.model.ModelDelegationSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w360dp-h640dp")
class DelegationSettingsContentTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun detailedControlsStayHiddenUntilAdvancedIsExpanded() {
        compose.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    ModelDelegationSettingsContent(ModelDelegationSettings(), emptyList(), false, null, {}, {})
                }
            }
        }
        compose.onNodeWithText("Search Queries: 9").assertDoesNotExist()
        compose.onNodeWithText("Workers").performScrollTo().performClick()
        compose.onNodeWithTag("delegate_model_dropdown").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Overview").performScrollTo().performClick()
        compose.onNodeWithTag("delegation_advanced").performScrollTo().performClick()
        compose.onNodeWithText("Search Queries: 9").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Pages To Read: 11").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun conversationSwitchRevealsDropdownAndSelectingOnlineHelperCreatesOverride() {
        var selected: ConversationDelegationSettings? = null
        val helper = PlatformV2(uid = "online", name = "Online helper", model = "model", compatibleType = ClientType.OPENROUTER, apiUrl = "https://openrouter.ai/api/v1")
        val defaults = ModelDelegationSettings(enabled = true).withRemoteWorkersAllowed(true)
        compose.setContent {
            MaterialTheme {
                var settings by remember { mutableStateOf(defaults.copy(enabled = false)) }
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    ConversationDelegationCard(settings, listOf(helper), selected == null) { value ->
                        selected = value
                        settings = value?.applyTo(defaults) ?: defaults
                    }
                }
            }
        }
        compose.onNodeWithTag("delegate_model_dropdown").assertDoesNotExist()
        compose.onNodeWithTag("conversation_delegation_toggle").performClick()
        compose.onNodeWithTag("delegate_model_dropdown").performScrollTo().assertIsDisplayed()
        // Drive dialog/text-field animations explicitly instead of waiting for Robolectric idling.
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("delegate_model_dropdown").performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("Online helper").performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.mainClock.autoAdvance = true
        assertEquals("online", selected?.targetProfileUid)
        assertTrue(selected?.enabled == true)
        assertTrue(selected?.allowRemoteWorker == true)
        compose.onNodeWithText("Use settings defaults").performScrollTo().performClick()
        assertEquals(null, selected)
    }
}
