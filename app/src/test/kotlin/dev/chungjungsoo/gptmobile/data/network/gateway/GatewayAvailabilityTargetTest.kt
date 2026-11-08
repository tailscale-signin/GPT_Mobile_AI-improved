package dev.chungjungsoo.gptmobile.data.network.gateway

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.github.GitHubChatProfileOverride
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.model.ClientType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayAvailabilityTargetTest {
    private val selected = PlatformV2(uid = "inference", name = "Coding model", compatibleType = ClientType.LLAMA, apiUrl = "http://10.0.0.2:8080/v1", token = "inference-token", model = "exact-model")
    private val gateway = selected.copy(uid = "gateway", name = "Gateway", apiUrl = "http://10.0.0.3:8090/v1", token = "gateway-token", model = "another-model")
    private val features = AppFeatureSettings(githubProfileOverrides = mapOf(selected.uid to GitHubChatProfileOverride(engineeringGatewayProfileUid = gateway.uid)))

    @Test fun githubReadinessUsesOnlyExplicitGatewayCredentialsAndExactInferenceModel() {
        val target = gatewayAvailabilityTargets(listOf(selected, gateway), features, true).single()
        assertEquals(selected.uid, target.profileUid)
        assertEquals(gateway.uid, target.gatewayProfileUid)
        assertEquals(gateway.apiUrl, target.config?.apiUrl)
        assertEquals(gateway.token, target.config?.token)
        assertEquals("exact-model", target.model)
        assertTrue(target.engineeringBinding)
    }

    @Test fun absentDisabledOrAmbiguousBindingNeverSubstitutesAnotherGateway() {
        assertTrue(gatewayAvailabilityTargets(listOf(selected, gateway), AppFeatureSettings(), true).isEmpty())
        for (profiles in listOf(listOf(selected), listOf(selected, gateway.copy(enabled = false)), listOf(selected, gateway, gateway))) {
            val target = gatewayAvailabilityTargets(profiles, features, true).single()
            assertNull(target.config)
            assertEquals(gateway.uid, target.gatewayProfileUid)
        }
    }

    @Test fun ordinaryCandidateKeepsItsOwnRouteAndDoesNotTreatCloudOrOnDeviceProfilesAsGateway() {
        val cloud = selected.copy(uid = "cloud", compatibleType = ClientType.OPENAI, name = "Gateway", apiUrl = "http://10.0.0.4:8090/v1")
        val device = selected.copy(uid = "device", compatibleType = ClientType.LITERT_LM)
        val target = gatewayAvailabilityTargets(listOf(selected, cloud, device), features, false).single()
        assertEquals(selected.apiUrl, target.config?.apiUrl)
        assertEquals(selected.token, target.config?.token)
    }

    @Test fun invalidModelsOrCredentialBearingUrlsNeverProbe() {
        for (profile in listOf(selected.copy(model = ""), selected.copy(model = "x".repeat(513)), selected.copy(apiUrl = "http://user:secret@10.0.0.2/v1"), selected.copy(apiUrl = "http://10.0.0.2/v1?token=secret"))) {
            assertNull(gatewayAvailabilityTargets(listOf(profile), features, false).single().config)
        }
    }

    @Test fun menuIncludesBothSavedInferenceRouteAndBoundGatewayWithoutIdentityCollisions() {
        val targets = gatewayMenuTargets(listOf(selected, gateway), features)
        assertEquals(setOf(selected.apiUrl, gateway.apiUrl), targets.map { it.config?.apiUrl }.toSet())
        assertEquals(3, targets.size)
        assertEquals(targets.size, targets.map { it.profileUid }.distinct().size)
        assertEquals(setOf("exact-model", "another-model"), targets.filter { it.gatewayProfileUid == gateway.uid }.map { it.model }.toSet())
        val missingBinding = features.copy(githubProfileOverrides = mapOf(selected.uid to GitHubChatProfileOverride(engineeringGatewayProfileUid = "missing")))
        assertEquals(selected.apiUrl, gatewayMenuTargets(listOf(selected), missingBinding).single().config?.apiUrl)
    }
}
