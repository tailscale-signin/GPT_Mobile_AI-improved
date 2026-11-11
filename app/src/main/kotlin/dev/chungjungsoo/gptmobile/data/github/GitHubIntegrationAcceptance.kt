package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.permissions.GitHubAuthorizationBoundary
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** Independent acceptance predicates. Evidence for one requirement cannot erase or satisfy another. */
internal object GitHubIntegrationAcceptance {
    private val sha = Regex("[a-fA-F0-9]{40}")
    private val applicationWork = Regex("(?i)\\b(?:fix|implement|repair|improve|stress|benchmark|reboot|performance|latency|features?)\\b|process death|install apk|app(?:lication)? health")
    private val deviceWork = Regex("(?i)\\b(?:device|android)\\b")
    fun requiresBuildBinding(task: String): Boolean = GitHubRequestCompletion.requirements(task).any { requirement ->
        val text = requirement.lowercase()
        ("bind" in text || "binding" in text) && ("build" in text || "revision" in text)
    }

    fun requirements(
        task: String,
        receipts: Map<String, JsonObject>,
        tools: List<AgentToolDefinition>,
        runtime: JsonObject = GitHubRuntimeIdentity.current(),
        fixturePreflight: JsonObject? = null
    ): JsonArray {
        val eligible = receipts.values.filter(::eligible)
        val catalog = GitHubAuthorizedCatalog.snapshot(receipts, tools)
        val source = binding(receipts.values, runtime)
        val classes = evidenceClasses(receipts)
        return JsonArray(
            GitHubRequestCompletion.requirements(task).map { requirement ->
                val text = requirement.lowercase()
                var proof: List<JsonObject> = emptyList()
                var conditionalFixture = false
                val assessment: Pair<Boolean, String> = when {
                    listOf("separate", "separation", "distinguish").any { it in text } && "device" in text && ("mock" in text || "live" in text) -> {
                        proof = eligible
                        (eligible.isNotEmpty() && classes.text("classification_status") == "COMPLETE") to
                            "Live account, cached/local, mocked, historical CI, current-device execution and app gateway observations are reported separately; absent execution classes remain NOT_TESTED."
                    }
                    ("bind" in text || "binding" in text) && ("build" in text || "revision" in text) -> {
                        proof = eligible.filter { it["runtime_identity"] == runtime && it.text("receipt_id") in source.ids() }
                        (source["verified"] == JsonPrimitive(true)) to source.text("reason")
                    }
                    ("catalog" in text || "catalogue" in text) && ("discover" in text || "exhaust" in text || "authorized" in text) -> {
                        val firstPage = eligible.indexOfFirst(::remotePage)
                        val reads = eligible.filterIndexed { index, receipt -> liveRead(receipt) && (firstPage < 0 || index < firstPage) }
                        proof = eligible.filter { it.text("tool") == GitHubAuthorizedCatalog.TOOL } + reads
                        val needsReads = "read" in text
                        val valid = catalog["complete"] == JsonPrimitive(true) && (!needsReads || reads.isNotEmpty())
                        valid to if (valid) {
                            "The version-bound authorized inventory is exhausted${if (needsReads) "; representative live reads preceded repository pagination" else ""}."
                        } else {
                            "Authorized catalog: ${catalog.text("status")}. ${if (needsReads && reads.isEmpty()) "A representative live read before repository pagination remains unverified." else "Every issued catalog page must be observed before its denominator is final."}"
                        }
                    }
                    ("fixture" in text || "disposable" in text) && ("mutation" in text || "write" in text || "cleanup" in text) -> {
                        proof = eligible.filter { !read(it) }
                        val clean = GitHubSelfTestFixtures.complete(receipts)
                        conditionalFixture = !clean && fixturePreflight?.get("state") != JsonPrimitive("READY") && GitHubSelfTestFixtures.checkpoint(receipts).isEmpty()
                        clean to if (clean) "Owned disposable mutation receipts and matching cleanup verification are retained." else if (conditionalFixture) {
                            "Conditional before mutation: effective approval mode requires native user approval, so no fixture branch was created. Select fixture-only auto approval for codex-self-test/* or run the approval interaction harness."
                        } else {
                            "Owned fixture creation, mutation verification and cleanup must all have matching receipts; planned or accepted writes are insufficient."
                        }
                    }
                    GitHubTaskCoverage.matches(requirement) &&
                        ("tool" in text || "integration" in text || text.trim().matches(Regex("test all[.!]?"))) &&
                        !applicationWork.containsMatchIn(requirement) &&
                        !deviceWork.containsMatchIn(requirement) -> {
                        proof = eligible
                        GitHubTestChecklist.capabilitiesComplete(task, receipts, tools, fixturePreflight) to
                            "Every requested authorized API action needs its own qualifying receipt and required fixture cleanup. Catalog, traversal and device execution have separate coverage."
                    }
                    else -> false to "No matching execution predicate is available for this requirement; independent API observations do not establish device, application or implementation acceptance."
                }
                buildJsonObject {
                    put("id", GitHubRequestCompletion.id(requirement))
                    put("requirement", requirement)
                    put("required", true)
                    put("status", when { assessment.first -> "PASSED"; conditionalFixture -> "CONDITIONAL"; else -> "NOT_TESTED" })
                    put("acceptance_state", when { assessment.first -> "PASS"; conditionalFixture -> "CONDITIONAL"; else -> "NOT_TESTED" })
                    put("acceptance", assessment.second)
                    put("evidence_receipt_ids", JsonArray(proof.mapNotNull { it["receipt_id"] }.distinct()))
                }
            }
        )
    }

    fun binding(receipts: Collection<JsonObject>, runtime: JsonObject = GitHubRuntimeIdentity.current()): JsonObject {
        val missingIdentity = buildList {
            if (runtime.text("identity_source") != "RUNNING_PROCESS_BUILD_CONFIG") add("identity_source")
            if (runtime.text("application_id").isBlank()) add("application_id")
            if (runtime.text("version_name").isBlank()) add("version_name")
            if ((runtime["version_code"] as? JsonPrimitive)?.intOrNull?.let { it > 0 } != true) add("version_code")
            if (!sha.matches(runtime.text("app_source_sha"))) add("app_source_sha")
        }
        val candidates = receipts.filter { it.text("tool").substringBefore("__") == "github_context" && args(it).text("action") == "status" }
        fun exclusions(receipt: JsonObject): List<String> = buildList {
            if (receipt["trusted"] != JsonPrimitive(true)) add("UNTRUSTED_RECEIPT")
            if (receipt["verification_eligible"] == JsonPrimitive(false)) add("CURRENT_AUTHORIZATION_EXCLUDES_RECEIPT")
            if (receipt["success"] != JsonPrimitive(true)) add("STATUS_OPERATION_FAILED")
            if (!sha.matches(data(receipt).text("commit_sha"))) add("FULL_REPOSITORY_SHA_MISSING")
            if (receipt["runtime_identity"] == null) {
                add("RECEIPT_BUILD_IDENTITY_MISSING")
            } else if (receipt["runtime_identity"] != runtime) {
                add("RECEIPT_BUILD_IDENTITY_DIFFERS")
            }
            if (!liveRead(receipt)) add("CURRENT_LIVE_READ_NOT_OBSERVED")
            if (receipt.text("repository").isBlank()) add("REPOSITORY_IDENTITY_MISSING")
        }
        val status = candidates.lastOrNull { exclusions(it).isEmpty() }
        val bound = missingIdentity.isEmpty() && status != null
        val reasonCode = when {
            missingIdentity.isNotEmpty() -> "RUNNING_BUILD_IDENTITY_INCOMPLETE"
            candidates.isEmpty() -> "STATUS_RECEIPT_NOT_OBSERVED"
            status == null -> "STATUS_RECEIPTS_EXCLUDED"
            else -> "CURRENT_BUILD_STATUS_BOUND"
        }
        return buildJsonObject {
            put("verified", bound)
            put("runtime_identity", runtime)
            put("reason_code", reasonCode)
            put("missing_identity_fields", JsonArray(missingIdentity.map(::JsonPrimitive)))
            put("candidate_receipt_count", candidates.size)
            put(
                "excluded_receipts",
                JsonArray(
                    candidates.filter { exclusions(it).isNotEmpty() }.takeLast(8).map { receipt ->
                        buildJsonObject {
                            receipt["receipt_id"]?.let { put("receipt_id", it) }
                            put("reasons", JsonArray(exclusions(receipt).map(::JsonPrimitive)))
                        }
                    }
                )
            )
            status?.let {
                put("repository", it.text("repository"))
                put("repository_revision", data(it).text("commit_sha"))
                put("repository_branch", data(it).text("branch"))
                put("receipt_ids", JsonArray(listOfNotNull(it["receipt_id"])))
                it["binding"]?.let { binding -> put("run_binding", binding) }
                it["run_id"]?.let { run -> put("run_id", run) }
            }
            put(
                "reason",
                when (reasonCode) {
                    "CURRENT_BUILD_STATUS_BOUND" -> "The qualifying receipt binds the running process build to the exact observed target repository revision. App source and target repository SHA are distinct identities."
                    "RUNNING_BUILD_IDENTITY_INCOMPLETE" -> "Running-build qualification is missing or has invalid fields: ${missingIdentity.joinToString()}."
                    "STATUS_RECEIPT_NOT_OBSERVED" -> "No repository status receipt was observed; matching app and repository SHAs alone do not establish runtime qualification."
                    else -> "No captured status receipt qualifies: ${candidates.flatMap(::exclusions).distinct().joinToString()}. Historical receipts keep their original build identity."
                }
            )
            put("device_execution", "NOT_TESTED")
        }
    }

    fun evidenceClasses(receipts: Map<String, JsonObject>): JsonObject {
        val observed = receipts.values.filter { it["trusted"] == JsonPrimitive(true) }
        val groups = linkedMapOf(
            "LIVE_ACCOUNT" to observed.filter { GitHubEvidenceOrigin.of(it) == "LIVE" && it.text("tool").substringBefore("__") != "repository_graph" },
            "CACHED_OR_LOCAL" to observed.filter { GitHubEvidenceOrigin.of(it) in setOf("CACHED", "LOCAL", "NOT_TESTED") },
            "MOCKED" to observed.filter { GitHubEvidenceOrigin.of(it) == "MOCKED" },
            "HISTORICAL_CI_OBSERVATION" to observed.filter { it.text("tool").substringBefore("__") == "github_ci" && args(it).text("action") in setOf("runs", "run", "jobs", "logs", "workflows", "artifacts", "failure") },
            "APP_GATEWAY" to observed.filter { it.text("tool").substringBefore("__") == "repository_graph" && GitHubEvidenceOrigin.of(it) == "LIVE" }
        )
        return buildJsonObject {
            put("classification_status", if (observed.any { it.text("evidence_origin").isNotBlank() && it.text("evidence_origin") !in setOf("LIVE", "CACHED", "LOCAL", "MOCKED", "NOT_TESTED") }) "INCOMPLETE" else "COMPLETE")
            groups.forEach { (name, values) ->
                put(
                    name,
                    buildJsonObject {
                        put("count", values.size)
                        put("receipt_ids", JsonArray(values.mapNotNull { it["receipt_id"] }))
                    }
                )
            }
            put(
                "CURRENT_DEVICE_EXECUTION",
                buildJsonObject {
                    put("count", 0)
                    put("status", "NOT_TESTED")
                    put("reason", "GitHub API and CI observations do not constitute current-device execution")
                }
            )
            put("semantics", "CI provenance overlaps its transport class. Counts describe evidence, not interchangeable test passes.")
        }
    }

    private fun eligible(receipt: JsonObject) = receipt["trusted"] == JsonPrimitive(true) && receipt["verification_eligible"] != JsonPrimitive(false) && receipt["success"] == JsonPrimitive(true)
    private fun read(receipt: JsonObject) = GitHubAuthorizationBoundary.isReplayableRead(receipt.text("tool"), args(receipt))
    private fun liveRead(receipt: JsonObject): Boolean {
        val execution = receipt["execution"] as? JsonObject
        return read(receipt) &&
            receipt.text("tool") != GitHubAuthorizedCatalog.TOOL &&
            GitHubEvidenceOrigin.of(receipt) == "LIVE" &&
            execution?.get("live_read_status") != JsonPrimitive("NOT_PERFORMED") &&
            execution?.get("live_read_status") != JsonPrimitive("RESPONSE_NOT_FULLY_OBSERVED")
    }
    private fun remotePage(receipt: JsonObject) = receipt.text("tool") != GitHubAuthorizedCatalog.TOOL &&
        (args(receipt)["page"] as? JsonPrimitive)?.intOrNull?.let { it > 1 } == true
    private fun args(receipt: JsonObject) = receipt["arguments"] as? JsonObject ?: JsonObject(emptyMap())
    private fun data(receipt: JsonObject) = (receipt["result"] as? JsonObject)?.let { (it["data"] as? JsonObject) ?: it } ?: JsonObject(emptyMap())
    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
    private fun JsonObject.ids() = (get("receipt_ids") as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
}
