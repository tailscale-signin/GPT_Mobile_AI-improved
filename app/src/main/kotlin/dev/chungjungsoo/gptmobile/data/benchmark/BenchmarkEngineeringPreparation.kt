package dev.chungjungsoo.gptmobile.data.benchmark

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** Preparation may fail before any model runs; its observed effort still belongs to this cohort. */
internal suspend fun benchmarkPrepareEngineeringPlan(
    plan: BenchmarkEngineeringPlan,
    cases: List<BenchmarkCase>,
    route: suspend (String) -> BenchmarkEngineeringTransport,
    onSetupCheckpoint: suspend (BenchmarkEngineeringPlan) -> Unit = {},
    nowMs: () -> Long = { System.nanoTime() / 1_000_000 }
): BenchmarkEngineeringPlan {
    require(plan.valid())
    if (plan.lane !in setOf(BenchmarkEngineeringLane.EXECUTABLE_CODING, BenchmarkEngineeringLane.LIVE_GITHUB)) return plan
    val started = nowMs()
    val transports = mutableListOf<BenchmarkEngineeringTransport>()
    var openingRoute = false
    var prepared: BenchmarkEngineeringPlan? = null
    var observed = plan
    val phoneManifests = mutableMapOf<Pair<String, String>, JsonObject>()
    try {
        if (plan.lane == BenchmarkEngineeringLane.LIVE_GITHUB) {
            require(plan.repositoryFullName?.matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) == true) { "Choose the authorized disposable GitHub repository." }
            require(plan.baseSha?.matches(Regex("[a-f0-9]{40}")) == true) { "Pin the disposable repository's exact base commit." }
        }
        val ids = cases.map { requireNotNull(it.engineeringTaskId) }.toSet()
        val frozen = linkedMapOf<String, String>()
        val contextManifests = linkedMapOf<String, String>()
        val contextIdentities = linkedMapOf<String, BenchmarkContextSourceIdentity>()
        val routes = linkedMapOf<String, String>()
        val authorities = linkedMapOf<String, String>()
        var grader: String? = null
        var environment: String? = null
        for (profileUid in plan.profileUids) {
            // An open can perform authenticated reads before returning an observable counter.
            // If it throws or is canceled, the complete request total remains unknown.
            openingRoute = true
            val selected = route(profileUid)
            transports += selected
            openingRoute = false
            val pack = if (plan.taskPack == "repository") "repository" else "python"
            // Phone identity includes the exact immutable catalog, template, grader and account.
            // Open every route to recheck its account/repository access; reuse only the already
            // verified descriptors from that identical frozen authority during this preparation.
            val cacheKey = (selected.identityDigest to selected.authorityDigest).takeIf { plan.externalTransport == BenchmarkEngineeringExternalTransport.PHONE_GITHUB_ACTIONS }
            val manifest = cacheKey?.let(phoneManifests::get)
                ?: selected.request("fixtures?seed=${plan.seed}&preset=${plan.preset}&task_pack=$pack")
            check((manifest["sandboxReady"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull == true) {
                manifest.setupText("readinessReason")?.takeIf { it.matches(Regex("[A-Z][A-Z0-9_]{0,95}")) } ?: "BENCHMARK_SANDBOX_NOT_READY"
            }
            val observedGrader = requireNotNull(manifest.setupText("graderDigest")).also(::requireSetupDigest)
            val observedEnvironment = requireNotNull(manifest.setupText("environmentDigest")).also(::requireSetupDigest)
            check(grader == null || grader == observedGrader) { "BENCHMARK_GRADER_MISMATCH" }
            check(environment == null || environment == observedEnvironment) { "BENCHMARK_ENVIRONMENT_MISMATCH" }
            grader = observedGrader
            environment = observedEnvironment
            routes[profileUid] = benchmarkHash(selected.identityDigest + "\u0000" + observedEnvironment)
            authorities[profileUid] = selected.authorityDigest
            val tasks = (manifest["tasks"] as? JsonArray).orEmpty().map { it as? JsonObject ?: error("BENCHMARK_MANIFEST_INVALID") }
            check(tasks.map { it.setupText("task_id") ?: it.setupText("id") }.toSet() == ids && tasks.size == ids.size) { "BENCHMARK_TASK_SCOPE_CHANGED" }
            for (task in tasks) {
                val id = requireNotNull(task.setupText("task_id") ?: task.setupText("id"))
                val hash = requireNotNull(task.setupText("taskDigest") ?: task.setupText("fixture_digest")).also(::requireSetupDigest)
                check(task.setupText("graderDigest") == observedGrader) { "BENCHMARK_GRADER_MISMATCH" }
                check(frozen[id] == null || frozen[id] == hash) { "BENCHMARK_FIXTURE_MISMATCH" }
                frozen[id] = hash
                if (plan.contextExperiment != null) {
                    val (inputs, contextHash) = benchmarkContextInputs(task)
                    check(contextManifests[id] == null || contextManifests[id] == contextHash) { "BENCHMARK_CONTEXT_MANIFEST_MISMATCH" }
                    contextManifests[id] = contextHash
                    contextIdentities[id] = benchmarkContextSourceIdentity(inputs)
                }
            }
            cacheKey?.let { phoneManifests[it] = manifest }
        }
        prepared = if (plan.externalFixtureDigests.isNotEmpty()) {
            check(plan.externalFixtureDigests == frozen && plan.graderDigest == grader && plan.externalRouteDigests == routes && plan.contextManifestDigests == contextManifests && plan.contextSourceIdentities == contextIdentities) { "BENCHMARK_FROZEN_ENVIRONMENT_CHANGED" }
            check(plan.externalAuthorityDigests.isEmpty() || plan.externalAuthorityDigests == authorities) { "BENCHMARK_AUTHORITY_CHANGED" }
            plan
        } else {
            plan.copy(externalFixtureDigests = frozen, graderDigest = grader, externalRouteDigests = routes, externalAuthorityDigests = authorities, contextManifestDigests = contextManifests, contextSourceIdentities = contextIdentities).frozen()
        }
    } finally {
        val elapsed = (nowMs() - started).coerceAtLeast(0)
        val priorRequests = plan.externalSetupRequestCount ?: 0.takeIf { plan.externalSetupMs == null && plan.externalFixtureDigests.isEmpty() }
        val counts = transports.map { it.setupRequestCount?.takeIf { count -> count >= 0 } }
        val requests = if (openingRoute || priorRequests == null || counts.any { it == null }) {
            null
        } else {
            (priorRequests.toLong() + counts.sumOf { requireNotNull(it).toLong() }).takeIf { it <= Int.MAX_VALUE }?.toInt()
        }
        observed = plan.copy(
            externalSetupMs = (plan.externalSetupMs ?: 0).let { prior -> if (elapsed > Long.MAX_VALUE - prior) null else prior + elapsed },
            externalSetupRequestCount = requests
        )
        // Keep the original frozen identity here; a partial manifest never becomes accepted scope.
        withContext(NonCancellable) { onSetupCheckpoint(observed) }
    }
    return requireNotNull(prepared).copy(externalSetupMs = observed.externalSetupMs, externalSetupRequestCount = observed.externalSetupRequestCount)
}

/** One shared cohort limit. Direct REST preparation scales with the worst-case read count. */
internal fun benchmarkEngineeringPreparationDeadlineMs(plan: BenchmarkEngineeringPlan, taskCount: Int): Long {
    require(taskCount > 0 && plan.profileUids.size in 1..2)
    if (plan.externalTransport != BenchmarkEngineeringExternalTransport.PHONE_GITHUB_ACTIONS) return 60_000
    // Each explicit connection reads repository metadata, catalog and template, then descriptors.
    // Immutable descriptor reuse may reduce actual work; it never shortens this accepted ceiling.
    val reads = plan.profileUids.size.toLong() * (taskCount.toLong() + 3)
    return (30_000 + 2_000 * reads).coerceIn(60_000, 600_000)
}

/** Both queue admission and canonical persistence allow only the first verified environment freeze. */
internal fun benchmarkPreparedPlanPreservesAcceptedScope(accepted: BenchmarkEngineeringPlan, prepared: BenchmarkEngineeringPlan): Boolean {
    if (accepted.externalFixtureDigests.isNotEmpty()) {
        return prepared.copy(externalSetupMs = accepted.externalSetupMs, externalSetupRequestCount = accepted.externalSetupRequestCount) == accepted
    }
    if (accepted.contextManifestDigests.isNotEmpty() || accepted.contextSourceIdentities.isNotEmpty()) return false
    return prepared.copy(
        externalFixtureDigests = accepted.externalFixtureDigests,
        graderDigest = accepted.graderDigest,
        externalRouteDigests = accepted.externalRouteDigests,
        externalAuthorityDigests = accepted.externalAuthorityDigests,
        contextManifestDigests = accepted.contextManifestDigests,
        contextSourceIdentities = accepted.contextSourceIdentities,
        externalSetupMs = accepted.externalSetupMs,
        externalSetupRequestCount = accepted.externalSetupRequestCount,
        cohortId = accepted.cohortId
    ) == accepted
}

/** Only observed counters can change during a preparation checkpoint, including on cancellation. */
internal fun BenchmarkBatch.withEngineeringSetupObservation(observed: BenchmarkEngineeringPlan): BenchmarkBatch {
    val before = requireNotNull(engineeringPlan)
    require(before.valid() && observed.valid() && observed.copy(externalSetupMs = before.externalSetupMs, externalSetupRequestCount = before.externalSetupRequestCount) == before) { "ENGINEERING_SETUP_IDENTITY_CHANGED" }
    return copy(engineeringPlan = observed)
}

private fun JsonObject.setupText(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
private fun requireSetupDigest(value: String) = require(value.matches(Regex("[a-f0-9]{64}")))
