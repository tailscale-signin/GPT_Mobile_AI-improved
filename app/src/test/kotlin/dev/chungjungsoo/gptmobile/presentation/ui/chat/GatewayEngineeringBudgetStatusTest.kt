package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import dev.chungjungsoo.gptmobile.data.github.GatewayEngineeringBinding
import dev.chungjungsoo.gptmobile.data.github.GatewayEngineeringBudget
import dev.chungjungsoo.gptmobile.data.github.GatewayEngineeringBudgetSelection
import dev.chungjungsoo.gptmobile.data.github.GatewayEngineeringJob
import dev.chungjungsoo.gptmobile.data.github.GatewayEngineeringRequest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class GatewayEngineeringBudgetStatusTest {
    @get:Rule val compose = createComposeRule()
    private val request = GatewayEngineeringRequest("owner/repo", "work", "main", "Inspect", "selected", chatId = 7, requestId = "saved")

    @Test fun negotiatedLimitIsVisibleBeforeAdmissionAndBecomesAcceptedOnlyWithServerEvidence() {
        val requested = GatewayEngineeringBudget(512, 2_097_152)
        val effective = requested.copy(modelCalls = 256)
        var binding by mutableStateOf(GatewayEngineeringBinding("profile", "http://gateway", request.copy(durationHours = 12, executionBudget = effective), budgetSelection = GatewayEngineeringBudgetSelection(24, 12, requested, effective)))
        compose.setContent { MaterialTheme { GatewayEngineeringBudgetStatus(binding) } }
        compose.onNodeWithTag("gateway_engineering_budget").assertIsDisplayed()
            .assertTextContains("Requested gateway allowance", substring = true)
            .assertTextContains("12 hours", substring = true)
            .assertTextContains("256 model calls", substring = true)
            .assertTextContains("lower allowance", substring = true)
        compose.runOnIdle {
            binding = binding.copy(job = GatewayEngineeringJob("job", "owner/repo", "work", state = "PLAN", revision = 0, createdAt = 1.0, deadline = 43201.0, message = "Queued", durationHours = 12, executionBudget = effective))
        }
        compose.onNodeWithTag("gateway_engineering_budget").assertIsDisplayed()
            .assertTextContains("Accepted gateway allowance", substring = true)
            .assertTextContains("12 hours", substring = true)
            .assertTextContains("256 model calls", substring = true)
    }

    @Test fun legacySnapshotWithoutBudgetEvidenceDoesNotInventAnUltraAllowance() {
        compose.setContent { MaterialTheme { GatewayEngineeringBudgetStatus(GatewayEngineeringBinding("profile", "http://gateway", request)) } }
        compose.onNodeWithTag("gateway_engineering_budget").assertDoesNotExist()
    }
}
