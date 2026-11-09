package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import java.io.EOFException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayRepositoryClientTest {
    private val config = ProviderRequestConfig("http://192.168.1.42:8090/v1", "test-token")
    private val operationId = "repoop_" + "a".repeat(32)
    private val arguments = buildJsonObject { put("operation", "clone_repository") }
    private fun http(engine: MockEngine) = HttpClient(engine) {
        install(HttpTimeout)
        followRedirects = false
    }

    @Test fun onlyTheQualifiedKnownOperationAndCopyModeAreSupported() {
        val valid = GatewayRepositoryCapability("github.repository-lifecycle.v1", true, listOf("clone_repository", "import_repository", "unreviewed_write"), listOf("history"))
        assertTrue(valid.supports("clone_repository"))
        assertTrue(valid.supports("import_repository"))
        assertFalse(valid.supports("unreviewed_write"))
        assertFalse(valid.copy(enabled = false).supports("clone_repository"))
        assertFalse(valid.copy(schema = "unknown-v2").supports("clone_repository"))
        assertFalse(valid.copy(copyModes = listOf("snapshot")).supports("import_repository"))
        assertFalse(valid.copy(operations = emptyList()).supports("import_repository"))
    }

    @Test fun repositoryPostUsesOneAttemptWhileStatusMayRecoverOneStaleRead() = runTest {
        var writes = 0
        var reads = 0
        var evictions = 0
        http(
            MockEngine { request ->
                writes++
                assertEquals(HttpMethod.Post, request.method)
                assertEquals("/v1/gateway/engineering/repository-operations", request.url.encodedPath)
                throw EOFException("no response")
            }
        ).use { write ->
            http(
                MockEngine { request ->
                    reads++
                    assertEquals(HttpMethod.Get, request.method)
                    assertEquals("/v1/gateway/engineering/repository-operations/$operationId", request.url.encodedPath)
                    if (reads == 1) throw EOFException("stale read")
                    respond("""{"operation_id":"$operationId"}""")
                }
            ).use { read ->
                val client = GatewayEngineeringClient(write, read) { evictions++ }
                assertTrue(runCatching { client.repositoryOperation(config, arguments) }.exceptionOrNull() is EOFException)
                assertEquals(1, writes)
                assertEquals(JsonPrimitive(operationId), client.repositoryOperationStatus(config, operationId)["operation_id"])
                assertEquals(2, reads)
                assertEquals(1, evictions)
                assertEquals(1, writes)
            }
        }
    }

    @Test fun statusIdentityIsValidatedBeforeAnyRequest() = runTest {
        var requests = 0
        http(
            MockEngine {
                requests++
                respond("{}")
            }
        ).use { http ->
            val client = GatewayEngineeringClient(http)
            for (invalid in listOf("../other", "repoop_" + "f".repeat(31), "repoop_" + "G".repeat(32), "")) {
                assertTrue(runCatching { client.repositoryOperationStatus(config, invalid) }.exceptionOrNull() is IllegalArgumentException)
            }
            assertEquals(0, requests)
        }
    }

    @Test fun cancellationIsPropagatedWithoutRetryingEitherTransport() = runTest {
        var calls = 0
        val cancellation = CancellationException("stop")
        http(
            MockEngine {
                calls++
                throw cancellation
            }
        ).use { http ->
            val client = GatewayEngineeringClient(http)
            assertTrue(runCatching { client.repositoryOperation(config, arguments) }.exceptionOrNull() is CancellationException)
            assertEquals(1, calls)
            assertTrue(runCatching { client.repositoryOperationStatus(config, operationId) }.exceptionOrNull() is CancellationException)
            assertEquals(2, calls)
        }
    }

    @Test fun capabilitiesDoNotInflateRepositoryMutationAccounting() = runTest {
        val accounting = GitHubDispatchAccounting(record = {})
        http(
            MockEngine { request ->
                if (request.url.encodedPath.endsWith("/capabilities")) {
                    respond("""{"service":"engineering-gateway","protocol_version":1,"enabled":true,"principal_id":"owner"}""")
                } else {
                    respond("{}", HttpStatusCode.Accepted)
                }
            }
        ).use { http ->
            val client = GatewayEngineeringClient(http)
            withContext(accounting) {
                client.capabilities(config)
                client.repositoryOperation(config, arguments)
            }
            val result = accounting.finish(AgentToolResult("call", ToolResultContent.Text("accepted"), false), mutation = true)
            val receipt = requireNotNull(result.executionReceipt)
            assertEquals(JsonPrimitive(1), receipt["mutation_requests"])
            assertEquals(JsonPrimitive(1), receipt["accepted_mutations"])
            assertEquals(JsonPrimitive(0), receipt["read_requests"])
            assertEquals(JsonPrimitive(false), receipt["requires_reconciliation"])
        }
    }

    @Test fun gatewayClientClosedStatusRetainsUnknownMutationAccounting() = runTest {
        val accounting = GitHubDispatchAccounting(record = {})
        var posts = 0
        http(
            MockEngine {
                posts++
                respond("{}", HttpStatusCode(499, "Client Closed Request"))
            }
        ).use { http ->
            val failure = withContext(accounting) { runCatching { GatewayEngineeringClient(http).repositoryOperation(config, arguments) }.exceptionOrNull() }
            assertTrue(failure is IllegalStateException)
            assertFalse(failure is GatewayEngineeringRequestRejected)
            assertEquals(1, posts)
            val result = accounting.finish(AgentToolResult("call", ToolResultContent.Text("response interrupted"), true), mutation = true)
            val receipt = requireNotNull(result.executionReceipt)
            assertEquals(JsonPrimitive(1), receipt["mutation_requests"])
            assertEquals(JsonPrimitive(1), receipt["unknown_mutations"])
            assertEquals(JsonPrimitive(0), receipt["rejected_mutations"])
            assertEquals(JsonPrimitive(true), receipt["requires_reconciliation"])
            assertEquals("RECONCILIATION_REQUIRED", result.errorCode)
        }
    }
}
