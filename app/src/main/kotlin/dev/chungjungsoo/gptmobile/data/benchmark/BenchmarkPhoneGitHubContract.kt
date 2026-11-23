package dev.chungjungsoo.gptmobile.data.benchmark

import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Pure admission and evidence checks shared by direct Actions execution and its adversarial tests. */
internal object BenchmarkPhoneGitHubContract {
    const val CATALOG = ".benchmark-phone/catalog.json"
    const val JOB = "Independent phone benchmark"
    val REQUIRED_STEPS = setOf("Grade frozen proposal", "Upload benchmark result")
    const val MAX_PROPOSAL_BYTES = 256 * 1024
    private val digest = Regex("[a-f0-9]{64}")
    private val task = Regex("[A-Za-z0-9_-]{1,120}")

    fun pack(plan: BenchmarkEngineeringPlan) = if (plan.taskPack == "repository") "repository" else "python"
    fun submissionId(requestId: String) = "phone_${benchmarkHash(requestId).take(32)}"
    fun jobId(submissionId: String) = "bench_${submissionId.removePrefix("phone_")}"
    fun branch(plan: BenchmarkEngineeringPlan, id: String) = "benchmark/phone/${plan.cohortId}/$id"
    fun submissionPath(id: String) = ".benchmark-phone/submissions/$id.json"
    fun workflowPath(id: String) = ".github/workflows/phone-benchmark-$id.yml"
    fun canonical(value: JsonElement): String = when (value) {
        is JsonObject -> value.toSortedMap().entries.joinToString(",", "{", "}") { "${canonical(JsonPrimitive(it.key))}:${canonical(it.value)}" }
        is JsonArray -> value.joinToString(",", "[", "]", transform = ::canonical)
        else -> value.toString().map { if (it.code > 127) "\\u${it.code.toString(16).padStart(4, '0')}" else it.toString() }.joinToString("")
    }

    fun content(response: JsonObject, maximumBytes: Int = 2 * 1024 * 1024): String {
        require(response.phoneText("type") == "file" && response.phoneText("encoding") == "base64") { "PHONE_BENCHMARK_FILE_INVALID" }
        val encoded = response.phoneText("content").replace("\n", "").replace("\r", "")
        require(encoded.length <= (maximumBytes * 4L / 3 + 8)) { "PHONE_BENCHMARK_FILE_TOO_LARGE" }
        val bytes = Base64.getDecoder().decode(encoded)
        require(bytes.size <= maximumBytes && response.phoneLong("size") == bytes.size.toLong()) { "PHONE_BENCHMARK_FILE_SIZE_MISMATCH" }
        return bytes.decodeToString(throwOnInvalidSequence = true)
    }

    fun catalog(raw: String, plan: BenchmarkEngineeringPlan): JsonObject {
        val catalog = Json.parseToJsonElement(raw) as? JsonObject ?: error("PHONE_BENCHMARK_CATALOG_INVALID")
        require(catalog.phoneText("schema") == "gateway.phone-benchmark-catalog.v1") { "PHONE_BENCHMARK_CATALOG_SCHEMA" }
        require(catalog.phoneText("sourceCommit").matches(Regex("[a-f0-9]{40}"))) { "PHONE_BENCHMARK_SOURCE_IDENTITY_INVALID" }
        val manifest = catalog["bundleManifest"] as? JsonObject ?: error("PHONE_BENCHMARK_BUNDLE_MANIFEST_MISSING")
        require(
            manifest.isNotEmpty() &&
                manifest.all { (path, hash) ->
                    path.matches(Regex("[A-Za-z0-9_./-]{1,256}")) &&
                        !path.startsWith('/') &&
                        path.split('/').none { it == ".." || it == "." || it.isEmpty() } &&
                        digest.matches((hash as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()) &&
                        path != CATALOG
                }
        ) { "PHONE_BENCHMARK_BUNDLE_MANIFEST_INVALID" }
        require(benchmarkHash(canonical(manifest)) == catalog.phoneText("bundleDigest")) { "PHONE_BENCHMARK_BUNDLE_DIGEST_MISMATCH" }
        val pack = pack(catalog, plan)
        require(digest.matches(pack.phoneText("graderDigest")) && digest.matches(pack.phoneText("environmentDigest"))) { "PHONE_BENCHMARK_GRADER_IDENTITY_INVALID" }
        require(pack["environment"] is JsonObject && benchmarkHash(canonical(pack.getValue("environment"))) == pack.phoneText("environmentDigest")) { "PHONE_BENCHMARK_ENVIRONMENT_DIGEST_MISMATCH" }
        require((pack["seeds"] as? JsonArray).orEmpty().any { (it as? JsonPrimitive)?.intOrNull == plan.seed }) { "PHONE_BENCHMARK_SEED_UNSUPPORTED" }
        val template = pack.phoneText("workflowTemplatePath")
        require(template.startsWith(".benchmark-phone/") && manifest[template] == JsonPrimitive(pack.phoneText("workflowTemplateDigest"))) { "PHONE_BENCHMARK_TEMPLATE_IDENTITY_INVALID" }
        require(pack.phoneText("fixturePathTemplate") == ".benchmark-phone/fixtures/${pack(plan)}/{seed}/{task_id}.json") { "PHONE_BENCHMARK_FIXTURE_PATH_INVALID" }
        val ids = taskIds(catalog, plan)
        require(ids.isNotEmpty() && ids.size == ids.distinct().size && ids.all(task::matches)) { "PHONE_BENCHMARK_TASK_MANIFEST_INVALID" }
        return catalog
    }

    fun pack(catalog: JsonObject, plan: BenchmarkEngineeringPlan): JsonObject =
        (catalog["packs"] as? JsonObject)?.get(pack(plan)) as? JsonObject ?: error("PHONE_BENCHMARK_TASK_PACK_UNSUPPORTED")

    fun taskIds(catalog: JsonObject, plan: BenchmarkEngineeringPlan): List<String> =
        ((pack(catalog, plan)["presets"] as? JsonObject)?.get(plan.preset) as? JsonArray)?.map { (it as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("PHONE_BENCHMARK_TASK_INVALID") }
            ?: error("PHONE_BENCHMARK_PRESET_UNSUPPORTED")

    fun fixturePath(plan: BenchmarkEngineeringPlan, taskId: String): String {
        require(task.matches(taskId))
        return ".benchmark-phone/fixtures/${pack(plan)}/${plan.seed}/$taskId.json"
    }

    fun descriptor(raw: String, taskId: String, catalog: JsonObject, plan: BenchmarkEngineeringPlan): JsonObject {
        require(taskId in taskIds(catalog, plan)) { "PHONE_BENCHMARK_TASK_OUTSIDE_COHORT" }
        val pack = pack(catalog, plan)
        val receipt = ((pack["tasksBySeed"] as? JsonObject)?.get(plan.seed.toString()) as? JsonObject)?.get(taskId) as? JsonObject
            ?: error("PHONE_BENCHMARK_TASK_RECEIPT_MISSING")
        val hash = benchmarkHash(raw)
        require(receipt.phoneText("descriptorDigest") == hash && (catalog["bundleManifest"] as? JsonObject)?.get(fixturePath(plan, taskId)) == JsonPrimitive(hash)) { "PHONE_BENCHMARK_DESCRIPTOR_DIGEST_MISMATCH" }
        val descriptor = Json.parseToJsonElement(raw) as? JsonObject ?: error("PHONE_BENCHMARK_DESCRIPTOR_INVALID")
        require((descriptor.phoneText("task_id").ifEmpty { descriptor.phoneText("id") }) == taskId && descriptor.phoneText("taskPack") == pack(plan) && descriptor.phoneLong("seed") == plan.seed.toLong()) { "PHONE_BENCHMARK_DESCRIPTOR_SCOPE_MISMATCH" }
        require(descriptor.phoneText("taskDigest") == receipt.phoneText("fixtureDigest") && digest.matches(receipt.phoneText("fixtureDigest"))) { "PHONE_BENCHMARK_FIXTURE_DIGEST_MISMATCH" }
        require(descriptor.phoneText("graderDigest") == pack.phoneText("graderDigest") && descriptor.phoneText("environmentDigest") == pack.phoneText("environmentDigest")) { "PHONE_BENCHMARK_DESCRIPTOR_GRADER_MISMATCH" }
        require(descriptor.phoneText("prompt").isNotBlank() && descriptor["outputSchema"] is JsonObject) { "PHONE_BENCHMARK_TASK_PROMPT_MISSING" }
        return descriptor
    }

    fun template(raw: String, catalog: JsonObject, plan: BenchmarkEngineeringPlan): String {
        require(benchmarkHash(raw) == pack(catalog, plan).phoneText("workflowTemplateDigest")) { "PHONE_BENCHMARK_TEMPLATE_CHANGED" }
        require(raw.length <= 128 * 1024 && setOf("__BENCHMARK_SUBMISSION_ID__", "__BENCHMARK_BASE_SHA__", "__BENCHMARK_BRANCH__").all { it in raw }) { "PHONE_BENCHMARK_TEMPLATE_INVALID" }
        return raw
    }

    fun rendered(template: String, plan: BenchmarkEngineeringPlan, id: String): String {
        require(id.matches(Regex("phone_[a-f0-9]{32}")) && plan.baseSha?.matches(Regex("[a-f0-9]{40}")) == true && plan.cohortId.matches(digest))
        return template.replace("__BENCHMARK_SUBMISSION_ID__", id).replace("__BENCHMARK_BASE_SHA__", requireNotNull(plan.baseSha)).replace("__BENCHMARK_BRANCH__", branch(plan, id))
    }

    fun submission(body: JsonObject, catalog: JsonObject, plan: BenchmarkEngineeringPlan): JsonObject {
        val requestId = body.phoneText("request_id")
        require(requestId.matches(Regex("[A-Za-z0-9_-]{8,160}"))) { "PHONE_BENCHMARK_REQUEST_ID_INVALID" }
        val id = submissionId(requestId)
        val taskId = body.phoneText("task_id")
        require(taskId in taskIds(catalog, plan) && body.phoneText("task_pack") == pack(plan) && body.phoneLong("seed") == plan.seed.toLong()) { "PHONE_BENCHMARK_SUBMISSION_SCOPE_MISMATCH" }
        require(body.phoneText("repository") == plan.repositoryFullName && body.phoneText("base_sha") == plan.baseSha && body.phoneText("cohort") == plan.cohortId) { "PHONE_BENCHMARK_REPOSITORY_SCOPE_MISMATCH" }
        require(body.phoneText("fixture_digest") == plan.externalFixtureDigests[taskId] && body.phoneText("grader_digest") == plan.graderDigest && body.phoneText("environment_digest") == pack(catalog, plan).phoneText("environmentDigest")) { "PHONE_BENCHMARK_SUBMISSION_FIXTURE_MISMATCH" }
        val proposal = body["proposal"] as? JsonPrimitive
        require(proposal?.isString == true && proposal.content.encodeToByteArray().size <= MAX_PROPOSAL_BYTES && '\u0000' !in proposal.content) { "PHONE_BENCHMARK_PROPOSAL_BOUND_EXCEEDED" }
        require(benchmarkHash(proposal.content) == body.phoneText("proposal_digest")) { "PHONE_BENCHMARK_PROPOSAL_DIGEST_MISMATCH" }
        val generation = body["generation_receipt"] as? JsonObject ?: error("PHONE_BENCHMARK_GENERATION_RECEIPT_MISSING")
        val profileUid = generation.phoneText("profile_uid")
        require(profileUid in plan.profileUids && generation.phoneText("profile_revision") == plan.profileConfigKeys[profileUid]) { "PHONE_BENCHMARK_PROFILE_BINDING_MISMATCH" }
        return buildJsonObject {
            put("schema", "gateway.phone-benchmark-submission.v1")
            listOf("task_id", "task_pack", "seed", "fixture_digest", "grader_digest", "environment_digest", "base_sha", "repository", "proposal_digest", "proposal", "generation_receipt").forEach { put(it, body.getValue(it)) }
            put("request_id", id)
            put("bundle_digest", catalog.phoneText("bundleDigest"))
            put("branch", branch(plan, id))
            put("workflow_path", workflowPath(id))
            put(
                "comparison_context",
                buildJsonObject {
                    put("logical_request_id", requestId)
                    put("plan_id", plan.id)
                    put("cohort", plan.cohortId)
                    put("preset", plan.preset)
                }
            )
        }
    }

    fun runBound(run: JsonObject, plan: BenchmarkEngineeringPlan, id: String, revision: String): Boolean {
        val path = run.phoneText("path")
        val expectedPath = workflowPath(id)
        return run.phoneLong("id")?.let { it > 0 } == true &&
            run.phoneLong("run_attempt")?.let { it > 0 } == true &&
            run.phoneText("event") == "push" &&
            run.phoneText("head_sha") == revision &&
            run.phoneText("head_branch") == branch(plan, id) &&
            (path == expectedPath || path == "$expectedPath@${branch(plan, id)}" || path == "$expectedPath@refs/heads/${branch(plan, id)}" || path == "$expectedPath@$revision") &&
            (run["repository"] as? JsonObject)?.phoneText("full_name")?.lowercase() == plan.repositoryFullName?.lowercase()
    }

    fun result(proof: JsonObject, submission: JsonObject, run: JsonObject, plan: BenchmarkEngineeringPlan, revision: String): JsonObject {
        val id = submission.phoneText("request_id")
        require(runBound(run, plan, id, revision) && run.phoneText("status") == "completed" && run.phoneText("conclusion") == "success") { "PHONE_BENCHMARK_RUN_NOT_VERIFIED" }
        require(proof.phoneText("schema") == "gateway.phone-benchmark-result.v1") { "PHONE_BENCHMARK_RESULT_SCHEMA" }
        require(proof.phoneText("receiptDigest") == benchmarkHash(canonical(JsonObject(proof - "receiptDigest")))) { "PHONE_BENCHMARK_RESULT_DIGEST_MISMATCH" }
        val fields = mapOf("requestId" to "request_id", "taskId" to "task_id", "taskPack" to "task_pack", "seed" to "seed", "repository" to "repository", "branch" to "branch", "baseSha" to "base_sha", "workflowPath" to "workflow_path", "proposalDigest" to "proposal_digest", "fixtureDigest" to "fixture_digest", "graderDigest" to "grader_digest", "environmentDigest" to "environment_digest", "bundleDigest" to "bundle_digest", "generationReceipt" to "generation_receipt")
        require(
            fields.all { (result, expected) ->
                if (result == "repository") proof.phoneText(result).equals(submission.phoneText(expected), true) else proof[result] == submission[expected]
            }
        ) { "PHONE_BENCHMARK_RESULT_BINDING_MISMATCH" }
        require(proof.phoneText("testedRevision") == revision && proof.phoneText("publishedRevision") == revision && proof.phoneLong("workflowRunId") == run.phoneLong("id") && proof.phoneLong("workflowRunAttempt") == run.phoneLong("run_attempt")) { "PHONE_BENCHMARK_RESULT_REVISION_MISMATCH" }
        require(proof.phoneText("liveGitHub") == "OBSERVED" && proof.phoneBoolean("outcomeKnown") == true) { "PHONE_BENCHMARK_OUTCOME_UNVERIFIED" }
        val row = proof["case"] as? JsonObject ?: error("PHONE_BENCHMARK_CASE_MISSING")
        require(row.phoneText("id") == submission.phoneText("task_id") && row.phoneText("status") in setOf("PASSED", "FAILED", "ERROR", "BLOCKED", "INTERRUPTED")) { "PHONE_BENCHMARK_CASE_STATUS_INVALID" }
        require(proof["status"] == row["status"] && listOf("qualified", "resolved", "graded", "executed").all { proof.phoneBoolean(it) != null && proof[it] == row[it] }) { "PHONE_BENCHMARK_CASE_VERDICT_MISMATCH" }
        val failureDomain = proof.phoneText("failureDomain")
        val modelOutcome = failureDomain in setOf("", "MODEL") && row.phoneText("status") in setOf("PASSED", "FAILED")
        return buildJsonObject {
            row.forEach { (key, value) -> put(key, value) }
            listOf("fixtureDigest", "graderDigest", "environmentDigest", "proposalDigest", "patchDigest", "taskPack", "graded", "executed", "qualified", "resolved").forEach { key -> proof[key]?.let { put(key, it) } }
            if (!modelOutcome) {
                put("status", "ERROR")
                put("resolved", false)
                put("error", "PHONE_BENCHMARK_INFRASTRUCTURE_OUTCOME")
            }
            put(
                "liveGitHubReceipt",
                buildJsonObject {
                    put("testedRevision", revision)
                    put("publishedRevision", revision)
                    put("remoteReadbackVerified", true)
                    put("workflowRunId", run.getValue("id"))
                    put("workflowRunAttempt", run.getValue("run_attempt"))
                }
            )
        }
    }
}

internal fun JsonObject.phoneText(key: String): String = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
internal fun JsonObject.phoneLong(key: String): Long? = (get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
internal fun JsonObject.phoneBoolean(key: String): Boolean? = (get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
