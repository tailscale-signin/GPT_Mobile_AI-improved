package dev.chungjungsoo.gptmobile.presentation.ui.chat

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.chungjungsoo.gptmobile.data.context.ReasoningCapabilityStatus
import dev.chungjungsoo.gptmobile.data.context.ReasoningMode
import dev.chungjungsoo.gptmobile.presentation.ui.setting.ReasoningCapabilityContent
import dev.chungjungsoo.gptmobile.presentation.ui.setting.ReasoningCapabilityUiState
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ProfileRecoveryPresentationTest {
    @get:Rule val compose = createComposeRule(effectContext = StandardTestDispatcher())

    @Test fun unsupportedOffExposesSeparateReadOnlyCheckAndExplicitAutoWithoutRetry() {
        var checks = 0
        var changes = 0
        var settings = 0
        compose.setContent {
            MaterialTheme {
                ProfileRecoveryActions(
                    ProfileRecoveryKind.REASONING, "Llama", ReasoningRecoveryUiState(requested = ReasoningMode.OFF, status = ReasoningCapabilityStatus.UNSUPPORTED, frozenRequest = true), false,
                    { settings++ }, { error("billing") }, { checks++ }, { changes++ }, { error("retry must not dispatch") }
                )
            }
        }
        compose.onNodeWithText("Off is unsupported by the current model and template.").assertIsDisplayed()
        compose.onNodeWithText("Retry checked configuration").assertDoesNotExist()
        compose.onNodeWithText("Recheck support for Llama").performClick()
        assertEquals(1, checks)
        assertEquals(0, changes)
        compose.onNodeWithText("Switch Llama to Auto").performClick()
        assertEquals(1, changes)
        compose.onNodeWithText("Change settings for Llama").performClick()
        assertEquals(1, settings)
    }

    @Test fun billingRepairTargetsItsNamedProfileWithoutRetryOrContributorRemoval() {
        var billing = 0
        compose.setContent { MaterialTheme { ProfileRecoveryActions(ProfileRecoveryKind.BILLING, "Claude account", null, true, {}, { billing++ }, { error("check") }, { error("auto") }, { error("retry") }) } }
        compose.onNodeWithText("Manage billing for Claude account").performClick()
        assertEquals(1, billing)
        compose.onNodeWithText("Retry checked configuration").assertDoesNotExist()
        compose.onNodeWithText("Restore this profile’s provider credits, then start a new request. Other replies remain saved.").assertIsDisplayed()
    }

    @Test fun checkingDisablesDuplicateQualificationAndModeChange() {
        compose.setContent { MaterialTheme { ProfileRecoveryActions(ProfileRecoveryKind.REASONING, "Llama", ReasoningRecoveryUiState(checking = true, requested = ReasoningMode.OFF), false, {}, {}, {}, {}, {}) } }
        compose.onNodeWithText("Recheck support for Llama").assertIsNotEnabled()
        compose.onNodeWithText("Switch Llama to Auto").assertIsNotEnabled()
        compose.onNodeWithText("Retry checked configuration").assertDoesNotExist()
    }

    @Test fun profileSettingsNeverChangeExplicitOffDuringAnUnverifiedCheck() {
        var auto = 0
        var recheck = 0
        compose.setContent { MaterialTheme { ReasoningCapabilityContent(ReasoningMode.OFF, ReasoningCapabilityUiState(), { recheck++ }, { auto++ }, true) } }
        assertEquals(0, auto)
        compose.onNodeWithText("Recheck reasoning support").performClick()
        assertEquals(1, recheck)
        assertEquals(0, auto)
        compose.onNodeWithText("Switch to Auto").performClick()
        assertEquals(1, auto)
    }
}
