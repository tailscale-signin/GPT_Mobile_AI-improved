package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubTool
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import java.io.IOException
import javax.net.ssl.SSLHandshakeException
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubReadRetryTest {
    @Test fun readWaitsForOneCircuitProbeWithoutCreatingAnotherToolCall() = runTest {
        val circuit = GitHubRequestCircuit(threshold = 1, cooldownMillis = 30_000, now = { testScheduler.currentTime })
        circuit.failed(circuit.admit(), IOException("connection reset"))
        var admitted = 0
        val result = GitHubReadRetry.run {
            val permit = circuit.admit()
            admitted++
            circuit.succeeded(permit)
            "same read"
        }
        assertEquals("same read", result)
        assertEquals(1, admitted)
        assertEquals(30_000L, testScheduler.currentTime)
    }

    @Test fun circuitRecoveryNeverWaitsForWritesOrLongCooldowns() = runTest {
        for ((readOnly, wait) in listOf(false to 1000L, true to 30_001L)) {
            var attempts = 0
            val failure = runCatching {
                GitHubReadRetry.run(readOnly) {
                    attempts++
                    throw GitHubRequestCircuit.Open(wait)
                }
            }.exceptionOrNull()
            assertTrue(failure is GitHubRequestCircuit.Open)
            assertEquals(1, attempts)
            assertEquals(0L, testScheduler.currentTime)
        }
    }

    @Test fun aRepeatedOpenCircuitCannotExtendTheReadRecoveryBudget() = runTest {
        var attempts = 0
        val failure = runCatching {
            GitHubReadRetry.run {
                attempts++
                throw GitHubRequestCircuit.Open(1000)
            }
        }.exceptionOrNull()
        assertTrue(failure is GitHubRequestCircuit.Open)
        assertEquals(2, attempts)
        assertEquals(1000L, testScheduler.currentTime)
    }

    @Test fun wrappedTlsFailureIsReportedWithoutRetryingOrTrippingTheCircuit() = runTest {
        var attempts = 0
        val http = HttpClient(
            MockEngine {
                if (++attempts <= 3) throw IOException("Transport failed", SSLHandshakeException("Untrusted certificate"))
                respond("""{"total_count":0,"workflow_runs":[]}""")
            }
        )
        try {
            val tool = GitHubTool("token", http)
            val arguments = buildJsonObject {
                put("action", "list_workflow_runs")
                put("owner", "o")
                put("repo", "r")
                put("verify_live", true)
            }
            repeat(3) { attempt ->
                val result = tool.execute("tls-$attempt", arguments)
                assertTrue(result.isError)
                assertEquals("GITHUB_TLS_FAILURE", result.errorCode)
                assertEquals(attempt + 1, attempts)
            }
            assertFalse(tool.execute("after-tls", arguments).isError)
            assertEquals(4, attempts)
        } finally {
            http.close()
        }
    }

    @Test fun nativeCiReadRecoversFrom502WithoutRegeneration() = runTest {
        var attempts = 0
        val http = HttpClient(
            MockEngine {
                if (++attempts < 3) respond("Server Error", HttpStatusCode.BadGateway) else respond("""{"total_count":0,"workflow_runs":[]}""")
            }
        )
        try {
            val result = GitHubTool("token", http).execute(
                "runs",
                buildJsonObject {
                    put("action", "list_workflow_runs")
                    put("owner", "o")
                    put("repo", "r")
                }
            )
            assertFalse(result.isError)
            assertEquals(3, attempts)
            assertEquals(JsonPrimitive(2), result.executionReceipt?.get("read_http_failures"))
            assertEquals(JsonPrimitive(3), result.executionReceipt?.get("read_response_observations"))
            assertEquals(JsonPrimitive(0), result.executionReceipt?.get("recovered_read_requests"))
        } finally {
            http.close()
        }
    }

    @Test fun workspaceReadRecoversFromReset() = runTest {
        var attempts = 0
        val http = HttpClient(
            MockEngine {
                if (++attempts == 1) throw IOException("stream was reset: CANCEL")
                respond("""{"id":1,"full_name":"o/r"}""")
            }
        )
        try {
            val accounting = GitHubDispatchAccounting(forceLiveReads = true, record = {})
            val response = withContext(accounting) {
                GitHubWorkspaceClient("token", http).execute(
                    "get_repository",
                    buildJsonObject {
                        put("owner", "o")
                        put("repo", "r")
                    }
                )
            }
            assertEquals(2, attempts)
            val result = accounting.finish(AgentToolResult("repository", ToolResultContent.Json(response), false), false)
            assertFalse(result.isError)
            assertEquals(JsonPrimitive(true), result.executionReceipt?.get("live_read_performed"))
            assertEquals(JsonPrimitive(1), result.executionReceipt?.get("recovered_read_requests"))
        } finally {
            http.close()
        }
    }

    @Test fun nativeCiReadKeepsLiveVerificationAfterConnectionReset() = runTest {
        var attempts = 0
        val http = HttpClient(
            MockEngine {
                if (++attempts == 1) throw IOException("connection reset")
                respond("""{"total_count":0,"workflow_runs":[]}""")
            }
        )
        try {
            val result = GitHubTool("token", http).execute(
                "runs-live",
                buildJsonObject {
                    put("action", "list_workflow_runs")
                    put("owner", "o")
                    put("repo", "r")
                    put("verify_live", true)
                }
            )
            assertFalse(result.isError)
            assertEquals(2, attempts)
            assertEquals(JsonPrimitive(true), result.executionReceipt?.get("live_read_performed"))
            assertEquals(JsonPrimitive(1), result.executionReceipt?.get("read_response_observations"))
            assertEquals(JsonPrimitive(1), result.executionReceipt?.get("recovered_read_requests"))
        } finally {
            http.close()
        }
    }
}
