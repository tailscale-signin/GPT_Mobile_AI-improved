package dev.chungjungsoo.gptmobile.data.network.gateway

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.github.GitHubChatProfileOverride
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.model.ClientType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayAvailabilityTargetsTest {
    private val inference = PlatformV2(uid = "inference", name = "Inference", compatibleType = ClientType.LLAMA, apiUrl = "http://127.0.0.1:8090/v1", model = "exact-model", token = "inference-token")
    private val engineering = inference.copy(uid = "engineering", name = "Engineering", apiUrl = "http://192.168.1.2:9000/v1", model = "another-model", token = "engineering-token")
    private val features = AppFeatureSettings(githubProfileOverrides = mapOf(inference.uid to GitHubChatProfileOverride(engineeringGatewayProfileUid = engineering.uid)))

    @Test fun ordinaryNewChatChecksSavedSelfHostedConnectionsWithoutHostedProviderSubstitution() {
        val cloud = inference.copy(uid = "cloud", compatibleType = ClientType.OPENAI, apiUrl = "https://api.example.org/v1")
        val arbitraryPrivate = inference.copy(uid = "custom", compatibleType = ClientType.CUSTOM)
        val publicLlama = inference.copy(uid = "public", apiUrl = "https://public.example.org/v1")
        val targets = gatewayAvailabilityTargets(listOf(inference, cloud, arbitraryPrivate, publicLlama), features, forGitHub = false)
        assertEquals(listOf(inference.uid, publicLlama.uid), targets.map { it.profileUid })
        val local = targets.single { it.profileUid == inference.uid }
        assertEquals(inference.uid, local.gatewayProfileUid)
        assertEquals(inference.apiUrl, local.config?.apiUrl)
        assertEquals(inference.token, local.config?.token)
    }

    @Test fun githubUsesItsSeparateConnectionAndNeverSubstitutesItsModelOrFallsBack() {
        val target = gatewayAvailabilityTargets(listOf(inference, engineering), features, forGitHub = true).single()
        assertEquals(inference.uid, target.profileUid)
        assertEquals(engineering.uid, target.gatewayProfileUid)
        assertEquals(engineering.apiUrl, target.config?.apiUrl)
        assertEquals(engineering.token, target.config?.token)
        assertEquals(inference.model, target.model)
        assertTrue(gatewayAvailabilityTargets(listOf(inference, engineering), AppFeatureSettings(), forGitHub = true).isEmpty())
    }

    @Test fun deletedDisabledOrMalformedGatewayConfigurationNeedsAttentionWithoutSubstitution() {
        for (profiles in listOf(listOf(inference), listOf(inference, engineering.copy(enabled = false)), listOf(inference, engineering.copy(apiUrl = "http://user:secret@192.168.1.2/v1")))) {
            val target = gatewayAvailabilityTargets(profiles, features, forGitHub = true).single()
            assertNull(target.config)
            assertEquals(engineering.uid, target.gatewayProfileUid)
            assertEquals(inference.uid, target.profileUid)
        }
    }

    @Test fun changingAModelOrCredentialChangesProbeInputsWithoutChangingSelection() {
        val first = gatewayAvailabilityTargets(listOf(inference), features, forGitHub = false).single()
        val model = gatewayAvailabilityTargets(listOf(inference.copy(model = "new-model")), features, forGitHub = false).single()
        val token = gatewayAvailabilityTargets(listOf(inference.copy(token = "new-token")), features, forGitHub = false).single()
        assertNotEquals(first.model, model.model)
        assertNotEquals(first.config?.token, token.config?.token)
        assertEquals(first.profileUid, model.profileUid)
        assertEquals(first.profileUid, token.profileUid)
    }

    @Test fun changingTheExplicitBindingOrConfigurationRevisionCannotReuseReadiness() {
        val first = gatewayAvailabilityTargets(listOf(inference, engineering), features, forGitHub = true).single()
        val replacement = engineering.copy(uid = "replacement")
        val changed = AppFeatureSettings(githubProfileOverrides = mapOf(inference.uid to GitHubChatProfileOverride(engineeringGatewayProfileUid = replacement.uid)))
        val next = gatewayAvailabilityTargets(listOf(inference, replacement), changed, forGitHub = true).single()
        assertNotEquals(first.config, next.config)
        val revision = first.copy(config = first.config?.copy(profileRevision = "new-revision"))
        assertNotEquals(first.config, revision.config)
        assertEquals(first.profileUid, next.profileUid)
    }

    @Test fun aMalformedSelectedModelKeepsTheSavedProfileVisibleWithRepairGuidance() {
        for (model in listOf("", " selected ", "bad\nmodel", "x".repeat(513))) {
            val target = gatewayAvailabilityTargets(listOf(inference.copy(model = model)), features, forGitHub = false).single()
            assertEquals(inference.uid, target.profileUid)
            assertNull(target.config)
        }
    }
}
