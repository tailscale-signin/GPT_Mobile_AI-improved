package dev.chungjungsoo.gptmobile.data.benchmark

import dev.chungjungsoo.gptmobile.data.github.GitHubWorkspaceClient
import java.net.URLDecoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A durable, once-admitted proposal. Recovery may only read or cancel the exact original run. */
internal class BenchmarkPhoneGitHubTransport private constructor(
    private val plan: BenchmarkEngineeringPlan,
    private val io: BenchmarkPhoneGitHubIo,
    private val journal: BenchmarkPhoneGitHubJournal,
    private val admission: Mutex,
    private val catalog: JsonObject,
    private val template: String,
    override val identityDigest: String,
    private val profileUid: String,
    private val catalogDigest: String
) : BenchmarkEngineeringTransport {
    override val setupRequestCount: Int get() = io.requestCount
    private val root = "/repos/${plan.repositoryFullName}"
    private val descriptors = mutableMapOf<String, JsonObject>()
    private data class Publication(val revision: String, val submission: JsonObject, val logicalRequestId: String)
    private val publications = mutableMapOf<String, Publication>()
    private val observedRunIds = mutableMapOf<String, Long>()

    override suspend fun request(path: String, body: JsonObject?): JsonObject = when {
        path == "evaluate" && body != null -> submit(body)
        path == "cancel-request" && body != null -> cancel(body.phoneText("request_id"))
        path.startsWith("?request_id=") && body == null -> lookup(URLDecoder.decode(path.removePrefix("?request_id="), "UTF-8"))
        path.matches(Regex("bench_[a-f0-9]{32}")) && body == null -> observe("phone_${path.removePrefix("bench_")}")
        path.substringBefore('?') == "fixtures" && body == null -> {
            checkQuery(path)
            val tasks = BenchmarkPhoneGitHubContract.taskIds(catalog, plan).map { descriptor(it) }
            buildJsonObject {
                put("schema", "gateway.benchmark-fixtures.v1")
                put("sandboxReady", true)
                put("readinessScope", "PINNED_ACTIONS_CONFIGURATION_ONLY")
                put("readinessReason", "Actual runtime and checks are confirmed only by the bound Actions artifact.")
                put("graderDigest", BenchmarkPhoneGitHubContract.pack(catalog, plan).getValue("graderDigest"))
                put("environmentDigest", BenchmarkPhoneGitHubContract.pack(catalog, plan).getValue("environmentDigest"))
                put("tasks", JsonArray(tasks))
            }
        }
        path.startsWith("fixtures/") && body == null -> {
            checkQuery(path)
            descriptor(URLDecoder.decode(path.substringBefore('?').removePrefix("fixtures/"), "UTF-8"))
        }
        else -> error("PHONE_BENCHMARK_ROUTE_UNSUPPORTED")
    }

    private fun checkQuery(path: String) {
        val values = path.substringAfter('?', "").split('&').map { it.split('=', limit = 2) }
        require(values.all { it.size == 2 } && values.size == 3)
        require(values.associate { it[0] to it[1] } == mapOf("seed" to plan.seed.toString(), "preset" to plan.preset, "task_pack" to BenchmarkPhoneGitHubContract.pack(plan))) { "PHONE_BENCHMARK_QUERY_SCOPE_CHANGED" }
    }

    private suspend fun descriptor(id: String): JsonObject = descriptors[id] ?: run {
        val raw = file(BenchmarkPhoneGitHubContract.fixturePath(plan, id), requireNotNull(plan.baseSha))
        BenchmarkPhoneGitHubContract.descriptor(raw, id, catalog, plan).also { descriptors[id] = it }
    }

    private fun key(id: String) = "execution:" + benchmarkHash("phone-submission:$id")
    private fun branch(id: String) = BenchmarkPhoneGitHubContract.branch(plan, id)
    private suspend fun file(path: String, revision: String): String = BenchmarkPhoneGitHubContract.content(
        io.request("$root/contents/${GitHubWorkspaceClient.filePath(path)}?ref=$revision")
    )
    private suspend fun ref(id: String): JsonObject = io.request("$root/git/ref/heads/${GitHubWorkspaceClient.segment(branch(id))}")

    private suspend fun lookup(requestId: String): JsonObject {
        require(requestId.matches(Regex("[A-Za-z0-9_-]{8,160}")))
        val id = BenchmarkPhoneGitHubContract.submissionId(requestId)
        val row = journal.read(key(id))
        if (row?.phoneText("phase") == "RETIRED") return buildJsonObject { put("submissionState", "REMOVED") }
        if (row == null && ref(id).phoneBoolean("owned_ref_absent") == true) return buildJsonObject { put("submissionState", "NOT_OBSERVED") }
        return buildJsonObject {
            put("submissionState", "OBSERVED")
            put("run", observe(id, requestId))
        }
    }

    private suspend fun submit(body: JsonObject): JsonObject = admission.withLock {
        require((body["generation_receipt"] as? JsonObject)?.phoneText("profile_uid") == profileUid) { "PHONE_BENCHMARK_ROUTE_PROFILE_MISMATCH" }
        val submission = BenchmarkPhoneGitHubContract.submission(body, catalog, plan)
        val id = submission.phoneText("request_id")
        val logical = body.phoneText("request_id")
        val payloadDigest = benchmarkHash(BenchmarkPhoneGitHubContract.canonical(submission))
        val previous = journal.read(key(id))
        if (previous != null) {
            require(previous.phoneText("payloadDigest").let { it.isEmpty() || it == payloadDigest }) { "PHONE_BENCHMARK_REQUEST_ID_REBOUND" }
            if (previous.phoneText("phase") == "RETIRED") return@withLock terminal(id, "CANCELLED", "PHONE_BENCHMARK_REQUEST_RETIRED")
            return@withLock observe(id, logical)
        }
        // The encrypted, non-prunable execution receipt precedes every remote write.
        journal.update(key(id)) { old ->
            require(old == null) { "PHONE_BENCHMARK_ADMISSION_RACE" }
            buildJsonObject {
                put("schema", "phone-benchmark-admission.v1")
                put("status", "RUNNING")
                put("phase", "ADMITTED")
                put("logicalRequestId", logical)
                put("payloadDigest", payloadDigest)
                put("cancelRequested", false)
            }
        }
        val observed = ref(id)
        require(observed.phoneBoolean("owned_ref_absent") == true) { "PHONE_BENCHMARK_BRANCH_ALREADY_EXISTS" }
        val base = io.request("$root/git/commits/${plan.baseSha}")
        require(base.phoneText("sha") == plan.baseSha) { "PHONE_BENCHMARK_BASE_CHANGED" }
        val baseTree = (base["tree"] as? JsonObject)?.phoneText("sha").orEmpty().also(::requireSha)
        val inventory = io.request("$root/git/trees/$baseTree?recursive=1")
        require(inventory.phoneBoolean("truncated") == false) { "PHONE_BENCHMARK_BASE_TREE_INCOMPLETE" }
        val paths = (inventory["tree"] as? JsonArray)?.map { (it as? JsonObject)?.phoneText("path") ?: error("PHONE_BENCHMARK_BASE_TREE_INVALID") } ?: error("PHONE_BENCHMARK_BASE_TREE_INVALID")
        val submissionPath = BenchmarkPhoneGitHubContract.submissionPath(id)
        val workflowPath = BenchmarkPhoneGitHubContract.workflowPath(id)
        require(submissionPath !in paths && workflowPath !in paths) { "PHONE_BENCHMARK_PATH_ALREADY_EXISTS" }
        val files = listOf(submissionPath to BenchmarkPhoneGitHubContract.canonical(submission), workflowPath to BenchmarkPhoneGitHubContract.rendered(template, plan, id))
        val tree = io.request(
            "$root/git/trees",
            buildJsonObject {
                put("base_tree", baseTree)
                put(
                    "tree",
                    JsonArray(
                        files.map { (path, text) ->
                            buildJsonObject {
                                put("path", path)
                                put("mode", "100644")
                                put("type", "blob")
                                put("content", text)
                            }
                        }
                    )
                )
            }
        ).phoneText("sha").also(::requireSha)
        val commit = io.request(
            "$root/git/commits",
            buildJsonObject {
                put("message", "Benchmark submission $id")
                put("tree", tree)
                put("parents", JsonArray(listOf(JsonPrimitive(requireNotNull(plan.baseSha)))))
            }
        ).phoneText("sha").also(::requireSha)
        journal.update(key(id)) { old -> JsonObject(requireNotNull(old) + mapOf("phase" to JsonPrimitive("PUBLISHING"), "revision" to JsonPrimitive(commit), "tree" to JsonPrimitive(tree))) }
        // Create-only publication; an existing branch is never updated, deleted or overwritten.
        try {
            io.request(
                "$root/git/refs",
                buildJsonObject {
                    put("ref", "refs/heads/${branch(id)}")
                    put("sha", commit)
                }
            )
        } catch (rejected: BenchmarkPhoneMutationRejected) {
            journal.update(key(id)) { old -> JsonObject(requireNotNull(old) + mapOf("status" to JsonPrimitive("COMPLETED"), "phase" to JsonPrimitive("RETIRED"), "reason" to JsonPrimitive("PHONE_BENCHMARK_MUTATION_REJECTED"))) }
            throw rejected
        }
        require((ref(id)["object"] as? JsonObject)?.phoneText("sha") == commit) { "PHONE_BENCHMARK_PUBLICATION_UNCONFIRMED" }
        journal.update(key(id)) { old -> JsonObject(requireNotNull(old) + ("phase" to JsonPrimitive("PUBLISHED"))) }
        observe(id, logical)
    }

    private suspend fun cancel(requestId: String): JsonObject = admission.withLock {
        require(requestId.matches(Regex("[A-Za-z0-9_-]{8,160}")))
        val id = BenchmarkPhoneGitHubContract.submissionId(requestId)
        var old = journal.read(key(id))
        if (old == null && ref(id).phoneBoolean("owned_ref_absent") != true) {
            // A restored benchmark can outlive a lost local body. Observe its original ref; do not retire it blindly.
            old = journal.update(key(id)) { previous ->
                previous ?: buildJsonObject {
                    put("status", "RECONCILE_REQUIRED")
                    put("phase", "PUBLISHING")
                    put("logicalRequestId", requestId)
                }
            }
        }
        if (old == null || old.phoneText("phase") in setOf("ADMITTED", "RETIRED")) {
            // No ref publication was admitted. Detached Git objects cannot start a workflow.
            journal.update(key(id)) { previous -> JsonObject(previous.orEmpty() + mapOf("status" to JsonPrimitive("COMPLETED"), "phase" to JsonPrimitive("RETIRED"), "logicalRequestId" to JsonPrimitive(requestId), "cancelRequested" to JsonPrimitive(true))) }
            return@withLock buildJsonObject { put("submissionState", "REMOVED") }
        }
        journal.update(key(id)) { previous -> JsonObject(requireNotNull(previous) + ("cancelRequested" to JsonPrimitive(true))) }
        val observed = observe(id, requestId)
        if (observed.phoneText("state") in TERMINAL && (observed["failure"] as? JsonObject)?.phoneText("code") != "CANCELLATION_UNCONFIRMED") {
            return@withLock buildJsonObject {
                put("submissionState", "OBSERVED")
                put("run", observed)
            }
        }
        val runId = observed.phoneLong("workflowRunId")
        val runAttempt = observed.phoneLong("workflowRunAttempt")
        if (runId != null && runAttempt != null) {
            // The readback that supplied this ID was already bound to our branch, commit and workflow.
            var dispatch = false
            val claim = journal.update(key(id)) { previous ->
                val retained = requireNotNull(previous)
                if (retained.phoneBoolean("cancelDispatched") == true) {
                    retained
                } else {
                    dispatch = true
                    JsonObject(retained + mapOf("cancelDispatched" to JsonPrimitive(true), "cancelRunId" to JsonPrimitive(runId), "cancelRunAttempt" to JsonPrimitive(runAttempt)))
                }
            }
            if (claim.phoneLong("cancelRunId") != runId || claim.phoneLong("cancelRunAttempt") != runAttempt) {
                return@withLock buildJsonObject {
                    put("submissionState", "OBSERVED")
                    put("run", pending(id, runId, "PHONE_BENCHMARK_CANCEL_TARGET_CHANGED", cancelRequested = true, runAttempt))
                }
            }
            if (dispatch) {
                try {
                    io.request("$root/actions/runs/$runId/cancel", JsonObject(emptyMap()))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // The durable claim precedes dispatch. A lost cancel response is only read back.
                }
            }
        }
        buildJsonObject {
            put("submissionState", "OBSERVED")
            put("run", observe(id, requestId))
        }
    }

    private suspend fun observe(id: String, requestId: String? = null): JsonObject {
        require(id.matches(Regex("phone_[a-f0-9]{32}")))
        val row = journal.read(key(id))
        if (row?.phoneText("phase") == "RETIRED") return terminal(id, "CANCELLED", "PHONE_BENCHMARK_REQUEST_RETIRED")
        val publication = publications[id] ?: publication(id, requestId, row)?.also { publications[id] = it }
            ?: return pending(id, null, "PHONE_BENCHMARK_PUBLICATION_UNCONFIRMED", row?.phoneBoolean("cancelRequested") == true)
        val revision = publication.revision
        val submission = publication.submission
        val logical = publication.logicalRequestId
        require(requestId == null || requestId == logical) { "PHONE_BENCHMARK_REQUEST_CHANGED" }
        val pinnedRunId = observedRunIds[id] ?: row?.phoneLong("workflowRunId")
        val runId = if (pinnedRunId != null) {
            pinnedRunId
        } else {
            val runs = io.request("$root/actions/runs?branch=${GitHubWorkspaceClient.segment(branch(id))}&event=push&head_sha=$revision&per_page=100")
            val candidates = boundedRows(runs, "workflow_runs").filter { BenchmarkPhoneGitHubContract.runBound(it, plan, id, revision) }
            if (candidates.isEmpty()) return pending(id, null, "PHONE_BENCHMARK_ACTIONS_NOT_OBSERVED", row?.phoneBoolean("cancelRequested") == true)
            require(candidates.size == 1) { "PHONE_BENCHMARK_MULTIPLE_RUNS" }
            requireNotNull(candidates.single().phoneLong("id"))
        }
        val run = io.request("$root/actions/runs/$runId")
        require(BenchmarkPhoneGitHubContract.runBound(run, plan, id, revision)) { "PHONE_BENCHMARK_RUN_CHANGED" }
        if (observedRunIds.put(id, runId) == null) {
            journal.update(key(id)) { previous -> JsonObject(previous.orEmpty() + mapOf("status" to JsonPrimitive("RUNNING"), "phase" to JsonPrimitive("PUBLISHED"), "workflowRunId" to JsonPrimitive(runId), "revision" to JsonPrimitive(revision), "logicalRequestId" to JsonPrimitive(logical))) }
        }
        if (run.phoneText("status") != "completed") return pending(id, runId, "PHONE_BENCHMARK_ACTIONS_PENDING", row?.phoneBoolean("cancelRequested") == true, run.phoneLong("run_attempt"))
        if (run.phoneText("conclusion") != "success") {
            return terminal(id, if (run.phoneText("conclusion") == "cancelled") "CANCELLED" else "FAILED", "PHONE_BENCHMARK_ACTIONS_INFRASTRUCTURE", runId)
        }
        return try {
            val attempt = requireNotNull(run.phoneLong("run_attempt"))
            val jobs = boundedRows(io.request("$root/actions/runs/$runId/attempts/$attempt/jobs?per_page=100"), "jobs")
            val job = jobs.singleOrNull { it.phoneText("name") == BenchmarkPhoneGitHubContract.JOB } ?: error("PHONE_BENCHMARK_REQUIRED_JOB_MISSING")
            require(job.phoneText("status") == "completed" && job.phoneText("conclusion") == "success" && job.phoneLong("run_id") == runId) { "PHONE_BENCHMARK_REQUIRED_JOB_UNSUCCESSFUL" }
            val steps = (job["steps"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            require(BenchmarkPhoneGitHubContract.REQUIRED_STEPS.all { name -> steps.singleOrNull { it.phoneText("name") == name }?.let { it.phoneText("status") == "completed" && it.phoneText("conclusion") == "success" } == true }) { "PHONE_BENCHMARK_REQUIRED_STEP_UNSUCCESSFUL" }
            val artifacts = boundedRows(io.request("$root/actions/runs/$runId/artifacts?per_page=100"), "artifacts")
            val artifact = artifacts.singleOrNull { it.phoneText("name") == "phone-benchmark-$id" && it.phoneBoolean("expired") == false } ?: error("PHONE_BENCHMARK_RESULT_ARTIFACT_MISSING")
            val artifactRun = artifact["workflow_run"] as? JsonObject ?: error("PHONE_BENCHMARK_ARTIFACT_RUN_MISSING")
            require(artifactRun.phoneLong("id") == runId && artifactRun.phoneText("head_sha") == revision && artifactRun.phoneText("head_branch") == branch(id)) { "PHONE_BENCHMARK_ARTIFACT_RUN_MISMATCH" }
            val artifactId = artifact.phoneLong("id")?.takeIf { it > 0 } ?: error("PHONE_BENCHMARK_ARTIFACT_ID_INVALID")
            val digest = artifact.phoneText("digest").removePrefix("sha256:")
            require(digest.matches(Regex("[a-f0-9]{64}")) && artifact.phoneLong("size_in_bytes")?.let { it in 1..4L * 1024 * 1024 } == true) { "PHONE_BENCHMARK_ARTIFACT_DIGEST_MISSING" }
            val proof = Json.parseToJsonElement(io.artifact(artifactId, digest)) as? JsonObject ?: error("PHONE_BENCHMARK_RESULT_INVALID")
            val current = io.request("$root/actions/runs/$runId")
            if (current["run_attempt"] != run["run_attempt"] || current.phoneText("status") != "completed") {
                return pending(id, runId, "PHONE_BENCHMARK_RUN_ATTEMPT_CHANGED", cancelRequested = true, current.phoneLong("run_attempt"))
            }
            require((ref(id)["object"] as? JsonObject)?.phoneText("sha") == revision) { "PHONE_BENCHMARK_FINAL_READBACK_CHANGED" }
            val verifiedCase = BenchmarkPhoneGitHubContract.result(proof, submission, current, plan, revision)
            val remoteReceipt = JsonObject(
                (verifiedCase["liveGitHubReceipt"] as JsonObject) + buildJsonObject {
                    put("repository", requireNotNull(plan.repositoryFullName))
                    put("branch", branch(id))
                    put("baseSha", requireNotNull(plan.baseSha))
                    put("submissionId", id)
                    put("workflowPath", BenchmarkPhoneGitHubContract.workflowPath(id))
                    put("artifactId", artifactId)
                    put("artifactDigest", digest)
                    put("resultDigest", proof.getValue("receiptDigest"))
                    put("catalogDigest", catalogDigest)
                    put("bundleDigest", catalog.getValue("bundleDigest"))
                    put("graderSourceCommit", catalog.getValue("sourceCommit"))
                }
            )
            val verified = JsonObject(verifiedCase + ("liveGitHubReceipt" to remoteReceipt))
            val result = buildJsonObject {
                put("id", BenchmarkPhoneGitHubContract.jobId(id))
                put("state", "COMPLETED")
                put("cases", JsonArray(listOf(verified)))
                put("workflowRunId", runId)
                put("workflowRunAttempt", attempt)
                put("artifactId", artifactId)
                put("artifactDigest", digest)
                put("executionScope", "PHONE_GITHUB_ACTIONS")
            }
            journal.update(key(id)) { previous -> JsonObject(previous.orEmpty() + mapOf("status" to JsonPrimitive("COMPLETED"), "phase" to JsonPrimitive("OBSERVED_TERMINAL"), "revision" to JsonPrimitive(revision), "logicalRequestId" to JsonPrimitive(logical), "remoteReceipt" to remoteReceipt, "terminalReceiptDigest" to JsonPrimitive(benchmarkHash(result.toString())))) }
            result
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // Completed Actions without matching independent evidence is infrastructure, never a model zero.
            val reason = failure.message?.takeIf { it.matches(Regex("PHONE_BENCHMARK_[A-Z_]{1,70}")) } ?: "PHONE_BENCHMARK_GRADE_UNVERIFIED"
            terminal(id, "FAILED", reason, runId)
        }
    }

    private suspend fun publication(id: String, requestId: String?, row: JsonObject?): Publication? {
        val branchRef = ref(id)
        if (branchRef.phoneBoolean("owned_ref_absent") == true) return null
        val revision = (branchRef["object"] as? JsonObject)?.phoneText("sha").orEmpty().also(::requireSha)
        require(row?.phoneText("revision").let { it.isNullOrEmpty() || it == revision }) { "PHONE_BENCHMARK_BRANCH_CHANGED" }
        val submission = Json.parseToJsonElement(file(BenchmarkPhoneGitHubContract.submissionPath(id), revision)) as? JsonObject ?: error("PHONE_BENCHMARK_SUBMISSION_INVALID")
        val context = submission["comparison_context"] as? JsonObject ?: error("PHONE_BENCHMARK_COMPARISON_CONTEXT_MISSING")
        val logical = context.phoneText("logical_request_id")
        require(context.phoneText("plan_id") == plan.id && context.phoneText("cohort") == plan.cohortId && context.phoneText("preset") == plan.preset && BenchmarkPhoneGitHubContract.submissionId(logical) == id && (requestId == null || logical == requestId)) { "PHONE_BENCHMARK_SUBMISSION_IDENTITY_MISMATCH" }
        val reconstructed = JsonObject(submission + mapOf("request_id" to JsonPrimitive(logical), "cohort" to JsonPrimitive(plan.cohortId)))
        val expected = BenchmarkPhoneGitHubContract.submission(reconstructed, catalog, plan)
        require(expected == submission && row?.phoneText("payloadDigest").let { it.isNullOrEmpty() || it == benchmarkHash(BenchmarkPhoneGitHubContract.canonical(submission)) }) { "PHONE_BENCHMARK_SUBMISSION_CHANGED" }
        require(file(BenchmarkPhoneGitHubContract.workflowPath(id), revision) == BenchmarkPhoneGitHubContract.rendered(template, plan, id)) { "PHONE_BENCHMARK_WORKFLOW_CHANGED" }
        val commit = io.request("$root/git/commits/$revision")
        require(commit.phoneText("sha") == revision && (commit["parents"] as? JsonArray)?.singleOrNull()?.let { (it as? JsonObject)?.phoneText("sha") } == plan.baseSha) { "PHONE_BENCHMARK_COMMIT_PARENT_CHANGED" }
        val diff = io.request("$root/compare/${plan.baseSha}...$revision")
        val files = (diff["files"] as? JsonArray)?.map { it as? JsonObject ?: error("PHONE_BENCHMARK_DELTA_INVALID") }.orEmpty()
        require(diff.phoneLong("total_commits") == 1L && files.size == 2 && files.all { it.phoneText("status") == "added" } && files.map { it.phoneText("filename") }.toSet() == setOf(BenchmarkPhoneGitHubContract.submissionPath(id), BenchmarkPhoneGitHubContract.workflowPath(id))) { "PHONE_BENCHMARK_DELTA_CHANGED" }
        return Publication(revision, submission, logical)
    }

    private fun boundedRows(response: JsonObject, key: String): List<JsonObject> {
        val rows = (response[key] as? JsonArray)?.map { it as? JsonObject ?: error("PHONE_BENCHMARK_LIST_INVALID") } ?: error("PHONE_BENCHMARK_LIST_MISSING")
        require(response.phoneLong("total_count") == rows.size.toLong() && rows.size <= 100) { "PHONE_BENCHMARK_LIST_INCOMPLETE" }
        return rows
    }

    private fun pending(id: String, runId: Long?, code: String, cancelRequested: Boolean, runAttempt: Long? = null) = buildJsonObject {
        put("id", BenchmarkPhoneGitHubContract.jobId(id))
        put("state", "RUNNING")
        runId?.let { put("workflowRunId", it) }
        runAttempt?.let { put("workflowRunAttempt", it) }
        put("failure", buildJsonObject { put("code", if (cancelRequested) "CANCELLATION_UNCONFIRMED" else code) })
        put("cases", JsonArray(emptyList()))
    }

    private fun terminal(id: String, state: String, reason: String, runId: Long? = null) = buildJsonObject {
        put("id", BenchmarkPhoneGitHubContract.jobId(id))
        put("state", state)
        put(
            "failure",
            buildJsonObject {
                put("code", reason)
                put("failureDomain", "INFRASTRUCTURE")
            }
        )
        runId?.let { put("workflowRunId", it) }
        put("cases", JsonArray(emptyList()))
    }

    companion object {
        private val TERMINAL = setOf("COMPLETED", "FAILED", "BLOCKED", "CANCELLED", "INTERRUPTED")
        private fun requireSha(value: String) = require(value.matches(Regex("[a-f0-9]{40}"))) { "PHONE_BENCHMARK_REVISION_INVALID" }

        suspend fun open(plan: BenchmarkEngineeringPlan, io: BenchmarkPhoneGitHubIo, journal: BenchmarkPhoneGitHubJournal, admission: Mutex, authorityDigest: String, profileUid: String = plan.profileUids.first()): BenchmarkPhoneGitHubTransport {
            require(plan.valid() && plan.externalTransport == BenchmarkEngineeringExternalTransport.PHONE_GITHUB_ACTIONS && plan.lane == BenchmarkEngineeringLane.LIVE_GITHUB)
            val root = "/repos/${plan.repositoryFullName}"
            val metadata = io.request(root)
            require(metadata.phoneText("full_name").equals(plan.repositoryFullName, true) && metadata.phoneBoolean("archived") == false && metadata.phoneBoolean("disabled") != true && (metadata["permissions"] as? JsonObject)?.phoneBoolean("push") == true) { "PHONE_BENCHMARK_REPOSITORY_NOT_WRITABLE" }
            val raw = BenchmarkPhoneGitHubContract.content(io.request("$root/contents/${BenchmarkPhoneGitHubContract.CATALOG}?ref=${plan.baseSha}"))
            val catalog = BenchmarkPhoneGitHubContract.catalog(raw, plan)
            val templatePath = BenchmarkPhoneGitHubContract.pack(catalog, plan).phoneText("workflowTemplatePath")
            val template = BenchmarkPhoneGitHubContract.template(BenchmarkPhoneGitHubContract.content(io.request("$root/contents/${GitHubWorkspaceClient.filePath(templatePath)}?ref=${plan.baseSha}")), catalog, plan)
            val digest = benchmarkHash(listOf(authorityDigest, benchmarkHash(raw), catalog.phoneText("bundleDigest"), BenchmarkPhoneGitHubContract.pack(catalog, plan).phoneText("graderDigest"), BenchmarkPhoneGitHubContract.pack(catalog, plan).phoneText("environmentDigest")).joinToString("\u0000"))
            return BenchmarkPhoneGitHubTransport(plan, io, journal, admission, catalog, template, digest, profileUid, benchmarkHash(raw))
        }
    }
}
