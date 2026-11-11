package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayFullBatchContractTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun legacyGatewayHasNoFullBatchPermission() {
        val old = json.decodeFromString<GatewayEngineeringCapabilities>("""{"service":"engineering-gateway","protocol_version":1}""")
        assertFalse(old.supportsFullBatch)
        assertEquals("standard", GitHubChatProfileOverride().executionBatchMode)
    }

    @Test fun fullModeRequiresAdvertisedCapability() {
        val result = json.decodeFromString<GatewayEngineeringCapabilities>(
            """{"service":"engineering-gateway","protocol_version":1,"execution_batch":{"protocol_version":1,"modes":["standard","smart","full"],"full_enabled":true}}"""
        )
        assertTrue(result.supportsFullBatch)
        val disabled = result.copy(executionBatch = result.executionBatch!!.copy(modes = listOf("standard", "smart")))
        assertFalse(disabled.supportsFullBatch)
        val inconsistent = result.copy(executionBatch = result.executionBatch.copy(fullEnabled = false))
        assertFalse(inconsistent.supportsFullBatch)
    }

    @Test fun exactModeRoundTripsWithRequestAndSavedJob() {
        val request = GatewayEngineeringRequest("a/b", "feature", "main", "Investigate", "local",
            chatId = 1, requestId = "fixed", executionMode = "full", batchPolicyVersion = 1)
        val frozen = json.decodeFromString<GatewayEngineeringRequest>(json.encodeToString(request))
        assertEquals("full", frozen.executionMode)
        assertEquals(1, frozen.batchPolicyVersion)
        val progress = json.decodeFromString<GatewayIntegrationBatchProgress>(
            """{"schema_version":1,"mode":"full","revision":4,"completed":3,"total":4,"current_phase":"CI","current_state":"waiting_rate_limit"}"""
        )
        assertEquals("CI", progress.currentPhase)
        assertEquals(3, progress.completed)
    }
}
