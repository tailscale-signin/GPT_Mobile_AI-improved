package dev.chungjungsoo.gptmobile.data.benchmark

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.github.GatewayEngineeringClient
import dev.chungjungsoo.gptmobile.data.github.GatewayScopeIdentity
import dev.chungjungsoo.gptmobile.data.github.engineeringConfig
import dev.chungjungsoo.gptmobile.data.github.engineeringGateway
import dev.chungjungsoo.gptmobile.data.github.withGatewayIdentity
import dev.chungjungsoo.gptmobile.data.repository.ChatRepository
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import io.ktor.http.encodeURLParameter
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** Model inference stays on the selected profile; only a fenced artifact goes to the independent host. */
@Singleton
class BenchmarkEngineeringExternalExecutor @Inject constructor(
    private val chats: ChatRepository,
    private val settings: SettingRepository,
    private val client: GatewayEngineeringClient,
    private val phone: BenchmarkPhoneGitHubExecutor
) {
    private suspend fun route(profileUid: String, plan: BenchmarkEngineeringPlan): BenchmarkEngineeringTransport {
        if (plan.externalTransport == BenchmarkEngineeringExternalTransport.PHONE_GITHUB_ACTIONS) {
            return phone.open(profileUid, plan)
        }
        val gateway = settings.engineeringGateway(profileUid)
        val config = gateway.engineeringConfig()
        val identity = client.connectionIdentity(config)
        val fenced = config.withGatewayIdentity(GatewayScopeIdentity(identity.gatewayId, identity.principal))
        return object : BenchmarkEngineeringTransport {
            override val identityDigest = benchmarkHash(listOf(identity.gatewayId, identity.principal, benchmarkConfigKey(gateway, "", BenchmarkExecutionPolicy.PROFILE_SETTINGS_V1)).joinToString("\u0000"))
            override val authorityDigest = benchmarkHash(listOf(gateway.uid, gateway.apiUrl.trim().trimEnd('/'), identity.gatewayId, identity.principal, "gateway-engineering-owner").joinToString("\u0000"))
            override var setupRequestCount: Int = 1
                private set
            override suspend fun request(path: String, body: JsonObject?): JsonObject {
                setupRequestCount++
                return client.benchmark(fenced, path, body)
            }
        }
    }

    /** Frozen against every selected host before the first profile can see any executable task. */
    suspend fun preparePlan(
        plan: BenchmarkEngineeringPlan,
        cases: List<BenchmarkCase>,
        onSetupCheckpoint: suspend (BenchmarkEngineeringPlan) -> Unit = {}
    ): BenchmarkEngineeringPlan = benchmarkPrepareEngineeringPlan(plan, cases, { profileUid -> route(profileUid, plan) }, onSetupCheckpoint)

    suspend fun execute(
        profile: PlatformV2,
        test: BenchmarkCase,
        plan: BenchmarkEngineeringPlan,
        logicalRequestId: String,
        onInterrupted: (BenchmarkSample) -> Unit = {},
        onCheckpoint: suspend (BenchmarkSample) -> Unit = {}
    ): BenchmarkSample {
        require(plan.valid())
        val started = System.nanoTime() / 1_000_000
        val taskId = requireNotNull(test.engineeringTaskId)
        val generations = mutableListOf<BenchmarkSample>()
        val attempts = mutableListOf<BenchmarkEngineeringAttemptReceipt>()
        var grade: JsonObject? = null
        var localVerification: BenchmarkEngineeringVerification? = null
        var activeJob: String? = null
        var activeRequestId: String? = null
        var activeRoute: BenchmarkEngineeringTransport? = null
        var validationMs = 0L
        var validatingSince: Long? = null
        var cleanupReceipt: String? = null
        var firstPassed: Boolean? = null
        var bestPassed = false
        fun elapsed() = (System.nanoTime() / 1_000_000 - started).coerceAtLeast(0)
        fun sample(outcome: BenchmarkOutcome, reason: String? = null): BenchmarkSample {
            val last = generations.lastOrNull()
            val verification = localVerification ?: grade?.let { benchmarkExternalVerification(it, taskId, plan, firstPassed, bestPassed, attempts.size) }
            val combined = last ?: BenchmarkSample(test.id, test.label, test.category, outcome)
            return combined.copy(
                testId = test.id, label = test.label, category = test.category,
                outcome = outcome, durationMs = elapsed(),
                inputTokens = generations.sumOf { it.inputTokens.toLong() }.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                outputTokens = generations.sumOf { it.outputTokens.toLong() }.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                estimatedTokens = generations.isEmpty() || generations.any { it.estimatedTokens },
                inputTokenSource = benchmarkCombinedTokenSource(generations.map { it.inputTokenSource }),
                outputTokenSource = benchmarkCombinedTokenSource(generations.map { it.outputTokenSource }),
                inputTokenConfidence = benchmarkCombinedConfidence(generations.map { it.inputTokenConfidence }),
                outputTokenConfidence = benchmarkCombinedConfidence(generations.map { it.outputTokenConfidence }),
                effectiveSettingsConfirmed = generations.isNotEmpty() && generations.all { it.effectiveSettingsConfirmed },
                reasoningTokens = generations.mapNotNull { it.reasoningTokens }.takeIf { it.size == generations.size && it.isNotEmpty() }?.sum(),
                reasoningTokenSource = benchmarkCombinedTokenSource(generations.map { it.reasoningTokenSource }),
                reasoningTokenConfidence = benchmarkCombinedConfidence(generations.map { it.reasoningTokenConfidence }),
                reportedCachedPromptTokens = generations.mapNotNull { it.reportedCachedPromptTokens }.takeIf { it.size == generations.size && it.isNotEmpty() }?.sum(),
                rounds = generations.flatMap { it.rounds }.mapIndexed { i, round -> round.copy(round = i) },
                physicalRequestIds = generations.flatMap { it.physicalRequestIds },
                invocationIds = generations.flatMap { it.invocationIds },
                localSegments = generations.flatMap { it.localSegments },
                runtimeExecutions = generations.flatMap { it.runtimeExecutions },
                logicalRequestId = logicalRequestId,
                phaseElapsedMs = mapOf("provider_request" to generations.sumOf { it.durationMs }, "independent_verification" to (validationMs + (validatingSince?.let { System.nanoTime() / 1_000_000 - it } ?: 0))),
                retryDurationMs = generations.drop(1).sumOf { it.durationMs } + generations.sumOf { it.retryDurationMs },
                reconnectAttempts = generations.sumOf { it.reconnectAttempts },
                requestDispatched = generations.any { it.requestDispatched == true },
                evidenceScope = plan.lane.evidenceClass,
                fixtureHash = plan.externalFixtureDigests[taskId],
                fixtureContentHash = plan.externalFixtureDigests[taskId],
                validatorVersion = plan.graderDigest ?: "external_grader_unavailable",
                timingEligible = false, throughputExclusion = "NOT_A_TIMING_WORKLOAD",
                failureCategory = reason, error = reason, workload = test.workload,
                engineeringVerification = verification,
                engineeringAttempts = attempts.toList(),
                validation = null,
                completionKind = when {
                    verification?.verificationScope == "LOCAL_GENERATION_CONTRACT" -> "observed_generation_contract_failure"
                    verification?.taskComplete(plan, test) == true -> "independently_verified_artifact"
                    else -> "external_verification_incomplete"
                },
                gatewayJobIdHash = activeJob?.let(::benchmarkHash),
                cleanupReceipt = cleanupReceipt
            )
        }
        try {
            return withTimeout(plan.budget.taskDeadlineMs) {
                val selected = route(profile.uid, plan)
                activeRoute = selected
                val fixture = selected.request("fixtures/${taskId.encodeURLParameter()}?seed=${plan.seed}&preset=${plan.preset}&task_pack=${plan.hostTaskPack()}")
                check(fixture.text("taskDigest") == plan.externalFixtureDigests[taskId] && fixture.text("graderDigest") == plan.graderDigest) { "BENCHMARK_FIXTURE_CHANGED" }
                check(benchmarkHash(selected.identityDigest + "\u0000" + fixture.text("environmentDigest")) == plan.externalRouteDigests[profile.uid]) { "BENCHMARK_ROUTE_CHANGED" }
                val originalPrompt = requireNotNull(fixture.text("prompt"))
                val schema = (fixture["outputSchema"] as? JsonObject)?.toString()
                var prompt = originalPrompt
                for (index in 0 until minOf(plan.budget.maxRepairAttempts + 1, plan.budget.maxModelRounds)) {
                    currentCoroutineContext().ensureActive()
                    // Evidence from a prior proposal cannot qualify an interrupted repair.
                    grade = null
                    localVerification = null
                    activeJob = null
                    activeRequestId = null
                    cleanupReceipt = null
                    verifyProfile(profile, plan)
                    val requestId = "eng_${benchmarkHash("$logicalRequestId:$index").take(48)}"
                    val generation = benchmarkEngineeringGeneration(
                        chats,
                        profile,
                        test,
                        prompt,
                        schema,
                        plan,
                        requestId,
                        (plan.budget.taskDeadlineMs - elapsed()).coerceAtLeast(1)
                    ) { interrupted ->
                        generations += interrupted
                        attempts += interrupted.attemptReceipt(index, requestId, null, false)
                        onInterrupted(sample(BenchmarkOutcome.CANCELED, "ENGINEERING_GENERATION_INTERRUPTED"))
                    }
                    generations += generation
                    verifyProfile(profile, plan)
                    val localFailure = benchmarkGenerationContractVerification(generation, taskId, plan, requestId, index + 1, firstPassed, bestPassed)
                    if (localFailure != null) {
                        if (index == 0) firstPassed = false
                        localVerification = localFailure.copy(firstAttemptPassed = firstPassed)
                        cleanupReceipt = "gateway:no_submission_dispatched"
                        attempts += generation.attemptReceipt(index, requestId, localFailure.receiptId, false).copy(verification = localVerification)
                        onCheckpoint(sample(BenchmarkOutcome.PARTIAL, localFailure.reasonCode))
                        prompt = "$originalPrompt\nThe previous generation violated the declared output contract (${localFailure.reasonCode}). Return only the bounded complete patch, without tool calls, against the ORIGINAL displayed blobs."
                        continue
                    }
                    if (generation.failureCategory !in setOf(null, "NO_VISIBLE_ANSWER", "PATCH_OUTPUT_TRUNCATED") || generation.terminalReceived != true) {
                        attempts += generation.attemptReceipt(index, requestId, null, false)
                        return@withTimeout sample(generation.outcome, generation.failureCategory ?: "PROVIDER_TERMINAL_MISSING")
                    }
                    val proposal = requireNotNull(generation.canonicalAnswer)
                    attempts += generation.attemptReceipt(index, requestId, null, false, "VERIFICATION_PENDING")
                    val body = buildJsonObject {
                        put("request_id", requestId)
                        put("task_id", taskId)
                        put("seed", plan.seed)
                        put("preset", plan.preset)
                        put("task_pack", plan.hostTaskPack())
                        put("fixture_digest", requireNotNull(plan.externalFixtureDigests[taskId]))
                        put("grader_digest", requireNotNull(plan.graderDigest))
                        put("environment_digest", requireNotNull(fixture.text("environmentDigest")))
                        put("proposal", proposal)
                        put("proposal_digest", benchmarkHash(proposal))
                        put("evidence_lane", if (plan.lane == BenchmarkEngineeringLane.LIVE_GITHUB) "LIVE_GITHUB" else "EXECUTABLE")
                        put("cohort", plan.cohortId)
                        if (plan.lane == BenchmarkEngineeringLane.LIVE_GITHUB) {
                            put("repository", requireNotNull(plan.repositoryFullName))
                            put("base_sha", requireNotNull(plan.baseSha))
                        }
                        put("generation_receipt", generationReceipt(profile, generation, plan))
                    }
                    val gradingStarted = System.nanoTime() / 1_000_000
                    validatingSince = gradingStarted
                    activeRequestId = requestId
                    cleanupReceipt = "gateway:submission_outcome_unknown"
                    // Durable intent must precede admission; a process restart can only reconcile this key.
                    onCheckpoint(sample(BenchmarkOutcome.PARTIAL, "VERIFICATION_PENDING"))
                    val submitted = submitOnce(selected, requestId, body)
                    activeJob = requireNotNull(submitted.text("id")).also { check(it.matches(Regex("bench_[a-f0-9]{32}"))) }
                    var result = submitted
                    while (result.text("state") !in TERMINAL) {
                        delay(1_000)
                        result = selected.request(requireNotNull(activeJob))
                    }
                    val attemptValidationMs = System.nanoTime() / 1_000_000 - gradingStarted
                    validationMs += attemptValidationMs
                    validatingSince = null
                    cleanupReceipt = if (benchmarkExternalSettled(result)) "gateway:terminal_readback_confirmed" else "gateway:shutdown_unconfirmed"
                    grade = benchmarkExternalCase(result, taskId, benchmarkHash(proposal), plan)
                    val currentGrade = requireNotNull(grade)
                    val passed = benchmarkExternalVerification(currentGrade, taskId, plan, firstPassed, bestPassed, attempts.size).taskComplete(plan, test)
                    if (index == 0) firstPassed = passed
                    bestPassed = bestPassed || passed
                    attempts[index] = generation.attemptReceipt(index, requestId, activeJob, passed, currentGrade.text("error")).copy(
                        durationMs = generation.durationMs + attemptValidationMs,
                        verification = benchmarkExternalVerification(currentGrade, taskId, plan, firstPassed, bestPassed, attempts.size)
                    )
                    onCheckpoint(sample(if (passed) BenchmarkOutcome.PASSED else BenchmarkOutcome.PARTIAL, currentGrade.text("error")?.safeCode()))
                    if (passed) return@withTimeout sample(BenchmarkOutcome.PASSED)
                    val state = currentGrade.text("status")
                    if (state in setOf("ERROR", "BLOCKED", "CANCELLED", "INTERRUPTED")) {
                        return@withTimeout sample(BenchmarkOutcome.ERROR, currentGrade.text("error")?.safeCode() ?: "INDEPENDENT_GRADER_UNAVAILABLE")
                    }
                    // Only a bounded public error code is returned between repairs. Hidden inputs and answers remain private.
                    val reason = currentGrade.text("error")?.safeCode() ?: "TASK_CHECKS_FAILED"
                    prompt = "$originalPrompt\nYour preceding candidate did not satisfy all required checks ($reason). Submit a corrected complete patch against the ORIGINAL displayed blobs. Hidden test inputs and answers are not disclosed.\nPrevious proposal:\n${proposal.take(65_536)}"
                }
                sample(BenchmarkOutcome.FAILED, localVerification?.reasonCode ?: grade?.text("error")?.safeCode() ?: "TASK_CHECKS_FAILED")
            }
        } catch (timeout: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            cleanupReceipt = cancelJob(activeRoute, activeJob, activeRequestId)
            return sample(BenchmarkOutcome.TIMED_OUT, "ENGINEERING_TASK_DEADLINE")
        } catch (cancel: CancellationException) {
            cleanupReceipt = cancelJob(activeRoute, activeJob, activeRequestId)
            onInterrupted(sample(BenchmarkOutcome.CANCELED, "ENGINEERING_INTERRUPTED"))
            throw cancel
        } catch (failure: Exception) {
            if (failure is BenchmarkStorageFailure) throw failure
            cleanupReceipt = cancelJob(activeRoute, activeJob, activeRequestId)
            val code = failure.message?.safeCode() ?: "EXTERNAL_VERIFICATION_UNAVAILABLE"
            return sample(BenchmarkOutcome.ERROR, code)
        }
    }

    private suspend fun verifyProfile(profile: PlatformV2, plan: BenchmarkEngineeringPlan) {
        val current = settings.fetchPlatformV2s().singleOrNull { it.uid == profile.uid } ?: error("BENCHMARK_PROFILE_UNAVAILABLE")
        check(benchmarkConfigKey(current, "", plan.executionPolicy) == benchmarkConfigKey(profile, "", plan.executionPolicy)) { "BENCHMARK_PROFILE_CHANGED" }
    }

    private suspend fun submitOnce(transport: BenchmarkEngineeringTransport, requestId: String, body: JsonObject): JsonObject {
        val previous = transport.request("?request_id=${requestId.encodeURLParameter()}")
        (previous["run"] as? JsonObject)?.let { return it }
        check(previous.text("submissionState") == "NOT_OBSERVED") { "BENCHMARK_REQUEST_ID_RETIRED" }
        return try {
            transport.request("evaluate", body)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            // A lost response is reconciled by the SAME immutable request ID, never replayed as another write.
            val readback = transport.request("?request_id=${requestId.encodeURLParameter()}")
            (readback["run"] as? JsonObject) ?: error("BENCHMARK_SUBMISSION_OUTCOME_UNKNOWN")
        }
    }

    /** Retire the original admission key before trusting an absent response. Never resubmit a proposal. */
    private suspend fun cancelJob(route: BenchmarkEngineeringTransport?, id: String?, requestId: String?): String {
        if (requestId == null) return "gateway:no_submission_dispatched"
        if (route == null) return "gateway:submission_outcome_unknown"
        return withContext(NonCancellable) {
            withTimeoutOrNull(10_000) {
                try {
                    var run = id?.let { route.request(it) }
                    if (run == null) {
                        val observed = route.request("?request_id=${requestId.encodeURLParameter()}")
                        if (observed.text("submissionState") == "REMOVED") return@withTimeoutOrNull "gateway:terminal_readback_confirmed"
                        run = observed["run"] as? JsonObject
                    }
                    if (run?.let(::benchmarkExternalSettled) == true) return@withTimeoutOrNull "gateway:terminal_readback_confirmed"
                    val canceled = route.request("cancel-request", buildJsonObject { put("request_id", requestId) })
                    if (canceled.text("submissionState") == "REMOVED") return@withTimeoutOrNull "gateway:terminal_readback_confirmed"
                    run = (canceled["run"] as? JsonObject) ?: run
                    val observedId = run?.text("id")
                    if (observedId == null || !observedId.matches(Regex("bench_[a-f0-9]{32}"))) {
                        return@withTimeoutOrNull "gateway:submission_outcome_unknown"
                    }
                    repeat(4) {
                        if (run?.let(::benchmarkExternalSettled) == true) return@withTimeoutOrNull "gateway:terminal_readback_confirmed"
                        delay(500)
                        run = route.request(observedId)
                    }
                    "gateway:shutdown_unconfirmed"
                } catch (_: Exception) {
                    if (id == null) "gateway:submission_outcome_unknown" else "gateway:shutdown_unconfirmed"
                }
            } ?: if (id == null) "gateway:submission_outcome_unknown" else "gateway:shutdown_unconfirmed"
        }
    }

    /** Resume only reconciles the original receipt; it cannot generate or submit another candidate. */
    suspend fun reconcileCleanup(profile: PlatformV2, test: BenchmarkCase, plan: BenchmarkEngineeringPlan, sample: BenchmarkSample): BenchmarkSample {
        if (sample.cleanupReceipt !in setOf("gateway:submission_outcome_unknown", "gateway:shutdown_unconfirmed")) return sample
        return try {
            require(plan.valid() && test.engineeringTaskId != null)
            val selected = route(profile.uid, plan)
            val authority = plan.externalAuthorityDigests[profile.uid]
            if (authority != null) {
                // Original authority can settle its saved job even after its grader/image is upgraded.
                // This path cannot generate, submit or qualify a replacement candidate.
                check(selected.authorityDigest == authority) { "BENCHMARK_AUTHORITY_CHANGED" }
            } else {
                // Older plans did not retain a separate authority fence; preserve their stricter check.
                val fixture = selected.request("fixtures/${test.engineeringTaskId.encodeURLParameter()}?seed=${plan.seed}&preset=${plan.preset}&task_pack=${plan.hostTaskPack()}")
                check(fixture.text("taskDigest") == plan.externalFixtureDigests[test.engineeringTaskId] && fixture.text("graderDigest") == plan.graderDigest) { "BENCHMARK_FIXTURE_CHANGED" }
                check(benchmarkHash(selected.identityDigest + "\u0000" + fixture.text("environmentDigest")) == plan.externalRouteDigests[profile.uid]) { "BENCHMARK_ROUTE_CHANGED" }
            }
            val pending = sample.engineeringAttempts.lastOrNull { it.outcome == "VERIFICATION_PENDING" }
                ?: sample.engineeringAttempts.lastOrNull()
            sample.copy(cleanupReceipt = cancelJob(selected, pending?.receiptId, pending?.logicalRequestId))
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            sample
        }
    }

    companion object {
        private val TERMINAL = setOf("COMPLETED", "FAILED", "BLOCKED", "CANCELLED", "INTERRUPTED")
    }
}

private fun BenchmarkEngineeringPlan.hostTaskPack(): String = if (taskPack == "repository") "repository" else "python"

private fun generationReceipt(profile: PlatformV2, sample: BenchmarkSample, plan: BenchmarkEngineeringPlan) = buildJsonObject {
    put("model", profile.model)
    put("profile_uid", profile.uid)
    put("profile_revision", requireNotNull(plan.profileConfigKeys[profile.uid]))
    put("settings_digest", benchmarkHash(benchmarkProfileSettings(profile).toString()))
    put("provider_kind", profile.compatibleType.name)
    put("route_identity", benchmarkHash(profile.apiUrl))
    put("request_ids", JsonArray((sample.physicalRequestIds + sample.invocationIds).distinct().map(::JsonPrimitive)))
    put("observed_model", JsonNull)
    put("identity_verified", sample.requestDispatched == true && (sample.physicalRequestIds.isNotEmpty() || sample.invocationIds.isNotEmpty()))
    put("input_tokens", if (sample.inputTokenConfidence == "final_reported") JsonPrimitive(sample.inputTokens) else JsonNull)
    put("output_tokens", if (!sample.estimatedTokens) JsonPrimitive(sample.outputTokens) else JsonNull)
    put("usage_complete", sample.inputTokenConfidence == "final_reported" && !sample.estimatedTokens)
    put("duration_ms", sample.durationMs)
    put("app_commit", plan.sourceCommit)
    put("settings_confirmed", sample.effectiveSettingsConfirmed)
    put("acknowledged_fields", JsonArray(emptyList()))
}

private fun BenchmarkSample.attemptReceipt(index: Int, requestId: String, receiptId: String?, verified: Boolean, reason: String? = failureCategory) = BenchmarkEngineeringAttemptReceipt(
    index, requestId, canonicalAnswer?.let(::benchmarkHash), receiptId,
    if (verified) "PASSED" else reason?.safeCode() ?: "FAILED", verified,
    inputTokens.takeIf { inputTokenConfidence == "final_reported" }, outputTokens.takeUnless { estimatedTokens }, durationMs, reason?.safeCode(),
    effectiveSettingsConfirmed, adapterRequestSettings, physicalRequestIds, invocationIds
)

internal fun benchmarkExternalCase(run: JsonObject, taskId: String, proposalDigest: String, plan: BenchmarkEngineeringPlan): JsonObject {
    val rows = run["cases"] as? JsonArray
    if (rows.isNullOrEmpty()) {
        val reason = (run["failure"] as? JsonObject)?.text("code")?.safeCode()
        error(reason ?: "BENCHMARK_CASE_RECEIPT_MISSING")
    }
    val result = (rows.singleOrNull() as? JsonObject)?.takeIf { it.text("id") == taskId }
        ?: error("BENCHMARK_CASE_RECEIPT_MISMATCH")
    check(result.text("fixtureDigest") == plan.externalFixtureDigests[taskId]) { "BENCHMARK_FIXTURE_RECEIPT_MISMATCH" }
    check(result.text("graderDigest") == plan.graderDigest) { "BENCHMARK_GRADER_RECEIPT_MISMATCH" }
    check(result.text("proposalDigest") == proposalDigest) { "BENCHMARK_CANDIDATE_RECEIPT_MISMATCH" }
    return JsonObject(result + mapOf("receiptId" to JsonPrimitive(requireNotNull(run.text("id")))))
}

internal fun benchmarkExternalVerification(result: JsonObject, taskId: String, plan: BenchmarkEngineeringPlan, firstPassed: Boolean?, bestPassed: Boolean, attemptCount: Int): BenchmarkEngineeringVerification {
    val checks = result["checks"] as? JsonObject
    val nativeChecks = checks?.get("hiddenBehavior") as? JsonObject
    val dimensions = result["dimensions"] as? JsonObject
    val live = result["liveGitHubReceipt"] as? JsonObject
    val verified = result.boolean("qualified") == true
    val resolved = result.boolean("resolved") == true
    val hidden = checks?.integer("hiddenTotal") ?: nativeChecks?.integer("total")
    val passed = checks?.integer("hiddenPassed") ?: nativeChecks?.integer("passed")
    val nativeIntegrity = plan.taskPack == "repository" &&
        result.text("taskPack") == "repository" &&
        (result["calibration"] as? JsonObject)?.text("status") == "PASSED" &&
        result["runtime"] is JsonObject &&
        checks?.boolean("runtimeExecuted") == true &&
        checks.boolean("calibrated") == true
    return BenchmarkEngineeringVerification(
        taskId, result.text("fixtureDigest").orEmpty(), result.text("graderDigest").orEmpty(), result.text("patchDigest") ?: result.text("proposalDigest"),
        result.text("receiptId").orEmpty(), benchmarkHash(result.toString()), plan.lane.evidenceClass,
        result.boolean("executed") == true, verified && result.boolean("graded") == true && hidden != null && hidden > 0,
        if (hidden == null || passed == null) null else passed == hidden,
        dimensions?.number("regression")?.let { it == 1.0 },
        result.text("integrity") == "VERIFIED" && (nativeIntegrity || result.text("inputMutationVerification") == "PASSED"),
        firstPassed, resolved, attemptCount,
        testedRevision = live?.text("testedRevision"), publishedRevision = live?.text("publishedRevision"),
        remoteReadbackVerified = live?.boolean("remoteReadbackVerified"),
        failureDomain = when {
            resolved -> null
            result.text("status") in setOf("ERROR", "BLOCKED", "INTERRUPTED") -> BenchmarkEngineeringFailureDomain.HARNESS
            else -> BenchmarkEngineeringFailureDomain.MODEL
        },
        reasonCode = result.text("error")?.safeCode(), passedChecks = passed, totalChecks = hidden,
        bestAttemptPassed = bestPassed,
        selectedAttemptIndex = (attemptCount - 1).takeIf { it >= 0 },
        finalAttemptIndex = (attemptCount - 1).takeIf { it >= 0 },
        remoteEvidence = benchmarkExternalRemoteEvidence(live)
    )
}

/** A local observed contract failure is a known failed attempt, never a remote test pass. */
internal fun benchmarkGenerationContractVerification(sample: BenchmarkSample, taskId: String, plan: BenchmarkEngineeringPlan, requestId: String, attemptCount: Int, firstPassed: Boolean?, bestPassed: Boolean): BenchmarkEngineeringVerification? {
    val code = sample.failureCategory?.takeIf { it in setOf("PATCH_OUTPUT_TOO_LARGE", "ENGINEERING_UNEXPECTED_TOOL_CALL") } ?: return null
    val observation = sample.adapterRequestSettings["generationContractEvidenceDigest"]?.takeIf { it.matches(Regex("[a-f0-9]{64}")) } ?: return null
    if (sample.completionKind != "local_generation_contract_failed" || sample.adapterRequestSettings["generationContractReason"] != code) return null
    val fixture = plan.externalFixtureDigests[taskId] ?: return null
    val grader = plan.graderDigest ?: return null
    val candidate = benchmarkHash(sample.canonicalAnswer.orEmpty())
    val evidence = benchmarkHash(listOf(plan.cohortId, plan.sourceCommit, taskId, fixture, grader, requestId, code, observation, candidate).joinToString("\u0000"))
    return BenchmarkEngineeringVerification(
        taskId, fixture, grader, candidate, "local-generation:$requestId", evidence, plan.lane.evidenceClass,
        independentChecksExecuted = false, requiredChecksKnown = false,
        hiddenChecksPassed = null, regressionPassed = null, integrityPassed = null,
        firstAttemptPassed = if (attemptCount == 1) false else firstPassed,
        finalArtifactPassed = false, submittedAttempts = attemptCount,
        failureDomain = BenchmarkEngineeringFailureDomain.MODEL, reasonCode = code,
        bestAttemptPassed = bestPassed, selectedAttemptIndex = attemptCount - 1, finalAttemptIndex = attemptCount - 1,
        verificationScope = "LOCAL_GENERATION_CONTRACT"
    )
}

/** Keep independently inspectable remote coordinates, excluding private journals and response text. */
private fun benchmarkExternalRemoteEvidence(receipt: JsonObject?): JsonObject? {
    if (receipt == null) return null
    val identities = setOf("schema", "repository", "branch", "workflowPath", "submissionId")
    val revisions = setOf("baseSha", "baseRevision", "testedRevision", "publishedRevision", "sourceCommit", "graderSourceCommit")
    val digests = setOf("artifactDigest", "bundleDigest", "catalogDigest", "receiptDigest", "resultDigest")
    val numbers = setOf("workflowRunId", "workflowRunAttempt", "jobId", "artifactId")
    return buildJsonObject {
        receipt.forEach { (key, value) ->
            val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            when {
                key in identities && text != null && text.length in 1..512 && text.none { it.code < 32 } -> put(key, value)
                key in revisions && text?.matches(Regex("[a-f0-9]{40}")) == true -> put(key, value)
                key in digests && text?.removePrefix("sha256:")?.matches(Regex("[a-f0-9]{64}")) == true -> put(key, value)
                key in numbers && (value as? JsonPrimitive)?.takeUnless { it.isString }?.contentOrNull?.toLongOrNull()?.let { it > 0 } == true -> put(key, value)
                key == "remoteReadbackVerified" && (value as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull != null -> put(key, value)
            }
        }
    }.takeIf { it.isNotEmpty() }
}

internal fun benchmarkExternalSettled(run: JsonObject): Boolean {
    if (run.text("state") !in setOf("COMPLETED", "FAILED", "BLOCKED", "CANCELLED", "INTERRUPTED")) return false
    val failure = run["failure"] as? JsonObject
    if (failure?.text("code") == "CANCELLATION_UNCONFIRMED") return false
    val cancellations = (failure?.get("cancellationReceipts") as? JsonArray).orEmpty()
    return cancellations.all { (it as? JsonObject)?.text("outcome") in setOf("confirmed", "terminal", "not_canceled") }
}

private fun requireDigest(value: String) {
    require(value.matches(Regex("[a-f0-9]{64}")))
}
private fun String.safeCode(): String? = takeIf { it.matches(Regex("[A-Z][A-Z0-9_]{0,95}")) }
private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
private fun JsonObject.boolean(key: String): Boolean? = (get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
private fun JsonObject.integer(key: String): Int? = (get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
private fun JsonObject.number(key: String): Double? = (get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull
