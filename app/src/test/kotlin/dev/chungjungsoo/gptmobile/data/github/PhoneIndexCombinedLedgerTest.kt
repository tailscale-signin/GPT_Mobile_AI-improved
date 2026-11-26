package dev.chungjungsoo.gptmobile.data.github

import android.content.Context
import android.content.SharedPreferences
import dev.chungjungsoo.gptmobile.data.agent.AgentRunLimits
import dev.chungjungsoo.gptmobile.data.agent.AgentTool
import dev.chungjungsoo.gptmobile.data.agent.CombinedBudgetSnapshot
import dev.chungjungsoo.gptmobile.data.agent.CombinedExecutionContext
import dev.chungjungsoo.gptmobile.data.agent.CombinedHardLimits
import dev.chungjungsoo.gptmobile.data.agent.CombinedRequestedPolicy
import dev.chungjungsoo.gptmobile.data.agent.CombinedReservationStatus
import dev.chungjungsoo.gptmobile.data.agent.CombinedResourceQuota
import dev.chungjungsoo.gptmobile.data.agent.CombinedRunRole
import dev.chungjungsoo.gptmobile.data.agent.CombinedTurnBudgetStore
import dev.chungjungsoo.gptmobile.data.agent.CombinedWorkItem
import dev.chungjungsoo.gptmobile.data.agent.CombinedWorkKind
import dev.chungjungsoo.gptmobile.data.agent.CombinedWorkPlan
import dev.chungjungsoo.gptmobile.data.agent.PreparableAgentTool
import dev.chungjungsoo.gptmobile.data.agent.ToolExecutionBudget
import dev.chungjungsoo.gptmobile.data.agent.combinedDigest
import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubTool
import dev.chungjungsoo.gptmobile.data.agent.tool.RepositoryGraphTool
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnection
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.model.ConversationCombinedSettings
import dev.chungjungsoo.gptmobile.data.network.PrivateExternalServiceGate
import dev.chungjungsoo.gptmobile.data.privacy.PrivacyModeStore
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import dev.chungjungsoo.gptmobile.data.repository.ToolConnectionRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Actual production ledger/store and native phone facade; remote GETs and Android services are host ports. */
class PhoneIndexCombinedLedgerTest {
    @Test fun deadlineChargesAndSettlesOneActualReservation() = runBlocking { deadline(nested = false) }

    @Test fun nestedDeadlineStillChargesOnePhysicalAttempt() = runBlocking { deadline(nested = true) }

    @Test fun parentCancellationSettlesStoppedLocalWork() = runBlocking { parentCancellation(nested = false) }

    @Test fun nestedParentCancellationSettlesStoppedLocalWork() = runBlocking { parentCancellation(nested = true) }

    private suspend fun deadline(nested: Boolean) {
        Fixture().use { fixture ->
            Ledger().use { ledger ->
                val inner = ledger.wrapper().bind(fixture.tool())
                val tool = if (nested) ledger.wrapper().bind(inner) else inner
                val prepared = (tool as PreparableAgentTool).prepareExecution("deadline", arguments("index"))
                val result = prepared()
                assertTrue(fixture.secondBlobEntered.isCompleted)
                withTimeout(5000) { fixture.secondBlobStopped.await() }
                assertTrue(fixture.secondBlobStopped.isCompleted)
                assertEquals("PHONE_INDEX_INTERRUPTED", result.errorCode)
                assertEquals(JsonPrimitive(false), result.executionReceipt?.get("request_replay_allowed"))
                assertEquals(JsonPrimitive(false), result.executionReceipt?.get("requires_reconciliation"))
                assertTrue(result.executionReceipt?.get("confirmed_index_attempt_hash") == null)
                val snapshot = assertStopped(ledger, "deadline-nested=$nested")
                assertEquals("DUPLICATE_INVOCATION", prepared().errorCode)
                assertEquals(snapshot, ledger.snapshot())
                assertEquals(1, fixture.firstBlobReads.get())
                assertEquals(1, fixture.secondBlobReads.get())
                ledger.readBack(snapshot)
            }
        }
    }

    private suspend fun parentCancellation(nested: Boolean) = kotlinx.coroutines.coroutineScope {
        Fixture().use { fixture ->
            Ledger().use { ledger ->
                val inner = ledger.wrapper(60_000).bind(fixture.tool())
                val tool = if (nested) ledger.wrapper(60_000).bind(inner) else inner
                val prepared = (tool as PreparableAgentTool).prepareExecution("cancelled", arguments("index"))
                val cancellation = CancellationException("Stop this exact phone-index attempt")
                val observedCancellation = CompletableDeferred<Throwable>()
                val running = async {
                    try {
                        prepared()
                    } catch (error: CancellationException) {
                        observedCancellation.complete(error)
                        throw error
                    }
                }
                withTimeout(5000) { fixture.secondBlobEntered.await() }
                running.cancel(cancellation)
                running.join()
                assertTrue(running.isCancelled)
                val observed = withTimeout(5000) { observedCancellation.await() }
                assertTrue(
                    "The original cancellation must survive coroutine recovery",
                    generateSequence(observed) { it.cause }.take(8).any { it === cancellation }
                )
                withTimeout(5000) { fixture.secondBlobStopped.await() }
                assertTrue(fixture.secondBlobStopped.isCompleted)
                val snapshot = assertStopped(ledger, "parent-cancel-nested=$nested")
                assertEquals("DUPLICATE_INVOCATION", prepared().errorCode)
                assertEquals(snapshot, ledger.snapshot())
                assertEquals(1, fixture.firstBlobReads.get())
                assertEquals(1, fixture.secondBlobReads.get())
                ledger.readBack(snapshot)
            }
        }
    }

    @Test fun phoneInterruptionAndReadbackCannotSettleUnknownGatewayWrite() = runBlocking {
        Fixture().use { fixture ->
            Ledger().use { ledger ->
                var gatewayDispatches = 0
                val budget = ledger.wrapper()
                val gatewayTool = budget.bind(
                    RepositoryGraphTool("repository_graph__selected") { payload ->
                        check(payload["map_source"] == JsonPrimitive("gateway"))
                        gatewayDispatches++
                        awaitCancellation()
                    }
                )
                val gatewayArguments = buildJsonObject {
                    put("action", "index")
                    put("map_source", "gateway")
                }
                assertEquals("TIMEOUT_OUTCOME_UNKNOWN", gatewayTool.execute("unknown-gateway", gatewayArguments).errorCode)
                val beforePhone = ledger.snapshot()
                val original = beforePhone.reservations.values.single()
                assertEquals(CombinedReservationStatus.UNKNOWN, original.status)
                assertEquals(1L, beforePhone.charged.toolOperations)
                val local = budget.bind(fixture.tool()).execute("local", arguments("index"))
                assertEquals("PHONE_INDEX_INTERRUPTED", local.errorCode)
                val stopped = ledger.snapshot()
                assertEquals(2L, stopped.charged.toolOperations)
                assertEquals(original, stopped.reservations[original.attemptId])
                assertEquals(setOf(original.attemptId), stopped.unknownAttemptIds)
                assertEquals(1, stopped.reservations.values.count { it.status == CombinedReservationStatus.SETTLED })
                assertTrue(stopped.completedWork.isEmpty())
                assertTrue(stopped.progressFingerprints.isEmpty())
                val readback = budget.bind(fixture.tool()).execute("authorized-status", arguments("status"))
                assertFalse(readback.isError)
                assertTrue(readback.executionReceipt?.get("confirmed_index_attempt_hash") == null)
                val afterReadback = ledger.snapshot()
                assertEquals(3L, afterReadback.charged.toolOperations)
                assertEquals(original, afterReadback.reservations[original.attemptId])
                assertEquals("RECONCILIATION_REQUIRED", gatewayTool.execute("forbidden-repeat", gatewayArguments).errorCode)
                assertEquals(1, gatewayDispatches)
                assertEquals(afterReadback, ledger.snapshot())
                ledger.readBack(afterReadback)
                println("INDEPENDENT_LEDGER unknown-gateway-preserved charged=3 unknown=1 local-settled=2 replay=blocked")
            }
        }
    }

    private suspend fun assertStopped(ledger: Ledger, label: String): CombinedBudgetSnapshot {
        val snapshot = ledger.snapshot()
        assertEquals("$label reservation count", 1, snapshot.reservations.size)
        val reservation = snapshot.reservations.values.single()
        assertEquals("$label actual reservation status", CombinedReservationStatus.SETTLED, reservation.status)
        assertEquals("$label actual usage", 1L, reservation.usage?.resources?.toolOperations)
        assertEquals("$label actual charged work", 1L, snapshot.charged.toolOperations)
        assertTrue("$label unresolved reservations", snapshot.unknownAttemptIds.isEmpty())
        assertTrue("$label must not invent completed work", snapshot.completedWork.isEmpty())
        assertTrue("$label must not invent stage completion", snapshot.stageOutcomes.isEmpty())
        assertTrue("$label must not invent evidence progress", snapshot.progressFingerprints.isEmpty())
        assertEquals("$label records no successful acquisition", 1, snapshot.noProgressBatches)
        println("INDEPENDENT_LEDGER $label charged=1 reservations=1 status=SETTLED unknown=0 progress=0")
        return snapshot
    }

    private fun arguments(action: String) = buildJsonObject {
        put("action", action)
        put("map_source", "phone")
    }

    private class Ledger : AutoCloseable {
        private val directory = Files.createTempDirectory("independent-phone-ledger").toFile()
        val store = CombinedTurnBudgetStore.forDirectory(directory)
        val context: CombinedExecutionContext
        init {
            val unsigned = CombinedRequestedPolicy(
                settings = ConversationCombinedSettings(effort = 100, continueLongAnswers = false),
                participantUids = listOf("cloud-ai"),
                requestedModels = mapOf("cloud-ai" to "fixture"),
                userPrompt = "Inspect the selected repository",
                acceptedAtMillis = 1
            )
            val requested = unsigned.copy(requestedPolicyDigest = combinedDigest(Json.encodeToString(unsigned)))
            val draft = CombinedWorkPlan(
                requested = requested,
                baselineByOwner = emptyMap(),
                finalizationReserve = CombinedResourceQuota(),
                optionalAllowance = CombinedResourceQuota(toolOperations = 16),
                hardLimits = CombinedHardLimits(toolOperations = 16),
                workItems = listOf(
                    CombinedWorkItem(
                        "phone-evidence",
                        CombinedRunRole.CONTRIBUTOR,
                        remainingUnits = 2,
                        kind = CombinedWorkKind.EVIDENCE,
                        description = "Inspect selected repository"
                    )
                )
            )
            val plan = draft.copy(effectivePlanDigest = combinedDigest(Json.encodeToString(draft)))
            context = CombinedExecutionContext(requested, plan, java.util.UUID.randomUUID().toString(), profileUid = "cloud-ai", chatId = 17, persistBudget = true)
        }

        // This owner must interrupt before the session's four-second per-blob read budget.
        fun wrapper(timeout: Long = 3000) = ToolExecutionBudget(AgentRunLimits(maxToolCalls = 32, toolTimeoutMillis = timeout, combined = context), combined = context, combinedBudgets = store)
        suspend fun snapshot() = store.open(context).snapshot()
        suspend fun readBack(expected: CombinedBudgetSnapshot) {
            val files = directory.listFiles { file -> file.extension == "json" }.orEmpty()
            assertEquals("The real durable store must write exactly one journal", 1, files.size)
            val fresh = Files.createTempDirectory("independent-phone-readback").toFile()
            try {
                files.single().copyTo(File(fresh, files.single().name))
                val reopened = CombinedTurnBudgetStore.forDirectory(fresh).open(context).snapshot()
                assertEquals("Fresh store must deserialize the exact durable reservation state", expected, reopened)
            } finally {
                fresh.deleteRecursively()
            }
        }
        override fun close() {
            directory.deleteRecursively()
        }
    }
    private class Fixture : AutoCloseable {
        private val directory = Files.createTempDirectory("phone-index-deadline").toFile()
        private val context = GitHubConversationContext("github", "owner", "repo", "main", "a".repeat(40), "cloud-ai")
        private val settings = mockk<SettingRepository>()
        private val connections = mockk<ToolConnectionRepository>()
        private val authentication = mockk<GitHubPublicAuthCoordinator>()
        private val connection = ToolConnection("github", "GitHub", "selected", "GITHUB", null, "BEARER", "credential", null)
        val firstBlobReads = AtomicInteger()
        val secondBlobReads = AtomicInteger()
        val repositoryReads = AtomicInteger()
        val mutations = AtomicInteger()
        val secondBlobEntered = CompletableDeferred<Unit>()
        val secondBlobStopped = CompletableDeferred<Unit>()

        init {
            val android = mockk<Context>()
            val preferences = mockk<SharedPreferences>()
            every { android.applicationContext } returns android
            every { android.getSharedPreferences("device_privacy", Context.MODE_PRIVATE) } returns preferences
            every { preferences.getBoolean("privacy_mode_enabled", false) } returns false
            PrivacyModeStore.initialize(android)
            coEvery { settings.fetchPlatformV2s() } returns listOf(PlatformV2(uid = "cloud-ai", name = "Cloud", compatibleType = ClientType.OPENAI, apiUrl = "https://provider.example/v1", model = "fixture"))
            coEvery { settings.getFeatureSettings() } returns AppFeatureSettings()
            coEvery { connections.getConnection("github") } returns connection
            coEvery { authentication.accessToken(connection) } returns "test-only-token"
            coEvery { authentication.durableIdentity("github") } returns "verified-account"
        }

        private val http = HttpClient(
            MockEngine { request ->
                if (request.method.value != "GET") mutations.incrementAndGet()
                assertEquals("GET", request.method.value)
                assertEquals("api.github.com", request.url.host)
                val path = request.url.encodedPath
                val value = when {
                    path == "/repos/owner/repo" -> {
                        repositoryReads.incrementAndGet()
                        buildJsonObject { put("full_name", "owner/repo") }
                    }
                    "/commits/" in path -> buildJsonObject {
                        put("sha", context.commitSha)
                        put("commit", buildJsonObject { put("tree", buildJsonObject { put("sha", "b".repeat(40)) }) })
                    }
                    "/git/trees/" in path -> buildJsonObject {
                        put("sha", path.substringAfterLast('/'))
                        put("truncated", false)
                        put(
                            "tree",
                            buildJsonArray {
                                for (index in 1..2) {
                                    add(
                                        buildJsonObject {
                                            put("path", "src/File$index.kt")
                                            put("type", "blob")
                                            put("mode", "100644")
                                            put("sha", "%040x".format(index))
                                            put("size", 40)
                                        }
                                    )
                                }
                            }
                        )
                    }
                    "/git/blobs/" in path -> {
                        val sha = path.substringAfterLast('/')
                        if (sha == "%040x".format(1)) firstBlobReads.incrementAndGet()
                        if (sha == "%040x".format(2) && secondBlobReads.incrementAndGet() == 1) {
                            secondBlobEntered.complete(Unit)
                            try {
                                awaitCancellation()
                            } finally {
                                secondBlobStopped.complete(Unit)
                            }
                        }
                        buildJsonObject {
                            put("sha", sha)
                            put("encoding", "base64")
                            put("content", Base64.getEncoder().encodeToString("package fixture\nfun useful() = 1\n".toByteArray()))
                        }
                    }
                    else -> error("Unexpected native request $path")
                }
                respond(value.toString(), HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
            }
        ) { install(PrivateExternalServiceGate) }

        fun tool(): AgentTool {
            val repository = PhoneRepositoryGraphRepository(settings, connections, authentication, GitHubRepositoryStateStore(directory)) { it.createClient(http) }
            return requireNotNull(
                GitHubTool(
                    apiToken = "test-only-token",
                    httpClient = http,
                    modelToolName = "github__selected",
                    repositoryScope = context,
                    connectionUid = context.connectionUid,
                    durableIdentity = "verified-account",
                    graphQuery = { scope, payload -> repository.executeForMap(scope, JsonObject(payload - "map_source"), forModel = true).result }
                ).graphTool()
            )
        }

        override fun close() {
            http.close()
            directory.deleteRecursively()
        }
    }
}
