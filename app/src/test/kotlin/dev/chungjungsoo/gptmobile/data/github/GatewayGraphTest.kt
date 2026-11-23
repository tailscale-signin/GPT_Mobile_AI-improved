package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.agent.tool.RepositoryGraphTool
import dev.chungjungsoo.gptmobile.data.agent.tool.RepositoryWorkspaceTool
import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayGraphTest {
    private val context = GitHubConversationContext("connection", "owner", "repo", "feature", "a".repeat(40), "local")

    @Test fun workspaceToolKeepsRepositoryScopeOutOfModelArguments() = runTest {
        var dispatched = 0
        val tool = RepositoryWorkspaceTool("repository_workspace") { args ->
            dispatched++
            args
        }
        val valid = tool.execute(
            "check",
            buildJsonObject {
                put("action", "run")
                put("request_id", "targeted-test")
                put("check_index", 0)
            }
        )
        assertFalse(valid.isError)
        val invalid = tool.execute(
            "scope-override",
            buildJsonObject {
                put("action", "catalog")
                put("repository", "other/repo")
            }
        )
        assertTrue(invalid.isError)
        assertEquals(1, dispatched)
    }

    @Test fun requestAlwaysCarriesSelectedImmutableRevision() {
        val payload = GatewayGraphRepository.boundRequest(
            context,
            buildJsonObject {
                put("action", "query")
                put("query", "auth")
            }
        )
        assertEquals(JsonPrimitive("a".repeat(40)), payload["commit_sha"])
        assertEquals(JsonPrimitive("owner/repo"), payload["repository"])
        assertTrue(runCatching { GatewayGraphRepository.boundRequest(context, buildJsonObject { put("commit_sha", "b".repeat(40)) }) }.isFailure)
    }

    @Test fun graphToolRejectsScopeOverrideBeforeCallingGateway() = runTest {
        var calls = 0
        val tool = RepositoryGraphTool("repository_graph") {
            calls++
            JsonObject(emptyMap())
        }
        val result = tool.execute(
            "call",
            buildJsonObject {
                put("action", "query")
                put("options", buildJsonObject { put("repository", "other/repo") })
            }
        )
        assertTrue(result.isError)
        assertFalse(result.dispatched)
        assertEquals(0, calls)
    }

    @Test fun graphToolRoutesOperationOptionsAndPreservesUncertainty() = runTest {
        val tool = RepositoryGraphTool("repository_graph") { arguments ->
            assertEquals(JsonPrimitive("path"), arguments["action"])
            assertEquals(JsonPrimitive("Auth"), arguments["source"])
            assertEquals(JsonPrimitive(16000), arguments["budget_bytes"])
            buildJsonObject {
                put("status", "NO_PATH_IN_INDEX")
                put("runtime_verified", false)
            }
        }
        val result = tool.execute(
            "call",
            buildJsonObject {
                put("action", "path")
                put(
                    "options",
                    buildJsonObject {
                        put("source", "Auth")
                        put("target", "Client")
                    }
                )
            }
        )
        assertFalse(result.isError)
        assertEquals(JsonPrimitive(false), ((result.content as ToolResultContent.Json).value as JsonObject)["runtime_verified"])
    }

    @Test fun graphClientUsesExistingGatewayEndpointAndPreservesCoverage() = runTest {
        val engine = MockEngine { request ->
            assertEquals("http://127.0.0.1:8090/v1/gateway/engineering/graph", request.url.toString())
            val body = Json.parseToJsonElement((request.body as TextContent).text) as JsonObject
            assertEquals(JsonPrimitive("a".repeat(40)), body["commit_sha"])
            respond("""{"status":"FOUND","stale":true,"graph":{"complete":false}}""", HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        }
        HttpClient(engine) { install(HttpTimeout) }.use { http ->
            val result = GatewayEngineeringClient(http).graph(ProviderRequestConfig("http://127.0.0.1:8090/v1", null), GatewayGraphRepository.boundRequest(context, buildJsonObject { put("action", "status") }))
            assertEquals(JsonPrimitive(true), result["stale"])
            assertEquals(JsonPrimitive(false), (result["graph"] as JsonObject)["complete"])
        }
    }

    @Test fun readinessDiagnosticsPreserveEveryExactPrerequisiteAndSelectedCommitWithoutRawGatewayText() = runTest {
        for ((checks, expected) in listOf(
            "\"journal\":\"disabled\"" to "JOURNAL_DISABLED",
            "\"journal\":\"write_failed\"" to "JOURNAL_UNAVAILABLE",
            "\"journal\":\"ready\",\"host_github_connection\":\"missing\"" to "HOST_GITHUB_CONNECTION_MISSING",
            "\"journal\":\"ready\",\"draining\":true" to "GATEWAY_DRAINING",
            "\"journal\":\"ready\"" to "GRAPH_SERVICE_UNAVAILABLE"
        )) {
            val http = HttpClient(
                MockEngine {
                    respond("""{"service":"engineering-gateway","protocol_version":1,"principal_id":"owner","code_graph":{"schema_version":1,"enabled":false},"readiness":{"schema_version":1,"graph_ready":false,"checks":{$checks}},"private_detail":"secret-token"}""", HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
                }
            ) { install(HttpTimeout) }
            http.use {
                val client = GatewayEngineeringClient(it)
                val tool = RepositoryGraphTool("repository_graph") {
                    try {
                        client.requireGraphReady(ProviderRequestConfig("http://127.0.0.1:8090/$expected", null))
                        error("Unexpected ready graph")
                    } catch (failure: GatewayGraphUnavailableException) {
                        throw failure.atRevision(context)
                    }
                }
                val result = tool.execute(
                    "status",
                    buildJsonObject {
                        put("action", "status")
                        put("map_source", "gateway")
                    }
                )
                val diagnostic = (result.content as ToolResultContent.Json).value as JsonObject
                assertTrue(result.isError)
                assertEquals(JsonPrimitive(expected), diagnostic["failure_category"])
                val revision = diagnostic["selected_revision"] as JsonObject
                assertEquals(JsonPrimitive("owner/repo"), revision["repository"])
                assertEquals(JsonPrimitive(context.commitSha), revision["commit_sha"])
                assertEquals(JsonPrimitive("NOT_QUALIFIED"), revision["qualification"])
                assertEquals(JsonPrimitive(false), diagnostic["graph_complete"])
                assertFalse(diagnostic.toString().contains("secret-token"))
                assertEquals(JsonPrimitive(expected), result.executionReceipt?.get("reason_code"))
                assertEquals(JsonPrimitive(false), result.executionReceipt?.get("requires_reconciliation"))
                val event = dev.chungjungsoo.gptmobile.data.database.entity.ToolEvent("event", "run", 0, result.callId, null, null, "repository_graph", "repository_graph", """{"action":"status","map_source":"gateway"}""", null, null, "FAILED")
                val fields = dev.chungjungsoo.gptmobile.data.diagnostics.ToolReportDiagnostics.fields(event, result)
                assertEquals(dev.chungjungsoo.gptmobile.data.diagnostics.IssueReportContext.identifierHash("tool-reason-code", expected), fields["original_reason_code_hash"])
            }
        }
    }

    @Test fun wrappedPolicyTlsAndGenericFailuresNeverAcquireNativeFallbackOrPriorCheckpointEvidence() = runTest {
        for (cause in listOf(
            GatewayGraphAdmissionException("PERMISSION_DENIED", "private grant restriction"),
            javax.net.ssl.SSLException("private certificate error"),
            GatewayGraphIntegrityException("GRAPH_REVISION_MISMATCH", "private revision"),
            IllegalStateException("GRAPH_INDEX_OUTCOME_UNKNOWN: private message is not a checkpoint")
        )) {
            var nativeReads = 0
            val tool = RepositoryGraphTool(
                "repository_graph__selected",
                nativeStatus = {
                    nativeReads++
                    dev.chungjungsoo.gptmobile.data.agent.AgentToolResult(it, ToolResultContent.Text("native"), false)
                }
            ) { throw GatewayGraphOperationException(cause, "host", "private host", null, false) }
            val result = tool.execute(
                "status",
                buildJsonObject {
                    put("action", "status")
                    put("map_source", "gateway")
                }
            )
            assertTrue(result.isError)
            assertEquals(JsonPrimitive(false), result.executionReceipt?.get("index_outcome_unknown"))
            assertEquals(JsonPrimitive(false), result.executionReceipt?.get("requires_reconciliation"))
            assertEquals(0, nativeReads)
            assertFalse(result.content.toString().contains("CHECK_SAVED_INDEX"))
        }
    }

    @Test fun readinessChangingBetweenHandshakeAndGraphPostKeepsTypedPrerequisite() = runTest {
        HttpClient(
            MockEngine {
                respond("""{"error":{"code":"graph_connection_required","reason":"HOST_GITHUB_CONNECTION_REQUIRED"},"readiness":{"schema_version":1,"graph_ready":false,"checks":{"journal":"ready","host_github_connection":"missing"}}}""", HttpStatusCode.PreconditionFailed, headersOf("Content-Type", "application/json"))
            }
        ) { install(HttpTimeout) }.use { http ->
            val failure = runCatching { GatewayEngineeringClient(http).graph(ProviderRequestConfig("http://127.0.0.1:8090/v1", null), GatewayGraphRepository.boundRequest(context, buildJsonObject { put("action", "status") })) }.exceptionOrNull()
            assertTrue(failure is GatewayGraphUnavailableException)
            assertEquals(listOf(GatewayGraphPrerequisite.HOST_GITHUB_CONNECTION_MISSING), (failure as GatewayGraphUnavailableException).prerequisites)
            assertEquals(412, failure.httpStatus)
            assertEquals(412, failure.atRevision(context).httpStatus)
        }
    }

    @Test fun graphHttpFailuresKeepObservedStatusAndRetryDelayWithoutPayloadOrReplay() = runTest {
        for (status in listOf(HttpStatusCode.Forbidden, HttpStatusCode.TooManyRequests, HttpStatusCode.ServiceUnavailable)) {
            var requests = 0
            var nativeReads = 0
            HttpClient(
                MockEngine {
                    requests++
                    respond(
                        """{"error":{"code":"private-backend-error","message":"secret-token https://private.example/repository"}}""",
                        status,
                        headersOf("Content-Type" to listOf("application/json"), "Retry-After" to listOf("7"))
                    )
                }
            ) { install(HttpTimeout) }.use { http ->
                val client = GatewayEngineeringClient(http)
                val tool = RepositoryGraphTool("repository_graph", nativeStatus = {
                    nativeReads++
                    error("An HTTP rejection is not fallback authority")
                }) { arguments ->
                    client.graph(ProviderRequestConfig("http://127.0.0.1:8090/v1", null), GatewayGraphRepository.boundRequest(context, arguments))
                }
                val result = tool.execute(
                    "http-status",
                    buildJsonObject {
                        put("action", "status")
                        put("map_source", "gateway")
                    }
                )
                assertTrue(result.isError)
                assertEquals("REPOSITORY_GRAPH_UNAVAILABLE", result.errorCode)
                assertEquals(JsonPrimitive(status.value), result.executionReceipt?.get("http_status"))
                assertEquals(JsonPrimitive(7000L), result.executionReceipt?.get("retry_after_ms"))
                assertEquals(JsonPrimitive(false), result.executionReceipt?.get("retryable"))
                assertEquals(1, requests)
                assertEquals(0, nativeReads)
                for (secret in listOf("private-backend-error", "secret-token", "private.example")) {
                    assertFalse(secret, result.content.toString().contains(secret))
                    assertFalse(secret, result.executionReceipt.toString().contains(secret))
                }
                val report = dev.chungjungsoo.gptmobile.data.diagnostics.IssueReportContext.fromFields("TOOL_RESULT", requireNotNull(result.executionReceipt), 1_800_000_000_000)
                assertEquals(status.value, report.httpStatus)
                assertEquals(7000L, report.retryAfterMillis)
            }
        }
    }

    @Test fun gatewayRateEvidenceDistinguishesLocalCapacityFromObservedUpstreamHttpWithoutChangingGraphDispatch() = runTest {
        val observations = listOf(
            """{"code":"GRAPH_CAPACITY_RESERVED","rate_limit_origin":"LOCAL_ADMISSION","upstream_request_dispatched":false}""",
            """{"code":"GITHUB_RATE_LIMIT_WAIT","rate_limit_origin":"LOCAL_ADMISSION","upstream_request_dispatched":false}""",
            """{"code":"GITHUB_PRIMARY_RATE_LIMITED","rate_limit_origin":"GITHUB_RESPONSE","upstream_request_dispatched":true,"upstream_http_status":403}""",
            """{"code":"GITHUB_SECONDARY_RATE_LIMITED","rate_limit_origin":"GITHUB_RESPONSE","upstream_request_dispatched":true,"upstream_http_status":429}"""
        ).map { Json.parseToJsonElement(it) as JsonObject }
        for (observation in observations) {
            var requests = 0
            HttpClient(
                MockEngine {
                    requests++
                    val detail = JsonObject(observation + mapOf("private_detail" to JsonPrimitive("private-body"), "retryable" to JsonPrimitive(true)))
                    respond(buildJsonObject { put("error", detail) }.toString(), HttpStatusCode.TooManyRequests, headersOf("Retry-After", "7"))
                }
            ) { install(HttpTimeout) }.use { http ->
                val tool = RepositoryGraphTool("repository_graph") { arguments ->
                    GatewayEngineeringClient(http).graph(ProviderRequestConfig("http://127.0.0.1:8090/v1", null), GatewayGraphRepository.boundRequest(context, arguments))
                }
                val result = tool.execute(
                    "rate-response",
                    buildJsonObject {
                        put("action", "status")
                        put("map_source", "gateway")
                    }
                )
                val receipt = requireNotNull(result.executionReceipt)
                assertTrue(result.dispatched)
                assertEquals(JsonPrimitive(true), receipt["dispatched"])
                assertEquals(JsonPrimitive(false), receipt["retryable"])
                assertEquals(JsonPrimitive(false), receipt["requires_reconciliation"])
                assertEquals(JsonPrimitive(429), receipt["http_status"])
                assertEquals(JsonPrimitive(7000L), receipt["retry_after_ms"])
                assertEquals(observation, receipt["graph_rate_limit"])
                assertEquals(observation, ((result.content as ToolResultContent.Json).value as JsonObject)["rate_limit"])
                assertFalse(result.content.toString().contains("private-body"))
                assertEquals(1, requests)
                val event = dev.chungjungsoo.gptmobile.data.database.entity.ToolEvent("event", "run", 0, result.callId, null, null, "repository_graph", "repository_graph", """{"action":"status","map_source":"gateway"}""", null, null, "FAILED")
                val fields = dev.chungjungsoo.gptmobile.data.diagnostics.ToolReportDiagnostics.fields(event, result)
                assertEquals(dev.chungjungsoo.gptmobile.data.diagnostics.IssueReportContext.identifierHash("tool-reason-code", (observation["code"] as JsonPrimitive).content), fields["original_reason_code_hash"])
                val report = dev.chungjungsoo.gptmobile.data.diagnostics.IssueReportContext.fromFields("TOOL_RESULT", receipt + fields, 1_800_000_000_000)
                assertEquals(429, report.httpStatus)
                assertEquals(true, report.dispatched)
                assertEquals(false, report.retryable)
            }
        }
        for (invalid in listOf(
            """{"code":"GRAPH_CAPACITY_RESERVED","rate_limit_origin":"LOCAL_ADMISSION","upstream_request_dispatched":false,"upstream_http_status":403}""",
            """{"code":"GITHUB_PRIMARY_RATE_LIMITED","rate_limit_origin":"GITHUB_RESPONSE","upstream_request_dispatched":false,"upstream_http_status":403}""",
            """{"code":"GITHUB_PRIMARY_RATE_LIMITED","rate_limit_origin":"GITHUB_RESPONSE","upstream_request_dispatched":true,"upstream_http_status":"403"}""",
            """{"code":"private-code","rate_limit_origin":"private-origin","upstream_request_dispatched":true,"upstream_http_status":403}"""
        )) {
            val failure = GatewayGraphHttpFailure.fromResponse(429, 7000, Json.parseToJsonElement(invalid) as JsonObject)
            assertEquals(null, failure.rateLimitObservation)
            assertEquals("GRAPH_HTTP_REJECTED", failure.code)
            assertFalse(failure.message.orEmpty().contains("private-"))
        }
        assertEquals(null, GatewayGraphHttpFailure.fromResponse(503, 7000, observations.first()).rateLimitObservation)
    }
}
