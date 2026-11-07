package dev.chungjungsoo.gptmobile.data.github

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import java.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubDispatchReceiptTest {
    private val sha = "a".repeat(40)
    private fun args(run: Long? = null, tag: String = "v1.2.3", execution: String? = null) = buildJsonObject {
        put("owner", "owner")
        put("repo", "repo")
        put("tag_name", tag)
        put("ref", "main")
        put("expected_head_sha", sha)
        run?.let { put("workflow_run_id", it) }
        execution?.let { put("execution_id", it) }
    }
    private inner class Fixture(private val ids: List<Long?>, var runHead: String = sha, val store: GitHubRepositoryStateStore = GitHubRepositoryStateStore()) : AutoCloseable {
        var dispatches = 0
        var loseAcknowledgement = false
        var rejectDispatch = false
        var workflowName = "Publish Signed Release"
        var beforeDispatchResponse: (suspend () -> Unit)? = null
        val runsRead = mutableListOf<Long>()
        val http = HttpClient(
            MockEngine { request ->
                when (val path = request.url.encodedPath) {
                    "/repos/owner/repo/releases" -> respond("[]")
                    "/repos/owner/repo/actions/workflows" -> respond("""{"total_count":1,"workflows":[{"id":42,"name":"$workflowName","path":".github/workflows/release-build.yml","state":"active"}]}""")
                    "/repos/owner/repo/actions/workflows/42" -> respond("""{"id":42,"name":"$workflowName","state":"active"}""")
                    "/repos/owner/repo/commits/main" -> respond("""{"sha":"$sha"}""")
                    "/repos/owner/repo/contents/app/build.gradle.kts" -> {
                        assertEquals(sha, request.url.parameters["ref"])
                        val encoded = Base64.getEncoder().encodeToString("versionName = \"1.2.3\"".toByteArray())
                        respond("""{"type":"file","encoding":"base64","content":"$encoded"}""")
                    }
                    "/repos/owner/repo/actions/workflows/42/dispatches" -> {
                        val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                        assertTrue(body["return_run_details"]!!.jsonPrimitive.boolean)
                        val id = ids[dispatches++]
                        beforeDispatchResponse?.invoke()
                        if (loseAcknowledgement) throw java.io.IOException("Acknowledgement lost")
                        if (rejectDispatch) {
                            respond("""{"message":"Invalid workflow input"}""", HttpStatusCode.UnprocessableEntity)
                        } else if (id == null) {
                            respond("", HttpStatusCode.NoContent)
                        } else {
                            respond("""{"workflow_run_id":$id,"run_url":"https://api.github.com/repos/owner/repo/actions/runs/$id","html_url":"https://github.com/owner/repo/actions/runs/$id"}""")
                        }
                    }
                    else -> {
                        check(path.startsWith("/repos/owner/repo/actions/runs/")) { "Unexpected request: ${request.url}" }
                        val id = path.substringAfterLast('/').toLong()
                        runsRead += id
                        respond("""{"id":$id,"workflow_id":42,"head_sha":"$runHead","event":"workflow_dispatch","head_branch":"main","status":"completed","conclusion":"success"}""")
                    }
                }
            }
        )
        val client = GitHubWorkspaceClient("token", http, stateStore = store)
        override fun close() = http.close()
        suspend fun dispatch(execution: String? = null): JsonObject = client.execute("publish_release", args(execution = execution)).jsonObject
    }

    @Test fun twoDispatchesOnTheSameBranchAndSourceKeepTheirOwnExactRuns() = runTest {
        Fixture(listOf(501L, 502L)).use { fixture ->
            val first = fixture.dispatch("first-request")
            val replay = fixture.dispatch("first-request")
            assertEquals(first["dispatch_receipt"], replay["dispatch_receipt"])
            assertEquals(1, fixture.dispatches)
            val second = fixture.dispatch("second-request")
            assertEquals("501", first["dispatch_receipt"]!!.jsonObject["workflow_run_id"]!!.jsonPrimitive.content)
            assertEquals("502", second["dispatch_receipt"]!!.jsonObject["workflow_run_id"]!!.jsonPrimitive.content)
            for (run in listOf(501L, 502L)) {
                val status = fixture.client.execute("release_status", args(run)).jsonObject
                assertEquals("DISPATCH_RECEIPT_AND_SOURCE_VERIFIED", status["workflow_correlation"]!!.jsonPrimitive.content)
                assertEquals(run.toString(), status["latest_workflow_run"]!!.jsonObject["id"]!!.jsonPrimitive.content)
                assertFalse(status["release_asset_source_verified"]!!.jsonPrimitive.boolean)
            }
            assertEquals(listOf(501L, 502L), fixture.runsRead)
            val restored = GitHubWorkspaceClient("token", fixture.http, stateStore = fixture.store, taskId = "next-turn")
            assertEquals("DISPATCH_RECEIPT_AND_SOURCE_VERIFIED", restored.execute("release_status", args(501)).jsonObject["workflow_correlation"]!!.jsonPrimitive.content)
            assertEquals("UNKNOWN_DISPATCH_RECEIPT_NOT_AVAILABLE", GitHubWorkspaceClient("other-token", fixture.http, stateStore = fixture.store).execute("release_status", args(502)).jsonObject["workflow_correlation"]!!.jsonPrimitive.content)
            assertTrue(runCatching { fixture.client.execute("release_status", args(501, "v9.9.9")) }.isFailure)
        }
    }

    @Test fun acceptedDispatchWithoutRunReceiptRemainsUnknownAndIsNotRedispatched() = runTest {
        Fixture(listOf(null)).use { fixture ->
            val result = fixture.dispatch()
            assertEquals("UNKNOWN_NO_RUN_RECEIPT_RETURNED", result["dispatch_receipt"]!!.jsonObject["run_correlation"]!!.jsonPrimitive.content)
            val status = fixture.client.execute("release_status", args()).jsonObject
            assertEquals("UNKNOWN_DISPATCH_RECEIPT_NOT_AVAILABLE", status["workflow_correlation"]!!.jsonPrimitive.content)
            assertEquals(JsonNull, status["latest_workflow_run"])
            assertEquals(1, fixture.dispatches)
            assertTrue(fixture.runsRead.isEmpty())
        }
    }

    @Test fun lostDispatchResponseRemainsUncertainAcrossClientRestart() = runTest {
        Fixture(listOf(501L)).use { fixture ->
            fixture.loseAcknowledgement = true
            assertTrue(runCatching { fixture.dispatch("lost-response") }.isFailure)
            val restored = GitHubWorkspaceClient("token", fixture.http, stateStore = fixture.store)
            val recovered = runCatching { restored.execute("publish_release", args(execution = "lost-response")) }.exceptionOrNull()
            assertTrue(recovered is GitHubExecutionBlocked)
            assertEquals(1, fixture.dispatches)
            assertTrue(fixture.runsRead.isEmpty())
        }
    }

    @Test fun presentationChangeCannotRepeatLostDispatchWithinSameExecution() = runTest {
        Fixture(listOf(501L, 502L)).use { fixture ->
            fixture.loseAcknowledgement = true
            assertTrue(runCatching { fixture.dispatch("lost-response") }.isFailure)
            fixture.loseAcknowledgement = false
            val restored = GitHubWorkspaceClient("token", fixture.http, stateStore = fixture.store)
            val changed = JsonObject(args(execution = "lost-response") + ("name" to kotlinx.serialization.json.JsonPrimitive("Reviewed display name")))
            val failure = runCatching { restored.execute("publish_release", changed) }.exceptionOrNull()
            assertEquals("Changing a display-only field must not repeat an uncertain dispatch", 1, fixture.dispatches)
            assertTrue(failure is GitHubExecutionBlocked)
        }
    }

    @Test fun newExecutionIdCannotBypassUnknownDispatchPayload() = runTest {
        Fixture(listOf(501L, 502L)).use { fixture ->
            fixture.loseAcknowledgement = true
            assertTrue(runCatching { fixture.dispatch("lost-response") }.isFailure)
            fixture.loseAcknowledgement = false
            val restored = GitHubWorkspaceClient("token", fixture.http, stateStore = fixture.store)
            val failure = runCatching { restored.execute("publish_release", args(execution = "replacement-id")) }.exceptionOrNull()
            assertEquals("A new operation ID cannot settle the earlier unknown POST", 1, fixture.dispatches)
            assertTrue(failure is GitHubExecutionBlocked)
        }
    }

    @Test fun presentationAndEmptyInputChangesReplayTheExactAcceptedReceipt() = runTest {
        Fixture(listOf(501L)).use { fixture ->
            val first = fixture.dispatch("accepted")
            val changed = JsonObject(args(execution = "accepted") + mapOf("name" to JsonPrimitive("Renamed display"), "inputs" to JsonObject(emptyMap())))
            val restored = GitHubWorkspaceClient("token", fixture.http, stateStore = fixture.store)
            val replay = restored.execute("publish_release", changed).jsonObject
            assertEquals(first["dispatch_receipt"], replay["dispatch_receipt"])
            assertEquals(1, fixture.dispatches)
        }
    }

    @Test fun missingRunIdDoesNotPermitAnotherDispatch() = runTest {
        Fixture(listOf(null, 502L)).use { fixture ->
            fixture.dispatch("accepted-without-id")
            val failure = runCatching { fixture.dispatch("replacement-id") }.exceptionOrNull()
            assertTrue(failure is GitHubExecutionBlocked)
            assertEquals(1, fixture.dispatches)
        }
    }

    @Test fun changedTagCannotSkipVersionValidationByReplayingCanonicalReceipt() = runTest {
        Fixture(listOf(501L, 502L)).use { fixture ->
            fixture.dispatch("accepted")
            val failure = runCatching { fixture.client.execute("publish_release", args(tag = "v9.9.9", execution = "accepted")) }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
            assertTrue(failure!!.message.orEmpty().contains("does not match app versionName"))
            assertEquals(1, fixture.dispatches)
        }
    }

    @Test fun concurrentDispatchWaitsForAuthoritativeReceiptAndCanResumeRejectedAttempt() = runTest {
        Fixture(listOf(501L, 502L)).use { fixture ->
            val sent = CompletableDeferred<Unit>()
            val respond = CompletableDeferred<Unit>()
            fixture.beforeDispatchResponse = {
                sent.complete(Unit)
                respond.await()
            }
            val first = async { fixture.dispatch("first-request") }
            sent.await()
            val contender = GitHubWorkspaceClient("token", fixture.http, stateStore = fixture.store)
            val blocked = runCatching { contender.execute("publish_release", args(execution = "second-request")) }.exceptionOrNull()
            assertTrue(blocked is GitHubExecutionBlocked)
            assertEquals(1, fixture.dispatches)
            respond.complete(Unit)
            first.await()
            fixture.beforeDispatchResponse = null
            val resumed = contender.execute("publish_release", args(execution = "second-request")).jsonObject
            assertEquals("502", resumed["dispatch_receipt"]!!.jsonObject["workflow_run_id"]!!.jsonPrimitive.content)
            assertEquals(2, fixture.dispatches)
        }
    }

    @Test fun definiteRejectionAllowsTheSameOperationToResumeWithoutReleasingUnknownClaims() = runTest {
        Fixture(listOf(501L, 502L)).use { fixture ->
            fixture.rejectDispatch = true
            assertTrue(runCatching { fixture.dispatch("rejected") }.exceptionOrNull() is GitHubRequestRejected)
            fixture.rejectDispatch = false
            val recovered = fixture.dispatch("rejected")
            assertEquals("502", recovered["dispatch_receipt"]!!.jsonObject["workflow_run_id"]!!.jsonPrimitive.content)
            assertEquals(2, fixture.dispatches)
        }
    }

    @Test fun receiptReservationOrGuardCapacityFailureCannotStrandAnUnsentPayload() = runTest {
        val directory = java.nio.file.Files.createTempDirectory("release-capacity").toFile()
        try {
            val reservation = 160L * 1024
            Fixture(listOf(501L), store = GitHubRepositoryStateStore(directory, protectedCapacityBytes = reservation + 1024)).use { fixture ->
                // The outer operation fits, but the canonical dispatch reservation does not.
                assertTrue(runCatching { fixture.dispatch("capacity-recovery") }.isFailure)
                assertEquals(0, fixture.dispatches)
                val markerKey = "execution:" + GitHubRepositoryStateStore.digest("$scope|publish_release.dispatch-migration|v1")
                val markerBytes = fixture.store.read(scope, markerKey)!!.toString().encodeToByteArray().size
                // Both response reservations now fit exactly; the fixed-size pointer cannot.
                val tightStore = GitHubRepositoryStateStore(directory, protectedCapacityBytes = 2 * reservation + markerBytes)
                val tightClient = GitHubWorkspaceClient("token", fixture.http, stateStore = tightStore)
                assertTrue(runCatching { tightClient.execute("publish_release", args(execution = "capacity-recovery")) }.isFailure)
                assertEquals(JsonPrimitive("REJECTED"), canonicalDispatchRow(tightStore)["status"])
                assertEquals(0, fixture.dispatches)
                val restoredStore = GitHubRepositoryStateStore(directory)
                val restored = GitHubWorkspaceClient("token", fixture.http, stateStore = restoredStore)
                val recovered = restored.execute("publish_release", args(execution = "capacity-recovery")).jsonObject
                assertEquals("501", recovered["dispatch_receipt"]!!.jsonObject["workflow_run_id"]!!.jsonPrimitive.content)
                assertEquals(1, fixture.dispatches)
                assertTrue(restoredStore.publicationCapacity()["critical_receipt_bytes"]!!.jsonPrimitive.content.toLong() < reservation)
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun missingOrMalformedCanonicalReceiptBlocksNewOperationAfterStoreRestart() = runTest {
        for (corruption in listOf("missing", "empty", "false-remote-proof")) {
            val directory = java.nio.file.Files.createTempDirectory("release-receipt-loss").toFile()
            try {
                Fixture(listOf(501L, 502L), store = GitHubRepositoryStateStore(directory)).use { fixture ->
                    fixture.dispatch("original")
                    val row = canonicalDispatchRow(fixture.store)
                    val key = "execution:" + row["execution_id"]!!.jsonPrimitive.content
                    if (corruption == "missing") {
                        val body = java.io.File(java.io.File(directory, "execution-receipts"), GitHubRepositoryStateStore.digest("$scope:$key") + ".record")
                        assertTrue(body.delete())
                    } else {
                        fixture.store.update(scope, key) { stored ->
                            JsonObject(requireNotNull(stored) + mapOf("result" to JsonObject(emptyMap()), "remote_reconciled" to JsonPrimitive(corruption == "false-remote-proof")))
                        }
                    }
                    val restored = GitHubWorkspaceClient("token", fixture.http, stateStore = GitHubRepositoryStateStore(directory))
                    assertTrue(runCatching { restored.execute("publish_release", args(execution = "next")) }.exceptionOrNull() is GitHubExecutionBlocked)
                    assertEquals(1, fixture.dispatches)
                }
            } finally {
                directory.deleteRecursively()
            }
        }
    }

    @Test fun boundedOversizedDispatchReceiptDoesNotReleasePayloadGuard() = runTest {
        Fixture(listOf(501L, 502L)).use { fixture ->
            fixture.workflowName += "x".repeat(140 * 1024)
            fixture.dispatch("oversized")
            val row = canonicalDispatchRow(fixture.store)
            val retained = fixture.store.read(scope, "execution:" + row["execution_id"]!!.jsonPrimitive.content)!!
            assertEquals(JsonPrimitive(true), retained["result"]!!.jsonObject["receipt_bounded"])
            assertTrue(runCatching { fixture.dispatch("replacement") }.exceptionOrNull() is GitHubExecutionBlocked)
            assertEquals(1, fixture.dispatches)
        }
    }

    @Test fun knownLegacyUnknownClaimIsLatchedEvenAfterItsContextWasEvicted() = runTest {
        Fixture(listOf(501L)).use { fixture ->
            val input = args(execution = "legacy-lost")
            runCatching {
                GitHubExecutionJournal(fixture.store, scope).run("publish_release", input) {
                    GitHubMutationAttempts.dispatched()
                    throw java.io.IOException("Lost old dispatch acknowledgement")
                }
            }
            fixture.store.remove(scope, "execution-context")
            assertTrue(runCatching { fixture.client.execute("publish_release", input) }.exceptionOrNull() is GitHubExecutionBlocked)
            fixture.store.write(scope, "execution-context", buildJsonObject { put("executions", JsonArray(emptyList())) })
            val changed = JsonObject(input + ("name" to JsonPrimitive("Changed display")))
            assertTrue(runCatching { fixture.client.execute("publish_release", changed) }.exceptionOrNull() is GitHubExecutionBlocked)
            assertEquals(0, fixture.dispatches)
        }
    }

    @Test fun legacyUnknownOrLostBodyRemainsBlockedAfterContextRewrite() = runTest {
        for (loseBody in listOf(false, true)) {
            val directory = java.nio.file.Files.createTempDirectory("release-legacy-loss").toFile()
            try {
                val store = GitHubRepositoryStateStore(directory)
                val input = args(execution = "legacy-lost")
                runCatching {
                    GitHubExecutionJournal(store, scope).run("publish_release", input) {
                        GitHubMutationAttempts.dispatched()
                        throw java.io.IOException("Lost old dispatch acknowledgement")
                    }
                }
                if (loseBody) {
                    val id = GitHubRepositoryStateStore.digest("$scope|publish_release|${GitHubExecutionJournal.canonical(input)}")
                    assertTrue(java.io.File(java.io.File(directory, "execution-receipts"), GitHubRepositoryStateStore.digest("$scope:execution:$id") + ".record").delete())
                }
                Fixture(listOf(501L), store = GitHubRepositoryStateStore(directory)).use { fixture ->
                    for (name in listOf("first-change", "after-context-eviction")) {
                        val changed = JsonObject(input + ("name" to JsonPrimitive(name)))
                        assertTrue(runCatching { fixture.client.execute("publish_release", changed) }.exceptionOrNull() is GitHubExecutionBlocked)
                        fixture.store.remove(scope, "execution-context")
                    }
                    assertEquals(0, fixture.dispatches)
                }
            } finally {
                directory.deleteRecursively()
            }
        }
    }

    @Test fun exactLegacyHttpUnknownClaimSurvivesOuterContextLossAndEmptyInputNormalization() = runTest {
        for (oldEmptyInputs in listOf(false, true)) {
            Fixture(listOf(501L)).use { fixture ->
                val identity = buildJsonObject {
                    put("method", "POST")
                    put("path", "/repos/owner/repo/actions/workflows/42/dispatches")
                    put(
                        "body",
                        buildJsonObject {
                            put("ref", "main")
                            put("return_run_details", true)
                            if (oldEmptyInputs) put("inputs", JsonObject(emptyMap()))
                        }
                    )
                }
                runCatching {
                    GitHubExecutionJournal(fixture.store, scope).run("http.POST", identity, singleHttpRequest = true) {
                        GitHubMutationAttempts.dispatched()
                        throw java.io.IOException("Lost legacy HTTP acknowledgement")
                    }
                }
                fixture.store.remove(scope, "execution-context")
                val request = JsonObject(args(execution = "new-id") + if (oldEmptyInputs) emptyMap() else mapOf("inputs" to JsonObject(emptyMap())))
                assertTrue(runCatching { fixture.client.execute("publish_release", request) }.exceptionOrNull() is GitHubExecutionBlocked)
                assertEquals(0, fixture.dispatches)
            }
        }
    }

    @Test fun exactInvocationHttpUnknownIsLatchedBeforePresentationHidesItsIdentity() = runTest {
        Fixture(listOf(501L)).use { fixture ->
            val input = args(execution = "legacy-invocation")
            val identity = buildJsonObject {
                put("path", "/repos/owner/repo/actions/workflows/42/dispatches")
                put("invocation", GitHubRepositoryStateStore.digest("publish_release|${GitHubExecutionJournal.canonical(input)}"))
                put(
                    "body",
                    buildJsonObject {
                        put("ref", "main")
                        put("return_run_details", true)
                    }
                )
            }
            runCatching {
                GitHubExecutionJournal(fixture.store, scope).run("http.POST", identity, singleHttpRequest = true) {
                    GitHubMutationAttempts.dispatched()
                    throw java.io.IOException("Lost invocation-scoped dispatch acknowledgement")
                }
            }
            fixture.store.remove(scope, "execution-context")
            assertTrue(runCatching { fixture.client.execute("publish_release", input) }.exceptionOrNull() is GitHubExecutionBlocked)
            fixture.store.remove(scope, "execution-context")
            val changed = JsonObject(input + ("name" to JsonPrimitive("Changed display")))
            assertTrue(runCatching { fixture.client.execute("publish_release", changed) }.exceptionOrNull() is GitHubExecutionBlocked)
            assertEquals(0, fixture.dispatches)
        }
    }

    private val scope get() = listOf(GitHubRepositoryStateStore.digest("token"), "", "", "").joinToString(":")

    private suspend fun canonicalDispatchRow(store: GitHubRepositoryStateStore): JsonObject =
        (store.read(scope, "execution-context")!!["executions"] as JsonArray).map { it.jsonObject }.single { it["action"] == JsonPrimitive("publish_release.dispatch") }

    @Test fun changedExpectedSourceStopsBeforeDispatch() = runTest {
        Fixture(emptyList()).use { fixture ->
            val changed = JsonObject(args() + ("expected_head_sha" to kotlinx.serialization.json.JsonPrimitive("b".repeat(40))))
            assertTrue(runCatching { fixture.client.execute("publish_release", changed) }.exceptionOrNull()?.message.orEmpty().contains("head changed"))
            assertEquals(0, fixture.dispatches)
        }
    }

    @Test fun olderRunWithMatchingBranchSourceAndWorkflowCannotEstablishDispatchCorrelation() = runTest {
        Fixture(emptyList()).use { fixture ->
            val status = fixture.client.execute("release_status", args(999)).jsonObject
            assertEquals("UNKNOWN_DISPATCH_RECEIPT_NOT_AVAILABLE", status["workflow_correlation"]!!.jsonPrimitive.content)
            assertEquals(JsonNull, status["latest_workflow_run"])
            assertEquals("999", status["candidate_workflow_run"]!!.jsonObject["id"]!!.jsonPrimitive.content)
        }
    }

    @Test fun dispatchedRunWithChangedSourceIsRejected() = runTest {
        Fixture(listOf(501L)).use { fixture ->
            fixture.dispatch()
            fixture.runHead = "b".repeat(40)
            assertTrue(runCatching { fixture.client.execute("release_status", args(501)) }.isFailure)
        }
    }
}
