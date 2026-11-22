package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** Read, review and delivery obligations cannot borrow unrelated commit or test receipts. */
internal object GitHubCriterionAcceptance {
    fun evaluate(
        criterion: GitHubAcceptanceContract.Criterion,
        acceptance: JsonObject?,
        linkedIds: Set<String>,
        receipts: Map<String, JsonObject>,
        now: Long
    ): GitHubRequestCompletion.Assessment? {
        val linked = linkedIds.mapNotNull(receipts::get).filter(::eligible)
        val observations = linked.flatMap(::sourceObservations)
        return when (criterion.producer) {
            GitHubAcceptanceContract.Producer.SOURCE_OBSERVATION -> inspection(criterion.text, observations)
            GitHubAcceptanceContract.Producer.DEFECT_ANALYSIS ->
                if (observations.any { !instructionPath(it.path) }) passed("The checked finding cites observed source content") else missing("Link the nonempty source windows supporting this correctness finding; an instruction or search inventory alone does not establish a defect")
            GitHubAcceptanceContract.Producer.EVIDENCE_REPORT ->
                if (linked.isNotEmpty()) passed("The report cites checked observations; every earlier deliverable remains a separate acceptance obligation") else missing("Link the exact changes and validation observations, including any stated limitations")
            GitHubAcceptanceContract.Producer.DIFF_REVIEW -> diffReview(linkedIds, receipts)
            GitHubAcceptanceContract.Producer.DRAFT_PULL_REQUEST -> draftPullRequest(criterion.text, acceptance, receipts, now)
            GitHubAcceptanceContract.Producer.REGRESSION_AND_TESTS -> {
                if (acceptance == null) return null
                val assessment = GitHubRequestCompletion.engineeringAcceptance(acceptance, receipts)
                if (assessment.state != GitHubAcceptanceLedger.State.PASS) return assessment
                val implementations = implementations(acceptance, receipts)
                val changedTests = implementations.flatMap(::changedTestFiles)
                val proven = changedTests.any { change ->
                    observations.any { it.path == change.path && it.blob == change.blob && it.revision == change.revision } ||
                        (data(change.receipt)["patch_preview"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().any { preview ->
                            preview.text("path") == change.path &&
                                preview.text("expected_blob_sha") == change.previousBlob &&
                                (preview["after"] as? JsonPrimitive)?.let { it.isString && it.content.isNotBlank() } == true &&
                                preview["before"] != preview["after"]
                        }
                }
                if (proven) assessment else missing("Link an added or modified regression in the committed change set and its exact source or retained patch preview at the tested revision; an unchanged test read is not a new regression")
            }
            GitHubAcceptanceContract.Producer.REVISION_AND_TESTS -> {
                if (!Regex("(?i)\\bon a new (?:feature )?branch\\b").containsMatchIn(criterion.text) || acceptance == null) return null
                val assessment = GitHubRequestCompletion.engineeringAcceptance(acceptance, receipts)
                if (assessment.state != GitHubAcceptanceLedger.State.PASS) return assessment
                val implementations = implementations(acceptance, receipts)
                val branches = linked.filter { native(it)?.text("action") == "create_branch" && GitHubEvidenceOrigin.of(it) == "LIVE" }
                val created = implementations.all { implementation ->
                    val branch = data(implementation).text("branch")
                    branch.isNotBlank() &&
                        branches.any { creation ->
                            creation["repository"] == implementation["repository"] &&
                                creation["binding"] == implementation["binding"] &&
                                native(creation)?.text("branch") == branch &&
                                data(creation).text("branch") == branch &&
                                data(creation)["created"] == JsonPrimitive(true) &&
                                data(creation)["receipt_verified"] == JsonPrimitive(true) &&
                                data(creation)["remote_state_verified"] == JsonPrimitive(true)
                        }
                }
                if (created) assessment else missing("Link the verified creation of the feature branch containing this implementation; a commit alone does not prove a new branch was created")
            }
            else -> null
        }
    }

    private fun inspection(requirement: String, observations: List<SourceObservation>): GitHubRequestCompletion.Assessment {
        if (observations.isEmpty()) return missing("Link nonempty source windows with exact paths, blob identities and the pinned revision")
        if (Regex("(?i)\\binstructions?\\b").containsMatchIn(requirement) && !completeInstructions(observations)) {
            return missing("Read the complete applicable repository instructions; another source file cannot satisfy instruction inspection")
        }
        if (Regex("(?i)\\btests?\\b").containsMatchIn(requirement) && observations.none { testPath.containsMatchIn(it.path) }) {
            return missing("Read the relevant test source as well as the requested instructions")
        }
        val paths = explicitPath.findAll(requirement).map { it.value }.toSet()
        if (paths.any { requested -> observations.none { it.path == requested || '/' !in requested && it.path.substringAfterLast('/') == requested } }) {
            return missing("Every explicitly named source path needs its own observed content; unrelated reads cannot satisfy the criterion")
        }
        if (paths.filter(::instructionPath).any { requested ->
                !completeInstructions(observations.filter { it.path == requested || '/' !in requested && it.path.substringAfterLast('/') == requested })
            }
        ) {
            return missing("Every explicitly named instruction file needs complete observed source coverage; another instruction file cannot fill a missing window")
        }
        return passed("Observed source content covers the explicitly requested paths and inspection categories")
    }

    private fun draftPullRequest(requirement: String, acceptance: JsonObject?, receipts: Map<String, JsonObject>, now: Long): GitHubRequestCompletion.Assessment {
        if (acceptance == null) return missing("Link the draft pull request operation and its exact-head live readback")
        // Preserve the entire requested suffix. The operation evaluator consumes its
        // complete grammar, so unknown repository/title/branch constraints stay unverified.
        val canonical = requirement.replaceFirst(draftPrefix, "create pull request")
        if (!GitHubOperationAcceptance.supports(canonical)) return missing("The complete draft pull request constraints need explicit operation-specific verification; no requested suffix may be discarded")
        val draftIds = ids(acceptance, "operation_receipts").filter { id ->
            receipts[id]?.let { receipt -> eligible(receipt) && native(receipt)?.let { it.text("action") == "create_pull_request" && it["draft"] == JsonPrimitive(true) } == true } == true
        }
        if (draftIds.isEmpty()) return missing("A created draft pull request receipt is required; a patch or ordinary pull request does not satisfy this deliverable")
        val exact = JsonObject(acceptance + ("operation_receipts" to JsonArray(draftIds.map(::JsonPrimitive))))
        return requireNotNull(GitHubOperationAcceptance.evaluate(canonical, exact, receipts, now)).let { GitHubRequestCompletion.Assessment(it.state, it.reason) }
    }

    private fun diffReview(linkedIds: Set<String>, receipts: Map<String, JsonObject>): GitHubRequestCompletion.Assessment {
        val coverage = GitHubComparisonCoverage.observe(receipts) { native(it)?.text("action") in setOf("changed_since", "compare_refs", "get_pull_request_files") }
        val head = receipts.values.lastOrNull { eligible(it) && it["head_observation"] == JsonPrimitive(true) }?.text("source_head_sha")
        val current = coverage?.receipts?.values?.filter { native(it)?.text("action") in setOf("changed_since", "compare_refs", "get_pull_request_files") }.orEmpty()
        return when {
            coverage == null || linkedIds.none { it in coverage.receipts } -> missing("Link the pinned comparison supporting this diff review")
            !coverage.complete -> blocked("Read every remote comparison page and retained fragment before completing the diff review")
            head != null && current.any { data(it).text("head_sha") != head } -> blocked("The diff review belongs to a superseded repository head")
            else -> passed("The review cites complete comparison coverage at the observed repository revision")
        }
    }

    private data class SourceIdentity(val path: String, val blob: String, val revision: String, val total: Int, val repository: String, val binding: String)
    private data class SourceObservation(val identity: SourceIdentity, val start: Int, val end: Int) {
        val path get() = identity.path
        val blob get() = identity.blob
        val revision get() = identity.revision
    }

    private fun completeInstructions(observations: List<SourceObservation>): Boolean = observations
        .filter { instructionPath(it.path) }
        .groupBy { it.identity }
        .any { (identity, windows) ->
            var next = 1L
            for (window in windows.sortedBy { it.start }) {
                if (window.start > next) return@any false
                next = maxOf(next, window.end.toLong() + 1)
            }
            next == identity.total.toLong() + 1
        }

    private fun sourceObservations(receipt: JsonObject): List<SourceObservation> {
        if (native(receipt)?.text("action") != "read_ranges") return emptyList()
        val result = data(receipt)
        val revision = result.text("commit_sha")
        if (!sha.matches(revision) || receipt.text("source_head_sha") != revision) return emptyList()
        return (result["items"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().mapNotNull { item ->
            val path = item.text("path")
            val start = (item["start_line"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
            val end = (item["end_line"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
            val total = (item["total_lines"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
            val content = item["content"] as? JsonPrimitive
            if (path.isBlank() ||
                path.startsWith('/') ||
                path.split('/').any { it in setOf("", ".", "..") } ||
                item.text("commit_sha") != revision ||
                !sha.matches(item.text("sha")) ||
                item["expected_blob_sha"] != item["sha"] ||
                start < 1 ||
                end < start ||
                end > total ||
                content?.isString != true ||
                content.content.isBlank() ||
                item["range_complete"] != JsonPrimitive(true)
            ) {
                return@mapNotNull null
            }
            SourceObservation(SourceIdentity(path, item.text("sha"), revision, total, receipt.text("repository"), receipt.text("binding")), start, end)
        }
    }

    private fun implementations(acceptance: JsonObject, receipts: Map<String, JsonObject>): List<JsonObject> =
        ids(acceptance, "implementation_receipts").mapNotNull(receipts::get).filter { receipt ->
            eligible(receipt) && ((receipt["arguments"] as? JsonObject)?.text("action") in setOf("commit", "commit_staged_patch") || GitHubIssueRemediation.publishedRevision(receipt) != null)
        }

    private data class ChangedTestFile(val path: String, val blob: String, val previousBlob: String, val revision: String, val receipt: JsonObject)
    private fun changedTestFiles(receipt: JsonObject): List<ChangedTestFile> {
        val result = data(receipt)
        if (!sha.matches(receipt.text("source_head_sha")) || result.text("sha") != receipt.text("source_head_sha")) return emptyList()
        return (result["files"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().mapNotNull { file ->
            val path = file.text("path")
            val blob = file.text("blob_sha")
            val previous = file.text("previous_blob_sha")
            ChangedTestFile(path, blob, previous, receipt.text("source_head_sha"), receipt).takeIf {
                testPath.containsMatchIn(path) &&
                    file.text("status") in setOf("added", "modified") &&
                    file["delete"] != JsonPrimitive(true) &&
                    sha.matches(blob) &&
                    (file.text("status") == "added" && previous.isEmpty() || sha.matches(previous) && previous != blob)
            }
        }
    }

    private val sha = Regex("[a-fA-F0-9]{40}")
    private val testPath = Regex("(?i)(?:^|/)(?:tests?|androidTest|testFixtures)(?:/|$)|(?:Test|Tests|Spec)\\.[a-z]+$|(?:^|/)test_[^/]+\\.[a-z]+$")
    private val explicitPath = Regex("[A-Za-z0-9_./-]+\\.(?:kt|kts|java|md|py|js|ts|tsx|yml|yaml|json|xml)\\b")
    private val draftPrefix = Regex("(?i)^(?:please\\s+)?(?:prepare|create)\\s+(?:a |the )?draft (?:pull request|pr)\\b")
    private fun instructionPath(path: String): Boolean = path.substringAfterLast('/').equals("AGENTS.md", true) || path.substringAfterLast('/').equals("instructions.md", true) || path.endsWith("/copilot-instructions.md", true) || "/instructions/" in "/$path"
    private fun ids(value: JsonObject, key: String) = (value[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    private fun native(receipt: JsonObject): JsonObject? {
        val args = receipt["arguments"] as? JsonObject ?: return null
        return if (receipt.text("tool").substringBefore("__") == "github") args else GitHubOperationTool.nativeArguments(receipt.text("tool"), args)
    }
    private fun data(receipt: JsonObject) = (receipt["result"] as? JsonObject)?.let { (it["data"] as? JsonObject) ?: it } ?: JsonObject(emptyMap())
    private fun eligible(receipt: JsonObject) = receipt["trusted"] == JsonPrimitive(true) && receipt["success"] == JsonPrimitive(true) && receipt["verification_eligible"] != JsonPrimitive(false)
    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
    private fun passed(reason: String) = GitHubRequestCompletion.Assessment(GitHubAcceptanceLedger.State.PASS, reason)
    private fun missing(reason: String) = GitHubRequestCompletion.Assessment(GitHubAcceptanceLedger.State.NOT_TESTED, reason)
    private fun blocked(reason: String) = GitHubRequestCompletion.Assessment(GitHubAcceptanceLedger.State.BLOCKED, reason)
}
