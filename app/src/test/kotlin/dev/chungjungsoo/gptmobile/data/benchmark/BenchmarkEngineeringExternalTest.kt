package dev.chungjungsoo.gptmobile.data.benchmark

import dev.chungjungsoo.gptmobile.data.agent.AgentProviderSession
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolExchange
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.github.GatewayEngineeringClient
import dev.chungjungsoo.gptmobile.data.repository.ChatRepository
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BenchmarkEngineeringExternalTest {
    private val hash = "a".repeat(64)
    private val grader = "b".repeat(64)
    private val proposal = "c".repeat(64)
    private val profile = PlatformV2(name = "Selected profile", uid = "selected", model = "selected-model")
    private val test = benchmarkExternalEngineeringCases(BenchmarkEngineeringLane.EXECUTABLE_CODING).first()

    private fun plan(lane: BenchmarkEngineeringLane = BenchmarkEngineeringLane.EXECUTABLE_CODING) = BenchmarkEngineeringPlan(
        id = "external-test", lane = lane, repeats = 3, ordering = BenchmarkEngineeringOrdering.COUNTERBALANCED,
        profileUids = listOf(profile.uid), profileConfigKeys = mapOf(profile.uid to hash),
        fixtureManifestHash = hash, executionPolicy = BenchmarkExecutionPolicy.PROFILE_SETTINGS_V1,
        budget = BenchmarkEngineeringBudget(), sourceCommit = "d".repeat(40), environmentDigest = hash,
        permissionsDigest = hash, externalFixtureDigests = mapOf(checkNotNull(test.engineeringTaskId) to hash),
        graderDigest = grader, externalRouteDigests = mapOf(profile.uid to hash)
    ).frozen()

    private fun result(resolved: Boolean = true): JsonObject = buildJsonObject {
        put("id", test.engineeringTaskId)
        put("fixtureDigest", hash)
        put("graderDigest", grader)
        put("proposalDigest", proposal)
        put("patchDigest", "e".repeat(64))
        put("receiptId", "bench_" + "f".repeat(32))
        put("status", if (resolved) "PASSED" else "FAILED")
        put("qualified", true)
        put("graded", true)
        put("resolved", resolved)
        put("executed", true)
        put("integrity", "VERIFIED")
        put("inputMutationVerification", "PASSED")
        put("dimensions", buildJsonObject { put("regression", 1.0) })
        put(
            "checks",
            buildJsonObject {
                put("hiddenPassed", 10)
                put("hiddenTotal", 10)
            }
        )
    }

    @Test fun nativeRepositoryPackCountsDistinctProblemsAndKeepsCodingPresetsSeparate() {
        for ((preset, count) in listOf("quick" to 4, "standard" to 8, "deep" to 12)) {
            val cases = benchmarkExternalEngineeringCases(BenchmarkEngineeringLane.EXECUTABLE_CODING, preset, taskPack = "repository")
            assertEquals(count, cases.size)
            assertEquals(count, cases.map { it.engineeringFamilyId }.distinct().size)
            assertTrue(cases.all { it.engineeringTaskId!!.startsWith("repository-") })
        }
        assertEquals(12, benchmarkExternalEngineeringCases(BenchmarkEngineeringLane.EXECUTABLE_CODING, "quick").size)
        assertEquals(36, benchmarkExternalEngineeringCases(BenchmarkEngineeringLane.EXECUTABLE_CODING, "standard").size)
        assertEquals(100, benchmarkExternalEngineeringCases(BenchmarkEngineeringLane.EXECUTABLE_CODING, "deep").size)
    }

    @Test fun exactTaskGraderCandidateAndPositiveCheckCoverageAreRequired() {
        val original = result()
        val accepted = benchmarkExternalVerification(original, test.engineeringTaskId!!, plan(), true, true, 1)
        assertTrue(accepted.taskComplete(plan(), test))
        assertFalse(accepted.copy(graderDigest = hash).taskComplete(plan(), test))
        assertFalse(accepted.copy(candidateDigest = null).taskComplete(plan(), test))
        assertFalse(accepted.copy(totalChecks = 0, passedChecks = 0).taskComplete(plan(), test))
        assertFalse(accepted.copy(independentChecksExecuted = false).taskComplete(plan(), test))
        val ungraded = JsonObject(original + ("graded" to JsonPrimitive(false)))
        assertFalse(benchmarkExternalVerification(ungraded, test.engineeringTaskId!!, plan(), true, true, 1).taskComplete(plan(), test))
    }

    @Test fun rejectedProposalStillHasBoundModelFailureEvidenceWithoutClaimingExecutedTests() {
        val rejected = JsonObject(
            result(false) - "patchDigest" - "checks" + mapOf(
                "executed" to JsonPrimitive(false),
                "qualified" to JsonPrimitive(false),
                "error" to JsonPrimitive("PATCH_JSON_INVALID")
            )
        )
        val receipt = benchmarkExternalVerification(rejected, test.engineeringTaskId!!, plan(), false, false, 1)
        assertTrue(receipt.boundTo(plan(), test))
        assertFalse(receipt.taskComplete(plan(), test))
        assertEquals(BenchmarkEngineeringFailureDomain.MODEL, receipt.failureDomain)
        assertEquals(proposal, receipt.candidateDigest)
    }

    @Test fun bestPriorCandidateCannotReplaceTheDeliveredCandidate() {
        val receipt = benchmarkExternalVerification(result(false), test.engineeringTaskId!!, plan(), true, true, 2)
        assertEquals(true, receipt.bestAttemptPassed)
        assertEquals(true, receipt.firstAttemptPassed)
        assertEquals(false, receipt.finalArtifactPassed)
        assertEquals(1, receipt.finalAttemptIndex)
        assertFalse(receipt.taskComplete(plan(), test))
    }

    @Test fun nativeTaskUsesItsActualCalibratedRuntimeChecksWithoutInventingPythonInputProof() {
        val selected = plan().copy(taskPack = "repository").frozen()
        val native = JsonObject(
            result() - "inputMutationVerification" + mapOf(
                "taskPack" to JsonPrimitive("repository"),
                "inputObjectImmutability" to JsonPrimitive("NOT_CLAIMED"),
                "runtime" to buildJsonObject { put("kind", "kotlin") },
                "calibration" to buildJsonObject { put("status", "PASSED") },
                "checks" to buildJsonObject {
                    put("runtimeExecuted", true)
                    put("calibrated", true)
                    put(
                        "hiddenBehavior",
                        buildJsonObject {
                            put("passed", 8)
                            put("total", 8)
                        }
                    )
                }
            )
        )
        assertTrue(benchmarkExternalVerification(native, test.engineeringTaskId!!, selected, true, true, 1).taskComplete(selected, test))
        assertFalse(benchmarkExternalVerification(JsonObject(native - "calibration"), test.engineeringTaskId!!, selected, true, true, 1).taskComplete(selected, test))
    }

    @Test fun terminalStateAloneCannotHideUnconfirmedRemoteShutdown() {
        val pending = buildJsonObject {
            put("state", "INTERRUPTED")
            put("failure", buildJsonObject { put("code", "CANCELLATION_UNCONFIRMED") })
        }
        assertFalse(benchmarkExternalSettled(pending))
        assertTrue(benchmarkExternalSettled(buildJsonObject { put("state", "CANCELLED") }))
        assertFalse(benchmarkExternalSettled(buildJsonObject { put("state", "CANCELLING") }))
        val unknown = buildJsonObject {
            put("state", "FAILED")
            put(
                "failure",
                buildJsonObject {
                    put(
                        "cancellationReceipts",
                        JsonArray(
                            listOf(
                                buildJsonObject {
                                    put("kind", "github_actions")
                                    put("outcome", "unknown")
                                }
                            )
                        )
                    )
                }
            )
        }
        assertFalse(benchmarkExternalSettled(unknown))
    }

    @Test fun liveVerificationRequiresMatchingPublishedAndTestedRevision() {
        val selected = plan(BenchmarkEngineeringLane.LIVE_GITHUB)
        val noReadback = benchmarkExternalVerification(result(), test.engineeringTaskId!!, selected, true, true, 1)
        assertFalse(noReadback.taskComplete(selected, test))
        val live = JsonObject(
            result() + (
                "liveGitHubReceipt" to buildJsonObject {
                    put("testedRevision", "a".repeat(40))
                    put("publishedRevision", "b".repeat(40))
                    put("remoteReadbackVerified", true)
                }
                )
        )
        assertFalse(benchmarkExternalVerification(live, test.engineeringTaskId!!, selected, true, true, 1).taskComplete(selected, test))
        val receipt = noReadback.copy(testedRevision = "a".repeat(40), publishedRevision = "a".repeat(40), remoteReadbackVerified = true)
        assertTrue(receipt.taskComplete(selected, test))
    }

    @Test(expected = IllegalStateException::class)
    fun readbackCannotSubstituteAResponseFromAnotherSubmittedCandidate() {
        val run = buildJsonObject {
            put("id", "bench_" + "f".repeat(32))
            put("cases", JsonArray(listOf(result())))
        }
        benchmarkExternalCase(run, test.engineeringTaskId!!, hash, plan())
    }

    @Test fun absentPhoneArtifactRetainsTheActualInfrastructureReason() {
        val run = buildJsonObject {
            put("id", "bench_" + "f".repeat(32))
            put("cases", JsonArray(emptyList()))
            put("failure", buildJsonObject { put("code", "PHONE_BENCHMARK_ARTIFACT_UNAVAILABLE") })
        }
        val failure = runCatching { benchmarkExternalCase(run, test.engineeringTaskId!!, proposal, plan()) }.exceptionOrNull()
        assertEquals("PHONE_BENCHMARK_ARTIFACT_UNAVAILABLE", failure?.message)
    }

    @Test fun oneTaskReadbackCannotSilentlyAcceptAnAdditionalCase() {
        val run = buildJsonObject {
            put("id", "bench_" + "f".repeat(32))
            put("cases", JsonArray(listOf(result(), JsonObject(result() + ("id" to JsonPrimitive("another-task"))))))
        }
        val failure = runCatching { benchmarkExternalCase(run, test.engineeringTaskId!!, proposal, plan()) }.exceptionOrNull()
        assertEquals("BENCHMARK_CASE_RECEIPT_MISMATCH", failure?.message)
    }

    @Test fun directActionsPlanningUsesTheExplicitPhoneRouteAndFreezesItsObservedIdentity() = runBlocking {
        val phone = mockk<BenchmarkPhoneGitHubExecutor>()
        val gateway = mockk<GatewayEngineeringClient>()
        val settings = mockk<SettingRepository>()
        val cases = listOf(test.copy(engineeringLane = BenchmarkEngineeringLane.LIVE_GITHUB))
        val selected = plan(BenchmarkEngineeringLane.LIVE_GITHUB).copy(
            fixtureManifestHash = BenchmarkEngineeringRegistry.manifestHash(cases),
            externalTransport = BenchmarkEngineeringExternalTransport.PHONE_GITHUB_ACTIONS,
            githubConnectionUid = "explicit-connection",
            repositoryFullName = "owner/disposable",
            baseSha = "a".repeat(40),
            externalFixtureDigests = emptyMap(),
            graderDigest = null,
            externalRouteDigests = emptyMap()
        ).frozen()
        val transport = object : BenchmarkEngineeringTransport {
            override val identityDigest = benchmarkHash("phone-authority")
            override val setupRequestCount = 5
            override suspend fun request(path: String, body: JsonObject?) = buildJsonObject {
                assertEquals("fixtures?seed=17&preset=quick&task_pack=python", path)
                assertEquals(null, body)
                put("sandboxReady", true)
                put("graderDigest", grader)
                put("environmentDigest", hash)
                put(
                    "tasks",
                    JsonArray(
                        listOf(
                            buildJsonObject {
                                put("id", test.engineeringTaskId)
                                put("taskDigest", hash)
                                put("graderDigest", grader)
                            }
                        )
                    )
                )
            }
        }
        coEvery { phone.open(profile.uid, selected) } returns transport
        val executor = BenchmarkEngineeringExternalExecutor(mockk(), settings, gateway, phone)
        val frozen = executor.preparePlan(selected, cases)
        assertTrue(frozen.valid())
        assertEquals(mapOf(test.engineeringTaskId to hash), frozen.externalFixtureDigests)
        assertEquals(benchmarkHash("${benchmarkHash("phone-authority")}\u0000$hash"), frozen.externalRouteDigests[profile.uid])
        assertEquals(benchmarkHash("phone-authority"), frozen.externalAuthorityDigests[profile.uid])
        assertEquals(5, frozen.externalSetupRequestCount)
        coVerify(exactly = 1) { phone.open(profile.uid, selected) }
        coVerify(exactly = 0) { gateway.connectionIdentity(any()) }
    }

    @Test fun actualProviderUsageRetainsFinalProvenanceAndConfiguredOutputCeiling() = runBlocking {
        val chats = mockk<ChatRepository>()
        coEvery { chats.openBenchmarkSession(any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            val invocation = arg<(String) -> Unit>(5)
            val physical = arg<(String) -> Unit>(6)
            object : AgentProviderSession {
                override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                    invocation("ledger")
                    physical("physical")
                    assertTrue(tools.isEmpty())
                    emit(ProviderEvent.RequestConfigured(requestedOutputTokens = 4096, effectiveOutputTokens = 2048))
                    emit(ProviderEvent.TextDelta("{\"changes\":[]}"))
                    emit(ProviderEvent.Usage(inputTokens = 37, outputTokens = 11, finishReason = "stop"))
                    emit(ProviderEvent.Completed)
                }
            }
        }
        val generated = benchmarkEngineeringGeneration(chats, profile, test, "Repair the fixture", null, plan(), "logical", 10_000) {}
        assertEquals(37, generated.inputTokens)
        assertEquals(11, generated.outputTokens)
        assertEquals("final_reported", generated.inputTokenConfidence)
        assertEquals("final_reported", generated.outputTokenConfidence)
        assertEquals("2048", generated.adapterRequestSettings["effectiveOutputTokens"])
        assertEquals(BenchmarkOutcome.PARTIAL, generated.outcome)
        assertFalse(generated.estimatedTokens)
    }

    @Test fun originalAuthorityCanReconcileAfterEvaluatorChangesButAnotherAuthorityCannot() = runBlocking {
        val authority = benchmarkHash("original-authority")
        val selected = plan(BenchmarkEngineeringLane.LIVE_GITHUB).copy(
            externalTransport = BenchmarkEngineeringExternalTransport.PHONE_GITHUB_ACTIONS,
            githubConnectionUid = "original-connection",
            repositoryFullName = "owner/disposable",
            baseSha = "a".repeat(40),
            externalAuthorityDigests = mapOf(profile.uid to authority)
        ).frozen()
        val original = BenchmarkSample(
            test.id,
            test.label,
            test.category,
            BenchmarkOutcome.PARTIAL,
            cleanupReceipt = "gateway:submission_outcome_unknown",
            engineeringAttempts = listOf(BenchmarkEngineeringAttemptReceipt(0, "original_request", proposal, null, "VERIFICATION_PENDING", false, null, null, 42))
        )
        for (changedAuthority in listOf(false, true)) {
            val reads = mutableListOf<String>()
            val transport = object : BenchmarkEngineeringTransport {
                override val identityDigest = benchmarkHash("upgraded-evaluator-route")
                override val authorityDigest = if (changedAuthority) hash else authority
                override suspend fun request(path: String, body: JsonObject?): JsonObject {
                    reads += path
                    assertEquals("?request_id=original_request", path)
                    assertEquals(null, body)
                    return buildJsonObject { put("submissionState", "REMOVED") }
                }
            }
            val phone = mockk<BenchmarkPhoneGitHubExecutor>()
            coEvery { phone.open(profile.uid, selected) } returns transport
            val executor = BenchmarkEngineeringExternalExecutor(mockk(), mockk(), mockk(), phone)
            val reconciled = executor.reconcileCleanup(profile, test, selected, original)
            assertEquals(if (changedAuthority) "gateway:submission_outcome_unknown" else "gateway:terminal_readback_confirmed", reconciled.cleanupReceipt)
            assertEquals(if (changedAuthority) emptyList<String>() else listOf("?request_id=original_request"), reads)
            assertEquals(original.engineeringAttempts, reconciled.engineeringAttempts)
        }
    }

    @Test fun enclosingDeadlineRetainsOriginalGenerationAndConfirmedOrUnknownCleanupBeforeRethrowing() = runBlocking {
        for (settled in listOf(true, false)) {
            val routeIdentity = benchmarkHash("phone-route")
            val selected = plan(BenchmarkEngineeringLane.LIVE_GITHUB).copy(
                externalTransport = BenchmarkEngineeringExternalTransport.PHONE_GITHUB_ACTIONS,
                githubConnectionUid = "original-connection",
                repositoryFullName = "owner/disposable",
                baseSha = "a".repeat(40),
                externalRouteDigests = mapOf(profile.uid to benchmarkHash("$routeIdentity\u0000$hash"))
            ).frozen()
            val chats = mockk<ChatRepository>()
            coEvery { chats.openBenchmarkSession(any(), any(), any(), any(), any(), any(), any()) } coAnswers {
                val invocation = arg<(String) -> Unit>(5)
                val physical = arg<(String) -> Unit>(6)
                object : AgentProviderSession {
                    override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                        invocation("original-invocation")
                        physical("original-physical")
                        emit(ProviderEvent.TextDelta("{\"changes\":[]}"))
                        emit(ProviderEvent.Usage(inputTokens = 13, outputTokens = 7))
                        emit(ProviderEvent.Completed)
                    }
                }
            }
            val settings = mockk<SettingRepository>()
            coEvery { settings.fetchPlatformV2s() } returns listOf(profile)
            var submitted = false
            var cancelCalls = 0
            val job = "bench_" + "f".repeat(32)
            val originalRequest = "eng_${benchmarkHash("original-logical:0").take(48)}"
            fun remoteRun() = buildJsonObject {
                put("id", job)
                put("state", if (cancelCalls > 0 && settled) "CANCELLED" else "RUNNING")
            }
            val transport = object : BenchmarkEngineeringTransport {
                override val identityDigest = routeIdentity
                override suspend fun request(path: String, body: JsonObject?): JsonObject = when {
                    path.startsWith("fixtures/") -> buildJsonObject {
                        put("taskDigest", hash)
                        put("graderDigest", grader)
                        put("environmentDigest", hash)
                        put("prompt", "Repair the exact pinned fixture")
                        put("outputSchema", buildJsonObject {})
                    }
                    path.startsWith("?request_id=") -> buildJsonObject {
                        assertEquals("?request_id=$originalRequest", path)
                        if (submitted) put("run", remoteRun()) else put("submissionState", "NOT_OBSERVED")
                    }
                    path == "evaluate" -> {
                        assertEquals(JsonPrimitive(originalRequest), body?.get("request_id"))
                        submitted = true
                        remoteRun()
                    }
                    path == job -> remoteRun()
                    path == "cancel-request" -> {
                        assertEquals(JsonPrimitive(originalRequest), body?.get("request_id"))
                        cancelCalls++
                        if (!settled) error("TEST_REMOTE_STOP_UNCONFIRMED")
                        buildJsonObject { put("run", remoteRun()) }
                    }
                    else -> error("Unexpected test route: $path")
                }
            }
            val phone = mockk<BenchmarkPhoneGitHubExecutor>()
            coEvery { phone.open(profile.uid, selected) } returns transport
            val executor = BenchmarkEngineeringExternalExecutor(chats, settings, mockk(), phone)
            var interrupted: BenchmarkSample? = null
            var checkpoint: BenchmarkSample? = null
            val timeout = try {
                withTimeout(2_000) {
                    executor.execute(profile, test, selected, "original-logical", { interrupted = it }, { checkpoint = it })
                }
                null
            } catch (error: TimeoutCancellationException) {
                error
            }
            assertTrue(submitted)
            assertNotNull(timeout)
            assertEquals(1, cancelCalls)
            val retained = assertNotNullSample(interrupted)
            assertEquals(BenchmarkOutcome.CANCELED, retained.outcome)
            assertEquals(listOf("original-physical"), retained.physicalRequestIds)
            assertEquals(listOf("original-invocation"), retained.invocationIds)
            assertEquals(13, retained.inputTokens)
            assertEquals(7, retained.outputTokens)
            assertEquals("original-logical", retained.logicalRequestId)
            assertEquals(assertNotNullSample(checkpoint).engineeringAttempts, retained.engineeringAttempts)
            assertEquals(1, retained.engineeringAttempts.size)
            assertEquals(if (settled) "gateway:terminal_readback_confirmed" else "gateway:shutdown_unconfirmed", retained.cleanupReceipt)
            assertEquals(!settled, retained.engineeringCleanupPending())
            coVerify(exactly = 1) { chats.openBenchmarkSession(any(), any(), any(), any(), any(), any(), any()) }
        }
    }

    private fun assertNotNullSample(sample: BenchmarkSample?): BenchmarkSample {
        assertNotNull("Cancellation must retain the original generation and pending attempt", sample)
        return requireNotNull(sample)
    }

    @Test fun observedOutputBoundsAreModelFailuresWithoutInventedHiddenChecksOrRemoteExecution() = runBlocking {
        for (answer in listOf("a".repeat(65_537), "é".repeat(32_769))) {
            val chats = mockk<ChatRepository>()
            coEvery { chats.openBenchmarkSession(any(), any(), any(), any(), any(), any(), any()) } returns object : AgentProviderSession {
                override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                    emit(ProviderEvent.TextDelta(answer))
                    emit(ProviderEvent.Completed)
                }
            }
            val generation = benchmarkEngineeringGeneration(chats, profile, test, "Repair", null, plan(), "original", 10_000) {}
            val receipt = checkNotNull(benchmarkGenerationContractVerification(generation, test.engineeringTaskId!!, plan(), "original", 1, null, false))
            assertEquals(BenchmarkOutcome.FAILED, generation.outcome)
            assertEquals("PATCH_OUTPUT_TOO_LARGE", receipt.reasonCode)
            assertEquals("LOCAL_GENERATION_CONTRACT", receipt.verificationScope)
            assertTrue(receipt.boundTo(plan(), test))
            assertFalse(receipt.taskComplete(plan(), test))
            assertFalse(receipt.independentChecksExecuted)
            assertNull(receipt.hiddenChecksPassed)
            assertNull(receipt.remoteEvidence)
            assertEquals(false, benchmarkEngineeringTaskResult(plan(), test, generation.copy(engineeringVerification = receipt, cleanupReceipt = "gateway:no_submission_dispatched")))
            assertFalse(receipt.copy(independentChecksExecuted = true, finalArtifactPassed = true).boundTo(plan(), test))
        }
    }

    @Test fun providerExceptionTextCannotImpersonateALocalOutputContractObservation() = runBlocking {
        val chats = mockk<ChatRepository>()
        coEvery { chats.openBenchmarkSession(any(), any(), any(), any(), any(), any(), any()) } throws IllegalStateException("PATCH_OUTPUT_TOO_LARGE")
        val generation = benchmarkEngineeringGeneration(chats, profile, test, "Repair", null, plan(), "original", 10_000) {}
        assertEquals(BenchmarkOutcome.ERROR, generation.outcome)
        assertNull(benchmarkGenerationContractVerification(generation, test.engineeringTaskId!!, plan(), "original", 1, null, false))
    }

    @Test fun remoteEvidenceKeepsInspectibleCoordinatesWithoutPrivateJournalContents() {
        val live = JsonObject(
            result() + (
                "liveGitHubReceipt" to buildJsonObject {
                    put("repository", "owner/disposable")
                    put("workflowRunId", 12345)
                    put("artifactId", 23456)
                    put("artifactDigest", hash)
                    put("journalPath", "/private/journal")
                    put("candidateSource", "private candidate")
                }
                )
        )
        val receipt = benchmarkExternalVerification(live, test.engineeringTaskId!!, plan(), true, true, 1)
        assertEquals(setOf("repository", "workflowRunId", "artifactId", "artifactDigest"), receipt.remoteEvidence?.keys)
    }
}
