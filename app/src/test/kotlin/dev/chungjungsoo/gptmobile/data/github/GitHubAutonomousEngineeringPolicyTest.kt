package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ClientType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubAutonomousEngineeringPolicyTest {
    private val profile = PlatformV2(uid = "local", name = "Local", compatibleType = ClientType.LLAMA, apiUrl = "http://192.168.1.2:8090/v1")
    private val workspace = GitHubConversationContext("connection", "owner", "repo", "main", "a".repeat(40), "local")

    @Test fun localLlamaAndOllamaAreEnabledWithoutAnyOptIn() {
        for (type in listOf(ClientType.LLAMA, ClientType.OLLAMA)) {
            for (endpoint in listOf("http://127.0.0.1:8080/v1", "http://192.168.1.2:11434", "http://100.99.2.3:8090/v1", "http://[fd00::1]:8090/v1")) {
                val selected = profile.copy(compatibleType = type, apiUrl = endpoint)
                assertTrue(GitHubAutonomousEngineeringPolicy.enabled(selected))
                assertTrue(GitHubAutonomousEngineeringPolicy.supportsSavedJobs(selected))
                assertTrue(GitHubAutonomousEngineeringPolicy.instruction(selected, workspace).contains("Autonomous engineering is enabled"))
            }
        }
    }

    @Test fun remoteProvidersAndPublicLlamaOrOllamaEndpointsCannotEnableAutonomy() {
        for (type in ClientType.entries - setOf(ClientType.LLAMA, ClientType.OLLAMA, ClientType.LITERT_LM)) {
            val selected = profile.copy(compatibleType = type)
            assertFalse(GitHubAutonomousEngineeringPolicy.enabled(selected))
            assertFalse(GitHubAutonomousEngineeringPolicy.supportsSavedJobs(selected))
            assertTrue(GitHubAutonomousEngineeringPolicy.instruction(selected, workspace).isEmpty())
        }
        for (type in listOf(ClientType.LLAMA, ClientType.OLLAMA)) {
            val selected = profile.copy(compatibleType = type, apiUrl = "https://inference.example/v1")
            assertFalse(GitHubAutonomousEngineeringPolicy.enabled(selected))
            assertFalse(GitHubAutonomousEngineeringPolicy.supportsSavedJobs(selected))
        }
    }

    @Test fun onDeviceAutonomyDoesNotPretendToBeAHostJobEndpoint() {
        val device = profile.copy(compatibleType = ClientType.LITERT_LM, apiUrl = "")
        assertTrue(GitHubAutonomousEngineeringPolicy.enabled(device))
        assertFalse(GitHubAutonomousEngineeringPolicy.supportsSavedJobs(device))
    }

    @Test fun disabledChangedOrUnselectedProfilesCannotInheritLocalAutonomy() {
        assertFalse(GitHubAutonomousEngineeringPolicy.enabled(null))
        assertFalse(GitHubAutonomousEngineeringPolicy.enabled(profile.copy(enabled = false)))
        assertTrue(GitHubAutonomousEngineeringPolicy.instruction(profile, null).isEmpty())
        assertTrue(GitHubAutonomousEngineeringPolicy.instruction(profile.copy(uid = "other"), workspace).isEmpty())
        assertFalse(GitHubAutonomousEngineeringPolicy.supportsSavedJobs(profile.copy(compatibleType = ClientType.OPENROUTER)))
        assertTrue(GitHubAutonomousEngineeringPolicy.instruction(profile, workspace).contains("this mode never grants it"))
    }
}
