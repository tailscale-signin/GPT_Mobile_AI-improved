package dev.chungjungsoo.gptmobile.data.privacy

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.model.FreeAiProvider
import dev.chungjungsoo.gptmobile.data.openrouter.account.OpenRouterIdentity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacyProfileEligibilityTest {
    private fun profile(
        type: ClientType,
        url: String = "",
        model: String = "test-model"
    ) = PlatformV2(
        name = "Test profile",
        compatibleType = type,
        apiUrl = url,
        model = model
    )

    @Test
    fun masterOff_keepsExistingProfilesSelectable() {
        val remote = profile(ClientType.GOOGLE, "https://generativelanguage.googleapis.com")
        assertTrue(PrivacyProfileEligibility.evaluate(remote, false).eligible)
    }

    @Test
    fun masterOn_onlyOnDeviceIsAdmittedWithoutRouteEvidence() {
        assertTrue(PrivacyProfileEligibility.evaluate(profile(ClientType.LITERT_LM), true).eligible)
        assertFalse(PrivacyProfileEligibility.evaluate(profile(ClientType.LLAMA), true).eligible)
        assertFalse(PrivacyProfileEligibility.evaluate(profile(ClientType.LLAMA, "http://192.168.1.20:8080"), true).eligible)
        assertFalse(PrivacyProfileEligibility.evaluate(profile(ClientType.OLLAMA, "http://100.101.22.33:11434"), true).eligible)
    }

    @Test
    fun masterOn_rejectsUnverifiedRemoteGateway() {
        assertFalse(PrivacyProfileEligibility.evaluate(profile(ClientType.LLAMA, "https://cloud-proxy.example.com/v1"), true).eligible)
        assertFalse(PrivacyProfileEligibility.evaluate(profile(ClientType.OLLAMA, "http://8.8.8.8:11434"), true).eligible)
        assertFalse(PrivacyProfileEligibility.evaluate(profile(ClientType.OLLAMA, "http://host.invalid:11434"), true).eligible)
    }

    @Test
    fun masterOn_rejectsAllFreeServicesWithoutEndpointAndAccountVerification() {
        FreeAiProvider.entries.forEach { provider ->
            val decision = PrivacyProfileEligibility.evaluate(profile(ClientType.FREE, provider.apiUrl, provider.model), true)
            assertFalse("${provider.displayName} must fail closed: ${decision.explanation}", decision.eligible)
            assertTrue(decision.explanation.isNotBlank())
        }
    }

    @Test
    fun masterOn_identifiesKiloAutoAndVolunteerWorkerRisk() {
        val kilo = PrivacyProfileEligibility.freeDecision(FreeAiProvider.KILO, "kilo-auto/free")
        val horde = PrivacyProfileEligibility.freeDecision(FreeAiProvider.AI_HORDE, "automatic")
        assertTrue(kilo.explanation.contains("routing", ignoreCase = true) || kilo.explanation.contains("route", ignoreCase = true))
        assertTrue(horde.explanation.contains("worker", ignoreCase = true))
        assertFalse(kilo.eligible)
        assertFalse(horde.eligible)
    }

    @Test
    fun masterOn_rejectsUnknownFreeRoutingAndStandardCloudModels() {
        assertFalse(PrivacyProfileEligibility.evaluate(profile(ClientType.FREE, "https://unrecognized.example/v1"), true).eligible)
        assertFalse(PrivacyProfileEligibility.evaluate(profile(ClientType.OPENROUTER, "https://openrouter.ai/api/v1", "openrouter/free"), true).eligible)
        assertFalse(PrivacyProfileEligibility.evaluate(profile(ClientType.GOOGLE), true).eligible)
        assertFalse(PrivacyProfileEligibility.evaluate(profile(ClientType.GROQ), true).eligible)
    }

    @Test
    fun categoryDefaultsAreAllEnabledWithMasterOff() {
        val state = PrivacyModeState()
        assertFalse(state.enabled)
        assertTrue(state.categories.containsAll(PrivacyCategory.entries))
        assertFalse(state.protects(PrivacyCategory.SECRETS))
        assertTrue(state.copy(enabled = true).protects(PrivacyCategory.SECRETS))
    }

    @Test
    fun privateGatewayRequiresLocalOrTailscaleTargetNotUserInfo() {
        assertTrue(PrivacyProfileEligibility.privateGateway("http://127.0.0.1:8080"))
        assertTrue(PrivacyProfileEligibility.privateGateway("https://laptop.tailnet.ts.net:443"))
        assertFalse(PrivacyProfileEligibility.privateGateway("https://example.com"))
        assertFalse(PrivacyProfileEligibility.privateGateway("http://username:pass@127.0.0.1:8080"))
        assertFalse(PrivacyProfileEligibility.privateGateway("https://192.168.0.999:8080"))
    }
    private val remote = profile(ClientType.OPENROUTER, "https://openrouter.ai/api/v1", "example/my-model").copy(token = "test-credential")
    private val url = "https://openrouter.ai/api/v1/chat/completions"
    private val terms = "https://openrouter.ai/docs/guides/features/zdr"
    private val proof = ProviderApproval(url, remote.model, OpenRouterIdentity.digest(remote.token.orEmpty()), "upstream", terms, 100, 1000, "stateless-text-v1", "zdr", true, true, true, true, true)

    @Test
    fun evidenceIsBoundToTheRequestAndHasAnExpiry() {
        assertTrue(proof.covers(remote, url, terms, 999))
        assertFalse(proof.covers(remote, url, terms, 1000))
        assertFalse(proof.covers(remote.copy(token = "rotated"), url, terms, 500))
        assertFalse(proof.covers(remote.copy(model = "other"), url, terms, 500))
        assertFalse(proof.covers(remote, url + "/relay", terms, 500))
        assertFalse(proof.copy(feature = "batch").covers(remote, url, terms, 500))
        assertFalse(proof.copy(safetyRetentionExcluded = false).covers(remote, url, terms, 500))
        assertFalse(proof.copy(loggingDisabled = false).covers(remote, url, terms, 500))
    }

    @Test
    fun strictRoutingPinsOnlyTheExactApprovedUpstream() {
        fun rows(tier: String, tag: String) = Json.parseToJsonElement("""[{"model_id":"example/my-model","tag":"$tag","retention_tier":"$tier"}]""") as JsonArray
        assertTrue(VerifiedPrivateRoute.strictCatalogMatch(rows("zdr", "upstream"), proof))
        assertFalse(VerifiedPrivateRoute.strictCatalogMatch(rows("safety_retention", "upstream"), proof))
        assertFalse(VerifiedPrivateRoute.strictCatalogMatch(rows("zdr", "upstream/other"), proof))
        val wire = PrivateInferenceSession.wireBody(VerifiedPrivateRoute(url, ClientType.OPENROUTER, proof), remote.model, JsonArray(emptyList()), 128)
        assertEquals(JsonPrimitive(false), wire["store"])
        val routing = requireNotNull(wire["provider"]).jsonObject
        assertEquals(JsonPrimitive(true), routing["zdr"])
        assertEquals(JsonPrimitive("deny"), routing["data_collection"])
        assertEquals(JsonPrimitive(false), routing["allow_fallbacks"])
        assertEquals(JsonArray(listOf(JsonPrimitive("upstream"))), routing["only"])
        assertFalse(wire.containsKey("plugins") || wire.containsKey("tools") || wire.containsKey("models"))
        assertEquals(null, VerifiedPrivateRoute.providerEndpoint(remote.copy(model = "openrouter/free")))
        assertEquals(null, VerifiedPrivateRoute.providerEndpoint(remote.copy(apiUrl = "https://unverified.example/v1")))
    }

    @Test
    fun maskingRoundTripsPrivateTermsAndRepeatedContactsLocally() {
        val mask = PrivateTextMask(setOf(PrivacyCategory.CUSTOM, PrivacyCategory.CONTACTS), listOf("Project Violet"))
        val first = mask.protect("Project Violet belongs to ada@example.org.")
        val next = mask.protect("Send to ada@example.org.")
        assertFalse(first.contains("Violet") || first.contains("example.org"))
        assertFalse(next.contains("example.org"))
        val restore = mask.sealForResponse()
        assertEquals("Project Violet belongs to ada@example.org.", restore(first))
        assertEquals("Send to ada@example.org.", restore(next))
        assertEquals("Other tokens [PRIVATE_1] stay intact", restore("Other tokens [PRIVATE_1] stay intact"))
    }
}
