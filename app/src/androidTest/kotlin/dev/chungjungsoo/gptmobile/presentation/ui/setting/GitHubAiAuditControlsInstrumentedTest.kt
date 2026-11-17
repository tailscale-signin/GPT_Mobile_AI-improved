package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class GitHubAiAuditControlsInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun explicitProfileCoverageAndPolicySurviveReorderingAndDispatchTogether() {
        var profiles by mutableStateOf(listOf(PlatformV2(uid = "a", name = "Same name", model = "model-a"), PlatformV2(uid = "b", name = "Same name", model = "model-b")))
        val requests = mutableListOf<Triple<String, Boolean, Boolean>>()
        compose.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    GitHubAiProfileAuditControls(profiles, ready = true, busy = false) { uid, full, strict -> requests += Triple(uid, full, strict) }
                }
            }
        }
        compose.onNodeWithTag("github_ai_audit_start").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("github_ai_audit_profile_b").performScrollTo().performClick()
        compose.runOnIdle { profiles = profiles.reversed() }
        compose.onNodeWithTag("github_ai_audit_profile_b").assertIsSelected()
        compose.onNodeWithTag("github_ai_audit_full").performScrollTo().performClick()
        compose.onNodeWithTag("github_ai_audit_advanced").performScrollTo().performClick()
        compose.onNodeWithTag("github_ai_audit_strict").performScrollTo().performClick()
        compose.onNodeWithTag("github_ai_audit_start").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf(Triple("b", true, true)), requests) }
    }

    @Test fun soleEligibleProfileCanStartWithoutSetupButMultipleProfilesRequireAChoice() {
        var profiles by mutableStateOf(listOf(PlatformV2(uid = "only", name = "Only profile", model = "model")))
        val requests = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    GitHubAiProfileAuditControls(profiles, ready = true, busy = false) { uid, _, _ -> requests += uid }
                }
            }
        }
        compose.onNodeWithTag("github_ai_audit_profile_only").assertIsSelected()
        compose.onNodeWithTag("github_ai_audit_start").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(listOf("only"), requests)
            profiles = profiles + PlatformV2(uid = "another", name = "Another profile", model = "model-2")
        }
        compose.onNodeWithTag("github_ai_audit_start").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("github_ai_audit_profile_another").performScrollTo().performClick()
        compose.onNodeWithTag("github_ai_audit_start").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf("only", "another"), requests) }
    }

    @Test fun unavailableSelectionAndExecutionOwnershipPreventNewRequests() {
        var profiles by mutableStateOf(listOf(PlatformV2(uid = "a", name = "Profile", model = "model")))
        var busy by mutableStateOf(false)
        var ready by mutableStateOf(true)
        var dispatched = 0
        compose.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    GitHubAiProfileAuditControls(profiles, ready, busy) { _, _, _ -> dispatched++ }
                }
            }
        }
        compose.onNodeWithTag("github_ai_audit_profile_a").performScrollTo().performClick()
        compose.runOnIdle { busy = true }
        compose.onNodeWithTag("github_ai_audit_profile_a").assertIsNotEnabled()
        compose.onNodeWithTag("github_ai_audit_start").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle {
            busy = false
            ready = false
        }
        compose.onNodeWithTag("github_ai_audit_start").assertIsNotEnabled()
        compose.runOnIdle {
            ready = true
            profiles = profiles.map { it.copy(enabled = false) }
        }
        compose.onNodeWithTag("github_ai_audit_start").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, dispatched) }
    }
}
