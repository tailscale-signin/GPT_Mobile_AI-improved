package dev.chungjungsoo.gptmobile.presentation.ui.setting

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
import androidx.compose.ui.test.junit4.v2.createComposeRule
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

/** Exercises the helper picker's separate Android dialog window on a device/emulator. */
class ConversationDelegationInstrumentedTest {
    @get:Rule val compose = createComposeRule()

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
        compose.onNodeWithTag("delegate_model_dropdown").performClick()
        compose.onNodeWithText("Online helper").performClick()
        assertEquals("online", selected?.targetProfileUid)
        assertTrue(selected?.enabled == true)
        assertTrue(selected?.allowRemoteWorker == true)
        compose.onNodeWithText("Use settings defaults").performScrollTo().performClick()
        assertEquals(null, selected)
    }
}
