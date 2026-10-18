package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import dev.chungjungsoo.gptmobile.data.database.entity.ConversationMode
import dev.chungjungsoo.gptmobile.data.model.CombinedChatSettingsDraft
import dev.chungjungsoo.gptmobile.data.model.ConversationCombinedSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

class CombinedSettingsInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun combinedTabStagesBothAxesAndRemovesDelegationCard() {
        var saved: CombinedChatSettingsDraft? = null
        compose.setContent {
            MaterialTheme {
                ChatModelDialog(
                    platformOrder = listOf("a"), activePlatformUids = setOf("a"), initialModels = mapOf("a" to "model"), platformNames = mapOf("a" to "AI"),
                    conversationMode = ConversationMode.COMBINED, conversationIdentity = "chat-one", chatId = 4,
                    onConfirmCombined = { saved = it }, onDismissRequest = {}, onConfirmRequest = { _, _ -> error("Combined must use its atomic save") }
                )
            }
        }
        compose.onNodeWithText("Combined").assertIsDisplayed()
        compose.onNodeWithTag("conversation_delegation_toggle").assertDoesNotExist()
        compose.onNodeWithTag("combined_merge_style").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(100f) }
        compose.onNodeWithTag("combined_response_effort").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }
        assertEquals(null, saved)
        compose.onNodeWithTag("combined_settings_save").performClick()
        assertEquals(100, saved?.settings?.collectionBias)
        assertEquals(0, saved?.settings?.effort)
    }

    @Test fun cancelDiscardsStagedMembershipAndPreventsEmptySave() {
        var open by mutableStateOf(true)
        var immediateMembershipCalled = false
        compose.setContent {
            MaterialTheme {
                if (open) {
                    ChatModelDialog(
                        platformOrder = listOf("a"), activePlatformUids = setOf("a"), initialModels = mapOf("a" to "model"), platformNames = mapOf("a" to "AI"),
                        conversationMode = ConversationMode.COMBINED, conversationIdentity = "chat-one", chatId = 4,
                        onPlatformActiveChanged = { _, _ -> immediateMembershipCalled = true }, onDismissRequest = { open = false }, onConfirmRequest = { _, _ -> }
                    )
                }
            }
        }
        compose.onNodeWithTag("combined_member_a").performScrollTo().performClick()
        compose.onNodeWithTag("combined_settings_save").assertIsNotEnabled()
        assertFalse(immediateMembershipCalled)
        compose.onNodeWithTag("combined_settings_cancel").performClick()
        compose.runOnIdle { open = true }
        compose.onNodeWithTag("combined_settings_save").assertIsEnabled()
        assertFalse(immediateMembershipCalled)
    }

    @Test fun saveableDraftRestoresTheTwoAxesTogether() {
        val restoration = StateRestorationTester(compose)
        var draft: CombinedChatSettingsDraft? = null
        restoration.setContent {
            MaterialTheme {
                var current by androidx.compose.runtime.saveable.rememberSaveable(stateSaver = CombinedChatSettingsDraftSaver) {
                    mutableStateOf(CombinedChatSettingsDraft("stable", 4, 3, participantUids = listOf("a"), models = mapOf("a" to "model"), creativity = 0.7f))
                }
                draft = current
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    CombinedSettingsControls(current.settings, { current = current.copy(settings = it) })
                }
            }
        }
        compose.onNodeWithTag("combined_merge_style").performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }
        compose.onNodeWithTag("combined_response_effort").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(100f) }
        restoration.emulateSavedInstanceStateRestore()
        assertEquals(0, draft?.settings?.collectionBias)
        assertEquals(100, draft?.settings?.effort)
        assertEquals(3L, draft?.baseRevision)
        assertEquals(0.7f, draft?.creativity)
    }

    @Test fun unsupportedFutureControlsStayVisibleButCannotBeEdited() {
        compose.setContent { MaterialTheme { CombinedSettingsControls(ConversationCombinedSettings(schemaVersion = 8), { error("Future values are read-only") }) } }
        compose.onNodeWithTag("combined_merge_style").assertIsNotEnabled()
        compose.onNodeWithTag("combined_response_effort").assertIsNotEnabled()
    }
}
