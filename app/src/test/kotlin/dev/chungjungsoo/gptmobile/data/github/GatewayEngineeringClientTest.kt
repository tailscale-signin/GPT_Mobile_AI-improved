package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewaySurfacePolicy
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayEngineeringClientTest {
    @Test fun invalidInputGuardIsNotMisreportedAsAChangedConversation() = runTest {
        http(
            MockEngine {
                respond("""{"error":{"code":"engineering_state_or_input_changed","reasonCode":"ENGINEERING_BRANCH_INVALID","invalid_fields":["branch","private-field"],"message":"private exception"}}""", HttpStatusCode.Conflict)
            }
        ).use { http ->
            val request = GatewayEngineeringRequest("owner/repo", "feature", "main", "Inspect repository", "local", chatId = 1, requestId = "command")
            val rejected = runCatching { GatewayEngineeringClient(http).preflight(config, request) }.exceptionOrNull() as GatewayEngineeringInputRejected
            assertTrue(rejected.message.orEmpty().contains("branch"))
            assertFalse(rejected.message.orEmpty().contains("conversation or job changed"))
            val failure = requireNotNull(dev.chungjungsoo.gptmobile.data.agent.AgentFailureEvidence.exception(rejected))
            assertEquals("ENGINEERING_BRANCH_INVALID", failure.failureDetails?.get("reasonCode")?.jsonPrimitive?.content)
            assertFalse(failure.failureDetails.toString().contains("private"))
            assertEquals(rejected.diagnosticDetails, rejected.withCommandOutcomeUnknown(true).diagnosticDetails)
        }
    }

    @Test fun selectionRejectionRetainsSafeMissingFieldEvidenceThroughFailureDelivery() = runTest {
        val selection = GatewayExecutionSelection("profile", "revision", "provider", "provider-revision", "LLAMA", config.apiUrl, "selected", kotlinx.serialization.json.JsonObject(emptyMap()), "PRIVATE")
        http(
            MockEngine {
                respond("""{"error":{"code":"EXECUTION_SELECTION_INVALID","failed_stage":"provider_admission","inference_attempted":false,"reasonCode":"SELECTION_SCHEMA_INVALID","missing_fields":["tool_owner","required_capability_policy"],"invalid_fields":["private-secret"],"unknown_field_count":1,"message":"private request"}}""", HttpStatusCode.Conflict)
            }
        ).use { http ->
            val rejected = runCatching { GatewayEngineeringClient(http).prepareBinding(config, selection) }.exceptionOrNull() as GatewayExecutionRejected
            val failure = requireNotNull(dev.chungjungsoo.gptmobile.data.agent.AgentFailureEvidence.exception(rejected))
            val details = requireNotNull(failure.failureDetails)
            assertEquals("SELECTION_SCHEMA_INVALID", details.getValue("reasonCode").jsonPrimitive.content)
            assertEquals(Json.parseToJsonElement("""["tool_owner","required_capability_policy"]"""), details["missing_fields"])
            assertFalse(details.containsKey("invalid_fields"))
            assertFalse(details.toString().contains("private"))
            assertEquals(kotlinx.serialization.json.JsonPrimitive(false), details["dispatched"])
            assertEquals(rejected.diagnosticDetails, rejected.withCommandOutcomeUnknown(true).diagnosticDetails)
        }
    }

    @Test fun bindingRequestIncludesDefaultedAuthorityFieldsRequiredByTheGateway() = runTest {
        val selection = GatewayExecutionSelection("profile", "revision", "connection", "connection-revision", "LLAMA", "http://localhost:8080/v1", "selected-model", kotlinx.serialization.json.JsonObject(emptyMap()), "private-chat")
        var calls = 0
        http(
            MockEngine { request ->
                calls++
                assertTrue(request.url.encodedPath.endsWith("/engineering/bindings"))
                val sent = Json.parseToJsonElement((request.body as TextContent).text).jsonObject.getValue("selection").jsonObject
                assertEquals("GATEWAY", sent.getValue("tool_owner").jsonPrimitive.content)
                assertEquals(Json.parseToJsonElement("""["SELECTED_ONLY","MODEL_IDENTITY","CONTEXT_BUDGET"]"""), sent["required_capability_policy"])
                val required = setOf("profile_uid", "profile_revision", "provider_connection_id", "provider_connection_revision", "provider_kind", "configured_endpoint", "requested_model_id", "generation_parameters", "privacy_scope", "tool_owner", "required_capability_policy")
                assertTrue(sent.keys.containsAll(required))
                respond("""{"binding":{"binding_id":"bind_${"a".repeat(32)}","routing_policy":"SELECTED_ONLY","profile_uid":"profile","profile_revision":"revision","provider_connection_id":"connection","provider_connection_revision":"connection-revision","provider_kind":"LLAMA","requested_model_id":"selected-model","privacy_scope":"private-chat","tool_owner":"GATEWAY","configured_endpoint_identity":"${selectionDigest(kotlinx.serialization.json.JsonPrimitive(selection.configuredEndpoint))}","generation_parameters":{}},"capabilities":{"requested_model_id":"selected-model","reported_model_id":"selected-model"}}""")
            }
        ).use { http ->
            assertEquals("bind_" + "a".repeat(32), GatewayEngineeringClient(http).prepareBinding(config, selection).bindingId)
        }
        assertEquals(1, calls)
    }

    @Test fun explicitHostRepairCheckRefreshesTheExactGatewayWithoutSubmittingAJob() = runTest {
        val selected = ProviderRequestConfig("http://127.0.0.1:29980/v1", "host-access")
        var reads = 0
        var repaired = false
        http(
            MockEngine { request ->
                reads++
                assertEquals(io.ktor.http.HttpMethod.Get, request.method)
                assertEquals("http://127.0.0.1:29980/v1/gateway/engineering/capabilities", request.url.toString())
                assertEquals("Bearer host-access", request.headers["Authorization"])
                val connection = if (repaired) "configured" else "missing"
                respond("""{"service":"engineering-gateway","protocol_version":1,"enabled":$repaired,"principal_id":"same-owner","readiness":{"schema_version":1,"ready":$repaired,"checks":{"journal":"ready","host_github_connection":"$connection","worker":"running"}}}""")
            }
        ).use { http ->
            val client = GatewayEngineeringClient(http)
            assertEquals(GatewayAdmissionReason.ENGINEERING_GITHUB_REQUIRED, client.capabilities(selected).jobReadinessFailure())
            repaired = true
            assertEquals(GatewayAdmissionReason.ENGINEERING_GITHUB_REQUIRED, client.capabilities(selected).jobReadinessFailure())
            assertEquals(1, reads)
            val ready = client.refreshCapabilities(selected)
            assertNull(ready.jobReadinessFailure())
            assertEquals("same-owner", client.principal(selected))
            assertEquals(2, reads)
        }
    }

    @Test fun preflightReturnsProducibleAcceptanceBeforeSubmittingAnyJob() = runTest {
        val objective = "Repair build"
        val sha = "a".repeat(40)
        val requirement = GatewayEngineeringAcceptance("build", listOf("file"), listOf("tests"), listOf(GatewayRequirementEvidence("behavior_test", "Fixture.build")))
        val result = GatewayEngineeringPreflight("github.engineering-admission.v1", true, listOf(requirement), objectiveSha256 = GitHubRepositoryStateStore.digest(objective), sourceSha = sha)
        var calls = 0
        http(
            MockEngine { request ->
                calls++
                assertTrue(request.url.encodedPath.endsWith("/engineering/preflight"))
                assertEquals(io.ktor.http.HttpMethod.Post, request.method)
                respond(Json.encodeToString(result))
            }
        ).use { http ->
            val request = GatewayEngineeringRequest("owner/repo", "feature", "main", objective, "local", chatId = 1, requestId = "command", sourceSha = sha)
            assertEquals(listOf(requirement), GatewayEngineeringClient(http).preflight(config, request).acceptance)
            assertEquals(1, calls)
        }
    }

    @Test fun preAdmissionEofIsTypedAsUnsentWhileJobPostEofRemainsAmbiguousAndIsNeverRetried() = runTest {
        val paths = mutableListOf<String>()
        val bodies = mutableListOf<String>()
        var idleConnectionEvictions = 0
        http(
            MockEngine { request ->
                assertEquals(io.ktor.http.HttpMethod.Post, request.method)
                paths += request.url.encodedPath
                bodies += (request.body as TextContent).text
                throw java.io.EOFException("Private socket details")
            }
        ).use { http ->
            val client = GatewayEngineeringClient(http, evictIdleConnections = { idleConnectionEvictions++ })
            val request = GatewayEngineeringRequest("owner/repo", "feature", "main", "Inspect", "local", chatId = 1, requestId = "original")
            val preflight = runCatching { client.preflight(config, request) }.exceptionOrNull()
            assertTrue(preflight is GatewayAdmissionException)
            assertEquals(GatewayAdmissionReason.TRANSPORT_INTERRUPTED, (preflight as GatewayAdmissionException).reason)
            assertEquals(GatewayAdmissionDispatchState.NOT_DISPATCHED, preflight.dispatchState)
            assertNull(preflight.httpStatus)
            assertFalse(preflight.message.orEmpty().contains("Private socket"))
            assertEquals(listOf("/v1/gateway/engineering/preflight", "/v1/gateway/engineering/preflight"), paths)
            assertEquals(1, idleConnectionEvictions)
            val create = runCatching { client.create(config, request) }.exceptionOrNull()
            assertTrue(create is java.io.EOFException)
            assertEquals(listOf("/v1/gateway/engineering/preflight", "/v1/gateway/engineering/preflight", "/v1/gateway/engineering/jobs"), paths)
            assertEquals(1, paths.count { it == "/v1/gateway/engineering/jobs" })
            assertEquals(1, idleConnectionEvictions)
            assertEquals(1, bodies.distinct().size)
            assertEquals("original", Json.parseToJsonElement(bodies.last()).jsonObject.getValue("request_id").jsonPrimitive.content)
        }
    }

    @Test fun missingProducerHasAnAdmissionReasonAndCannotBecomeGenericPartialWork() = runTest {
        val objective = "Repair build"
        val result = GatewayEngineeringPreflight("github.engineering-admission.v1", false, blockers = listOf(GatewayEngineeringBlocker("acceptance", "ACCEPTANCE_PRODUCER_REQUIRED", "admission", "Configure the build behavior test", "CONFIGURE_ACCEPTANCE_MAPPING")), objectiveSha256 = GitHubRepositoryStateStore.digest(objective))
        http(MockEngine { respond(Json.encodeToString(result)) }).use { http ->
            val failure = runCatching { GatewayEngineeringClient(http).preflight(config, GatewayEngineeringRequest("owner/repo", "feature", "main", objective, "local", chatId = 1, requestId = "command")) }.exceptionOrNull()
            assertTrue(failure is GatewayEngineeringRequestRejected)
            assertTrue(failure?.message.orEmpty().contains("ACCEPTANCE_PRODUCER_REQUIRED"))
            assertTrue(failure?.message.orEmpty().contains("CONFIGURE_ACCEPTANCE_MAPPING"))
        }
    }

    @Test fun preflightForAnotherObjectiveCannotAuthorizeThisTask() = runTest {
        val result = GatewayEngineeringPreflight("github.engineering-admission.v1", true, objectiveSha256 = "b".repeat(64))
        http(MockEngine { respond(Json.encodeToString(result)) }).use { http ->
            val failure = runCatching { GatewayEngineeringClient(http).preflight(config, GatewayEngineeringRequest("owner/repo", "feature", "main", "Repair build", "local", chatId = 1, requestId = "command")) }.exceptionOrNull()
            assertTrue(failure?.message.orEmpty().contains("does not match"))
        }
    }

    private val config = ProviderRequestConfig("https://gateway.test/v1", "test-token")
    private val job = GatewayEngineeringJob("eng_" + "a".repeat(32), "owner/repo", "feature/job", state = "MERGE_READY", revision = 12, createdAt = 1.0, deadline = 99999.0, message = "Review", pr = 7, headSha = "b".repeat(40), mergeReady = true)
    private fun http(engine: MockEngine) = HttpClient(engine) {
        install(HttpTimeout)
        followRedirects = false
    }

    @Test fun engineeringDiscoveryUsesItsOwnAuthenticatedRouteAndCanRefreshNegativeEvidence() = runTest {
        val direct = config.copy(apiUrl = "https://confirmed-direct.example/v1")
        GatewaySurfacePolicy.shared.observe(GatewaySurfacePolicy.identity(direct), GatewaySurfacePolicy.Surface.CAPABILITIES, 404)
        var calls = 0
        http(
            MockEngine {
                calls++
                if (calls == 1) {
                    respond("", HttpStatusCode.NotFound)
                } else {
                    respond("""{"service":"engineering-gateway","protocol_version":1,"enabled":true,"principal_id":"new-account"}""")
                }
            }
        ).use { http ->
            val client = GatewayEngineeringClient(http)
            assertTrue(runCatching { client.capabilities(direct) }.exceptionOrNull() is GatewayGraphUnavailableException)
            assertTrue(runCatching { client.capabilities(direct) }.exceptionOrNull() is GatewayGraphUnavailableException)
            assertEquals(1, calls)
            assertEquals("new-account", client.capabilities(direct, forceRefresh = true).principalId)
            assertEquals("new-account", client.principal(direct.copy(token = "new-token")))
            assertEquals(3, calls)
        }
    }

    @Test fun directNetworkIpAndHostnameDoNotRequireCredentials() = runTest {
        for (host in listOf("192.168.1.100", "my-pc.local", "[fd00::10]")) {
            val direct = ProviderRequestConfig("http://$host:8090/v1", null)
            http(
                MockEngine { request ->
                    assertEquals("http://$host:8090/v1/gateway/engineering/capabilities", request.url.toString())
                    assertNull(request.headers["Authorization"])
                    respond("""{"service":"engineering-gateway","protocol_version":1,"enabled":true,"principal_id":"network:shared"}""", HttpStatusCode.OK)
                }
            ).use { http ->
                assertEquals("network:shared", GatewayEngineeringClient(http).principal(direct))
            }
        }
    }

    @Test fun unsupportedInferenceEndpointIsCachedSeparatelyFromAnotherGatewayIdentity() = runTest {
        var calls = 0
        http(
            MockEngine {
                calls++
                respond("not an engineering server", HttpStatusCode.NotFound)
            }
        ).use { http ->
            val client = GatewayEngineeringClient(http)
            repeat(3) { assertTrue(runCatching { client.principal(config) }.isFailure) }
            assertEquals(1, calls)
            assertTrue(runCatching { client.principal(config.copy(token = "other-account")) }.isFailure)
            assertEquals(2, calls)
        }
    }

    @Test fun disabledEngineeringCapabilityNeverEstablishesReadiness() = runTest {
        http(MockEngine { respond("""{"service":"engineering-gateway","protocol_version":1,"enabled":false,"principal_id":"owner"}""", HttpStatusCode.OK) }).use { http ->
            assertTrue(runCatching { GatewayEngineeringClient(http).principal(config) }.isFailure)
        }
    }

    @Test fun engineeringReadinessIsCachedPerAuthenticatedRouteAndObservedServerEpoch() = runTest {
        val first = "a".repeat(32)
        val second = "b".repeat(32)
        var epoch = first
        var calls = 0
        dev.chungjungsoo.gptmobile.data.network.gateway.GatewayClientDiagnostics.observeInstance(config, first)
        http(
            MockEngine {
                calls++
                respond("""{"service":"engineering-gateway","protocol_version":1,"enabled":true,"principal_id":"owner"}""", HttpStatusCode.OK, headersOf("X-Gateway-Instance-ID", epoch))
            }
        ).use { http ->
            val client = GatewayEngineeringClient(http)
            repeat(3) { assertEquals("owner", client.principal(config)) }
            assertEquals("owner", client.principal(config.copy(profileUid = "another-profile-on-same-connection")))
            assertEquals(1, calls)
            epoch = second
            dev.chungjungsoo.gptmobile.data.network.gateway.GatewayClientDiagnostics.observeInstance(config, second)
            assertEquals("owner", client.principal(config))
            assertEquals(2, calls)
            assertEquals("owner", client.principal(config.copy(token = "different-principal")))
            assertEquals(3, calls)
            assertEquals("owner", client.principal(config.copy(extraHeaders = mapOf("X-Identity" to "different-proxy-identity"))))
            assertEquals(4, calls)
        }
    }

    @Test fun missingHostGithubConnectionBlocksGraphAndNewWorkButPreservesSavedResultIdentity() = runTest {
        var calls = 0
        http(
            MockEngine {
                calls++
                respond("""{"service":"engineering-gateway","protocol_version":1,"enabled":false,"principal_id":"owner","code_graph":{"schema_version":1,"enabled":false},"readiness":{"schema_version":1,"ready":false,"graph_ready":false,"saved_results":true,"checks":{"journal":"ready","host_github_connection":"missing","worker":"running"}}}""", HttpStatusCode.OK)
            }
        ).use { http ->
            val client = GatewayEngineeringClient(http)
            assertEquals("owner", client.principal(config, requireReady = false))
            assertTrue(runCatching { client.principal(config) }.exceptionOrNull()?.message.orEmpty().contains("GitHub connection"))
            assertTrue(runCatching { client.requireGraphReady(config) }.exceptionOrNull()?.message.orEmpty().contains("GitHub connection"))
            assertEquals(1, calls)
        }
    }

    @Test fun engineeringAuthenticationFailureIsCachedAsTypedRejectionUntilTheIdentityChanges() = runTest {
        var calls = 0
        http(
            MockEngine {
                calls++
                respond("credentials-and-private-source", HttpStatusCode.Forbidden)
            }
        ).use { http ->
            val client = GatewayEngineeringClient(http)
            repeat(2) {
                val message = runCatching { client.capabilities(config) }.exceptionOrNull()?.message.orEmpty()
                assertTrue(message.contains("GATEWAY_HTTP_REJECTED"))
                assertFalse(message.contains("private-source"))
            }
            assertEquals(1, calls)
            assertTrue(runCatching { client.capabilities(config.copy(token = "changed-account")) }.exceptionOrNull() is GatewayAdmissionException)
            assertEquals(2, calls)
        }
    }

    @Test fun successfulHandshakeRetainsTheSpecificEngineeringPrerequisiteInsteadOfConnectionFailure() = runTest {
        for ((checks, reason) in listOf(
            "\"journal\":\"disabled\"" to GatewayAdmissionReason.ENGINEERING_STORAGE_DISABLED,
            "\"journal\":\"unavailable\"" to GatewayAdmissionReason.ENGINEERING_STORAGE_UNAVAILABLE,
            "\"host_github_connection\":\"missing\"" to GatewayAdmissionReason.ENGINEERING_GITHUB_REQUIRED,
            "\"draining\":true" to GatewayAdmissionReason.ENGINEERING_DRAINING,
            "\"worker\":\"unavailable\"" to GatewayAdmissionReason.ENGINEERING_WORKER_UNAVAILABLE
        )) {
            http(MockEngine { respond("""{"service":"engineering-gateway","protocol_version":1,"enabled":false,"principal_id":"owner","readiness":{"schema_version":1,"ready":false,"saved_results":true,"checks":{$checks}}}""") }).use { http ->
                val client = GatewayEngineeringClient(http)
                assertEquals("owner", client.principal(config, requireReady = false))
                val failure = runCatching { client.principal(config) }.exceptionOrNull() as GatewayAdmissionException
                assertEquals(reason, failure.reason)
                assertEquals(200, failure.httpStatus)
                assertEquals(GatewayAdmissionDispatchState.NOT_DISPATCHED, failure.dispatchState)
                assertEquals("GATEWAY_${reason.name}", dev.chungjungsoo.gptmobile.data.agent.ProfileFailureRecord.classify(requireNotNull(failure.message)).category)
            }
        }
    }

    @Test fun oneStaleReadRetriesOnTheDedicatedPoolWhileMutatingPostsAreNeverReplayed() = runTest {
        var reads = 0
        var writes = 0
        var evictions = 0
        val readAttempts = mutableListOf<String?>()
        http(
            MockEngine { request ->
                writes++
                assertNull(request.headers["X-Gateway-Retry-Attempt"])
                throw java.io.EOFException("closed before response")
            }
        ).use { write ->
            http(
                MockEngine { request ->
                    reads++
                    readAttempts += request.headers["X-Gateway-Retry-Attempt"]
                    if (reads == 1) throw java.io.EOFException("closed before response")
                    respond(Json.encodeToString(job))
                }
            ).use { read ->
                val client = GatewayEngineeringClient(write, read) { evictions++ }
                assertEquals(job.id, client.get(config, job.id, 0).id)
                assertEquals(2, reads)
                assertEquals(listOf("0", "1"), readAttempts)
                assertEquals(1, evictions)
                val payload = GatewayEngineeringRequest("owner/repo", "feature/job", "main", "Fix tests", "local", chatId = 4, requestId = "saved-before-dispatch")
                assertTrue(runCatching { client.create(config, payload) }.exceptionOrNull() is java.io.EOFException)
                assertEquals(1, writes)
                assertEquals(2, reads)
            }
        }
    }

    @Test fun createRetriesKeepTheSameRequestIdentityAndNeverCarryApproval() = runTest {
        val payload = GatewayEngineeringRequest("owner/repo", "feature/job", "main", "Fix tests", "local", chatId = 4, requestId = "saved-before-dispatch")
        var count = 0
        http(
            MockEngine { request ->
                count++
                assertEquals("/v1/gateway/engineering/jobs", request.url.encodedPath)
                assertEquals("Bearer test-token", request.headers["Authorization"])
                assertNull(request.headers["X-Gateway-User-Action"])
                assertEquals(payload, Json.decodeFromString<GatewayEngineeringRequest>((request.body as TextContent).text))
                respond(Json.encodeToString(job), HttpStatusCode.Accepted, headersOf("Content-Type", "application/json"))
            }
        ).use { http ->
            val client = GatewayEngineeringClient(http)
            repeat(2) { assertEquals(job.id, client.create(config, payload).id) }
        }
        assertEquals(2, count)
    }

    @Test fun reconnectRequestsOnlyMissedEventsAndParsesTheNextCursor() = runTest {
        val snapshot = job.copy(updates = listOf(GatewayEngineeringUpdate(11, "CI", "Checks passed", 10.0)), nextRevision = 11, hasMoreUpdates = true)
        http(
            MockEngine { request ->
                assertEquals("10", request.url.parameters["after_revision"])
                assertNull(request.headers["X-Gateway-User-Action"])
                respond(Json.encodeToString(snapshot), HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
            }
        ).use { http ->
            val result = GatewayEngineeringClient(http).get(config, job.id, 10)
            assertEquals(11, result.nextRevision)
            assertTrue(result.hasMoreUpdates)
            assertEquals("Checks passed", result.updates.single().message)
        }
    }

    @Test fun approvalIsAnExplicitExactCommitRequestAndInvalidStateDoesNotDispatch() = runTest {
        var calls = 0
        http(
            MockEngine { request ->
                calls++
                assertEquals("approve-and-merge", request.headers["X-Gateway-User-Action"])
                val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                assertEquals(job.headSha, body.getValue("head_sha").jsonPrimitive.content)
                assertEquals("7", body.getValue("pr").jsonPrimitive.content)
                assertEquals("12", body.getValue("revision").jsonPrimitive.content)
                respond(Json.encodeToString(job.copy(state = "MERGE", mergeReady = false)), HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
            }
        ).use { http ->
            val client = GatewayEngineeringClient(http)
            assertEquals("MERGE", client.control(config, job, "approve").state)
            try {
                client.control(config, job.copy(mergeReady = false), "approve")
                error("Expected invalid approval to be rejected")
            } catch (_: IllegalArgumentException) {
                assertEquals(1, calls)
            }
        }
    }

    @Test fun rejectedRequestsExposeNoServerBodyOrCredentials() = runTest {
        http(MockEngine { respond("private source and credentials", HttpStatusCode.Conflict) }).use { http ->
            try {
                GatewayEngineeringClient(http).get(config, job.id, 0)
                error("Expected stale state")
            } catch (failure: IllegalStateException) {
                assertTrue(failure.message.orEmpty().contains("Refresh"))
                assertFalse(failure.message.orEmpty().contains("credentials"))
            }
        }
    }

    @Test fun canonicalConversationReplyUsesMessageEndpointAndRetainsItsExpectedRevision() = runTest {
        var calls = 0
        http(
            MockEngine { request ->
                calls++
                assertEquals("/v1/gateway/conversations/conv_original/messages", request.url.encodedPath)
                val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                assertEquals("message", body.getValue("kind").jsonPrimitive.content)
                assertEquals("stable-command", body.getValue("command_id").jsonPrimitive.content)
                assertEquals("42", body.getValue("expected_revision").jsonPrimitive.content)
                val engineering = body.getValue("engineering_request").jsonObject
                assertEquals("original-owner/repo", engineering.getValue("repository").jsonPrimitive.content)
                assertEquals("original-branch", engineering.getValue("branch").jsonPrimitive.content)
                assertEquals("original-model", engineering.getValue("model").jsonPrimitive.content)
                respond(Json.encodeToString(job))
            }
        ).use { http ->
            GatewayEngineeringClient(http).create(config, GatewayEngineeringRequest("original-owner/repo", "original-branch", "original-base", "Inspect the repository", "original-model", chatId = 7, requestId = "stable-command", taskScope = "read", conversationId = "conv_original", expectedRevision = 42))
        }
        assertEquals(1, calls)
    }

    @Test fun definitiveAdmissionRejectionIsDistinctFromAnUnconfirmedServerFailure() = runTest {
        val request = GatewayEngineeringRequest("owner/repo", "work", "main", "Inspect", "model", chatId = 7, requestId = "command")
        for (status in listOf(HttpStatusCode.BadRequest, HttpStatusCode.Forbidden, HttpStatusCode.Conflict)) {
            http(MockEngine { respond("Rejected", status) }).use { http ->
                val failure = runCatching { GatewayEngineeringClient(http).create(config, request) }.exceptionOrNull()
                assertTrue(failure is GatewayEngineeringRequestRejected)
                assertEquals(status.value, (failure as GatewayEngineeringRequestRejected).httpStatus)
                assertFalse(failure.commandOutcomeUnknown)
            }
        }
        http(MockEngine { respond("Unavailable", HttpStatusCode.ServiceUnavailable) }).use { http ->
            val failure = runCatching { GatewayEngineeringClient(http).create(config, request) }.exceptionOrNull()
            assertTrue(failure != null && failure !is GatewayEngineeringRequestRejected)
        }
    }

    @Test fun bindingConflictPreservesItsSelectedModelReasonAndDoesNotReplayThePost() = runTest {
        val selection = GatewayExecutionSelection("profile", "revision", "provider", "provider-revision", "LLAMA", config.apiUrl, "selected", kotlinx.serialization.json.JsonObject(emptyMap()), "PRIVATE")
        var calls = 0
        http(
            MockEngine { request ->
                calls++
                assertEquals(io.ktor.http.HttpMethod.Post, request.method)
                assertTrue(request.url.encodedPath.endsWith("/bindings"))
                respond("""{"error":{"code":"EXECUTION_MODEL_LOADING","failed_stage":"model_readiness","retryable":true,"inference_attempted":false,"message":"private backend details"}}""", HttpStatusCode.Conflict)
            }
        ).use { http ->
            val failure = runCatching { GatewayEngineeringClient(http).prepareBinding(config, selection) }.exceptionOrNull()
            assertTrue(failure is GatewayExecutionRejected)
            val rejected = failure as GatewayExecutionRejected
            assertEquals("EXECUTION_MODEL_LOADING", rejected.code)
            assertEquals("model_readiness", rejected.failedStage)
            assertEquals(409, rejected.httpStatus)
            assertEquals(true, rejected.retryable)
            assertEquals(false, rejected.inferenceAttempted)
            assertFalse(rejected.commandOutcomeUnknown)
            assertFalse(rejected.message.orEmpty().contains("private backend"))
            val reconciled = rejected.withCommandOutcomeUnknown(true)
            assertTrue(reconciled.commandOutcomeUnknown)
            assertEquals(rejected.code, reconciled.code)
            assertEquals(rejected.failedStage, reconciled.failedStage)
            assertEquals(rejected.retryable, reconciled.retryable)
            assertEquals(rejected.inferenceAttempted, reconciled.inferenceAttempted)
        }
        assertEquals(1, calls)
    }

    @Test fun bindingRejectionDoesNotCoerceUntrustedMetadataIntoExecutionEvidence() = runTest {
        val selection = GatewayExecutionSelection("profile", "revision", "provider", "provider-revision", "LLAMA", config.apiUrl, "selected", kotlinx.serialization.json.JsonObject(emptyMap()), "PRIVATE")
        http(MockEngine { respond("""{"detail":{"code":"EXECUTION_MODEL_UNAVAILABLE","stage":"private backend path/token","retryable":"true","inference_attempted":"false"}}""", HttpStatusCode.Conflict) }).use { http ->
            val failure = runCatching { GatewayEngineeringClient(http).prepareBinding(config, selection) }.exceptionOrNull() as GatewayExecutionRejected
            assertEquals("provider_admission", failure.failedStage)
            assertNull(failure.retryable)
            assertNull(failure.inferenceAttempted)
            assertFalse(failure.message.orEmpty().contains("token"))
        }
    }
}
