package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayEngineeringModelsTest {
    @Test fun backupRetainsSettingsJobIdentityAndReconnectCursorWithoutApproval() {
        val request = GatewayEngineeringRequest("owner/repo", "feature/job", "main", "Fix tests", "local", 12, 5, 1, "stable-request")
        val job = GatewayEngineeringJob("eng_" + "a".repeat(32), request.repository, request.branch, 1, "MERGE_READY", 15, 1000.0, 5000.0, "Review commit", 7, 10, pr = 7, headSha = "b".repeat(40), mergeReady = true)
        val binding = GatewayEngineeringBinding("profile", "http://gateway:8090/v1", request, job, syncedRevision = 15)
        val settings = AppFeatureSettings(githubEngineeringSettings = GatewayEngineeringSettings(12, 5), gatewayEngineeringJobs = listOf(binding))
        val encoded = Json.encodeToString(settings)
        val restored = Json.decodeFromString<AppFeatureSettings>(encoded)
        assertEquals(settings.githubEngineeringSettings, restored.githubEngineeringSettings)
        assertEquals(binding, restored.gatewayEngineeringJobs.single())
        assertFalse(encoded.contains("approve-and-merge"))
    }

    @Test fun progressUsesMilestonesAndDefaultBudgetIsThreeHours() {
        val job = GatewayEngineeringJob("id", "owner/repo", "feature", state = "CI", revision = 2, createdAt = 1.0, deadline = 999999.0, message = "CI", milestonesCompleted = 6, milestonesTotal = 10)
        assertEquals(.6f, job.progress)
        assertFalse(job.mergeReady)
        assertFalse(job.terminal)
        assertTrue(job.copy(state = "COMPLETED").terminal)
        assertEquals(3, GatewayEngineeringSettings().durationHours)
    }

    @Test fun derivedWorkingBranchMatchesOnlyThePinnedCanonicalRequestAndNeverTheBase() {
        val request = GatewayEngineeringRequest("owner/repo", "main", "main", "Implement the change", "model", chatId = 7, requestId = "request", taskScope = "implement", sourceBranch = "main", sourceSha = "a".repeat(40))
        val derived = "codex/gateway-74ae4eaeb8567f989cf6"
        assertEquals(derived, gatewayDerivedWorkingBranch(request))
        assertTrue(gatewayWorkingBranchMatches(request, derived, "main", "a".repeat(40), derived))
        assertFalse(gatewayWorkingBranchMatches(request, "main", "main", "a".repeat(40), "main"))
        assertFalse(gatewayWorkingBranchMatches(request, "codex/gateway-unrelated", "main", "a".repeat(40), "codex/gateway-unrelated"))
        assertFalse(gatewayWorkingBranchMatches(request, derived, "main", "b".repeat(40), derived))
        assertEquals(null, gatewayDerivedWorkingBranch(request.copy(sourceSha = null)))
        assertEquals(null, gatewayDerivedWorkingBranch(request.copy(taskScope = "read")))
        assertTrue(gatewayWorkingBranchMatches(request.copy(branch = "selected-work"), "selected-work", "main", "a".repeat(40), "selected-work"))
        assertFalse(gatewayWorkingBranchMatches(request.copy(branch = "selected-work"), derived, "main", "a".repeat(40), derived))
    }
}
