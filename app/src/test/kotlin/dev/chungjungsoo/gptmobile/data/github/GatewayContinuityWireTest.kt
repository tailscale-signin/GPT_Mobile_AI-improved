package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fixtures were emitted by the actual gateway SQLite continuity implementation. */
class GatewayContinuityWireTest {
    private val json = Json { ignoreUnknownKeys = true }
    private fun fixture(name: String) = requireNotNull(javaClass.classLoader?.getResource("gateway-continuity/$name.json")).readText()

    @Test fun productionIdentityCatalogSnapshotAndCommandDecodeWithoutInventedDefaults() {
        json.decodeFromString<GatewayIdentity>(fixture("identity")).validate()
        val catalog = json.decodeFromString<GatewayJobCatalog>(fixture("catalog"))
        assertEquals(setOf("engineering", "chat"), catalog.items.map { it.kind }.toSet())
        val snapshot = json.decodeFromString<GatewayConversationSnapshot>(fixture("engineering-snapshot"))
        val receipt = json.decodeFromString<GatewayCommandReceipt>(fixture("command"))
        assertEquals(receipt.jobId, receipt.job.id)
        assertEquals(snapshot.conversationId, receipt.conversationId)
        assertEquals(snapshot.executionBinding, receipt.job.executionBinding)
        assertEquals("feature/source", snapshot.sourceBranch)
        assertEquals("a".repeat(40), receipt.job.sourceSha)
        assertEquals(snapshot.sourceSha, requireNotNull(snapshot.latestJob).sourceSha)
        assertTrue(receipt.job.createdAt > 0)
        assertTrue(receipt.job.deadline > receipt.job.createdAt)
        val ordinary = json.decodeFromString<GatewayConversationSnapshot>(fixture("ordinary-snapshot"))
        assertEquals("chat", ordinary.latestJob?.kind)
        assertEquals("completed", ordinary.latestJob?.schedulingState)
        assertTrue(ordinary.profileId.isNotBlank())
    }

    @Test fun actualHttpCustomWorkingBranchRetainsSeparateSourceAndBase() {
        val catalog = json.decodeFromString<GatewayJobCatalog>(fixture("custom-base-catalog"))
        val snapshot = json.decodeFromString<GatewayConversationSnapshot>(fixture("custom-base-snapshot"))
        val receipt = json.decodeFromString<GatewayCommandReceipt>(fixture("custom-base-command"))
        val latest = requireNotNull(snapshot.latestJob)
        val item = catalog.items.single { it.jobId == receipt.jobId && it.kind == "engineering" }
        assertEquals("feature/source", snapshot.sourceBranch)
        assertEquals("a".repeat(40), snapshot.sourceSha)
        assertEquals("work/custom", snapshot.workingBranch)
        assertEquals("work/custom", latest.workingBranch)
        assertEquals("work/custom", receipt.job.branch)
        assertEquals("release/base", snapshot.base)
        assertEquals("release/base", latest.base)
        assertEquals("release/base", item.base)
        assertEquals("release/base", receipt.job.base)
        assertEquals(snapshot.executionBinding, receipt.job.executionBinding)
    }

    @Test fun generatedReplayKeepsFullVisibleConversationAndEmptyTailCursor() {
        val page = json.decodeFromString<GatewayMessagePage>(fixture("replay"))
        val tail = json.decodeFromString<GatewayMessagePage>(fixture("replay-empty-tail"))
        assertEquals(listOf("user", "assistant", "user", "assistant"), page.items.map { it.role })
        assertEquals("Earlier answer", page.items[1].content)
        val saved = GatewayReplayPolicy.plan(GatewayReplayCursor("local"), page, emptyMap()).cursor
        val end = GatewayReplayPolicy.plan(saved, tail, emptyMap())
        assertEquals(saved.sequence, end.cursor.sequence)
        assertEquals(saved.cursor, end.cursor.cursor)
        assertTrue(end.messages.isEmpty())
        assertFalse(end.cursor.hasMore)
    }

    @Test fun generatedMultipartDuplicateAndResumeReconstructOneLogicalMessage() = runTest {
        val first = json.decodeFromString<GatewayMessagePage>(fixture("replay-parts-first"))
        val duplicate = json.decodeFromString<GatewayMessagePage>(fixture("replay-parts-duplicate"))
        val resume = json.decodeFromString<GatewayMessagePage>(fixture("replay-parts-resume"))
        val records = linkedMapOf<Long, GatewayMessageRecord>()
        var cursor = GatewayReplayCursor("local")
        var admitted = 0
        for (page in listOf(first, duplicate, resume)) {
            val plan = GatewayReplayPolicy.plan(cursor, page, records)
            for (message in plan.messages) {
                records[message.sequence] = GatewayMessageRecord("local", message.sequence, message.messageId, message.jobId, message.role, message.kind, message.content, message.createdAtMs, GatewayReplayPolicy.fingerprint(message), null, message.logicalMessageId, message.partIndex, message.partCount, message.contentSha256)
                admitted++
            }
            cursor = plan.cursor
        }
        assertEquals(records.size, admitted)
        assertEquals(resume.latestSequence, cursor.sequence)
        val parts = records.values.filter { it.partCount > 1 }.sortedBy { it.partIndex }
        assertEquals(parts.first().partCount, parts.size)
        val assembled = GatewayLogicalMessageAssembler.assemble(parts.first()) { offset -> parts.drop(offset).take(16) }
        assertEquals(parts.joinToString("") { it.content }, assembled.content)
        assertTrue(requireNotNull(assembled.content).contains("Unicode"))
    }

    @Test fun actualClientUsesOnlyGetForCatalogSnapshotReplayAndReceipt() = runTest {
        val methods = mutableListOf<HttpMethod>()
        val engine = MockEngine { request ->
            methods += request.method
            assertEquals("Bearer scoped", request.headers["Authorization"])
            val name = when {
                request.url.encodedPath.endsWith("/identity") -> "identity"
                request.url.encodedPath.endsWith("/job-catalog") -> "catalog"
                request.url.encodedPath.contains("/commands/") -> "command"
                request.url.encodedPath.endsWith("/messages") -> "replay"
                else -> "ordinary-snapshot"
            }
            respond(fixture(name), HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        }
        val http = HttpClient(engine) { install(HttpTimeout) }
        try {
            val client = GatewayConversationClient(http)
            val config = ProviderRequestConfig("http://127.0.0.1:8081/v1", "scoped")
            client.identity(config)
            val catalog = client.catalog(config)
            val id = catalog.items.first { it.kind == "chat" }.conversationId
            client.conversation(config, id)
            client.messages(config, id)
            assertNotNull(client.command(config, "fixture-command"))
            assertEquals(List(5) { HttpMethod.Get }, methods)
        } finally {
            http.close()
        }
    }

    @Test fun unauthorizedCommandLookupCannotMasqueradeAsUnknownCommand() = runTest {
        val http = HttpClient(MockEngine { respond("{}", HttpStatusCode.Forbidden, headersOf("Content-Type", "application/json")) }) { install(HttpTimeout) }
        try {
            val failure = runCatching { GatewayConversationClient(http).command(ProviderRequestConfig("http://127.0.0.1:8081/v1", "scope"), "command") }.exceptionOrNull()
            assertTrue(failure is GatewayContinuityFailure && failure.status == 403)
        } finally {
            http.close()
        }
    }

    @Test fun conflictKeepsExactServerCodeAndBoundedDetails() = runTest {
        val detail = "This original command belongs to another payload. " + "x".repeat(700)
        val response = kotlinx.serialization.json.buildJsonObject {
            put(
                "detail",
                kotlinx.serialization.json.buildJsonObject {
                    put("code", kotlinx.serialization.json.JsonPrimitive("COMMAND_ID_CONFLICT"))
                    put("message", kotlinx.serialization.json.JsonPrimitive(detail))
                }
            )
        }
        val http = HttpClient(MockEngine { respond(response.toString(), HttpStatusCode.Conflict, headersOf("Content-Type", "application/json")) }) { install(HttpTimeout) }
        try {
            val failure = runCatching { GatewayConversationClient(http).followUp(ProviderRequestConfig("http://127.0.0.1:8081/v1", "scope"), "conversation", "command", 3, "Follow up", "read") }.exceptionOrNull()
            assertTrue(failure is GatewayContinuityFailure)
            failure as GatewayContinuityFailure
            assertEquals("COMMAND_ID_CONFLICT", failure.code)
            assertTrue(requireNotNull(failure.message).startsWith("COMMAND_ID_CONFLICT: This original command"))
            assertTrue(requireNotNull(failure.message).length <= 522)
        } finally {
            http.close()
        }
    }

    @Test fun actualHttpInteriorAndTrailingGapsKeepRetainedMessagesAndAdvanceTheDeclaredWatermark() {
        for (name in listOf("replay-interior-gap", "replay-trailing-gap", "replay-interior-trailing-gap")) {
            val response = json.decodeFromString<GatewayMessagePage>(fixture(name))
            val snapshot = json.decodeFromString<GatewayConversationSnapshot>(fixture("$name-snapshot"))
            val tail = json.decodeFromString<GatewayMessagePage>(fixture("$name-tail"))
            val plan = GatewayReplayPolicy.plan(GatewayReplayCursor("local"), response, emptyMap())
            assertEquals(response.items, plan.messages)
            assertEquals(response.latestSequence, plan.cursor.sequence)
            assertEquals(response.nextCursor, plan.cursor.cursor)
            assertFalse(plan.cursor.historyComplete)
            assertFalse(snapshot.historyComplete)
            val end = GatewayReplayPolicy.plan(plan.cursor, tail, emptyMap())
            assertTrue(end.messages.isEmpty())
            assertEquals(plan.cursor, end.cursor)
        }
    }

    @Test fun mutationPreconditionsReachBothClientsAndIdentityConflictIsNeverRetried() = runTest {
        val identity = json.decodeFromString<GatewayIdentity>(fixture("identity"))
        val methods = mutableListOf<String>()
        val http = HttpClient(
            MockEngine { request ->
                methods += request.url.encodedPath
                assertEquals(HttpMethod.Post, request.method)
                assertEquals(identity.gatewayId, request.headers["X-Gateway-Installation-ID"])
                assertEquals(identity.principalId, request.headers["X-Gateway-Principal-ID"])
                assertEquals(listOf(identity.gatewayId), request.headers.getAll("X-Gateway-Installation-ID"))
                respond("""{"detail":{"code":"CONTINUITY_IDENTITY_MISMATCH","message":"The original identity changed."}}""", HttpStatusCode.Conflict, headersOf("Content-Type", "application/json"))
            }
        ) { install(HttpTimeout) }
        try {
            val config = ProviderRequestConfig("http://127.0.0.1:8081/v1", "scope", extraHeaders = mapOf("x-gateway-installation-id" to "stale")).withGatewayIdentity(identity)
            val engineering = GatewayEngineeringClient(http)
            val continuity = GatewayConversationClient(http)
            val selection = executionSelection(PlatformV2(uid = "profile", name = "Original", compatibleType = ClientType.LLAMA, apiUrl = config.apiUrl, model = "model"), null, "private")
            val job = json.decodeFromString<GatewayCommandReceipt>(fixture("command")).job
            val failures = listOf(
                runCatching { engineering.prepareBinding(config, selection) }.exceptionOrNull(),
                runCatching { engineering.create(config, GatewayEngineeringRequest("owner/repo", "branch", "base", "Read", "model", chatId = 7, requestId = "original-command")) }.exceptionOrNull(),
                runCatching { engineering.control(config, job, "cancel") }.exceptionOrNull(),
                runCatching { continuity.followUp(config, "conversation", "command", 3, "Read", "read") }.exceptionOrNull(),
                runCatching { continuity.cancelOrdinary(config, "ordinary-job") }.exceptionOrNull()
            )
            assertTrue(failures.all { it?.message?.startsWith("CONTINUITY_IDENTITY_MISMATCH:") == true })
            assertEquals(5, methods.size)
        } finally {
            http.close()
        }
    }
}
