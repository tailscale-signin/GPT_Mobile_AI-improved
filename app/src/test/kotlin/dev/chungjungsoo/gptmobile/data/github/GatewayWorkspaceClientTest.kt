package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.content.TextContent
import java.io.IOException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayWorkspaceClientTest {
    private val config = ProviderRequestConfig("http://127.0.0.1:8080/v1", "token")
    private val command = GatewayWorkspacePendingCommand("operation-1", "eng_" + "a".repeat(32), "b".repeat(40), 8, "review_draft")
    private val receipt = GatewayWorkspaceCommandReceipt(1, command.jobId, command.commandId, command.headSha, "QUEUED")

    @Test fun explicitWorkspaceWriteCarriesAnImmutableEnvelopeAndUserActionHeader() = runTest {
        val arguments = buildJsonObject {
            put("draft_id", "draft")
            put("body", "Keep this review private")
        }
        var count = 0
        HttpClient(
            MockEngine { request ->
                count++
                assertEquals("POST", request.method.value)
                assertEquals("/v1/gateway/engineering/jobs/${command.jobId}/workspace", request.url.encodedPath)
                assertEquals("workspace-command", request.headers["X-Gateway-User-Action"])
                val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                assertEquals(arguments, body["arguments"])
                assertEquals(JsonPrimitive(command.commandId), body["command_id"])
                assertEquals(JsonPrimitive(command.headSha), body["head_sha"])
                assertEquals(JsonPrimitive(8), body["revision"])
                respond(Json.encodeToString(receipt))
            }
        ) { install(HttpTimeout) }.use { http ->
            assertEquals(receipt, GatewayEngineeringClient(http).workspaceCommand(config, command, arguments))
            assertEquals(1, count)
        }
    }

    @Test fun lostWriteResponseIsNotAutomaticallyRepeated() = runTest {
        var count = 0
        HttpClient(
            MockEngine {
                count++
                throw IOException("response lost")
            }
        ) { install(HttpTimeout) }.use { http ->
            assertTrue(runCatching { GatewayEngineeringClient(http).workspaceCommand(config, command, JsonObject(emptyMap())) }.isFailure)
            assertEquals(1, count)
        }
    }

    @Test fun reconciliationReadsOnlyTheOriginalCommand() = runTest {
        HttpClient(
            MockEngine { request ->
                assertEquals("GET", request.method.value)
                assertEquals("/v1/gateway/engineering/jobs/${command.jobId}/workspace/commands/${command.commandId}", request.url.encodedPath)
                respond(Json.encodeToString(receipt.copy(status = "COMPLETED")))
            }
        ) { install(HttpTimeout) }.use { http ->
            assertTrue(GatewayEngineeringClient(http).workspaceCommandStatus(config, command).settled)
        }
    }

    @Test fun receiptCannotReconcileADifferentOperationOrSource() = runTest {
        for (wrong in listOf(receipt.copy(commandId = "other"), receipt.copy(headSha = "c".repeat(40)), receipt.copy(jobId = "other-job"))) {
            HttpClient(MockEngine { respond(Json.encodeToString(wrong)) }) { install(HttpTimeout) }.use { http ->
                assertTrue(runCatching { GatewayEngineeringClient(http).workspaceCommandStatus(config, command) }.isFailure)
            }
        }
    }

    @Test fun identityOverrideAndTraversalAreRejectedBeforeDispatch() = runTest {
        var calls = 0
        HttpClient(
            MockEngine {
                calls++
                error("Must not dispatch")
            }
        ) { install(HttpTimeout) }.use { http ->
            val client = GatewayEngineeringClient(http)
            assertTrue(runCatching { client.workspaceCommand(config, command, buildJsonObject { put("head_sha", "override") }) }.isFailure)
            assertTrue(runCatching { client.workspaceCommandStatus(config, command.copy(commandId = "../other")) }.isFailure)
            assertTrue(runCatching { client.workspace(config, "../other") }.isFailure)
            assertEquals(0, calls)
        }
    }

    @Test fun sourceReadsEncodeTheObservedPathAndVerifyTheBlob() = runTest {
        val file = GatewayWorkspaceSourcePath("src/a file.kt", "c".repeat(40))
        val source = GatewayWorkspaceSource(1, command.jobId, command.headSha, file.path, file.blobSha, "value", 1, 1, 1)
        HttpClient(
            MockEngine { request ->
                assertEquals("GET", request.method.value)
                assertEquals(command.headSha, request.url.parameters["head_sha"])
                assertEquals(file.path, request.url.parameters["path"])
                assertEquals("80", request.url.parameters["line_count"])
                respond(Json.encodeToString(source))
            }
        ) { install(HttpTimeout) }.use { http ->
            val client = GatewayEngineeringClient(http)
            assertEquals(source, client.workspaceSource(config, command.jobId, command.headSha, file, 1))
            assertTrue(runCatching { client.workspaceSource(config, command.jobId, command.headSha, file.copy(blobSha = "d".repeat(40)), 1) }.isFailure)
        }
    }

    @Test fun treeReadbackCannotSubstituteAnotherJobOrPage() = runTest {
        val tree = GatewayWorkspaceTree(1, command.jobId, command.headSha, listOf(GatewayWorkspaceSourcePath("src/Main.kt", "c".repeat(40))), 1, 0)
        HttpClient(
            MockEngine { request ->
                assertEquals("src/", request.url.parameters["query"])
                respond(Json.encodeToString(tree))
            }
        ) { install(HttpTimeout) }.use { http ->
            val client = GatewayEngineeringClient(http)
            assertEquals(tree, client.workspaceTree(config, command.jobId, command.headSha, "src/", 0))
            assertTrue(runCatching { client.workspaceTree(config, command.jobId, command.headSha, "src/", 1) }.isFailure)
            assertTrue(runCatching { client.workspaceTree(config, "eng_" + "d".repeat(32), command.headSha, "src/", 0) }.isFailure)
        }
    }

    @Test fun importedCandidatesRemainReviewableBeforeAnyCheckHasExecuted() = runTest {
        val candidate = GatewayEngineeringCandidate("c".repeat(64), "d".repeat(64), null, "UNVERIFIED", command.headSha)
        val history = GatewayEngineeringCheckpoints(command.jobId, 8, command.headSha, "IMPLEMENT", false, listOf(candidate))
        val json = Json { encodeDefaults = true }
        HttpClient(MockEngine { respond(json.encodeToString(history)) }) { install(HttpTimeout) }.use { http ->
            val observed = GatewayEngineeringClient(http).checkpoints(config, command.jobId).candidates.single()
            assertNull(observed.checkIndex)
            assertEquals("UNVERIFIED", observed.status)
            assertEquals(candidate.checkId, observed.checkId)
        }
    }
}
