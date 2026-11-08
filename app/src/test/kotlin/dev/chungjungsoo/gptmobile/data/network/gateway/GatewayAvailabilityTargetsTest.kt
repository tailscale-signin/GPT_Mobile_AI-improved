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

    @Test fun ordinaryNewChatChecksOnlyTheExplicitPrivateInferenceConnection() {
        val cloud = inference.copy(uid = "cloud", compatibleType = ClientType.OPENAI, apiUrl = "https://api.example.org/v1")
        val arbitraryPrivate = inference.copy(uid = "custom", compatibleType = ClientType.CUSTOM)
        val publicLlama = inference.copy(uid = "public", apiUrl = "https://public.example.org/v1")
        val targets = gatewayAvailabilityTargets(listOf(inference, cloud, arbitraryPrivate, publicLlama), features, engineering = false)
        assertEquals(listOf(inference.uid), targets.map { it.profileUid })
        assertEquals(inference.uid, targets.single().gatewayProfileUid)
        assertEquals(inference.apiUrl, targets.single().config?.apiUrl)
        assertEquals(inference.token, targets.single().config?.token)
    }

    @Test fun githubUsesItsSeparateConnectionAndNeverSubstitutesItsModelOrFallsBack() {
        val target = gatewayAvailabilityTargets(listOf(inference, engineering), features, engineering = true).single()
        assertEquals(inference.uid, target.profileUid)
        assertEquals(engineering.uid, target.gatewayProfileUid)
        assertEquals(engineering.apiUrl, target.config?.apiUrl)
        assertEquals(engineering.token, target.config?.token)
        assertEquals(inference.model, target.model)
        assertTrue(gatewayAvailabilityTargets(listOf(inference, engineering), AppFeatureSettings(), engineering = true).isEmpty())
    }

    @Test fun deletedDisabledOrMalformedGatewayConfigurationNeedsAttentionWithoutSubstitution() {
        for (profiles in listOf(listOf(inference), listOf(inference, engineering.copy(enabled = false)), listOf(inference, engineering.copy(apiUrl = "http://user:secret@192.168.1.2/v1")))) {
            val target = gatewayAvailabilityTargets(profiles, features, engineering = true).single()
            assertNull(target.config)
            assertTrue(!target.problem.isNullOrBlank())
            assertEquals(inference.uid, target.profileUid)
        }
    }

    @Test fun changingAModelOrCredentialChangesObservationIdentityWithoutChangingSelection() {
        val first = gatewayAvailabilityTargets(listOf(inference), features, engineering = false).single()
        val model = gatewayAvailabilityTargets(listOf(inference.copy(model = "new-model")), features, engineering = false).single()
        val token = gatewayAvailabilityTargets(listOf(inference.copy(token = "new-token")), features, engineering = false).single()
        assertNotEquals(first.identity, model.identity)
        assertNotEquals(first.identity, token.identity)
        assertEquals(first.profileUid, model.profileUid)
        assertEquals(first.profileUid, token.profileUid)
    }
}
