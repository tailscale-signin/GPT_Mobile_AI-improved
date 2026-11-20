package dev.chungjungsoo.gptmobile.data.privacy

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.model.FreeAiProvider
import dev.chungjungsoo.gptmobile.data.openrouter.account.OpenRouterIdentity
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
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
    fun customProfileWithoutVerifiedRouteRemainsIneligibleInPrivacyMode() {
        val custom = profile(ClientType.CUSTOM, "https://custom.example/v1")
        assertTrue(PrivacyProfileEligibility.evaluate(custom, false).eligible)
        assertFalse(PrivacyProfileEligibility.evaluate(custom, true).eligible)
        assertEquals(PrivacyProfileEligibility.Status.NOT_VERIFIED, PrivacyProfileEligibility.evaluate(custom, true).status)
        assertEquals(null, VerifiedPrivateRoute.providerEndpoint(custom))
        assertEquals(null, VerifiedPrivateRoute.gateway(custom))
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
    private val proof = ProviderApproval(url, remote.model, OpenRouterIdentity.digest(remote.token.orEmpty()), "upstream", terms, 100, 1000, "stateless-text-v1", "zdr", true, true, true, true, true, policySha256 = "a".repeat(64))
    private val accountProof = proof.copy(model = "*", upstream = "*", scope = "openrouter-account-v1")

    private class DispatchScope(val permission: String) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<DispatchScope>
    }

    private fun json(value: String) = Json.parseToJsonElement(value).jsonObject

    private fun endpoint(tag: String, model: String = remote.model, status: Int = 0) = buildJsonObject {
        put("model_id", model)
        put("tag", tag)
        put("status", status)
        put("service_tier", JsonNull)
    }

    private fun catalogRow(tag: String, safetyRetention: JsonElement? = JsonPrimitive(false), model: String = remote.model) =
        JsonObject(endpoint(tag, model) + if (safetyRetention == null) emptyMap() else mapOf("safety_retention" to safetyRetention))

    private fun inventory(vararg endpoints: JsonObject, model: String = remote.model) = buildJsonObject {
        put(
            "data",
            buildJsonObject {
                put("id", model)
                put("endpoints", JsonArray(endpoints.toList()))
            }
        )
    }

    private fun modelMetadata(model: String, canonical: String = model) = buildJsonObject {
        put(
            "data",
            buildJsonObject {
                put("id", model)
                put("canonical_slug", canonical)
                put("endpoints", JsonArray(listOf(JsonPrimitive("/api/v1/chat/completions"))))
            }
        )
    }

    private fun signedApprovals(records: List<ProviderApproval>): Pair<String, String> {
        val pair = java.security.KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val encoder = java.util.Base64.getEncoder()
        val payload = Json.encodeToString(kotlinx.serialization.builtins.ListSerializer(ProviderApproval.serializer()), records).encodeToByteArray()
        val signature = java.security.Signature.getInstance("SHA256withECDSA").run {
            initSign(pair.private)
            update(payload)
            encoder.encodeToString(sign())
        }
        return encoder.encodeToString(pair.public.encoded) to """{"payload":"${encoder.encodeToString(payload)}","signature":"$signature"}"""
    }

    @Test
    fun evidenceIsBoundToTheRequestAndHasAnExpiry() {
        assertTrue(proof.covers(remote, url, terms, 999))
        assertFalse(proof.copy(policySha256 = "").covers(remote, url, terms, 999))
        assertFalse(proof.permitsDocument("https://openrouter.ai.unverified.example/policy"))
        assertFalse(proof.permitsDocument("http://openrouter.ai/docs"))
        assertFalse(proof.permitsDocument("https://user@openrouter.ai/docs"))
        assertFalse(proof.covers(remote, url, terms, 1000))
        assertFalse(proof.covers(remote.copy(token = "rotated"), url, terms, 500))
        assertFalse(proof.covers(remote.copy(model = "other"), url, terms, 500))
        assertFalse(proof.covers(remote, url + "/relay", terms, 500))
        assertFalse(proof.copy(feature = "batch").covers(remote, url, terms, 500))
        assertFalse(proof.copy(safetyRetentionExcluded = false).covers(remote, url, terms, 500))
        assertFalse(proof.copy(loggingDisabled = false).covers(remote, url, terms, 500))
    }

    @Test
    fun accountApprovalMustExplicitlyOptIntoDiscoveryAndNeverWidensLegacyRecords() {
        assertTrue(accountProof.covers(remote, url, terms, 500))
        assertTrue(accountProof.covers(remote.copy(model = "another/concrete-model"), url, terms, 500))
        assertFalse(proof.covers(remote.copy(model = "another/concrete-model"), url, terms, 500))
        assertFalse(proof.copy(model = "*", upstream = "*").covers(remote, url, terms, 500))
        assertFalse(accountProof.copy(scope = "exact-route-v1").covers(remote, url, terms, 500))
        assertFalse(accountProof.copy(scope = "future-account-scope").covers(remote, url, terms, 500))
        assertFalse(accountProof.copy(model = remote.model).covers(remote, url, terms, 500))
        assertFalse(accountProof.copy(upstream = "upstream").covers(remote, url, terms, 500))
        assertFalse(accountProof.covers(remote.copy(compatibleType = ClientType.OPENAI), url, terms, 500))

        val legacyJson = JsonObject(Json.encodeToJsonElement(ProviderApproval.serializer(), proof).jsonObject - "scope")
        val legacy = Json.decodeFromJsonElement(ProviderApproval.serializer(), legacyJson)
        assertEquals("exact-route-v1", legacy.scope)
        assertTrue(legacy.covers(remote, url, terms, 500))
        assertFalse(legacy.covers(remote.copy(model = "another/concrete-model"), url, terms, 500))
    }

    @Test
    fun accountApprovalRetainsCredentialEndpointExpiryAndAllPrivacyRequirements() {
        assertFalse(accountProof.covers(remote.copy(token = "rotated"), url, terms, 500))
        assertFalse(accountProof.covers(remote.copy(apiUrl = "https://unverified.example/api/v1"), url, terms, 500))
        assertFalse(accountProof.covers(remote, "$url/relay", terms, 500))
        assertFalse(accountProof.covers(remote, url, "$terms/other", 500))
        assertFalse(accountProof.covers(remote, url, terms, 99))
        assertFalse(accountProof.covers(remote, url, terms, 1000))
        assertFalse(accountProof.copy(expiresAtMillis = 86_400_101L).covers(remote, url, terms, 500))
        listOf(
            accountProof.copy(noTraining = false),
            accountProof.copy(loggingDisabled = false),
            accountProof.copy(externalToolsDisabled = false),
            accountProof.copy(providerDefaultsDisabled = false),
            accountProof.copy(safetyRetentionExcluded = false),
            accountProof.copy(retentionTier = "safety_retention"),
            accountProof.copy(policySha256 = ""),
            accountProof.copy(feature = "batch")
        ).forEach { invalid -> assertFalse("Account scope cannot bypass $invalid", invalid.covers(remote, url, terms, 500)) }
    }

    @Test
    fun accountScopeCannotApproveAnUnofficialHostEvenWhenAllSignedFieldsAgree() {
        listOf(
            "https://openrouter.ai.unverified.example/api/v1",
            "http://openrouter.ai/api/v1",
            "https://user@openrouter.ai/api/v1",
            "https://openrouter.ai:444/api/v1",
            "https://openrouter.ai/api/v1/relay"
        ).forEach { base ->
            val target = "$base/chat/completions"
            assertFalse(accountProof.copy(endpoint = target).covers(remote.copy(apiUrl = base), target, terms, 500))
        }
        OpenRouterIdentity.endpoints.forEach { base ->
            val target = "$base/chat/completions"
            assertTrue(accountProof.copy(endpoint = target).covers(remote.copy(apiUrl = base), target, terms, 500))
        }
    }

    @Test
    fun unknownGuardrailMutationCannotBeReconciledByAnotherAlreadyStrictGroup() {
        val id = "00000000-0000-0000-0000-000000000001"
        val key = "a".repeat(64)
        val google = json("""{"enforce_zdr_google":true,"allow_safety_retention_google":false,"allow_safety_retention_other":false}""")
        val openAi = json("""{"enforce_zdr_openai":true,"allow_safety_retention_google":false,"allow_safety_retention_other":false}""")
        val pending = PrivateOpenRouterRouting.guardrailUpdateIdentity(id, key, google)
        assertFalse(pending == PrivateOpenRouterRouting.guardrailUpdateIdentity(id, key, openAi))
        assertFalse(pending == PrivateOpenRouterRouting.guardrailUpdateIdentity("00000000-0000-0000-0000-000000000002", key, google))
        assertFalse(pending == PrivateOpenRouterRouting.guardrailUpdateIdentity(id, "b".repeat(64), google))
        assertFalse(pending == PrivateOpenRouterRouting.guardrailUpdateIdentity(id, key, JsonObject(google - "allow_safety_retention_other")))
        assertFalse(pending == PrivateOpenRouterRouting.guardrailUpdateIdentity(id, key, JsonObject(google + ("allow_safety_retention_other" to JsonPrimitive(true)))))
        assertTrue(pending.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun guardrailMutationIdentityIsStableAcrossObjectOrderButPreservesValuesAndArrayOrder() {
        val first = json("""{"enforce_zdr_google":true,"policy":{"providers":["first","second"],"safety_retention":false},"allow_safety_retention_other":false}""")
        val reordered = json("""{"allow_safety_retention_other":false,"policy":{"safety_retention":false,"providers":["first","second"]},"enforce_zdr_google":true}""")
        fun identity(update: JsonObject) = PrivateOpenRouterRouting.guardrailUpdateIdentity("guardrail", "credential-hash", update)
        assertEquals(identity(first), identity(reordered))
        val changedOrder = json("""{"enforce_zdr_google":true,"policy":{"providers":["second","first"],"safety_retention":false},"allow_safety_retention_other":false}""")
        assertFalse(identity(first) == identity(changedOrder))
        assertFalse(identity(first) == identity(JsonObject(first + ("allow_safety_retention_other" to JsonPrimitive("false")))))
    }

    @Test
    fun signedAccountScopeAndEmptyRevocationPayloadRoundTripWithoutInventedApprovals() {
        val (issuer, signed) = signedApprovals(listOf(accountProof))
        assertEquals(listOf(accountProof), ProviderApprovalSource.verify(issuer, signed))
        val (revocationIssuer, revoked) = signedApprovals(emptyList())
        assertTrue(ProviderApprovalSource.verify(revocationIssuer, revoked).isEmpty())
    }

    @Test
    fun onlyTheEnrolledIssuerCanApproveAccountEvidence() {
        val pair = java.security.KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val encoder = java.util.Base64.getEncoder()
        val issuer = encoder.encodeToString(pair.public.encoded)
        val payload = Json.encodeToString(kotlinx.serialization.builtins.ListSerializer(ProviderApproval.serializer()), listOf(proof)).encodeToByteArray()
        val signer = java.security.Signature.getInstance("SHA256withECDSA")
        signer.initSign(pair.private)
        signer.update(payload)
        val signature = encoder.encodeToString(signer.sign())
        fun envelope(data: ByteArray) = """{"payload":"${encoder.encodeToString(data)}","signature":"$signature"}"""
        assertEquals(listOf(proof), ProviderApprovalSource.verify(issuer, envelope(payload)))
        assertTrue(runCatching { ProviderApprovalSource.verify(issuer, envelope("[]".encodeToByteArray())) }.isFailure)
        val widerScope = Json.encodeToString(kotlinx.serialization.builtins.ListSerializer(ProviderApproval.serializer()), listOf(accountProof)).encodeToByteArray()
        assertTrue(runCatching { ProviderApprovalSource.verify(issuer, envelope(widerScope)) }.isFailure)
        val other = java.security.KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        assertTrue(runCatching { ProviderApprovalSource.verify(encoder.encodeToString(other.public.encoded), envelope(payload)) }.isFailure)
    }

    @Test
    fun strictRoutingPinsOnlyTheExactApprovedUpstream() {
        assertTrue(VerifiedPrivateRoute.strictCatalogMatch(JsonArray(listOf(catalogRow("upstream"))), proof))
        assertFalse(VerifiedPrivateRoute.strictCatalogMatch(JsonArray(listOf(catalogRow("upstream", JsonPrimitive(true)))), proof))
        assertFalse(VerifiedPrivateRoute.strictCatalogMatch(JsonArray(listOf(catalogRow("upstream/other"))), proof))
        val wire = PrivateInferenceSession.wireBody(VerifiedPrivateRoute(url, ClientType.OPENROUTER, proof), remote.model, JsonArray(emptyList()), 128)
        val routing = requireNotNull(wire["provider"]).jsonObject
        assertEquals(JsonPrimitive(true), routing["zdr"])
        assertEquals(JsonPrimitive("deny"), routing["data_collection"])
        assertEquals(JsonPrimitive(false), routing["allow_fallbacks"])
        assertEquals(JsonArray(listOf(JsonPrimitive("upstream"))), routing["only"])
        assertEquals(JsonArray(listOf(JsonPrimitive("upstream"))), routing["order"])
        assertFalse(wire.containsKey("tools") || wire.containsKey("models"))
        assertEquals(null, VerifiedPrivateRoute.providerEndpoint(remote.copy(model = "openrouter/free")))
        assertEquals(null, VerifiedPrivateRoute.providerEndpoint(remote.copy(apiUrl = "https://unverified.example/v1")))
    }

    @Test
    fun strictCatalogRejectsUnknownOrCoercedSafetyRetentionAndConflictingEvidence() {
        listOf(null, JsonNull, JsonPrimitive(true), JsonPrimitive("false"), JsonPrimitive(0)).forEach { unsafe ->
            val row = catalogRow("upstream", unsafe)
            assertFalse("Safety retention must be a literal false: $unsafe", PrivateOpenRouterRouting.strictRow(row))
            assertFalse(VerifiedPrivateRoute.strictCatalogMatch(JsonArray(listOf(row)), proof))
        }
        val strict = catalogRow("upstream")
        assertFalse(VerifiedPrivateRoute.strictCatalogMatch(JsonArray(listOf(strict, strict)), proof))
        assertFalse(VerifiedPrivateRoute.strictCatalogMatch(JsonArray(listOf(strict, catalogRow("upstream", JsonPrimitive(true)))), proof))
        assertFalse(VerifiedPrivateRoute.strictCatalogMatch(JsonArray(listOf(catalogRow("upstream", model = "another/model"))), proof))
        assertFalse(PrivateOpenRouterRouting.strictRow(JsonObject(strict + ("requires_safety_retention" to JsonPrimitive(true)))))
    }

    @Test
    fun discoveryKeepsUnknownRetentionEvidenceNotVerified() {
        listOf(null, JsonNull, JsonPrimitive("false"), JsonPrimitive(0)).forEach { unverified ->
            val selected = PrivateOpenRouterRouting.select(
                JsonArray(listOf(catalogRow("provider/zdr", unverified))),
                inventory(endpoint("provider/zdr")),
                remote.model,
                null,
                JsonObject(emptyMap())
            )
            assertEquals("Unknown retention must not admit an endpoint: $unverified", null, selected.upstream)
            assertEquals(PrivateOpenRouterRouting.Failure.NO_STRICT_ZDR_ENDPOINT, selected.failure)
            val decision = PrivacyProfileEligibility.Reason.valueOf(requireNotNull(selected.failure).name).decision(terms)
            assertFalse(decision.eligible)
            assertEquals(PrivacyProfileEligibility.Status.NOT_VERIFIED, decision.status)
        }
    }

    @Test
    fun discoveryKeepsMissingApprovedUpstreamNotVerified() {
        val selected = PrivateOpenRouterRouting.select(
            JsonArray(listOf(catalogRow("provider/zdr"))),
            inventory(endpoint("provider/zdr")),
            remote.model,
            "missing/zdr",
            JsonObject(emptyMap())
        )
        assertEquals(null, selected.upstream)
        assertEquals(PrivateOpenRouterRouting.Failure.NO_STRICT_ZDR_ENDPOINT, selected.failure)
        val decision = PrivacyProfileEligibility.Reason.valueOf(requireNotNull(selected.failure).name).decision(terms)
        assertFalse(decision.eligible)
        assertEquals(PrivacyProfileEligibility.Status.NOT_VERIFIED, decision.status)
    }

    @Test
    fun discoveryKeepsExplicitlyExcludedVerifiedEndpointsIncompatible() {
        val selected = PrivateOpenRouterRouting.select(
            JsonArray(listOf(catalogRow("provider/zdr"))),
            inventory(endpoint("provider/zdr")),
            remote.model,
            null,
            requireNotNull(PrivateOpenRouterRouting.options("""{"ignore":["provider"]}"""))
        )
        assertEquals(null, selected.upstream)
        assertEquals(PrivateOpenRouterRouting.Failure.PROVIDER_RESTRICTIONS, selected.failure)
        val decision = PrivacyProfileEligibility.Reason.valueOf(requireNotNull(selected.failure).name).decision(terms)
        assertFalse(decision.eligible)
        assertEquals(PrivacyProfileEligibility.Status.INCOMPATIBLE, decision.status)
    }

    @Test
    fun discoveryRejectsMalformedWrongModelOrMissingEndpointEvidence() {
        val catalog = JsonArray(listOf(catalogRow("provider/zdr")))
        val none = JsonObject(emptyMap())
        val malformed = listOf(
            none,
            json("""{"data":{"id":"example/my-model"}}"""),
            json("""{"data":{"id":"example/my-model","endpoints":"provider/zdr"}}"""),
            inventory(endpoint("provider/zdr"), model = "another/model"),
            inventory(endpoint("provider/zdr", model = "another/model")),
            inventory(JsonObject(endpoint("provider/zdr") - "model_id")),
            inventory(JsonObject(endpoint("provider/zdr") - "tag"))
        )
        malformed.forEach { invalid ->
            val selected = PrivateOpenRouterRouting.select(catalog, invalid, remote.model, null, none)
            assertEquals("Malformed inventory cannot qualify: $invalid", null, selected.upstream)
            assertEquals(PrivateOpenRouterRouting.Failure.CATALOG_MALFORMED, selected.failure)
        }
        val missing = PrivateOpenRouterRouting.select(JsonArray(emptyList()), inventory(endpoint("provider/zdr")), remote.model, null, none)
        assertEquals(PrivateOpenRouterRouting.Failure.MODEL_NOT_LISTED, missing.failure)
        val malformedCatalog = PrivateOpenRouterRouting.select(JsonArray(listOf(JsonPrimitive("not an endpoint"))), inventory(endpoint("provider/zdr")), remote.model, null, none)
        assertEquals(PrivateOpenRouterRouting.Failure.CATALOG_MALFORMED, malformedCatalog.failure)
    }

    @Test
    fun discoveryRejectsDuplicateCatalogAndInventorySelectors() {
        val strict = catalogRow("provider/zdr")
        val full = inventory(endpoint("provider/zdr"))
        val options = JsonObject(emptyMap())
        listOf(
            JsonArray(listOf(strict, strict)),
            JsonArray(listOf(strict, catalogRow("provider/zdr", JsonPrimitive(true))))
        ).forEach { duplicate ->
            val selected = PrivateOpenRouterRouting.select(duplicate, full, remote.model, null, options)
            assertEquals(null, selected.upstream)
            assertEquals(PrivateOpenRouterRouting.Failure.AMBIGUOUS_ENDPOINT, selected.failure)
        }
        val duplicateInventory = inventory(endpoint("provider/zdr"), endpoint("provider/zdr"))
        assertEquals(
            PrivateOpenRouterRouting.Failure.AMBIGUOUS_ENDPOINT,
            PrivateOpenRouterRouting.select(JsonArray(listOf(strict)), duplicateInventory, remote.model, null, options).failure
        )
    }

    @Test
    fun discoveryRejectsBroadSelectorsThatAlsoAdmitUnsafeVariantsEvenWhenUnavailable() {
        val catalog = JsonArray(listOf(catalogRow("provider"), catalogRow("provider/unsafe", JsonPrimitive(true))))
        for (status in listOf(0, -1)) {
            val full = inventory(endpoint("provider"), endpoint("provider/unsafe", status = status))
            val selected = PrivateOpenRouterRouting.select(catalog, full, remote.model, null, JsonObject(emptyMap()))
            assertEquals(null, selected.upstream)
            assertEquals(PrivateOpenRouterRouting.Failure.AMBIGUOUS_ENDPOINT, selected.failure)
        }
        val missingVariantEvidence = PrivateOpenRouterRouting.select(
            JsonArray(listOf(catalogRow("provider"))),
            inventory(endpoint("provider"), endpoint("provider/unverified")),
            remote.model,
            null,
            JsonObject(emptyMap())
        )
        assertEquals(PrivateOpenRouterRouting.Failure.AMBIGUOUS_ENDPOINT, missingVariantEvidence.failure)
    }

    @Test
    fun discoveryCanPinAStrictVariantWithoutAdmittingItsUnsafeBaseOrSibling() {
        val selected = PrivateOpenRouterRouting.select(
            JsonArray(listOf(catalogRow("provider", JsonPrimitive(true)), catalogRow("provider/zdr"))),
            inventory(endpoint("provider"), endpoint("provider/zdr"), endpoint("provider/other")),
            remote.model,
            null,
            JsonObject(emptyMap())
        )
        assertEquals("provider/zdr", selected.upstream)
        assertEquals(null, selected.failure)
    }

    @Test
    fun inventoryRetentionMetadataCannotContradictStrictCatalogEvidence() {
        val catalog = JsonArray(listOf(catalogRow("provider/zdr")))
        val base = endpoint("provider/zdr")
        fun select(row: JsonObject) = PrivateOpenRouterRouting.select(catalog, inventory(row), remote.model, null, JsonObject(emptyMap()))
        assertEquals("provider/zdr", select(base).upstream)
        assertEquals("provider/zdr", select(JsonObject(base + json("""{"safety_retention":false,"requires_safety_retention":false,"retention_tier":"zdr","data_policy_tier":"zdr"}"""))).upstream)
        listOf(
            "safety_retention" to JsonPrimitive(true),
            "safety_retention" to JsonPrimitive("false"),
            "safety_retention" to JsonNull,
            "requires_safety_retention" to JsonPrimitive(true),
            "requires_safety_retention" to JsonPrimitive("false"),
            "requires_safety_retention" to JsonNull,
            "retention_tier" to JsonPrimitive("safety_retention"),
            "retention_tier" to JsonNull,
            "data_policy_tier" to JsonPrimitive("safety_retention"),
            "data_policy_tier" to JsonPrimitive(false)
        ).forEach { contradictory ->
            val selected = select(JsonObject(base + contradictory))
            assertEquals("Conflicting inventory must veto $contradictory", null, selected.upstream)
            assertEquals(PrivateOpenRouterRouting.Failure.AMBIGUOUS_ENDPOINT, selected.failure)
        }
    }

    @Test
    fun discoveryHonorsOnlyIgnoreOrderAndExistingApprovedUpstream() {
        val tags = listOf("provider/eu", "provider/us", "other/zdr")
        val catalog = JsonArray(tags.map { catalogRow(it) })
        val full = inventory(*tags.map { endpoint(it) }.toTypedArray())
        fun selected(raw: String, approved: String? = null) = PrivateOpenRouterRouting.select(
            catalog,
            full,
            remote.model,
            approved,
            requireNotNull(PrivateOpenRouterRouting.options(raw))
        )
        assertEquals("provider/eu", selected("""{"only":["provider"],"ignore":["provider/us"],"order":["provider/us","other","provider/eu"]}""").upstream)
        assertEquals("other/zdr", selected("""{"order":["other","provider"]}""").upstream)
        assertEquals("other/zdr", selected("{}", "other/zdr").upstream)
        assertEquals("other/zdr", selected("""{"ignore":["provider"]}""").upstream)
        assertEquals(PrivateOpenRouterRouting.Failure.PROVIDER_RESTRICTIONS, selected("""{"only":["provider"],"ignore":["provider"]}""").failure)
        assertEquals(PrivateOpenRouterRouting.Failure.PROVIDER_RESTRICTIONS, selected("""{"order":["missing"],"allow_fallbacks":false}""").failure)
        assertEquals(PrivateOpenRouterRouting.Failure.NO_STRICT_ZDR_ENDPOINT, selected("{}", "missing/zdr").failure)
        assertEquals("other/zdr", selected("{}").upstream)
    }

    @Test
    fun discoveryJoinsCanonicalMetadataWithoutChangingTheRoutableModel() {
        val canonical = "example/my-model-20261119"
        val target = requireNotNull(PrivateOpenRouterRouting.concreteTarget(modelMetadata(remote.model, canonical), remote.model, false))
        assertEquals(remote.model, target.model)
        assertEquals(canonical, target.canonical)
        val selected = PrivateOpenRouterRouting.select(
            JsonArray(listOf(catalogRow("provider/zdr", model = canonical))),
            inventory(endpoint("provider/zdr", model = canonical), model = remote.model),
            target.model,
            null,
            JsonObject(emptyMap()),
            target.canonical
        )
        assertEquals("provider/zdr", selected.upstream)
    }

    @Test
    fun modelResolutionRequiresConcreteMetadataAndPreservesFreeSelection() {
        val target = modelMetadata("example/model-v2")
        assertEquals("example/model-v2", PrivateOpenRouterRouting.concreteTarget(target, "example/model-v2", false)?.model)
        assertEquals(null, PrivateOpenRouterRouting.concreteTarget(target, "another/unrelated-model", true))
        assertEquals(null, PrivateOpenRouterRouting.concreteTarget(target, "~example/model-latest", true))
        assertEquals(null, PrivateOpenRouterRouting.concreteTarget(target, "~example/model-latest", false))
        assertEquals(null, PrivateOpenRouterRouting.concreteTarget(target, "example/model:free", true))
        assertEquals(null, PrivateOpenRouterRouting.concreteTarget(modelMetadata("example/model-v2:free"), "example/model", true))
        assertEquals("example/model-v2:free", PrivateOpenRouterRouting.concreteTarget(modelMetadata("example/model-v2:free", "example/model-v2"), "example/model-v2:free", false)?.model)
        assertEquals(null, PrivateOpenRouterRouting.concreteTarget(modelMetadata("~example/model-latest"), "~example/model-latest", true))
        assertEquals(null, PrivateOpenRouterRouting.concreteTarget(modelMetadata("example/model-latest"), "example/model-latest", true))
        assertEquals(null, PrivateOpenRouterRouting.concreteTarget(json("""{"data":{"id":"example/model-v2","canonical_slug":"example/model-v2","endpoints":["/api/v1/responses"]}}"""), "example/model-v2", false))
        assertEquals(null, PrivateOpenRouterRouting.concreteTarget(json("""{"data":{"id":"example/model-v2","endpoints":["/api/v1/chat/completions"]}}"""), "example/model-v2", false))
    }

    @Test
    fun aliasMetadataCannotStandInForItsConcreteTargetOrResolveToAnotherRouter() {
        fun alias(slug: String) = json("""{"data":{"id":"~example/model-latest","alias_target":{"slug":"$slug","name":"Target"}}}""")
        val valid = alias("example/model-v2")
        assertEquals("example/model-v2", PrivateOpenRouterRouting.aliasTarget(valid))
        assertEquals("example/model-v2", PrivateOpenRouterRouting.aliasTarget(valid, "~example/model-latest"))
        assertEquals(null, PrivateOpenRouterRouting.aliasTarget(valid, "~example/unrelated-latest"))
        assertEquals(null, PrivateOpenRouterRouting.concreteTarget(valid, "~example/model-latest", true))
        listOf("~example/model-latest", "example/model-latest", "openrouter/free", "example/model:online").forEach { invalid ->
            assertEquals(null, PrivateOpenRouterRouting.aliasTarget(alias(invalid)))
        }
        assertEquals(null, PrivateOpenRouterRouting.aliasTarget(json("""{"data":{"alias_target":{"slug":123}}}""")))
        listOf("openrouter/free", "openrouter/auto", "example/model:online", "example/model:nitro", "example/model/extra", "https://example/model").forEach { invalid ->
            assertFalse("Unsupported routing model $invalid", PrivateOpenRouterRouting.selectedModel(invalid))
        }
        assertTrue(PrivateOpenRouterRouting.selectedModel("example/model:free"))
        assertTrue(PrivateOpenRouterRouting.selectedModel("~example/model-latest"))
    }

    @Test
    fun routingOptionsPreserveKnownLimitsButRejectFallbacksPluginsAndUnknownControls() {
        val provider = json("""{"only":["provider/zdr"],"ignore":["other"],"order":["provider"],"max_price":{"prompt":0.25,"completion":"0.5"},"quantizations":["fp16"],"sort":"price"}""")
        assertEquals(provider, PrivateOpenRouterRouting.options(provider.toString()))
        assertEquals(provider, PrivateOpenRouterRouting.options("""{"provider":$provider,"fallback_models":[],"stream":false,"max_tokens":128}"""))
        listOf(
            """{"fallback_models":["other/model"]}""",
            """{"models":["other/model"]}""",
            """{"provider":{},"plugins":[{"id":"web"}]}""",
            """{"plugins":[]}""",
            """{"preset":"unverified-defaults"}""",
            """{"provider":{"unrecognized_control":true}}""",
            """{"only":"provider/zdr"}""",
            """{"only":[123]}""",
            """{"ignore":["provider/*"]}""",
            """{"order":["provider name"]}"""
        ).forEach { invalid -> assertEquals("Unsupported routing must fail closed: $invalid", null, PrivateOpenRouterRouting.options(invalid)) }
    }

    @Test
    fun routingOptionsRejectCoercedBooleansAndInvalidPriceOrLatencyTypes() {
        listOf(
            """{"allow_fallbacks":"false"}""",
            """{"require_parameters":[]}""",
            """{"zdr":1}""",
            """{"enforce_distillable_text":"false"}""",
            """{"data_collection":"sometimes"}""",
            """{"max_price":true}""",
            """{"max_price":{"prompt":true}}""",
            """{"max_price":{"completion":-1}}""",
            """{"max_price":{"prompt":"NaN"}}""",
            """{"max_price":{"prompt":"1e999"}}""",
            """{"max_price":{"unknown_price":0.1}}""",
            """{"preferred_max_latency":"soon"}""",
            """{"preferred_min_throughput":false}""",
            """{"quantizations":[2]}""",
            """{"sort":true}"""
        ).forEach { invalid -> assertEquals("Invalid routing type must not reach inference: $invalid", null, PrivateOpenRouterRouting.options(invalid)) }
    }

    @Test
    fun decimalStringsArePreservedForPricesWithoutCoercingPerformanceValues() {
        val valid = json("""{"max_price":{"prompt":"0.000001","completion":0.25,"image":"0","request":"1e2"},"preferred_min_throughput":10,"preferred_max_latency":{"p50":0.25,"p99":2}}""")
        assertEquals(valid, PrivateOpenRouterRouting.options(valid.toString()))
        listOf(
            """{"preferred_min_throughput":"10"}""",
            """{"preferred_max_latency":{"p50":"0.25"}}""",
            """{"max_price":{"prompt":"-0.01"}}""",
            """{"max_price":{"prompt":"Infinity"}}""",
            """{"max_price":{"prompt":" 0.25"}}""",
            """{"max_price":{"prompt":"0x10"}}""",
            """{"max_price":{"prompt":"1e-25"}}""",
            """{"max_price":{"prompt":"${"1".repeat(41)}"}}"""
        ).forEach { invalid -> assertEquals("Price support must not relax other value types: $invalid", null, PrivateOpenRouterRouting.options(invalid)) }
    }

    @Test
    fun profileRoutingCannotRemoveConnectionExclusionsPrivacyFlagsOrPriceCaps() {
        val defaults = json("""{"only":["provider","other/zdr"],"ignore":["provider/unsafe"],"allow_fallbacks":false,"require_parameters":true,"zdr":true,"enforce_distillable_text":true,"data_collection":"deny","quantizations":["fp16","bf16"],"max_price":{"prompt":"0.20","completion":0.30,"request":0.01},"sort":"price","preferred_min_throughput":5}""")
        val profile = json("""{"only":["provider/zdr","third"],"ignore":["profile/unsafe"],"order":["provider/zdr"],"allow_fallbacks":true,"require_parameters":false,"zdr":false,"enforce_distillable_text":false,"data_collection":"allow","quantizations":["bf16","fp32"],"max_price":{"prompt":0.40,"completion":"0.10","image":"0.03"},"sort":"latency","preferred_max_latency":2.5}""")
        val merged = requireNotNull(PrivateOpenRouterRouting.mergeOptions(defaults, profile))
        assertEquals(JsonArray(listOf(JsonPrimitive("provider/zdr"))), merged["only"])
        assertEquals(JsonArray(listOf(JsonPrimitive("provider/unsafe"), JsonPrimitive("profile/unsafe"))), merged["ignore"])
        assertEquals(JsonArray(listOf(JsonPrimitive("bf16"))), merged["quantizations"])
        assertEquals(JsonPrimitive(false), merged["allow_fallbacks"])
        listOf("require_parameters", "zdr", "enforce_distillable_text").forEach { key -> assertEquals(JsonPrimitive(true), merged[key]) }
        assertEquals(JsonPrimitive("deny"), merged["data_collection"])
        assertEquals(json("""{"prompt":"0.20","completion":"0.10","request":0.01,"image":"0.03"}"""), merged["max_price"])
        assertEquals(profile["sort"], merged["sort"])
        assertEquals(defaults["preferred_min_throughput"], merged["preferred_min_throughput"])
        assertEquals(profile["preferred_max_latency"], merged["preferred_max_latency"])
        val route = VerifiedPrivateRoute(url, ClientType.OPENROUTER, accountProof, "provider/zdr", merged, remote.model)
        val transmitted = requireNotNull(PrivateInferenceSession.wireBody(route, remote.model, JsonArray(emptyList()), 128)["provider"]).jsonObject
        assertEquals(merged["ignore"], transmitted["ignore"])
        assertEquals(merged["max_price"], transmitted["max_price"])
    }

    @Test
    fun routingMergeUsesPrefixIntersectionAndAllowsProfilesToStrengthenDefaults() {
        val base = json("""{"only":["provider/zdr"],"quantizations":["fp16"],"allow_fallbacks":true,"require_parameters":false,"zdr":false,"enforce_distillable_text":false,"data_collection":"allow","max_price":{"prompt":"0.10"}}""")
        val profile = json("""{"only":["provider"],"quantizations":["fp16","bf16"],"allow_fallbacks":false,"require_parameters":true,"zdr":true,"enforce_distillable_text":true,"data_collection":"deny","max_price":{"prompt":0.1}}""")
        val merged = requireNotNull(PrivateOpenRouterRouting.mergeOptions(base, profile))
        assertEquals(base["only"], merged["only"])
        assertEquals(base["quantizations"], merged["quantizations"])
        assertEquals(JsonPrimitive(false), merged["allow_fallbacks"])
        assertEquals(JsonPrimitive("deny"), merged["data_collection"])
        listOf("require_parameters", "zdr", "enforce_distillable_text").forEach { key -> assertEquals(JsonPrimitive(true), merged[key]) }
        assertEquals(base["max_price"], merged["max_price"])
    }

    @Test
    fun emptyProfileListsCannotClearConnectionRestrictionsOrPreferredOrder() {
        val defaults = json("""{"only":["provider"],"ignore":["provider/us"],"quantizations":["fp16"],"order":["provider/us","provider/eu"],"max_price":{"prompt":0.1}}""")
        val emptyLists = json("""{"only":[],"ignore":[],"quantizations":[],"order":[],"max_price":{}}""")
        val merged = requireNotNull(PrivateOpenRouterRouting.mergeOptions(defaults, emptyLists))
        assertEquals(defaults, merged)
        val selected = PrivateOpenRouterRouting.select(
            JsonArray(listOf(catalogRow("provider/us"), catalogRow("provider/eu"))),
            inventory(endpoint("provider/us"), endpoint("provider/eu")),
            remote.model,
            null,
            merged
        )
        assertEquals("provider/eu", selected.upstream)
    }

    @Test
    fun routingMergeRejectsConflictingRestrictionsAndValidatesBothInputsAndCombinedLimits() {
        assertEquals(null, PrivateOpenRouterRouting.mergeOptions(json("""{"only":["provider"]}"""), json("""{"only":["provider-other"]}""")))
        assertEquals(null, PrivateOpenRouterRouting.mergeOptions(json("""{"quantizations":["fp16"]}"""), json("""{"quantizations":["bf16"]}""")))
        assertEquals(null, PrivateOpenRouterRouting.mergeOptions(json("""{"ignore":["provider"]}"""), json("""{"only":["provider/us"]}""")))
        assertEquals(null, PrivateOpenRouterRouting.mergeOptions(json("""{"zdr":"false"}"""), json("""{"zdr":true}""")))
        assertEquals(null, PrivateOpenRouterRouting.mergeOptions(json("{}"), json("""{"max_price":{"prompt":-1}}""")))
        fun exclusions(prefix: String) = JsonObject(mapOf("ignore" to JsonArray((1..60).map { JsonPrimitive("$prefix/$it") })))
        assertEquals(null, PrivateOpenRouterRouting.mergeOptions(exclusions("base"), exclusions("profile")))
    }

    @Test
    fun dispatchRecheckPreservesOriginalPermissionContextWithoutCompletingItsJob() {
        val original = Job()
        val permission = DispatchScope("approved-scope")
        var observed: DispatchScope? = null
        try {
            PrivateInferenceSession.recheckDispatch(original + permission) {
                observed = currentCoroutineContext()[DispatchScope]
            }
            assertEquals(permission, observed)
            assertTrue(original.isActive)
        } finally {
            original.cancel()
        }
    }

    @Test
    fun dispatchRecheckRejectsAnAlreadyCancelledRequestBeforeValidation() {
        val original = Job().apply { cancel() }
        var called = false
        val failure = runCatching {
            PrivateInferenceSession.recheckDispatch(original + DispatchScope("approved-scope")) { called = true }
        }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertFalse(called)
    }

    @Test
    fun dispatchRecheckRejectsCancellationThatArrivesDuringValidation() {
        val original = Job()
        var called = false
        val failure = runCatching {
            PrivateInferenceSession.recheckDispatch(original + DispatchScope("approved-scope")) {
                called = true
                original.cancel()
            }
        }.exceptionOrNull()
        assertTrue(called)
        assertTrue(failure is CancellationException)
    }

    @Test
    fun privateWirePinsResolvedModelAndUpstreamOverridesWeakerFlagsAndPreservesPriceCaps() {
        val options = requireNotNull(PrivateOpenRouterRouting.options("""{"allow_fallbacks":true,"require_parameters":false,"data_collection":"allow","zdr":false,"only":["unverified"],"order":["unverified"],"ignore":["blocked"],"max_price":{"prompt":0.25,"completion":"0.5"},"sort":"price"}"""))
        val messages = JsonArray(listOf(json("""{"role":"user","content":"A masked prompt"}""")))
        val route = VerifiedPrivateRoute(url, ClientType.OPENROUTER, accountProof, "provider/zdr", options, "example/model-v2")
        val wire = PrivateInferenceSession.wireBody(route, "~example/model-latest", messages, 128)
        assertEquals(JsonPrimitive("example/model-v2"), wire["model"])
        assertEquals(messages, wire["messages"])
        assertEquals(JsonPrimitive(128), wire["max_tokens"])
        val provider = requireNotNull(wire["provider"]).jsonObject
        assertEquals(JsonArray(listOf(JsonPrimitive("provider/zdr"))), provider["only"])
        assertEquals(provider["only"], provider["order"])
        assertEquals(JsonPrimitive(true), provider["zdr"])
        assertEquals(JsonPrimitive("deny"), provider["data_collection"])
        assertEquals(JsonPrimitive(false), provider["allow_fallbacks"])
        assertEquals(JsonPrimitive(true), provider["require_parameters"])
        assertEquals(options["max_price"], provider["max_price"])
        assertEquals(options["sort"], provider["sort"])
        assertEquals(options["ignore"], provider["ignore"])
        assertFalse(wire.containsKey("models") || wire.containsKey("tools") || wire.containsKey("preset") || wire.containsKey("debug") || wire.containsKey("store"))
        val plugins = requireNotNull(wire["plugins"]) as JsonArray
        val ids = plugins.map { it.jsonObject["id"] }.toSet()
        val documented = setOf("alignment", "armor", "auto-beta-router", "auto-router", "context-compression", "file-parser", "fusion", "pareto-router", "response-healing", "web").map(::JsonPrimitive).toSet()
        assertEquals(documented, ids)
        assertEquals(documented.size, plugins.size)
        plugins.forEach { plugin ->
            assertEquals(setOf("id", "enabled"), plugin.jsonObject.keys)
            assertEquals(JsonPrimitive(false), plugin.jsonObject["enabled"])
        }
    }

    @Test
    fun openAiPrivateWireRetainsItsDocumentedStorageOptOut() {
        val route = VerifiedPrivateRoute("https://api.openai.com/v1/chat/completions", ClientType.OPENAI)
        val wire = PrivateInferenceSession.wireBody(route, "concrete-model", JsonArray(emptyList()), 128)
        assertEquals(JsonPrimitive(false), wire["store"])
        assertEquals(JsonPrimitive(128), wire["max_completion_tokens"])
        assertFalse(wire.containsKey("provider") || wire.containsKey("plugins"))
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
