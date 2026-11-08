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
        runtime: JsonObject = GitHubRuntimeIdentity.current()
    ): JsonArray {
        val eligible = receipts.values.filter(::eligible)
        val catalog = GitHubAuthorizedCatalog.snapshot(receipts, tools)
        val source = binding(eligible, runtime)
        val classes = evidenceClasses(receipts)
        return JsonArray(
            GitHubRequestCompletion.requirements(task).map { requirement ->
                val text = requirement.lowercase()
                var proof: List<JsonObject> = emptyList()
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
                        clean to if (clean) "Owned disposable mutation receipts and matching cleanup verification are retained." else "Owned fixture creation, mutation verification and cleanup must all have matching receipts; planned or accepted writes are insufficient."
                    }
                    GitHubTaskCoverage.matches(requirement) &&
                        ("tool" in text || "integration" in text || text.trim().matches(Regex("test all[.!]?"))) &&
                        !applicationWork.containsMatchIn(requirement) &&
                        !deviceWork.containsMatchIn(requirement) -> {
                        proof = eligible
                        GitHubTestChecklist.capabilitiesComplete(task, receipts, tools) to
                            "Every requested authorized API action needs its own qualifying receipt and required fixture cleanup. Catalog, traversal and device execution have separate coverage."
                    }
                    else -> false to "No matching execution predicate is available for this requirement; independent API observations do not establish device, application or implementation acceptance."
                }
                buildJsonObject {
                    put("id", GitHubRequestCompletion.id(requirement))
                    put("requirement", requirement)
                    put("required", true)
                    put("status", if (assessment.first) "PASSED" else "NOT_TESTED")
                    put("acceptance_state", if (assessment.first) "PASS" else "NOT_TESTED")
                    put("acceptance", assessment.second)
                    put("evidence_receipt_ids", JsonArray(proof.mapNotNull { it["receipt_id"] }.distinct()))
                }
            }
        )
    }

    fun binding(receipts: Collection<JsonObject>, runtime: JsonObject = GitHubRuntimeIdentity.current()): JsonObject {
        val eligible = receipts.filter(::eligible)
        val identityComplete = runtime.text("identity_source") == "RUNNING_PROCESS_BUILD_CONFIG" &&
            runtime.text("application_id").isNotBlank() &&
            runtime.text("version_name").isNotBlank() &&
            (runtime["version_code"] as? JsonPrimitive)?.intOrNull?.let { it > 0 } == true &&
            sha.matches(runtime.text("app_source_sha"))
        val status = eligible.lastOrNull {
            it.text("tool").substringBefore("__") == "github_context" &&
                args(it).text("action") == "status" &&
                sha.matches(data(it).text("commit_sha")) &&
                it["runtime_identity"] == runtime &&
                liveRead(it)
        }
        val bound = identityComplete && status != null && status.text("repository").isNotBlank()
        return buildJsonObject {
            put("verified", bound)
            put("runtime_identity", runtime)
            status?.let {
                put("repository", it.text("repository"))
                put("repository_revision", data(it).text("commit_sha"))
                put("repository_branch", data(it).text("branch"))
                put("receipt_ids", JsonArray(listOfNotNull(it["receipt_id"])))
            }
            put("reason", if (bound) "The receipt binds the running process build to the exact observed target repository revision. App source and target repository SHA are distinct identities." else "A complete running-process build identity and matching current-build repository status receipt at a full commit SHA are required; historical receipts cannot acquire a new identity on restore.")
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
