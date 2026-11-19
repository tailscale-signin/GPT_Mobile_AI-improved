package dev.chungjungsoo.gptmobile.data.privacy

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.openrouter.account.OpenRouterIdentity
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.IOException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Request
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenRouterPrivacySetupTest {
    @Test
    fun existingStrictPolicyAndWorkspacePatchCompletePersonalSetupWithoutSignedEvidence() = runBlocking {
        val fixture = Fixture()
        fixture.enableWorkspaceLogging()
        val setup = fixture.setup()

        val preview = setup.inspect(fixture.profile, MANAGEMENT)
        assertTrue(preview.guardrailReady)
        assertFalse(preview.policyReviewRequired)
        assertFalse(preview.promptLoggingOff)
        assertTrue(fixture.writes.isEmpty())
        assertFalse(fixture.requests.any { it.url.encodedPath == "/api/v1/$POLICY_PATH" })
        setup.apply(fixture.profile, preview, pluginsConfirmed = true)

        assertEquals(listOf("PATCH"), fixture.writes.map { it.method })
        assertEquals(OpenRouterWorkspacePrivacy.strictPatch, fixture.writes.single().jsonBody())
        val proof = fixture.saved.single()
        assertEquals("openrouter-personal-v1", proof.scope)
        assertEquals(fixture.hash, proof.providerKeyHash)
        assertEquals(fixture.hash, proof.credentialSha256)
        assertEquals(WORKSPACE, proof.personalWorkspaceId)
        assertEquals(POLICY, proof.modelAvailabilityPolicyId)
        assertEquals(fixture.clock + 86_400_000, proof.expiresAtMillis)
        assertNull(fixture.pending)
        verify(exactly = 0) { fixture.authority.install(any()) }
        verify(exactly = 0) { fixture.authority.trustIssuer(any()) }
    }

    @Test
    fun onlyTheExactKeyWorkspaceAndExplicitCreatorStateCanAuthorizeMutation() = runBlocking {
        listOf("hash", "workspace", "missing-creator", "malformed-creator").forEach { mismatch ->
            val unknownCreator = mismatch.endsWith("creator")
            val fixture = Fixture(directPolicy = !unknownCreator)
            if (mismatch == "hash") fixture.keyData = JsonObject(fixture.keyData + ("hash" to JsonPrimitive("b".repeat(64))))
            if (mismatch == "workspace") fixture.workspaceData = JsonObject(fixture.workspaceData + ("id" to JsonPrimitive(OTHER_WORKSPACE)))
            if (mismatch == "missing-creator") fixture.keyData = JsonObject(fixture.keyData - "creator_user_id")
            if (mismatch == "malformed-creator") fixture.keyData = JsonObject(fixture.keyData + ("creator_user_id" to JsonPrimitive(false)))

            expectSetupFailure { fixture.setup().inspect(fixture.profile, MANAGEMENT) }

            assertTrue(mismatch, fixture.writes.isEmpty())
            assertTrue(fixture.saved.isEmpty())
        }
        val noCreator = Fixture(directPolicy = false)
        val setup = noCreator.setup()
        val preview = setup.inspect(noCreator.profile, MANAGEMENT)
        assertFalse(preview.guardrailReady)
        assertFalse(preview.policyReviewRequired)
        setup.apply(noCreator.profile, preview, pluginsConfirmed = true)
        assertEquals(listOf("POST", "PUT"), noCreator.writes.map { it.method })
        assertEquals(1, noCreator.saved.size)
    }

    @Test
    fun pluginConfirmationAndCurrentPersonalAuthorityAreRequiredBeforeChanges() = runBlocking {
        val fixture = Fixture()
        val setup = fixture.setup()
        val preview = setup.inspect(fixture.profile, MANAGEMENT)
        expectSetupFailure { setup.apply(fixture.profile, preview, pluginsConfirmed = false) }
        assertTrue(fixture.writes.isEmpty())
        assertTrue(fixture.saved.isEmpty())

        fixture.personalAllowed = false
        val observedRequests = fixture.requests.size
        expectSetupFailure { fixture.setup().inspect(fixture.profile, MANAGEMENT) }
        assertEquals(observedRequests, fixture.requests.size)
        assertTrue(fixture.saved.isEmpty())
    }

    @Test
    fun absentNullOrStringWorkspaceBooleansNeverCountAsOff() = runBlocking {
        OpenRouterWorkspacePrivacy.strictPatch.keys.forEach { field ->
            listOf(null, JsonNull, JsonPrimitive("false")).forEach { malformed ->
                val fixture = Fixture()
                fixture.workspaceData = JsonObject(if (malformed == null) fixture.workspaceData - field else fixture.workspaceData + (field to malformed))

                expectSetupFailure { fixture.setup().inspect(fixture.profile, MANAGEMENT) }

                assertTrue("$field=$malformed", fixture.writes.isEmpty())
                assertTrue(fixture.saved.isEmpty())
            }
        }
    }

    @Test
    fun nonstrictDirectOrInheritedRestrictionsRequireReviewWithoutPolicyReplacement() = runBlocking {
        val source = Json.parseToJsonElement(
            """{
              "allowed_models": ["allowed/model"],
              "ignored_models": ["blocked/model"],
              "allowed_providers": ["approved"],
              "allowed_data_regions": ["europe"],
              "enable_paid_model_training": false,
              "future_restriction": {"mode": "deny"},
              "allow_safety_retention_google": true
            }"""
        ) as JsonObject
        listOf(true, false).forEach { direct ->
            val fixture = Fixture(directPolicy = direct)
            fixture.keyData = JsonObject(fixture.keyData + ("creator_user_id" to JsonPrimitive("user_creator")))
            if (!direct) fixture.memberAssignment = assignment(POLICY)
            fixture.policies[POLICY] = fixture.policy(POLICY, source, direct = direct)
            val beforePolicy = fixture.policies.getValue(POLICY)
            val beforeKey = fixture.keyAssignment
            val beforeMember = fixture.memberAssignment
            val setup = fixture.setup()

            val preview = setup.inspect(fixture.profile, MANAGEMENT)
            assertFalse(preview.guardrailReady)
            assertTrue(preview.policyReviewRequired)
            val error = expectSetupFailure { setup.apply(fixture.profile, preview, pluginsConfirmed = true) }

            assertTrue(error.userMessage.contains("OpenRouter"))
            assertTrue(fixture.writes.isEmpty())
            assertEquals(beforePolicy, fixture.policies.getValue(POLICY))
            assertEquals(beforeKey, fixture.keyAssignment)
            assertEquals(beforeMember, fixture.memberAssignment)
            assertTrue(fixture.saved.isEmpty())
        }
    }

    @Test
    fun explicitNullCreatorCannotReplaceAnExistingWorkspaceDefault() = runBlocking {
        val fixture = Fixture(directPolicy = false)
        fixture.keyData = JsonObject(fixture.keyData + ("creator_user_id" to JsonNull))
        fixture.keyAssignment = JsonObject(assignment() + ("workspace_default_policy_ids" to JsonArray(listOf(JsonPrimitive(POLICY)))))
        val restrictions = JsonObject(mapOf("ignored_models" to JsonArray(listOf(JsonPrimitive("blocked/model")))))
        fixture.policies[POLICY] = fixture.policy(POLICY, restrictions, direct = false)
        val setup = fixture.setup()

        val preview = setup.inspect(fixture.profile, MANAGEMENT)
        assertFalse(preview.guardrailReady)
        assertTrue(preview.policyReviewRequired)
        expectSetupFailure { setup.apply(fixture.profile, preview, pluginsConfirmed = true) }

        assertTrue(fixture.writes.isEmpty())
        assertEquals(restrictions, fixture.policies.getValue(POLICY)["config"])
        assertFalse(fixture.requests.any { "/assignments/members/" in it.url.encodedPath })
        assertTrue(fixture.saved.isEmpty())
    }

    @Test
    fun strictInheritedPoliciesAreReusedWithoutChangingAssignments() = runBlocking {
        listOf("member", "default").forEach { inheritance ->
            val fixture = Fixture(directPolicy = false)
            val config = JsonObject(OpenRouterPrivacyPolicy.strictFields + ("ignored_models" to JsonArray(listOf(JsonPrimitive("blocked/model")))))
            fixture.policies[POLICY] = fixture.policy(POLICY, config, direct = false)
            if (inheritance == "member") {
                fixture.keyData = JsonObject(fixture.keyData + ("creator_user_id" to JsonPrimitive("user_creator")))
                fixture.memberAssignment = assignment(POLICY)
            } else {
                fixture.keyAssignment = JsonObject(assignment() + ("workspace_default_policy_ids" to JsonArray(listOf(JsonPrimitive(POLICY)))))
                fixture.policies[POLICY] = JsonObject(fixture.policies.getValue(POLICY) + ("is_workspace_default" to JsonPrimitive(true)))
            }
            val beforeKey = fixture.keyAssignment
            val beforeMember = fixture.memberAssignment
            val setup = fixture.setup()

            val preview = setup.inspect(fixture.profile, MANAGEMENT)
            assertTrue(inheritance, preview.guardrailReady)
            assertFalse(inheritance, preview.policyReviewRequired)
            assertFalse(fixture.requests.any { it.url.encodedPath == "/api/v1/$POLICY_PATH" })
            setup.apply(fixture.profile, preview, pluginsConfirmed = true)

            assertTrue(inheritance, fixture.writes.isEmpty())
            assertEquals(beforeKey, fixture.keyAssignment)
            assertEquals(beforeMember, fixture.memberAssignment)
            val proof = fixture.saved.single()
            assertEquals(POLICY, proof.modelAvailabilityPolicyId)
            assertTrue(setup.verifyPersonal(fixture.profile, proof))
            if (inheritance == "member") fixture.memberAssignment = assignment() else fixture.keyAssignment = assignment()
            assertFalse(setup.verifyPersonal(fixture.profile, proof))
        }
    }

    @Test
    fun assignmentConflictDoesNotBlindlyRetryOrSaveAnApproval() = runBlocking {
        val fixture = Fixture(directPolicy = false)
        fixture.beforeCommitFailure = { request ->
            if (request.method == "PUT") OpenRouterPrivacySetup.SetupException("Assignment changed", rejected = true) else null
        }
        val setup = fixture.setup()

        expectSetupFailure { setup.apply(fixture.profile, setup.inspect(fixture.profile, MANAGEMENT), pluginsConfirmed = true) }

        assertEquals(listOf("POST", "PUT"), fixture.writes.map { it.method })
        assertTrue(fixture.saved.isEmpty())
        assertNull(fixture.pending)
        assertEquals(assignment(), fixture.keyAssignment)
    }

    @Test
    fun unresolvedPatchCreateAndAssignmentSurviveRecreationWithoutDuplicateWrites() = runBlocking {
        listOf("PATCH", "POST", "PUT").forEach { failedMethod ->
            val fixture = Fixture(directPolicy = failedMethod == "PATCH")
            if (failedMethod == "PATCH") fixture.enableWorkspaceLogging()
            fixture.beforeCommitFailure = { if (it.method == failedMethod) IOException("Lost response") else null }
            val setup = fixture.setup()
            expectSetupFailure { setup.apply(fixture.profile, setup.inspect(fixture.profile, MANAGEMENT), pluginsConfirmed = true) }
            val pending = fixture.pending
            assertNotNull(failedMethod, pending)
            val firstWrites = fixture.writes.size

            val recreated = fixture.setup()
            expectSetupFailure { recreated.apply(fixture.profile, recreated.inspect(fixture.profile, MANAGEMENT), pluginsConfirmed = true) }

            assertEquals(failedMethod, pending, fixture.pending)
            assertEquals(failedMethod, firstWrites, fixture.writes.size)
            assertEquals(failedMethod, 1, fixture.writes.count { it.method == failedMethod })
            assertTrue(fixture.saved.isEmpty())
        }
    }

    @Test
    fun committedWritesWithLostResponsesAreReconciledInsteadOfRepeated() = runBlocking {
        listOf("PATCH", "POST", "PUT").forEach { failedMethod ->
            val fixture = Fixture(directPolicy = failedMethod == "PATCH")
            if (failedMethod == "PATCH") fixture.enableWorkspaceLogging()
            var lost = false
            fixture.afterCommitFailure = { request ->
                if (!lost && request.method == failedMethod) {
                    lost = true
                    IOException("Committed response was lost")
                } else {
                    null
                }
            }
            val setup = fixture.setup()
            expectSetupFailure { setup.apply(fixture.profile, setup.inspect(fixture.profile, MANAGEMENT), pluginsConfirmed = true) }
            assertNotNull(fixture.pending)

            val recreated = fixture.setup()
            recreated.apply(fixture.profile, recreated.inspect(fixture.profile, MANAGEMENT), pluginsConfirmed = true)

            assertEquals(failedMethod, 1, fixture.writes.count { it.method == failedMethod })
            assertNull(failedMethod, fixture.pending)
            assertEquals(failedMethod, 1, fixture.saved.size)
        }
    }

    @Test
    fun foreignOrUnsupportedPendingReceiptsCannotBeClearedByMatchingCurrentSettings() = runBlocking {
        mapOf("version" to JsonPrimitive(2), "origin" to JsonPrimitive("https://unrelated.example/api/v1")).forEach { (field, invalid) ->
            val fixture = Fixture()
            fixture.pending = JsonObject(
                mapOf(
                    "version" to JsonPrimitive(1),
                    "origin" to JsonPrimitive(OpenRouterPrivacySetup.ORIGIN),
                    "kind" to JsonPrimitive("workspace"),
                    "key_hash" to JsonPrimitive(fixture.hash),
                    "workspace_id" to JsonPrimitive(WORKSPACE),
                    "body" to OpenRouterWorkspacePrivacy.strictPatch
                ) + (field to invalid)
            ).toString()
            val pending = fixture.pending
            val setup = fixture.setup()

            expectSetupFailure { setup.apply(fixture.profile, setup.inspect(fixture.profile, MANAGEMENT), pluginsConfirmed = true) }

            assertEquals(pending, fixture.pending)
            assertTrue(fixture.writes.isEmpty())
            assertTrue(fixture.saved.isEmpty())
        }
    }

    @Test
    fun discardedExpiredOrCredentialChangedPreviewsCannotWrite() = runBlocking {
        listOf("discarded", "expired", "credential", "workspace").forEach { change ->
            val fixture = Fixture()
            fixture.enableWorkspaceLogging()
            val setup = fixture.setup()
            val preview = setup.inspect(fixture.profile, MANAGEMENT)
            if (change == "discarded") setup.discard(preview)
            if (change == "expired") fixture.clock += 600_000
            if (change == "workspace") fixture.keyData = JsonObject(fixture.keyData + ("workspace_id" to JsonPrimitive(OTHER_WORKSPACE)))
            val selected = if (change == "credential") fixture.profile.copy(token = "replacement-credential") else fixture.profile

            expectSetupFailure { setup.apply(selected, preview, pluginsConfirmed = true) }

            assertTrue(change, fixture.writes.isEmpty())
            assertTrue(fixture.saved.isEmpty())
        }
    }

    @Test
    fun revocationAtTheNetworkGuardStopsPreparedMutationAndClearsUnsentIntent() = runBlocking {
        val fixture = Fixture()
        fixture.enableWorkspaceLogging()
        val setup = fixture.setup()
        val preview = setup.inspect(fixture.profile, MANAGEMENT)
        fixture.beforeDispatch = { if (it.method != "GET") fixture.personalAllowed = false }

        expectSetupFailure { setup.apply(fixture.profile, preview, pluginsConfirmed = true) }

        assertTrue(fixture.writes.isEmpty())
        assertTrue(fixture.saved.isEmpty())
        assertNull(fixture.pending)
    }

    @Test
    fun savedPersonalApprovalStillRequiresFreshWorkspaceAndPolicyEvidence() = runBlocking {
        val fixture = Fixture()
        val setup = fixture.setup()
        setup.apply(fixture.profile, setup.inspect(fixture.profile, MANAGEMENT), pluginsConfirmed = true)
        val proof = fixture.saved.single()
        assertTrue(setup.verifyPersonal(fixture.profile, proof))

        fixture.enableWorkspaceLogging()
        assertFalse(setup.verifyPersonal(fixture.profile, proof))
        fixture.workspaceData = JsonObject(fixture.workspaceData + OpenRouterWorkspacePrivacy.strictPatch)
        fixture.policies[POLICY] = fixture.policy(POLICY, JsonObject(OpenRouterPrivacyPolicy.strictFields - "allow_safety_retention_other"))
        assertFalse(setup.verifyPersonal(fixture.profile, proof))
    }

    private class Fixture(directPolicy: Boolean = true) {
        var clock = 1_800_000_000_000L
        val profile = PlatformV2(
            uid = "profile-under-test",
            name = "Personal OpenRouter",
            compatibleType = ClientType.OPENROUTER,
            apiUrl = "https://openrouter.ai/api/v1",
            model = "allowed/model",
            token = "test-inference-credential"
        )
        val hash = OpenRouterIdentity.digest(profile.token.orEmpty())
        val authority = mockk<ProviderApprovalSource>()
        var personalAllowed = true
        var pending: String? = null
        val saved = mutableListOf<ProviderApproval>()
        val requests = mutableListOf<Request>()
        val writes get() = requests.filter { it.method != "GET" }
        var beforeDispatch: (Request) -> Unit = {}
        var beforeCommitFailure: (Request) -> Exception? = { null }
        var afterCommitFailure: (Request) -> Exception? = { null }
        var keyData = JsonObject(
            mapOf(
                "hash" to JsonPrimitive(hash),
                "disabled" to JsonPrimitive(false),
                "expires_at" to JsonNull,
                "workspace_id" to JsonPrimitive(WORKSPACE),
                "creator_user_id" to JsonNull
            )
        )
        var workspaceData = JsonObject(
            OpenRouterWorkspacePrivacy.strictPatch + mapOf("id" to JsonPrimitive(WORKSPACE), "name" to JsonPrimitive("Personal workspace"))
        )
        val policies = linkedMapOf<String, JsonObject>()
        var keyAssignment = if (directPolicy) assignment(POLICY) else assignment()
        var memberAssignment = assignment()

        init {
            if (directPolicy) policies[POLICY] = policy(POLICY, OpenRouterPrivacyPolicy.strictFields)
            every { authority.personalRevision() } returns REVISION
            every { authority.personalAllowed(any()) } answers { personalAllowed && firstArg<String>() == REVISION }
            every { authority.records() } answers { saved.toList() }
            coEvery { authority.managementKey() } returns MANAGEMENT
            every { authority.pendingSetupMutation() } answers { pending }
            every { authority.beginSetupMutation(any()) } answers {
                if (pending != null) {
                    false
                } else {
                    pending = firstArg()
                    true
                }
            }
            every { authority.confirmSetupMutation(any()) } answers {
                if (pending == firstArg<String>()) pending = null
            }
            coEvery { authority.savePersonal(any(), any(), any()) } answers {
                assertEquals(MANAGEMENT, secondArg<String>())
                assertEquals(REVISION, thirdArg<String>())
                check(personalAllowed)
                saved += firstArg<ProviderApproval>()
            }
        }

        fun setup() = OpenRouterPrivacySetup(authority, ::transport, { "c".repeat(64) }, { clock })

        fun enableWorkspaceLogging() {
            workspaceData = JsonObject(workspaceData + ("is_observability_io_logging_enabled" to JsonPrimitive(true)))
        }

        fun policy(id: String, config: JsonObject, direct: Boolean = true, name: String = "Existing model policy") = JsonObject(
            mapOf(
                "id" to JsonPrimitive(id),
                "name" to JsonPrimitive(name),
                "policy_type" to JsonPrimitive("model_availability"),
                "config" to config,
                "is_workspace_default" to JsonPrimitive(false),
                "api_key_hashes" to JsonArray(if (direct) listOf(JsonPrimitive(hash)) else emptyList()),
                "member_ids" to JsonArray(emptyList()),
                "opted_out_api_key_hashes" to JsonArray(emptyList()),
                "overridden_api_key_hashes" to JsonArray(emptyList())
            )
        )

        private suspend fun transport(request: Request, authorized: () -> Unit): JsonObject {
            beforeDispatch(request)
            authorized()
            assertEquals("openrouter.ai", request.url.host)
            assertEquals("Bearer $MANAGEMENT", request.header("Authorization"))
            requests += request
            val path = request.url.encodedPath.removePrefix("/api/v1/")
            if (request.method == "GET") {
                return when {
                    path == "keys/$hash" -> envelope(keyData)
                    path == "workspaces/$WORKSPACE" -> envelope(workspaceData)
                    path == POLICY_PATH -> JsonObject(mapOf("data" to JsonArray(policies.values.toList()), "total_count" to JsonPrimitive(policies.size)))
                    path.startsWith("$POLICY_PATH/") -> envelope(policies.getValue(path.substringAfterLast('/')))
                    path == "guardrails/policies/assignments/api-keys/$hash" -> envelope(keyAssignment)
                    path == "guardrails/policies/assignments/members/user_creator" -> envelope(memberAssignment)
                    else -> error("Unexpected request ${request.method} $path")
                }
            }
            beforeCommitFailure(request)?.let { throw it }
            val body = request.jsonBody()
            val result = when {
                request.method == "PATCH" && path == "workspaces/$WORKSPACE" -> {
                    workspaceData = JsonObject(workspaceData + body)
                    envelope(workspaceData)
                }
                request.method == "POST" && path == POLICY_PATH -> {
                    check(NEW_POLICY !in policies)
                    policies[NEW_POLICY] = policy(NEW_POLICY, body.getValue("config") as JsonObject, direct = false, name = (body.getValue("name") as JsonPrimitive).content)
                    envelope(policies.getValue(NEW_POLICY))
                }
                request.method == "PUT" && path == "guardrails/policies/assignments/api-keys/$hash" -> {
                    if (body["expected_assignment"] != keyAssignment) throw OpenRouterPrivacySetup.SetupException("Assignment changed", rejected = true)
                    val id = (body.getValue("policy_id") as JsonPrimitive).content
                    keyAssignment = assignment(id)
                    policies[id] = JsonObject(policies.getValue(id) + ("api_key_hashes" to JsonArray(listOf(JsonPrimitive(hash)))))
                    JsonObject(mapOf("data" to JsonPrimitive(true)))
                }
                else -> error("Unexpected mutation ${request.method} $path")
            }
            afterCommitFailure(request)?.let { throw it }
            return result
        }
    }

    companion object {
        private const val MANAGEMENT = "test-management-credential"
        private const val WORKSPACE = "11111111-1111-1111-1111-111111111111"
        private const val POLICY = "22222222-2222-2222-2222-222222222222"
        private const val NEW_POLICY = "33333333-3333-3333-3333-333333333333"
        private const val OTHER_WORKSPACE = "44444444-4444-4444-4444-444444444444"
        private const val REVISION = "55555555-5555-5555-5555-555555555555"
        private const val POLICY_PATH = "guardrails/policies/model-availability"

        private fun envelope(data: JsonObject) = JsonObject(mapOf("data" to data))

        private fun assignment(policy: String? = null) = JsonObject(
            mapOf(
                "opted_out" to JsonPrimitive(false),
                "policy_ids" to JsonArray(listOfNotNull(policy?.let(::JsonPrimitive))),
                "workspace_default_policy_ids" to JsonArray(emptyList())
            )
        )

        private fun Request.jsonBody(): JsonObject {
            val buffer = Buffer()
            requireNotNull(body).writeTo(buffer)
            return Json.parseToJsonElement(buffer.readUtf8()) as JsonObject
        }

        private suspend fun expectSetupFailure(block: suspend () -> Unit): OpenRouterPrivacySetup.SetupException {
            try {
                block()
            } catch (error: OpenRouterPrivacySetup.SetupException) {
                return error
            }
            throw AssertionError("Expected privacy setup to fail closed")
        }
    }
}
