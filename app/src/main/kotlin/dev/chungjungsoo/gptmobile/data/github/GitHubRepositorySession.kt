package dev.chungjungsoo.gptmobile.data.github

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** One task pins a repository revision; immutable bytes are shared only within the account scope. */
internal class GitHubRepositorySession(
    private val accountId: String,
    private val connectionUid: String,
    private val conversationId: String,
    private val taskId: String,
    private val store: GitHubRepositoryStateStore,
    private val request: suspend (String) -> JsonElement,
    private val commit: suspend (String, JsonObject) -> JsonElement,
    private val reconcileCommit: suspend (String, JsonObject) -> JsonElement? = { _, _ -> null },
    private val searchBlobTimeoutMs: Long = 8_000,
    private val readOperationTimeoutMs: Long = 12_000,
    private val indexBlobTimeoutMs: Long = 4_000,
    private val initialRepositoryContext: GitHubConversationContext? = null,
    private val taskContextBlobTimeoutMs: Long = 2500
) {
    private val scope = listOf(accountId, connectionUid, conversationId, taskId).joinToString(":")
    private val conversationScope = listOf(accountId, connectionUid, conversationId).joinToString(":")
    private val executions = GitHubExecutionJournal(store, scope)
    private val codec = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private fun String.sha() = matches(Regex("[a-fA-F0-9]{40}|[a-fA-F0-9]{64}"))
    private fun JsonObject.s(key: String) = (get(key) as? JsonPrimitive)?.content.orEmpty()
    private fun root(owner: String, repo: String) = "/repos/${GitHubWorkspaceClient.segment(owner)}/${GitHubWorkspaceClient.segment(repo)}"
    private fun blobScope(context: GitHubTaskSnapshot) = "$accountId:${context.owner.lowercase()}/${context.repo.lowercase()}"

    private suspend fun forceLiveReads(): Boolean = kotlinx.coroutines.currentCoroutineContext()[GitHubDispatchAccounting]?.forceLiveReads == true

    /** The transport policy also applies to parsed metadata and immutable source caches above HTTP. */
    private suspend fun cachedSource(scope: String, key: String): JsonObject? {
        if (forceLiveReads()) return null
        return store.read(scope, key)?.also { GitHubDispatchAccounting.cached(null, immutable = true) }
    }

    private fun decodeSnapshot(value: JsonObject?): GitHubTaskSnapshot? = value?.let {
        runCatching { codec.decodeFromJsonElement(GitHubTaskSnapshot.serializer(), it) }.getOrNull()?.takeIf { row -> row.accountId == accountId && row.connectionUid == connectionUid && row.conversationId == conversationId && row.taskId == taskId }
    }

    suspend fun snapshot(): GitHubTaskSnapshot? = decodeSnapshot(store.read(scope, "snapshot"))

    private fun evidenceKeys(owner: String, repo: String) = "source-evidence-keys:" + GitHubRepositoryStateStore.digest("${owner.lowercase()}/${repo.lowercase()}")

    /** Immutable source descriptors may cross turns; stages and mutation receipts remain task-local. */
    private suspend fun retainedEvidence(id: String): JsonObject? = store.read(scope, "evidence:$id")
        ?: if (conversationId.isNotBlank()) store.read(conversationScope, "source-evidence:$id") else null

    private suspend fun retainSourceDescriptor(id: String, evidence: JsonObject) {
        if (conversationId.isBlank()) return
        val files = JsonArray(
            evidence["items"]?.jsonArray.orEmpty().map { raw ->
                JsonObject(raw.jsonObject.filterKeys { it in setOf("path", "sha", "start_line", "end_line", "total_lines") })
            }
        )
        val descriptor = JsonObject(evidence + ("items" to files))
        store.write(conversationScope, "source-evidence:$id", descriptor)
        val evicted = mutableListOf<String>()
        store.update(conversationScope, evidenceKeys(evidence.s("owner"), evidence.s("repo"))) { old ->
            val ids = old?.get("keys")?.jsonArray.orEmpty().map { it.jsonPrimitive.content }.filter { it != id } + id
            evicted += ids.dropLast(128)
            buildJsonObject { put("keys", JsonArray(ids.takeLast(128).map(::JsonPrimitive))) }
        }
        evicted.forEach { store.remove(conversationScope, "source-evidence:$it") }
    }

    private suspend fun updateSnapshot(transform: (GitHubTaskSnapshot) -> GitHubTaskSnapshot) {
        store.update(scope, "snapshot") { retained ->
            val current = requireNotNull(decodeSnapshot(retained)) { "The repository task revision is unavailable; resolve it again." }
            codec.encodeToJsonElement(GitHubTaskSnapshot.serializer(), transform(current)) as JsonObject
        }
    }

    suspend fun context(): GitHubRepositoryContext? = snapshot()?.let { GitHubRepositoryContext(it.owner, it.repo, it.ref) }
        ?: store.read(conversationScope, "selected_context")?.let { row ->
            GitHubRepositoryContext(row.s("owner"), row.s("repo"), row.s("ref")).takeIf { it.owner.isNotBlank() && it.repo.isNotBlank() && it.ref.isNotBlank() }
        }

    private suspend fun save(value: GitHubTaskSnapshot) {
        store.write(scope, "snapshot", codec.encodeToJsonElement(GitHubTaskSnapshot.serializer(), value) as JsonObject)
        store.write(
            conversationScope,
            "selected_context",
            buildJsonObject {
                put("owner", value.owner)
                put("repo", value.repo)
                put("ref", value.ref)
            }
        )
    }

    /** Seed the frozen chat target before a first historical read, without replacing a concurrent selection. */
    private suspend fun initialSnapshot(owner: String, repo: String): GitHubTaskSnapshot? {
        val bound = initialRepositoryContext?.takeIf {
            it.connectionUid == connectionUid && it.owner.equals(owner, true) && it.repo.equals(repo, true)
        } ?: return null
        val response = request("${root(owner, repo)}/commits/${GitHubWorkspaceClient.segment(bound.commitSha)}").jsonObject
        val sha = response.s("sha")
        val treeSha = response["commit"]?.jsonObject?.get("tree")?.jsonObject?.s("sha").orEmpty()
        require(sha.equals(bound.commitSha, true) && treeSha.sha()) { "GitHub did not supply the bound immutable commit and tree." }
        val pinned = GitHubTaskSnapshot(accountId, connectionUid, conversationId, taskId, owner, repo, bound.ref, sha, treeSha)
        val selected = store.update(scope, "snapshot") { existing ->
            existing?.takeIf { decodeSnapshot(it) != null } ?: (codec.encodeToJsonElement(GitHubTaskSnapshot.serializer(), pinned) as JsonObject)
        }
        return decodeSnapshot(selected)
    }

    suspend fun resolve(owner: String, repo: String, args: JsonObject, selectContext: Boolean = false): GitHubTaskSnapshot {
        val explicit = args.s("commit_sha").ifBlank { args.s("ref").takeIf { it.sha() }.orEmpty() }
        if (explicit.isNotEmpty()) require(explicit.sha()) { "commit_sha must be an immutable 40/64-hex commit." }
        val retained = snapshot() ?: if (selectContext) null else initialSnapshot(owner, repo)
        val requestedRef = args.s("ref").ifBlank { args.s("branch") }
        val refresh = args["refresh"]?.jsonPrimitive?.booleanOrNull == true || selectContext && requestedRef.isNotBlank() && requestedRef != retained?.ref
        if (!refresh && retained != null && retained.owner.equals(owner, true) && retained.repo.equals(repo, true) && (explicit.isBlank() || explicit == retained.commitSha)) {
            if (forceLiveReads()) {
                // Re-observe the accepted immutable revision; live verification does not
                // grant permission to move a task to a newer mutable branch head.
                val observed = request("${root(owner, repo)}/commits/${GitHubWorkspaceClient.segment(retained.commitSha)}").jsonObject
                require(observed.s("sha") == retained.commitSha && observed["commit"]?.jsonObject?.get("tree")?.jsonObject?.s("sha") == retained.treeSha) {
                    "Live commit observation differs from the task's pinned revision."
                }
            }
            return retained
        }
        val repository = root(owner, repo)
        val previous = context()?.takeIf { it.owner.equals(owner, true) && it.repo.equals(repo, true) }
        val ref = explicit.ifBlank { args.s("ref").ifBlank { args.s("branch").ifBlank { previous?.ref.orEmpty().ifBlank { request(repository).jsonObject.s("default_branch").ifBlank { "HEAD" } } } } }
        val response = request("$repository/commits/${GitHubWorkspaceClient.segment(ref)}").jsonObject
        val sha = response.s("sha")
        val tree = response["commit"]?.jsonObject?.get("tree")?.jsonObject?.s("sha").orEmpty()
        require(sha.sha() && tree.sha() && (explicit.isBlank() || explicit.equals(sha, true))) { "GitHub did not supply the requested immutable commit and tree." }
        return GitHubTaskSnapshot(accountId, connectionUid, conversationId, taskId, owner, repo, if (explicit.isBlank()) ref else requestedRef.takeUnless { it.sha() || it.isBlank() } ?: retained?.ref ?: ref, sha, tree).also { resolved ->
            if (retained == null || selectContext || explicit.isBlank() && (refresh || retained.owner != owner || retained.repo != repo)) save(resolved)
        }
    }

    private suspend fun retainedTree(context: GitHubTaskSnapshot): JsonObject? {
        val sourceScope = blobScope(context)
        val treeKey = "phone-graph:tree-v$GITHUB_TREE_CACHE_VERSION:${context.treeSha}"
        return cachedSource(sourceScope, treeKey)?.takeIf { it.s("tree_sha") == context.treeSha }
    }

    private suspend fun tree(context: GitHubTaskSnapshot): JsonObject {
        val scope = blobScope(context)
        retainedTree(context)?.let { return it }
        val response = GitHubRepositoryIndex.checkedTree(request("${root(context.owner, context.repo)}/git/trees/${context.treeSha}?recursive=1").jsonObject, context.treeSha)
        val entries = response.getValue("tree").jsonArray
        val bounded = buildJsonObject {
            put("tree", JsonArray(entries.take(20000).map { row -> JsonObject(row.jsonObject.filterKeys { it in setOf("path", "sha", "size", "type", "mode") }) }))
            put("truncated", entries.size > 20000 || response["truncated"]?.jsonPrimitive?.booleanOrNull == true)
            put("tree_sha", context.treeSha)
        }
        store.write(scope, "phone-graph:tree-v$GITHUB_TREE_CACHE_VERSION:${context.treeSha}", bounded)
        return bounded
    }

    private data class BlobRead(val content: JsonObject, val cacheHit: Boolean)

    private suspend fun blob(context: GitHubTaskSnapshot, item: JsonObject): JsonObject = readBlob(context, item).content

    private suspend fun readBlob(context: GitHubTaskSnapshot, item: JsonObject): BlobRead {
        val sha = item.s("sha")
        require(sha.sha()) { "Blob identity is unavailable." }
        cachedSource(blobScope(context), "blob:$sha")?.let {
            retainSymbols(context, sha, it)
            return BlobRead(it, cacheHit = true)
        }
        GitHubBlobContent.checkSize(item["size"]?.jsonPrimitive?.longOrNull ?: 0)
        val response = request("${root(context.owner, context.repo)}/git/blobs/$sha").jsonObject
        if (response.s("sha").isNotBlank() && response.s("sha") != sha) {
            throw GitHubReadValidation("EVIDENCE_REVISION_MISMATCH", buildJsonObject { put("message", "Returned blob identity differs from the selected tree.") })
        }
        val decoded = GitHubBlobContent.decode(response.s("encoding"), response.s("content"))
        val text = decoded.content
        // Cached ingestion can resume on Main when invoked from the workspace UI.
        // Hashing and lexical indexing a 2 MiB blob belongs on a CPU dispatcher.
        val result = withContext(Dispatchers.Default) {
            val active = kotlinx.coroutines.currentCoroutineContext()
            buildJsonObject {
                put("sha", sha)
                put("content", text)
                put("bytes", decoded.bytes)
                put("content_hash", GitHubRepositoryStateStore.digest(text))
                put("index", GitHubLocalIndex.build(text) { active.ensureActive() })
            }
        }
        store.write(blobScope(context), "blob:$sha", result)
        retainSymbols(context, sha, result)
        return BlobRead(result, cacheHit = false)
    }

    private suspend fun retainSymbols(context: GitHubTaskSnapshot, sha: String, blob: JsonObject, fillOnly: Boolean = false) {
        val index = blob["index"] as? JsonObject ?: return
        val names = (index["definitions"] as? JsonArray).orEmpty().take(2000).mapNotNull { (it as? JsonObject)?.s("name") }
        val text = GitHubTaskContextBundle.terms(names.joinToString(" "), 2048).joinToString(" ").take(8192)
        val sourceScope = blobScope(context)
        val catalogKey = "source-symbols:${PhoneRepositoryGraph.PARSER_VERSION}:catalog-v1:${sha.take(2)}"
        val existing = store.read(sourceScope, catalogKey)?.get(sha)
        if (existing == JsonPrimitive(text) || fillOnly && existing != null) return
        var changed = false
        store.update(sourceScope, catalogKey) { prior ->
            changed = prior?.get(sha) != JsonPrimitive(text)
            JsonObject((prior.orEmpty() - sha).entries.toList().takeLast(255).associate { it.toPair() } + (sha to JsonPrimitive(text)))
        }
        if (changed) {
            store.update(sourceScope, "source-symbols:${PhoneRepositoryGraph.PARSER_VERSION}:generation-v1") { prior ->
                buildJsonObject { put("generation", ((prior?.get("generation") as? JsonPrimitive)?.longOrNull ?: 0) + 1) }
            }
        }
    }

    /** Explicit patch targets have their own immutable base and never move the selected workspace. */
    private suspend fun patchContext(action: String, owner: String, repo: String, args: JsonObject): GitHubTaskSnapshot {
        val savedId = args.s(if (action == "commit_staged_patch") "stage_id" else "restore_stage_id")
        if (savedId.isNotBlank()) {
            val saved = requireNotNull(store.read(scope, "stage:$savedId")) { "Patch stage is unavailable or belongs to another task/account." }
            require(saved.s("owner").equals(owner, true) && saved.s("repo").equals(repo, true)) { "Stage belongs to another repository." }
            require(args.s("branch").isBlank() || args.s("branch") == saved.s("branch")) { "Stage belongs to a different explicit branch." }
            val requestedBase = if (action == "stage_patch") args.s("expected_head_sha").ifBlank { saved.s("expected_head_sha") } else saved.s("expected_head_sha")
            require(args.s("commit_sha").isBlank() || args.s("commit_sha") == requestedBase) { "Stage base arguments disagree." }
            return GitHubTaskSnapshot(accountId, connectionUid, conversationId, taskId, owner, repo, saved.s("branch"), saved.s("expected_head_sha"), saved.s("tree_sha"), stagedPatchId = savedId)
        }
        val branch = args.s("branch")
        if (branch.isBlank()) return resolve(owner, repo, args)
        require(normalizeGitHubBranch(branch) == branch) { "Supply the branch name without the refs/heads/ prefix." }
        val expected = args.s("expected_head_sha").ifBlank { args.s("commit_sha") }
        require(expected.isBlank() || expected.sha()) { "expected_head_sha must be an immutable commit." }
        require(args.s("commit_sha").isBlank() || expected == args.s("commit_sha")) { "Patch base arguments disagree." }
        val retained = snapshot()?.takeIf { it.owner.equals(owner, true) && it.repo.equals(repo, true) && it.commitSha == expected }
        if (retained != null) return retained.copy(ref = branch)
        val response = request("${root(owner, repo)}/commits/${GitHubWorkspaceClient.segment(expected.ifBlank { branch })}").jsonObject
        val sha = response.s("sha")
        val tree = response["commit"]?.jsonObject?.get("tree")?.jsonObject?.s("sha").orEmpty()
        require(sha.sha() && tree.sha() && (expected.isBlank() || sha == expected)) { "GitHub did not supply the patch's immutable base." }
        return GitHubTaskSnapshot(accountId, connectionUid, conversationId, taskId, owner, repo, branch, sha, tree)
    }

    private suspend fun rememberStage(context: GitHubTaskSnapshot, id: String, requireSelectedRevision: Boolean = false) {
        fun matches(current: GitHubTaskSnapshot) = current.owner.equals(context.owner, true) && current.repo.equals(context.repo, true) && current.ref == context.ref && current.commitSha == context.commitSha
        val selected = snapshot()
        if (requireSelectedRevision || selected != null) {
            updateSnapshot { current ->
                val sameRevision = matches(current)
                require(!requireSelectedRevision || sameRevision) { "Task revision changed during stage preparation; inspect the selected revision before staging again." }
                if (sameRevision) current.copy(stagedPatchId = id, updatedAt = System.currentTimeMillis()) else current
            }
        }
        // The small index lets recovery find an explicit-branch stage even if the
        // process stops before the caller receives its ID.
        store.update(scope, "staged-patches") { old ->
            val rows = (old?.get("stages") as? JsonArray).orEmpty().filter { it.jsonObject.s("stage_id") != id }
            buildJsonObject {
                put(
                    "stages",
                    JsonArray(
                        (
                            rows + buildJsonObject {
                                put("stage_id", id)
                                put("owner", context.owner)
                                put("repo", context.repo)
                                put("branch", context.ref)
                                put("expected_head_sha", context.commitSha)
                            }
                            ).takeLast(32)
                    )
                )
            }
        }
    }

    suspend fun execute(action: String, owner: String, repo: String, args: JsonObject): JsonElement {
        val readBudget = if (action in setOf("search_repository", "build_index")) GitHubReadWorkBudget(readOperationTimeoutMs) else null
        if (action == "search_repository") GitHubSearchArguments.validate(args)
        val commitImmediately = args["commit_immediately"]?.let { requireNotNull((it as? JsonPrimitive)?.booleanOrNull) { "commit_immediately must be a boolean." } } == true
        if (commitImmediately) {
            require(action == "stage_patch") { "commit_immediately is supported only for stage_patch." }
            require(args.s("branch").isNotBlank() && args.s("expected_head_sha").sha() && args.s("message").isNotBlank() && args.s("message").length <= 4000) {
                "Immediate publication requires explicit branch, expected_head_sha and a commit message of at most 4000 characters."
            }
        }
        if (action == "changed_since") {
            require(args.s("base").isNotBlank()) {
                "CHANGES_BASE_REQUIRED: Supply base_sha to github_context(action=changes), or base to changed_since. Use github_pr(action=context, include_files=true) for a pull request's changed files, or github_context(action=status) for repository status."
            }
        }
        if (action == "read_ranges" && args.s("evidence_id").isNotEmpty()) {
            val retained = retainedEvidence(args.s("evidence_id")) ?: error("EVIDENCE_UNAVAILABLE: This source handle is unavailable in the current account, connection and conversation. Use github_context action=recovery to find retained source handles, or read exact ranges at the observed commit_sha. Do not repeat the unavailable handle.")
            require(retained.s("owner").equals(owner, true) && retained.s("repo").equals(repo, true)) { "Evidence belongs to another repository." }
            require(retained.s("commit_sha").sha() && retained.s("tree_sha").sha()) { "Retained source identity is unavailable." }
            GitHubEvidenceRevisionGuard.validate(owner, repo, args, retained)
            // Pin the new turn to its selected branch before reopening older evidence.
            // Historical reads must not change the conversation's selected revision.
            val current = snapshot()?.takeIf { it.owner.equals(owner, true) && it.repo.equals(repo, true) }
                ?: resolve(owner, repo, JsonObject(emptyMap()))
            val requested = JsonObject(args + mapOf("commit_sha" to retained.getValue("commit_sha"), "ranges" to (args["ranges"] ?: retained.getValue("ranges"))))
            return read(current.copy(commitSha = retained.s("commit_sha"), treeSha = retained.s("tree_sha")), requested)
        }
        val requested = if (action == "changed_since" && args.s("head").isNotBlank()) {
            val head = request("${root(owner, repo)}/commits/${GitHubWorkspaceClient.segment(args.s("head"))}").jsonObject.s("sha")
            JsonObject(args + ("commit_sha" to JsonPrimitive(head)))
        } else {
            args
        }
        if (action == "recovery_context") {
            val selectedContext = snapshot()?.takeIf { it.owner.equals(owner, true) && it.repo.equals(repo, true) }
            val stages = (store.read(scope, "staged-patches")?.get("stages") as? JsonArray).orEmpty().filter { row ->
                row.jsonObject.s("owner").equals(owner, true) && row.jsonObject.s("repo").equals(repo, true)
            }
            val stageId = args.s("stage_id").ifBlank {
                if (args.s("branch").isNotBlank()) {
                    stages.lastOrNull { it.jsonObject.s("branch") == args.s("branch") }?.jsonObject?.s("stage_id").orEmpty()
                } else {
                    selectedContext?.stagedPatchId.orEmpty().ifBlank { stages.lastOrNull()?.jsonObject?.s("stage_id").orEmpty() }
                }
            }
            val stage = stageId.takeIf { it.isNotBlank() }?.let { store.read(scope, "stage:$it") }
                ?.takeIf { it.s("owner").equals(owner, true) && it.s("repo").equals(repo, true) }
            val localStageContext = stage?.takeIf { it.s("expected_head_sha").sha() && it.s("tree_sha").sha() && it.s("branch").isNotBlank() }?.let {
                GitHubTaskSnapshot(accountId, connectionUid, conversationId, taskId, owner, repo, it.s("branch"), it.s("expected_head_sha"), it.s("tree_sha"), stagedPatchId = stageId)
            }
            val retained = selectedContext ?: localStageContext ?: resolve(owner, repo, JsonObject(emptyMap()))
            val page = args.s("page").ifBlank { "1" }.toInt().also { require(it in 1..1000) }
            val conversationHandles = if (conversationId.isNotBlank()) store.read(conversationScope, evidenceKeys(owner, repo))?.get("keys")?.jsonArray.orEmpty().map { it.jsonPrimitive.content } else emptyList()
            val allHandles = (retained.evidenceHandles + conversationHandles).distinct().takeLast(128)
            val handles = allHandles.drop((page - 1) * 16).take(16)
            val evidence = handles.map { id ->
                val saved = retainedEvidence(id)?.takeIf { it.s("owner").equals(owner, true) && it.s("repo").equals(repo, true) }
                buildJsonObject {
                    put("evidence_id", id)
                    put("available", saved != null)
                    saved?.let {
                        put("commit_sha", it["commit_sha"] ?: JsonNull)
                        put("tree_sha", it["tree_sha"] ?: JsonNull)
                        put("ranges", it["ranges"] ?: JsonArray(emptyList()))
                        put("files", JsonArray(it["items"]?.jsonArray.orEmpty().map { item -> JsonObject(item.jsonObject.filterKeys { key -> key in setOf("path", "sha", "start_line", "end_line", "total_lines") }) }))
                    }
                }
            }
            val executionContext = executions.context()
            return buildJsonObject {
                put("commit_sha", retained.commitSha)
                put("branch", retained.ref)
                put("evidence", JsonArray(evidence))
                put("page", page)
                put("has_more", page * 16 < allHandles.size)
                put("upstream_record_ids", JsonArray(handles.map(::JsonPrimitive)))
                put("total_count", allHandles.size)
                put("total_retained_evidence", allHandles.size)
                put("retention_note", "Bounded source inventory for this repository, account, connection and conversation; expired records are marked unavailable. Stages remain task-local. Use read_recovery_context for the conversation action ledger.")
                put("stage_id", stageId)
                put("stage_available", stage != null)
                put("retained_stages", JsonArray(stages))
                put(
                    "context_source",
                    if (selectedContext != null) {
                        "SELECTED_SNAPSHOT"
                    } else if (localStageContext != null) {
                        "RETAINED_STAGE"
                    } else {
                        "RESOLVED_REPOSITORY"
                    }
                )
                put("execution_context", executionContext)
                stage?.let {
                    put("stage", JsonObject(it.filterKeys { key -> key != "files" }))
                    put("staged_files", JsonArray(it["files"]?.jsonArray.orEmpty().map { file -> JsonObject(file.jsonObject.filterKeys { key -> key in setOf("path", "expected_blob_sha", "delete") }) }))
                }
            }
        }
        val context = if (action in setOf("stage_patch", "commit_staged_patch")) {
            patchContext(action, owner, repo, args)
        } else if (readBudget != null) {
            readBudget.run { resolve(owner, repo, requested) } ?: throw GitHubReadValidation(
                "READ_DEADLINE",
                buildJsonObject {
                    put("error_code", "READ_DEADLINE")
                    put("status", "READ_INCOMPLETE")
                    put("failed_stage", "resolve_revision")
                    put("owner", owner)
                    put("repo", repo)
                    put("commit_sha", args.s("commit_sha"))
                    put("remote_write_dispatched", false)
                    put("message", "The immutable repository revision could not be resolved within the read budget. No candidate files were scanned.")
                }
            )
        } else {
            resolve(owner, repo, requested, action in setOf("resolve_context", "task_context"))
        }
        return when (action) {
            "resolve_context", "task_context" -> buildJsonObject {
                put("owner", owner)
                put("repo", repo)
                put("ref", context.ref)
                put("commit_sha", context.commitSha)
                put("tree_sha", context.treeSha)
                put("snapshot", codec.encodeToJsonElement(GitHubTaskSnapshot.serializer(), context))
                put("index", indexStatus(context))
                put("persistent_scope", scope.substringBefore(':'))
            }
            "index_status" -> indexStatus(context)
            "build_index" -> buildIndex(context, args, budget = requireNotNull(readBudget))
            "workflow_catalog" -> workflowCatalog(context, args)
            "search_repository" -> search(context, args, budget = requireNotNull(readBudget))
            "read_ranges" -> read(context, args)
            "stage_patch" -> {
                val staged = if (args.s("restore_stage_id").isNotBlank()) restoreStage(context, args) else stage(context, args)
                if (!commitImmediately) {
                    staged
                } else {
                    val publication = buildJsonObject {
                        put("stage_id", staged.getValue("stage_id"))
                        put("branch", args.getValue("branch"))
                        put("expected_head_sha", args.getValue("expected_head_sha"))
                        put("message", args.getValue("message"))
                    }
                    val receipt = commitStage(context, publication).jsonObject
                    JsonObject(
                        receipt + mapOf(
                            "stage_id" to staged.getValue("stage_id"),
                            "workflow" to JsonPrimitive("STAGE_AND_COMMIT"),
                            "stage_persisted" to JsonPrimitive(true),
                            "committed" to JsonPrimitive(receipt["sha"] != null),
                            "publication_guard" to JsonPrimitive("EXPECTED_HEAD_AND_BLOB_CHECKS"),
                            "atomic_head_precondition" to JsonPrimitive(false)
                        )
                    )
                }
            }
            "commit_staged_patch" -> commitStage(context, args)
            "changed_since" -> changed(context, args)
            else -> error("Unknown repository session action: $action")
        }
    }

    /** Index coverage excludes binary and unsupported blobs that preparation never fetches. */
    private fun indexableSourceEntries(entries: List<JsonObject>): List<JsonObject> {
        val extensions = setOf("kt", "kts", "java", "py", "js", "jsx", "ts", "tsx", "mjs", "cjs", "mts", "cts", "json", "yaml", "yml", "xml", "toml", "md", "c", "cc", "cpp", "h", "hpp", "rs", "go", "cs", "swift", "sh", "properties", "gradle", "txt")
        val extensionless = setOf("README", "LICENSE", "DOCKERFILE", "MAKEFILE")
        return entries.filter { entry ->
            val path = entry.s("path")
            path.substringAfterLast('.').lowercase() in extensions ||
                path.substringAfterLast('/').uppercase() in extensionless
        }
    }

    private suspend fun indexStatus(context: GitHubTaskSnapshot): JsonObject {
        val tree = tree(context)
        val candidates = tree["tree"]!!.jsonArray.map { it.jsonObject }.filter { it.s("type") == "blob" }
        val eligible = indexableSourceEntries(candidates)
        val sourceScope = blobScope(context)
        val present = store.presentKeys(sourceScope, candidates.map { "blob:${it.s("sha")}" })
        val availableShas = candidates.map { it.s("sha") }.filterTo(mutableSetOf()) { "blob:$it" in present }
        val cachedAll = candidates.count { it.s("sha") in availableShas }
        val cachedEligible = eligible.count { it.s("sha") in availableShas }
        val treeComplete = tree["truncated"]?.jsonPrimitive?.booleanOrNull != true
        return buildJsonObject {
            put("commit_sha", context.commitSha)
            put("tree_sha", context.treeSha)
            put("total_paths", candidates.size)
            put("indexed_blobs", cachedAll)
            put("eligible_paths", eligible.size)
            put("indexed_eligible_blobs", cachedEligible)
            put("excluded_nonindexable_paths", candidates.size - eligible.size)
            put("index_coverage_scope", "ELIGIBLE_TEXT_SOURCE_ONLY")
            put("complete", cachedEligible == eligible.size && treeComplete)
            put("tree_complete", treeComplete)
            put("kind", "lexical")
            put("compiler_resolution", false)
        }
    }

    /** Build the immutable tree and inspect its cached shards away from the workspace UI. */
    internal suspend fun repositoryMap(owner: String, repo: String, args: JsonObject): JsonObject = withContext(Dispatchers.Default) {
        require(args.toString().length <= 64_000) { "Repository map request is too large." }
        val action = args.s("action").ifBlank { "status" }
        if (action !in setOf("status", "index", "architecture", "query", "node", "callers", "callees", "path", "impact", "metrics", "task_context_bundle", "cached_path_hints")) {
            throw GatewayGraphResultException("PHONE_GRAPH_OPERATION_UNSUPPORTED", "This operation requires the optional Gateway map.", "SELECT_GATEWAY_ANALYSIS")
        }
        require(args.s("commit_sha").sha()) { "Repository map requires an immutable selected commit." }
        require(args.s("job_action").isBlank() && args["background"] != JsonPrimitive(true)) {
            "Phone indexing runs in bounded foreground batches."
        }
        val budget = GitHubReadWorkBudget(readOperationTimeoutMs)
        val context = budget.run { resolve(owner, repo, args) }
            ?: throw GatewayGraphResultException("READ_DEADLINE", "The pinned repository could not be resolved within the read budget.", "REFRESH_PHONE_MAP")
        if (action == "cached_path_hints") {
            return@withContext GitHubCompactTaskContext.prepare(context, retainedTree(context), args.s("query")) { sha ->
                if (args["allow_cached_source"] == JsonPrimitive(true)) cachedSource(blobScope(context), "blob:$sha") else null
            }
        }
        val tree = budget.run {
            if (action == "task_context_bundle") taskContextPhase(PhoneTaskContextPhase.TREE_SELECTION) { tree(context) } else tree(context)
        } ?: throw readBudgetExpired(context, "phone_index_tree")
        if (action == "task_context_bundle") return@withContext taskContextBundle(context, tree, args)
        val entries = tree.getValue("tree").jsonArray.map { it.jsonObject }.filter { it.s("type") == "blob" }.sortedBy { it.s("path") }
        val eligible = indexableSourceEntries(entries)
        val sourceScope = blobScope(context)
        val checkpointKey = "phone-map-progress:v$GITHUB_TREE_CACHE_VERSION:${context.commitSha}:${PhoneRepositoryGraph.PARSER_VERSION}"
        val cursorPrefix = "phone-index:${GitHubRepositoryStateStore.digest("$GITHUB_TREE_CACHE_VERSION|$accountId|$connectionUid|${context.owner.lowercase()}/${context.repo.lowercase()}|${context.commitSha}|${context.treeSha}|${PhoneRepositoryGraph.PARSER_VERSION}").take(32)}:"
        val saved = store.read(sourceScope, checkpointKey)
        val savedPosition = (saved?.get("prepared_through") as? JsonPrimitive)?.intOrNull?.takeIf { it in 0..eligible.size } ?: 0
        val suppliedCursor = args.s("index_cursor")
        val start = when {
            args["retry_failed"] == JsonPrimitive(true) -> 0
            suppliedCursor.isNotBlank() -> {
                if (!suppliedCursor.startsWith(cursorPrefix)) throw GatewayGraphResultException("PHONE_GRAPH_CURSOR_STALE", "Refresh this repository map before continuing its index.", "REFRESH_PHONE_MAP")
                val position = suppliedCursor.removePrefix(cursorPrefix).toIntOrNull()?.takeIf { it in 0..eligible.size }
                    ?: throw GatewayGraphResultException("PHONE_GRAPH_CURSOR_INVALID", "Refresh this repository map before continuing its index.", "REFRESH_PHONE_MAP")
                // Older checkpoints may replay already traversed immutable source.
                // A synthesized future suffix must never skip unread candidates.
                if (position > savedPosition) throw GatewayGraphResultException("PHONE_GRAPH_CURSOR_UNISSUED", "This index cursor is ahead of the retained progress. Read the current phone map status before continuing.", "REFRESH_PHONE_MAP")
                position
            }
            else -> savedPosition
        }

        fun shardKey(entry: JsonObject) = "phone-graph:${PhoneRepositoryGraph.PARSER_VERSION}:${entry.s("path").substringAfterLast('.', "").lowercase()}:${entry.s("sha")}"
        suspend fun shard(entry: JsonObject): JsonObject? = store.read(sourceScope, shardKey(entry))
            ?.takeIf { it.s("blob_sha") == entry.s("sha") && it["parser_version"] == JsonPrimitive(PhoneRepositoryGraph.PARSER_VERSION) }

        suspend fun retainShard(entry: JsonObject, blob: JsonObject): JsonObject {
            val active = kotlinx.coroutines.currentCoroutineContext()
            val parsed = PhoneRepositoryGraph.sourceIndex(entry.s("path"), blob.s("content")) { active.ensureActive() }
            val retained = buildJsonObject {
                put("blob_sha", entry.s("sha"))
                put("parser_version", PhoneRepositoryGraph.PARSER_VERSION)
                put("phone_graph", parsed)
            }
            store.write(sourceScope, shardKey(entry), retained)
            return retained
        }

        val batch = if (action == "index") {
            buildIndex(
                context,
                buildJsonObject { put("index_cursor", start) },
                alreadyPrepared = { entry ->
                    shard(entry)?.also { retained ->
                        (retained["phone_graph"] as? JsonObject)?.let { parsed -> retainSymbols(context, entry.s("sha"), buildJsonObject { put("index", parsed) }, fillOnly = true) }
                    } != null
                },
                onBlob = { entry, blob -> retainShard(entry, blob) },
                budget = budget,
                onCheckpoint = { progress ->
                    store.update(sourceScope, checkpointKey) { previous ->
                        val previousPosition = (previous?.get("prepared_through") as? JsonPrimitive)?.intOrNull ?: 0
                        val currentPosition = requireNotNull((progress["prepared_through"] as? JsonPrimitive)?.intOrNull)
                        // Replaying an older issued cursor cannot erase a later durable frontier.
                        if (args["retry_failed"] != JsonPrimitive(true) && previousPosition > currentPosition) requireNotNull(previous) else progress
                    }
                }
            )
        } else {
            saved
        }
        val position = (batch?.get("prepared_through") as? JsonPrimitive)?.intOrNull?.takeIf { it in 0..eligible.size } ?: 0
        val inspect = action !in setOf("status", "index")
        val cached = linkedMapOf<String, JsonObject>()
        var indexed = 0
        var indexedSupported = 0
        var partialSupported = 0
        var shardBytes = 0
        var sourceBytes = 0
        var unparsedCached = 0
        var retryableFiles = 0
        var shardsTruncated = false
        val focus = (args["paths"] as? JsonArray).orEmpty().map { it.jsonPrimitive.content }.toSet()
        val query = args.s("query")
        val prioritized = if (focus.isEmpty() && query.isBlank()) {
            eligible
        } else {
            eligible.sortedByDescending {
                (if (it.s("path") in focus) 2 else 0) + (if (query.isNotBlank() && it.s("path").contains(query, true)) 1 else 0)
            }
        }
        var inventoryComplete = true
        val present = budget.run { store.presentKeys(sourceScope, eligible.flatMap { listOf(shardKey(it), "blob:${it.s("sha")}") }) }
        if (present == null) inventoryComplete = false
        val observedInventory = present.orEmpty()
        val inspected = budget.run {
            for ((index, entry) in prioritized.withIndex()) {
                if (index % 64 == 0) budget.yieldIfActive()
                var parsed = if (shardKey(entry) in observedInventory) shard(entry) else null
                val hasBlob = parsed != null || "blob:${entry.s("sha")}" in observedInventory
                if (!hasBlob) {
                    if (position == eligible.size &&
                        PhoneRepositorySourceIndex.supports(entry.s("path")) &&
                        entry.s("mode") !in setOf("120000", "160000") &&
                        (entry["size"]?.jsonPrimitive?.longOrNull ?: MAX_BLOB_BYTES.toLong()) in 0..MAX_BLOB_BYTES.toLong()
                    ) {
                        retryableFiles++
                    }
                    continue
                }
                if (inspect && parsed == null) {
                    val size = entry["size"]?.jsonPrimitive?.longOrNull ?: MAX_BLOB_BYTES.toLong()
                    // Read-time preparation stays bounded. Explicit index batches finish
                    // other cached sources and persist their reusable compact shards.
                    if (size in 0..MAX_BLOB_BYTES.toLong() && sourceBytes + size <= 4 * 1024 * 1024) {
                        cachedSource(sourceScope, "blob:${entry.s("sha")}")?.let { blob ->
                            sourceBytes += blob.s("content").toByteArray(Charsets.UTF_8).size
                            parsed = retainShard(entry, blob)
                        }
                    }
                }
                val prepared = parsed
                if (prepared != null) {
                    indexed++
                    val sourceIndex = prepared["phone_graph"] as? JsonObject
                    if (sourceIndex?.get("supported") == JsonPrimitive(true)) {
                        indexedSupported++
                        if (sourceIndex["complete"] != JsonPrimitive(true)) partialSupported++
                    }
                } else {
                    unparsedCached++
                    if (position == eligible.size && PhoneRepositorySourceIndex.supports(entry.s("path")) && entry.s("mode") !in setOf("120000", "160000")) retryableFiles++
                }
                if (inspect && prepared != null) {
                    val size = prepared.toString().toByteArray(Charsets.UTF_8).size
                    if (shardBytes + size <= 24 * 1024 * 1024) {
                        cached[entry.s("path")] = JsonObject(prepared + ("sha" to JsonPrimitive(entry.s("sha"))))
                        shardBytes += size
                    } else {
                        shardsTruncated = true
                    }
                }
            }
            true
        }
        inventoryComplete = inventoryComplete && inspected == true
        val treeComplete = tree["truncated"] != JsonPrimitive(true)
        val coverageComplete = inventoryComplete && indexed == eligible.size && treeComplete
        val more = !coverageComplete && position < eligible.size
        val status = buildJsonObject {
            batch?.filterKeys { it in setOf("prepared_through", "fetched_this_batch", "attempted_this_batch", "skipped_this_batch", "prepared_bytes_this_batch", "read_deadline_reached", "deferred_files", "stop_reason") }?.forEach { (name, value) -> put(name, value) }
            put("commit_sha", context.commitSha)
            put("tree_sha", context.treeSha)
            put("total_paths", entries.size)
            put("eligible_paths", eligible.size)
            put("indexed_blobs", indexed)
            put("indexed_eligible_blobs", indexed)
            put("unparsed_cached_files", unparsedCached)
            put("phone_indexed_supported_files", indexedSupported)
            put("phone_partial_supported_files", partialSupported)
            put("phone_retryable_files", retryableFiles)
            put("coverage_observation_complete", inventoryComplete)
            put("counts_are_lower_bounds", !inventoryComplete)
            put("operation_deadline_reached", budget.expired || !inventoryComplete)
            put("processed_files", indexed)
            put("expected_files", eligible.size)
            put("excluded_nonindexable_paths", entries.size - eligible.size)
            put("tree_complete", treeComplete)
            put("complete", coverageComplete)
            put("coverage_complete", coverageComplete)
            put("processing_complete", coverageComplete || position == eligible.size)
            put("preparation_finished", position == eligible.size && treeComplete)
            put("has_more", more)
            if (more) put("index_cursor", cursorPrefix + position)
            put("cached_sources_truncated", !inventoryComplete || shardsTruncated || inspect && unparsedCached > 0)
            put("loaded_source_files", cached.size)
            put("map_source", "phone")
            put("graph_source", "PHONE")
            put("index_coverage_scope", "ELIGIBLE_TEXT_SOURCE_ONLY")
            put("compiler_resolution", false)
        }
        val topologyKey = if (inspect && !forceLiveReads()) PhoneMapTopology.key(context, cached, args["paths"] as? JsonArray) else null
        val retained = topologyKey?.let { PhoneMapTopology.load(store, sourceScope, it) }
        var built: PhoneMapTopology? = null
        val result = PhoneRepositoryGraph.execute(context, tree, cached, status, args, retained) { built = it }
        if (topologyKey != null) built?.save(store, sourceScope, topologyKey)
        if (action == "status") {
            val summary = store.read(scope, "phone-context-summary:v$GITHUB_TREE_CACHE_VERSION:${context.commitSha}")?.takeIf { it.s("source_sha") == context.commitSha }
            JsonObject(result + mapOf("source_storage" to store.sourceStorageStatus()) + summary?.let { mapOf("task_context" to it) }.orEmpty())
        } else {
            result
        }
    }

    private suspend fun taskContextBundle(context: GitHubTaskSnapshot, tree: JsonObject, args: JsonObject): JsonObject {
        val started = System.nanoTime()
        val preparationBudgetMs = ((args["preparation_budget_ms"] as? JsonPrimitive)?.longOrNull ?: 6000).coerceIn(500, 20_000)
        val variant = runCatching { GitHubContextVariant.valueOf(args.s("variant").ifBlank { "TASK" }.uppercase(java.util.Locale.ROOT)) }
            .getOrElse { throw IllegalArgumentException("Choose CURRENT, REFERENCE, TASK or SEMANTIC context.") }
        val query = args.s("query").take(6000)
        val budget = ((args["budget_bytes"] as? JsonPrimitive)?.intOrNull ?: 12_000).coerceIn(4096, 64_000)
        return taskContextPhase(PhoneTaskContextPhase.PACKET_ASSEMBLY) {
            val sourceScope = blobScope(context)
            val entries = taskContextPhase(PhoneTaskContextPhase.TREE_SELECTION) {
                indexableSourceEntries(tree.getValue("tree").jsonArray.map { it.jsonObject }.filter { it.s("type") == "blob" && it.s("mode") !in setOf("120000", "160000") }).associateBy { it.s("path") }
            }
            suspend fun generation() = store.read(sourceScope, "source-symbols:${PhoneRepositoryGraph.PARSER_VERSION}:generation-v1")?.s("generation").orEmpty()
            val requestKey = GitHubRepositoryStateStore.digest(
                buildJsonObject {
                    put("bundle_version", GitHubTaskContextBundle.VERSION)
                    put("tree_cache_version", GITHUB_TREE_CACHE_VERSION)
                    put("parser_version", PhoneRepositoryGraph.PARSER_VERSION)
                    put("repository", "${context.owner}/${context.repo}")
                    put("ref", context.ref)
                    put("commit_sha", context.commitSha)
                    put("tree_sha", context.treeSha)
                    put("query", query)
                    put("variant", variant.name)
                    put("budget_bytes", budget)
                    put("preparation_budget_ms", preparationBudgetMs)
                }.toString()
            )
            val cacheKey = "task-context:$requestKey:${generation()}"
            if (variant != GitHubContextVariant.SEMANTIC && !forceLiveReads()) {
                taskContextPhase(PhoneTaskContextPhase.PACKET_CACHE_READ) { store.read(sourceScope, cacheKey) }?.takeIf {
                    it.s("repository") == "${context.owner}/${context.repo}" &&
                        it.s("branch") == context.ref &&
                        it.s("source_sha") == context.commitSha &&
                        it.s("variant") == variant.name
                }?.let {
                    val reused = GitHubTaskContextBundle.measure(JsonObject(it + mapOf("cache_hit" to JsonPrimitive(true), "cache" to JsonObject((it["cache"] as? JsonObject).orEmpty() + ("cache_hit" to JsonPrimitive(true))), "cache_status" to JsonPrimitive("PINNED_PACKET_REUSED"), "preparation_ms" to JsonPrimitive(0))))
                    retainTaskContextSummary(context, reused)
                    return@taskContextPhase reused
                }
            }
            val catalogs = if (variant in setOf(GitHubContextVariant.TASK, GitHubContextVariant.SEMANTIC)) {
                taskContextPhase(PhoneTaskContextPhase.SYMBOL_CATALOG) {
                    entries.values.map { it.s("sha").take(2) }.distinct().associateWith { prefix -> cachedSource(sourceScope, "source-symbols:${PhoneRepositoryGraph.PARSER_VERSION}:catalog-v1:$prefix") }
                }
            } else {
                emptyMap()
            }
            val files = entries.values.map { entry ->
                val blobSha = entry.s("sha")
                GitHubContextFile(entry.s("path"), blobSha, (entry["size"] as? JsonPrimitive)?.longOrNull ?: 0, (catalogs[blobSha.take(2)]?.get(blobSha) as? JsonPrimitive)?.content.orEmpty())
            }
            val keys = mutableListOf<String>()
            val progressKey = "task-context-progress:$requestKey"
            val progress = GitHubTaskContextReadProgress(store.read(scope, progressKey))
            val result = taskContextPhase(PhoneTaskContextPhase.PACKET_ASSEMBLY) {
                GitHubTaskContextBundle.build(
                    "${context.owner}/${context.repo}", context.commitSha, context.ref, files, query, variant, budget,
                    treeComplete = tree["truncated"] != JsonPrimitive(true),
                    load = { path ->
                        val entry = entries[path]
                        if (entry == null) {
                            null
                        } else {
                            val blob = entry.s("sha")
                            val key = "blob:$blob"
                            val remaining = preparationBudgetMs - (System.nanoTime() - started) / 1_000_000
                            val read = when {
                                remaining <= 0 -> {
                                    progress.record(path, blob, GitHubContextReadState.PREPARATION_DEADLINE)
                                    null
                                }
                                progress.exhausted(blob) -> {
                                    // A separately authorized source read can recover this immutable
                                    // blob. Never retry its stalled transport merely by repeating a bundle.
                                    cachedSource(sourceScope, key)?.let { BlobRead(it, cacheHit = true) }
                                        ?: run {
                                            progress.record(path, blob, GitHubContextReadState.NO_PROGRESS_LIMIT_REACHED)
                                            null
                                        }
                                }
                                else -> kotlinx.coroutines.withTimeoutOrNull(minOf(remaining, taskContextBlobTimeoutMs.coerceIn(1, 2500))) { readBlob(context, entry) }
                                    ?: run {
                                        val reason = if (remaining <= taskContextBlobTimeoutMs.coerceIn(1, 2500)) GitHubContextReadState.PREPARATION_DEADLINE else GitHubContextReadState.BLOB_READ_DEADLINE
                                        progress.record(path, blob, reason, attempted = true)
                                        null
                                    }
                            }
                            if (read != null) progress.record(path, blob, if (read.cacheHit) GitHubContextReadState.SOURCE_REUSED else GitHubContextReadState.SOURCE_FETCHED)
                            // Retain each completed/deferred blob before another read can time out.
                            store.write(scope, progressKey, progress.checkpoint())
                            read?.let {
                                keys += key
                                GitHubContextSource(path, entry.s("sha"), it.content.s("content"), it.cacheHit)
                            }
                        }
                    },
                    localRanker = kotlinx.coroutines.currentCoroutineContext()[GitHubTaskContextRanking]?.let { ranker ->
                        { query, records -> ranker.rank(query, records, context.accountId) }
                    },
                    indexedFiles = files.count { catalogs[it.blobSha.take(2)]?.containsKey(it.blobSha) == true }.takeIf { variant in setOf(GitHubContextVariant.TASK, GitHubContextVariant.SEMANTIC) },
                    localRankingFallback = kotlinx.coroutines.currentCoroutineContext()[GitHubTaskContextRanking]?.fallbackReason,
                    readState = progress::state
                )
            }
            store.retainSourceWorkingSet(sourceScope, keys)
            val indexedNow = files.count { catalogs[it.blobSha.take(2)]?.containsKey(it.blobSha) == true || "blob:${it.blobSha}" in keys }
            val coverage = JsonObject((result["coverage"] as? JsonObject).orEmpty() + ("indexed_files" to if (variant in setOf(GitHubContextVariant.TASK, GitHubContextVariant.SEMANTIC)) JsonPrimitive(indexedNow) else JsonNull))
            val enriched = GitHubTaskContextBundle.measure(JsonObject(result + mapOf("coverage" to coverage, "cache_status" to JsonPrimitive(if (result["cache_hit"] == JsonPrimitive(true)) "SOURCE_REUSED" else "SOURCE_FETCHED"))))
            taskContextPhase(PhoneTaskContextPhase.PACKET_CACHE_WRITE) {
                if (variant != GitHubContextVariant.SEMANTIC && result["source_complete"] == JsonPrimitive(true)) store.write(sourceScope, "task-context:$requestKey:${generation()}", enriched)
                retainTaskContextSummary(context, enriched)
            }
            enriched
        }
    }

    /** UI diagnostics retain identities and counts, never task text or source excerpts. */
    private suspend fun retainTaskContextSummary(context: GitHubTaskSnapshot, packet: JsonObject) {
        val scalarKeys = setOf("schema", "repository", "source_sha", "variant", "reference_digest", "index_digest", "manifest_status", "freshness", "ranking", "semantic_applied", "semantic_fallback", "cache_hit", "cache_status", "cached_files", "loaded_files", "coverage", "cache", "preparation_ms", "content_bytes", "bytes", "token_count", "token_count_basis", "token_estimate", "token_estimate_basis", "selection_truncated", "selected_paths", "test_candidates", "unavailable_files", "source_complete", "source_identity_kind", "source_readiness", "delivery_status", "source_read_progress_total", "source_read_progress_omitted", "deferred_reason_counts", "automatic_retry", "no_progress_attempt_limit")
        val summary = buildJsonObject {
            packet.filterKeys { it in scalarKeys }.forEach { (key, value) -> put(key, value) }
            put("source_read_candidates", JsonArray((packet["source_read_candidates"] as? JsonArray).orEmpty().mapNotNull { row -> (row as? JsonObject)?.let { JsonObject(it.filterKeys { name -> name in setOf("path", "blob_sha", "source_loaded", "deferred_reason") }) } }))
            put("source_read_progress", JsonArray((packet["source_read_progress"] as? JsonArray).orEmpty().take(20).mapNotNull { row -> (row as? JsonObject)?.let { JsonObject(it.filterKeys { name -> name in setOf("path", "blob_sha", "state") }) } }))
            put("selected_ranges", JsonArray((packet["selected_ranges"] as? JsonArray).orEmpty().mapNotNull { row -> (row as? JsonObject)?.let { JsonObject(it.filterKeys { name -> name in setOf("path", "blob_sha", "start_line", "end_line", "reason") }) } }))
            put("documents", JsonArray((packet["documents"] as? JsonArray).orEmpty().mapNotNull { row -> (row as? JsonObject)?.let { JsonObject(it.filterKeys { name -> name in setOf("path", "blob_sha", "start_line", "end_line") }) } }))
            put("source_current", packet.s("source_sha") == context.commitSha)
            put("summary_scope", "SELECTED_ACCOUNT_PROFILE_REPOSITORY_REVISION")
        }
        store.write(scope, "phone-context-summary:v$GITHUB_TREE_CACHE_VERSION:${context.commitSha}", summary)
    }

    /** Explicit foreground preparation. Every batch is bounded and pinned to one SHA. */
    private suspend fun buildIndex(
        context: GitHubTaskSnapshot,
        args: JsonObject,
        alreadyPrepared: (suspend (JsonObject) -> Boolean)? = null,
        onBlob: (suspend (JsonObject, JsonObject) -> Unit)? = null,
        budget: GitHubReadWorkBudget = GitHubReadWorkBudget(readOperationTimeoutMs),
        onCheckpoint: (suspend (JsonObject) -> Unit)? = null
    ): JsonObject {
        val pinnedTree = budget.run { tree(context) } ?: throw readBudgetExpired(context, "index_tree")
        val entries = pinnedTree["tree"]!!.jsonArray.map { it.jsonObject }.filter { it.s("type") == "blob" }.sortedBy { it.s("path") }
        val eligible = indexableSourceEntries(entries)
        val cursor = args.s("index_cursor").ifBlank { "0" }.toInt().also { require(it in 0..eligible.size) }
        val failureKey = "index-read-failures:${context.commitSha}:${PhoneRepositoryGraph.PARSER_VERSION}"
        var next = cursor
        var bytes = 0L
        var fetched = 0
        var attempted = 0
        var skipped = 0
        var deadline = false
        var deadlineItem: JsonObject? = null
        var deadlineScope = "BLOB"
        val deferred = mutableListOf<JsonObject>()
        val failedIdentities = GitHubSearchFileFailures.entries(store.read(blobScope(context), failureKey)).keys.toMutableSet()
        val treeComplete = pinnedTree["truncated"] != JsonPrimitive(true)
        fun checkpoint() = buildJsonObject {
            put("commit_sha", context.commitSha)
            put("tree_sha", context.treeSha)
            put("preparation_scope", "bounded_text_source_files")
            put("eligible_paths", eligible.size)
            put("prepared_through", next)
            put("fetched_this_batch", fetched)
            put("attempted_this_batch", attempted)
            put("skipped_this_batch", skipped)
            put("prepared_bytes_this_batch", bytes)
            put("read_deadline_reached", deadline)
            put("deferred_files", JsonArray(deferred))
            put(
                "stop_reason",
                if (deadline || budget.expired) {
                    "time_budget"
                } else if (next == eligible.size) {
                    "traversal_complete"
                } else {
                    "batch_limit"
                }
            )
            put("preparation_finished", next == eligible.size && treeComplete)
            if (next < eligible.size) put("next_index_cursor", next)
        }
        suspend fun retainProgress() {
            val progress = checkpoint()
            store.write(scope, "index-preparation:${context.commitSha}", progress)
            onCheckpoint?.invoke(progress)
        }
        suspend fun prepareEntries() {
            while (next < eligible.size && next - cursor < 100 && attempted < 24 && bytes < 4L * 1024 * 1024) {
                budget.yieldIfActive()
                // Reserve time to publish progress and bounded coverage metadata.
                if (budget.remainingMs <= 250) {
                    deadline = true
                    break
                }
                val item = eligible[next]
                val size = item["size"]?.jsonPrimitive?.longOrNull ?: MAX_BLOB_BYTES.toLong()
                if (size > MAX_BLOB_BYTES || size < 0 || item.s("mode") in setOf("120000", "160000")) {
                    next++
                    skipped++
                    retainProgress()
                    continue
                }
                if (bytes + size > 4L * 1024 * 1024) break
                var readInFlight = false
                try {
                    val blobBudgetMs = minOf(indexBlobTimeoutMs, (budget.remainingMs - 250).coerceAtLeast(1))
                    val completed = budget.run(blobBudgetMs) {
                        readInFlight = true
                        if (forceLiveReads() || !(alreadyPrepared?.invoke(item) ?: store.contains(blobScope(context), "blob:${item.s("sha")}"))) {
                            attempted++
                            val retained = blob(context, item)
                            onBlob?.invoke(item, retained)
                            bytes += retained.s("content").toByteArray(Charsets.UTF_8).size
                            fetched++
                        }
                        true
                    }
                    if (completed == null) {
                        deadline = true
                        deadlineItem = item.takeIf { readInFlight }
                        deadlineScope = if (blobBudgetMs < indexBlobTimeoutMs) "OPERATION" else "BLOB"
                        break
                    }
                    readInFlight = false
                    val identity = GitHubSearchFileFailures.identity(context, item.s("path"), item.s("sha"))
                    if (failedIdentities.remove(identity)) store.update(blobScope(context), failureKey) { GitHubSearchFileFailures.update(it, setOf(identity), null) }
                    next++
                    retainProgress()
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    if (readInFlight && cancelled is kotlinx.coroutines.TimeoutCancellationException) {
                        deadline = true
                        deadlineItem = item
                        deadlineScope = "OPERATION"
                    }
                    throw cancelled
                } catch (_: GitHubBlobContentFailure) {
                    skipped++
                    next++
                    retainProgress()
                } catch (_: java.nio.charset.CharacterCodingException) {
                    skipped++
                    next++
                    retainProgress()
                }
            }
        }
        try {
            prepareEntries()
        } finally {
            // An outer deadline must retain the active blob's no-progress attempt too.
            // Plain cancellation never increments failures. A bounded explicit deferral
            // may advance traversal, but never counts as successfully indexed coverage.
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                kotlinx.coroutines.withTimeoutOrNull(1000) {
                    deadlineItem?.let { item ->
                        val identity = GitHubSearchFileFailures.identity(context, item.s("path"), item.s("sha"))
                        val failures = store.update(blobScope(context), failureKey) { GitHubSearchFileFailures.update(it, emptySet(), identity) }
                        val attempts = GitHubSearchFileFailures.entries(failures)[identity] ?: 0
                        val exhausted = attempts >= GitHubSearchFileFailures.MAX_ATTEMPTS
                        deferred += buildJsonObject {
                            put("path", item.getValue("path"))
                            put("sha", item.getValue("sha"))
                            put(
                                "reason",
                                if (exhausted) {
                                    "REPEATED_READ_DEADLINE"
                                } else if (deadlineScope == "OPERATION") {
                                    "OPERATION_READ_DEADLINE"
                                } else {
                                    "BLOB_READ_DEADLINE"
                                }
                            )
                            put("deadline_scope", deadlineScope)
                            put("attempts", attempts)
                        }
                        if (exhausted) {
                            skipped++
                            next++
                        }
                    }
                    retainProgress()
                }
            }
        }
        val status = budget.run { indexStatus(context) } ?: buildJsonObject {
            put("commit_sha", context.commitSha)
            put("tree_sha", context.treeSha)
            put("tree_complete", treeComplete)
            put("total_paths", entries.size)
            put("eligible_paths", eligible.size)
            put("complete", false)
            put("coverage_observation_complete", false)
        }
        return JsonObject(status + checkpoint())
    }

    private fun readBudgetExpired(context: GitHubTaskSnapshot, stage: String) = GitHubReadValidation(
        "READ_DEADLINE",
        buildJsonObject {
            put("error_code", "READ_DEADLINE")
            put("status", "READ_INCOMPLETE")
            put("failed_stage", stage)
            put("commit_sha", context.commitSha)
            put("tree_sha", context.treeSha)
            put("remote_write_dispatched", false)
            put("message", "The bounded read preparation deadline was reached. Completed immutable source and previously issued continuations remain retained.")
        }
    )

    private suspend fun workflowCatalog(context: GitHubTaskSnapshot, args: JsonObject): JsonObject {
        val tree = tree(context)
        val requestedWorkflow = args.s("workflow_id").takeIf { it.isNotBlank() }?.let(::normalizeGitHubWorkflowSelector).orEmpty()
        val selectedPath = if (requestedWorkflow.toLongOrNull() != null) {
            request("${root(context.owner, context.repo)}/actions/workflows/${GitHubWorkspaceClient.segment(requestedWorkflow)}").jsonObject.s("path")
                .also { require(it.startsWith(".github/workflows/")) { "Workflow metadata has no repository definition path." } }
        } else {
            requestedWorkflow.takeIf { it.isNotBlank() }?.let { ".github/workflows/${normalizeGitHubWorkflowSelector(it)}" }
        }
        val workflows = tree["tree"]!!.jsonArray.map { it.jsonObject }.filter {
            it.s("type") == "blob" &&
                it.s("path").startsWith(".github/workflows/") &&
                it.s("path").substringAfter(".github/workflows/").let { name -> '/' !in name && (name.endsWith(".yml") || name.endsWith(".yaml")) } &&
                (selectedPath == null || it.s("path") == selectedPath)
        }.distinctBy { it.s("path") }.sortedBy { it.s("path") }
        require(selectedPath == null || workflows.isNotEmpty()) { "The selected workflow has no definition at the pinned revision." }
        val cursor = args.s("index_cursor").ifBlank { "0" }.toInt().also { require(it in 0..workflows.size) }
        val selected = workflows.drop(cursor).take(4)
        val rows = selected.map { file ->
            val key = "workflow-metadata-v1:${file.s("sha")}"
            val retained = cachedSource(blobScope(context), key)
            val metadata = retained ?: GitHubWorkflowDefinitionCapture.capture { blob(context, file).s("content") }.also {
                if (it["metadata_available"] != JsonPrimitive(false)) store.write(blobScope(context), key, it)
            }
            buildJsonObject {
                put("path", file.s("path"))
                put("workflow_id", file.s("path").substringAfterLast('/'))
                put("blob_sha", file.s("sha"))
                put("cache_hit", retained != null)
                put("definition", metadata)
                if (metadata["metadata_available"] == JsonPrimitive(false)) {
                    put(
                        "recovery",
                        buildJsonObject {
                            put("action", "workflow_catalog")
                            put("workflow_id", file.s("path").substringAfterLast('/'))
                            put("commit_sha", context.commitSha)
                        }
                    )
                }
            }
        }
        val next = cursor + selected.size
        return buildJsonObject {
            put("commit_sha", context.commitSha)
            put("workflows", JsonArray(rows))
            put("total_count", workflows.size)
            put("captured_definition_count", rows.count { it["definition"]?.jsonObject?.get("metadata_available") != JsonPrimitive(false) })
            put("unavailable_definition_count", rows.count { it["definition"]?.jsonObject?.get("metadata_available") == JsonPrimitive(false) })
            put("page_complete", rows.all { it["definition"]?.jsonObject?.get("metadata_available") != JsonPrimitive(false) })
            put("assembled_at_ms", System.currentTimeMillis())
            put("inventory_scope", "workflow_files_at_commit")
            put("tree_complete", tree["truncated"]?.jsonPrimitive?.booleanOrNull != true)
            put("has_more", next < workflows.size)
            if (next < workflows.size) {
                put(
                    "continuation",
                    buildJsonObject {
                        put("action", "workflow_catalog")
                        put("commit_sha", context.commitSha)
                        put("index_cursor", next)
                        args["workflow_id"]?.let { put("workflow_id", it) }
                    }
                )
            }
        }
    }

    private class SearchPageDeadline : RuntimeException()

    private data class SearchRequest(
        val query: String,
        val mode: String,
        val options: GitHubSearchOptions,
        val extension: String,
        val pathPrefix: String,
        val batchSize: Int,
        val maxNewBlobs: Int,
        val searchId: String,
        val all: List<JsonObject>,
        val cursor: Int,
        val treeComplete: Boolean,
        val priorCoverage: JsonObject?,
        val recovery: JsonObject? = null
    ) {
        val cacheKey get() = "search-page-v6:$searchId:$cursor:$batchSize:$maxNewBlobs:${GitHubRepositoryStateStore.digest(priorCoverage.toString())}"
        val fromStart get() = priorCoverage?.get("from_start")?.jsonPrimitive?.booleanOrNull == true
    }

    private class SearchProgress(request: SearchRequest, val knownFailures: Set<String>, val budget: GitHubReadWorkBudget) {
        var next = request.cursor
        var fetched = 0
        var skipped = 0
        var inFlight: Int? = null
        var readDeadline = false
        var incompleteSymbols = false
        var truncatedMatches = false
        var noProgressAttempts = 0
        val completedFailures = mutableSetOf<String>()
        val deferred = mutableListOf<JsonObject>()
        val hits = mutableListOf<JsonObject>()
        val contextBudget = SearchContextBudget()
        val prefetched = mutableMapOf<String, Result<JsonObject?>>()
        val hitLimit = if (request.mode == "paths") 100 else 20
        fun timedOut() = budget.expired
    }

    private suspend fun search(context: GitHubTaskSnapshot, args: JsonObject, budget: GitHubReadWorkBudget = GitHubReadWorkBudget(readOperationTimeoutMs)): JsonObject {
        val request = budget.run { prepareSearch(context, args, tree(context)) } ?: throw readBudgetExpired(context, "search_prepare")
        cachedSource(scope, request.cacheKey)?.let { cached ->
            return withSearchRecovery(JsonObject(cached + mapOf("cache_hit" to JsonPrimitive(true), "new_blobs" to JsonPrimitive(0))), request.recovery)
        }
        val knownFailures = if (request.mode == "paths") emptySet() else GitHubSearchFileFailures.entries(store.read(scope, GitHubSearchFileFailures.STORE_KEY)).keys
        val progress = SearchProgress(request, knownFailures, budget)
        try {
            if (budget.run {
                    scanSearch(context, request, progress)
                    true
                } == null
            ) {
                progress.next = progress.inFlight ?: progress.next
                progress.readDeadline = true
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            progress.next = progress.inFlight ?: progress.next
            progress.readDeadline = true
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                kotlinx.coroutines.withTimeoutOrNull(1000) { retainSearchPage(request, progress, searchResult(context, request, progress)) }
            }
            throw cancelled
        }
        advanceRepeatedSearchDeadline(context, request, progress)
        val result = searchResult(context, request, progress)
        retainSearchPage(request, progress, result)
        return withSearchRecovery(result, request.recovery)
    }

    private fun withSearchRecovery(result: JsonObject, recovery: JsonObject?): JsonObject =
        if (recovery == null) result else JsonObject(result + ("recovery" to recovery))

    private suspend fun prepareSearch(context: GitHubTaskSnapshot, args: JsonObject, data: JsonObject): SearchRequest {
        val query = args.s("query")
        val mode = args.s("mode").ifBlank { "literal" }
        val options = GitHubSearchArguments.validate(args)
        val extension = args.s("file_extension").removePrefix(".").lowercase(java.util.Locale.ROOT)
        val batchSize = GitHubSearchArguments.integer(args, "batch_size", 1000)
        val maxNewBlobs = GitHubSearchArguments.integer(args, "max_new_blobs", 4)
        val pathPrefix = args.s("path_prefix")
        val definition = buildJsonObject {
            put("owner", context.owner.lowercase(java.util.Locale.ROOT))
            put("repo", context.repo.lowercase(java.util.Locale.ROOT))
            put("commit_sha", context.commitSha.lowercase(java.util.Locale.ROOT))
            put("tree_sha", context.treeSha.lowercase(java.util.Locale.ROOT))
            put("query", query)
            put("mode", mode)
            put("path_prefix", pathPrefix)
            put("file_extension", extension)
            put("batch_size", batchSize)
            put("max_new_blobs", maxNewBlobs)
            options.json().forEach { (key, value) -> put(key, value) }
        }
        val searchId = GitHubRepositoryStateStore.digest("$GITHUB_TREE_CACHE_VERSION:$definition")
        val requestedSearchId = args.s("search_id")
        val cursorText = args.s("index_cursor").ifBlank { "0" }
        val requestedCursor = cursorText.toIntOrNull()?.takeIf { it >= 0 }
            ?: throw searchValidation("GITHUB_SEARCH_CURSOR_INVALID", context, definition, "index_cursor must be a nonnegative integer. Start a new read-only search with index_cursor=0 and no search_id.")
        val changed = requestedSearchId.isNotBlank() && requestedSearchId != searchId
        val previous = if (changed) store.read(scope, "search-definition:$requestedSearchId") else null
        if (changed && previous == null) {
            throw searchValidation(
                "GITHUB_SEARCH_CURSOR_UNAVAILABLE",
                context,
                definition,
                "The search_id was not issued for a retained search in this task. Start with index_cursor=0 without search_id; no candidate files were scanned.",
                cursorDiagnostics = GitHubSearchCursorDiagnostics.observation(GitHubSearchCursorDiagnostics.State.RETAINED_SEARCH_MISSING, requestedSearchId, cursorText)
            )
        }
        if (requestedCursor > 0 && requestedSearchId.isBlank()) {
            val retainedDefinition = store.read(scope, "search-definition:$searchId")
            val issuedBoundary = store.read(scope, "search-coverage:$searchId:$requestedCursor")
            val correction = if (retainedDefinition == definition && issuedBoundary != null) {
                JsonObject(args + mapOf("owner" to JsonPrimitive(context.owner), "repo" to JsonPrimitive(context.repo), "commit_sha" to JsonPrimitive(context.commitSha), "search_id" to JsonPrimitive(searchId)))
            } else {
                null
            }
            throw searchValidation("GITHUB_SEARCH_CURSOR_UNBOUND", context, definition, "A nonzero index_cursor requires the search_id from the exact continuation. Use the retained correction_call when available; otherwise reset index_cursor=0 to begin a new read-only search.", correction)
        }
        val recovery = if (changed) {
            buildJsonObject {
                put("code", "SEARCH_PARAMETERS_CHANGED")
                put("continuation_status", "PRECHECK_REJECTED")
                put("action", "RESTARTED_READ_ONLY_SEARCH")
                put("restarted_at_index_cursor", 0)
                put("previous_search_id", requestedSearchId)
                put("previous_results_policy", "Previously returned pages and evidence are retained under their original search identity, subject to normal bounded retention. They are not mixed into the new query.")
                put("previous_search", JsonObject(requireNotNull(previous).filterKeys { it !in setOf("tree_sha", "pattern_match") } + mapOf("index_cursor" to JsonPrimitive(0), "search_id" to JsonPrimitive(requestedSearchId))))
            }
        } else {
            null
        }
        val all = withContext(Dispatchers.Default) {
            data["tree"]!!.jsonArray.map { it.jsonObject }.filter { item ->
                item.s("type") == "blob" &&
                    (mode != "tests" || item.s("path").contains("test", true)) &&
                    (pathPrefix.isBlank() || item.s("path").startsWith(pathPrefix)) &&
                    (options.suffix.isEmpty() || item.s("path").endsWith(options.suffix)) &&
                    (extension.isEmpty() || item.s("path").substringAfterLast('.', "").equals(extension, true))
            }.distinctBy { it.s("path") }.sortedBy { it.s("path") }
        }
        val cursor = if (changed) 0 else requestedCursor
        if (cursor > all.size) throw searchValidation("GITHUB_SEARCH_CURSOR_INVALID", context, definition, "index_cursor exceeds the pinned candidate count. Use an issued continuation or restart from index_cursor=0 without search_id.")
        val priorCoverage = if (cursor == 0) buildJsonObject { put("from_start", true) } else store.read(scope, "search-coverage:$searchId:$cursor")
        if (cursor > 0 && priorCoverage == null) {
            throw searchValidation(
                "GITHUB_SEARCH_CURSOR_UNAVAILABLE",
                context,
                definition,
                "This cursor is not an observed page boundary in the retained search. Restart from index_cursor=0 without search_id; existing evidence remains retained.",
                cursorDiagnostics = GitHubSearchCursorDiagnostics.observation(GitHubSearchCursorDiagnostics.State.PAGE_BOUNDARY_UNOBSERVED, searchId, cursorText)
            )
        }
        store.write(scope, "search-definition:$searchId", definition)
        val evicted = mutableListOf<String>()
        store.update(scope, "search-definition-keys") { old ->
            val ids = old?.get("keys")?.jsonArray.orEmpty().map { it.jsonPrimitive.content }.filter { it != searchId } + searchId
            evicted += ids.dropLast(64)
            buildJsonObject { put("keys", JsonArray(ids.takeLast(64).map(::JsonPrimitive))) }
        }
        evicted.forEach {
            store.remove(scope, "search-definition:$it")
            store.remove(scope, "search-no-progress:$it")
        }
        return SearchRequest(query, mode, options, extension, pathPrefix, batchSize, maxNewBlobs, searchId, all, cursor, data["truncated"]?.jsonPrimitive?.booleanOrNull != true, priorCoverage, recovery)
    }

    private fun searchValidation(code: String, context: GitHubTaskSnapshot, definition: JsonObject, message: String, correction: JsonObject? = null, cursorDiagnostics: JsonObject = JsonObject(emptyMap())) = GitHubReadValidation(
        code,
        buildJsonObject {
            put("schema", "github.read_validation.v1")
            put("error_code", code)
            put("status", "PRECHECK_REJECTED")
            put("failed_stage", "search_continuation")
            put("read_dispatched", false)
            put("retry_unchanged", false)
            put("owner", context.owner)
            put("repo", context.repo)
            put("ref", context.ref)
            put("commit_sha", context.commitSha)
            put("message", message)
            cursorDiagnostics.forEach { (key, value) -> put(key, value) }
            correction?.let {
                put(
                    "correction_call",
                    buildJsonObject {
                        put("tool", "github_search")
                        put("arguments", it)
                    }
                )
            }
            put(
                "corrective_actions",
                JsonArray(
                    listOf(
                        buildJsonObject {
                            put("tool", "github_search")
                            put("arguments", JsonObject(definition.filterKeys { it !in setOf("tree_sha", "pattern_match") } + ("index_cursor" to JsonPrimitive(0))))
                        }
                    )
                )
            )
        }
    )

    private suspend fun scanSearch(context: GitHubTaskSnapshot, request: SearchRequest, progress: SearchProgress) {
        val all = request.all
        // Related-file discovery reuses one path list for the entire page.
        val paths = all.map { it.s("path") }
        while (progress.next < all.size && progress.next - request.cursor < request.batchSize && progress.hits.size < progress.hitLimit) {
            progress.budget.yieldIfActive()
            if (progress.timedOut()) break
            val item = all[progress.next]
            if (request.mode == "paths") {
                progress.next++
                if (request.query == "*" || request.options.score(item.s("path"), request.query) > 0) {
                    progress.hits += buildJsonObject {
                        put("path", item["path"]!!)
                        put("sha", item["sha"]!!)
                    }
                }
                continue
            }
            progress.inFlight = progress.next
            val cached = cachedSource(blobScope(context), "blob:${item.s("sha")}")
            if (cached == null && item.s("sha") !in progress.prefetched && progress.fetched >= request.maxNewBlobs) break
            progress.next++
            val content = searchContent(context, request, progress, item, cached)
            if (progress.readDeadline) break
            if (content == null) {
                progress.inFlight = null
                continue
            }
            val match = matchSearchFile(context, item, content, request.options, request.mode, request.query, progress.budget, progress.contextBudget)
            progress.incompleteSymbols = progress.incompleteSymbols || match.incompleteSymbols
            if (match.deadlineReached) {
                progress.next--
                progress.readDeadline = true
                break
            }
            if (progress.knownFailures.isNotEmpty()) {
                val identity = GitHubSearchFileFailures.identity(context, item.s("path"), item.s("sha"))
                if (identity in progress.knownFailures) progress.completedFailures += identity
            }
            progress.inFlight = null
            val matches = match.items
            if (matches.size > 10) progress.truncatedMatches = true
            if (matches.isNotEmpty()) {
                progress.hits += buildJsonObject {
                    put("path", item["path"]!!)
                    put("sha", item["sha"]!!)
                    put("matches", JsonArray(matches.take(10)))
                    put("matches_truncated", matches.size > 10)
                    put("related_files", GitHubSearchContext.related(item.s("path"), paths))
                }
            }
        }
    }

    private suspend fun searchContent(context: GitHubTaskSnapshot, request: SearchRequest, progress: SearchProgress, item: JsonObject, cached: JsonObject?): JsonObject? {
        if ((item["size"]?.jsonPrimitive?.longOrNull ?: 0) > MAX_BLOB_BYTES) {
            progress.skipped++
            return null
        }
        try {
            if (cached == null && item.s("sha") !in progress.prefetched) {
                val candidates = request.all.subList(progress.next - 1, minOf(request.all.size, request.cursor + request.batchSize)).asSequence()
                    .take(4).filter { (it["size"]?.jsonPrimitive?.longOrNull ?: 0) <= MAX_BLOB_BYTES }
                    .distinctBy { it.s("sha") }.filter { it.s("sha") !in progress.prefetched }
                    .take(request.maxNewBlobs - progress.fetched).toList()
                    .filter { cachedSource(blobScope(context), "blob:${it.s("sha")}") == null }
                progress.fetched += candidates.size
                val remainingMs = progress.budget.remainingMs.coerceIn(1, searchBlobTimeoutMs.coerceIn(1, 8_000))
                prefetchSearchBlobs(context, candidates, remainingMs).forEach { (sha, result) -> progress.prefetched[sha] = result }
            }
            val retained = progress.prefetched.remove(item.s("sha"))?.getOrThrow() ?: cached ?: cachedSource(blobScope(context), "blob:${item.s("sha")}")
            if (retained == null) {
                progress.next--
                progress.readDeadline = true
            }
            return retained
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: IllegalArgumentException) {
            progress.skipped++
        } catch (_: java.nio.charset.CharacterCodingException) {
            progress.skipped++
        }
        return null
    }

    private suspend fun advanceRepeatedSearchDeadline(context: GitHubTaskSnapshot, request: SearchRequest, progress: SearchProgress) {
        // Earlier files may have advanced this page; only the exact file still at next stalled.
        val item = request.all.getOrNull(progress.next)?.takeIf { progress.readDeadline && progress.inFlight == progress.next }
        val identity = item?.let { GitHubSearchFileFailures.identity(context, it.s("path"), it.s("sha")) }
        if (identity == null && progress.completedFailures.isEmpty()) return
        val retained = store.update(scope, GitHubSearchFileFailures.STORE_KEY) {
            GitHubSearchFileFailures.update(it, progress.completedFailures, identity)
        }
        if (identity == null || item == null) return
        progress.noProgressAttempts = GitHubSearchFileFailures.entries(retained)[identity] ?: 0
        if (progress.noProgressAttempts < GitHubSearchFileFailures.MAX_ATTEMPTS) return
        // A permanently slow blob must not pin every later file behind the same cursor.
        // Query variants share failures, never coverage: this chain retains its own deferred file.
        progress.deferred += buildJsonObject {
            put("path", item.getValue("path"))
            put("sha", item.getValue("sha"))
            put("reason", "REPEATED_READ_DEADLINE")
            put("attempts", progress.noProgressAttempts)
        }
        progress.skipped++
        progress.next++
    }

    private suspend fun retainSearchPage(request: SearchRequest, progress: SearchProgress, result: JsonObject) {
        if (progress.next > request.cursor) {
            store.write(
                scope,
                "search-coverage:${request.searchId}:${progress.next}",
                buildJsonObject {
                    put("from_start", request.fromStart)
                    put("skipped", result.getValue("total_skipped_files"))
                    put("deferred", result.getValue("total_deferred_files"))
                    put("symbols_incomplete", progress.incompleteSymbols || request.priorCoverage?.get("symbols_incomplete")?.jsonPrimitive?.booleanOrNull == true)
                    put("matches_truncated", progress.truncatedMatches || request.priorCoverage?.get("matches_truncated")?.jsonPrimitive?.booleanOrNull == true)
                }
            )
        }
        // Do not freeze a transport timeout into a permanent empty cached page.
        if (!progress.readDeadline && progress.next > request.cursor) {
            store.write(scope, request.cacheKey, result)
            val evicted = mutableListOf<String>()
            store.update(scope, "search-page-keys") { old ->
                val retained = old?.get("keys")?.jsonArray.orEmpty().map { it.jsonPrimitive.content }.filter { it != request.cacheKey } + request.cacheKey
                evicted += retained.dropLast(64)
                buildJsonObject { put("keys", JsonArray(retained.takeLast(64).map(::JsonPrimitive))) }
            }
            evicted.forEach { store.remove(scope, it) }
        }
    }

    private fun searchResult(context: GitHubTaskSnapshot, request: SearchRequest, progress: SearchProgress): JsonObject {
        val query = request.query
        val mode = request.mode
        val options = request.options
        val extension = request.extension
        val batchSize = request.batchSize
        val maxNewBlobs = request.maxNewBlobs
        val searchId = request.searchId
        val all = request.all
        val cursor = request.cursor
        val fromStart = request.fromStart
        val treeComplete = request.treeComplete
        val next = progress.next
        val fetched = progress.fetched
        val skipped = progress.skipped
        val readDeadline = progress.readDeadline
        val incompleteSymbols = progress.incompleteSymbols
        val truncatedMatches = progress.truncatedMatches
        val hits = progress.hits
        val contextBudget = progress.contextBudget
        val hitLimit = progress.hitLimit
        val totalSkipped = skipped + (request.priorCoverage?.get("skipped")?.jsonPrimitive?.intOrNull ?: 0)
        val totalDeferred = progress.deferred.size + (request.priorCoverage?.get("deferred")?.jsonPrimitive?.intOrNull ?: 0)
        val anyIncompleteSymbols = incompleteSymbols || request.priorCoverage?.get("symbols_incomplete")?.jsonPrimitive?.booleanOrNull == true
        val anyTruncatedMatches = truncatedMatches || request.priorCoverage?.get("matches_truncated")?.jsonPrimitive?.booleanOrNull == true
        return buildJsonObject {
            put("query", query)
            put("filters", options.json())
            put("search_id", searchId)
            put("cache_hit", false)
            put("file_extension", extension)
            put("path_prefix", request.pathPrefix)
            put("interval_start", cursor)
            put("interval_end", next)
            put("tree_complete", treeComplete)
            put("page_complete", skipped == 0 && !incompleteSymbols && !readDeadline)
            put("traversal_complete", next == all.size)
            put("coverage_from_start", fromStart)
            put("total_skipped_files", totalSkipped)
            put("deferred_files", JsonArray(progress.deferred))
            put("total_deferred_files", totalDeferred)
            put("no_progress_attempts", progress.noProgressAttempts)
            put("matches_complete", fromStart && next == all.size && treeComplete && totalSkipped == 0 && !anyIncompleteSymbols && !anyTruncatedMatches)
            put("mode", mode)
            put("commit_sha", context.commitSha)
            put("tree_sha", context.treeSha)
            put("items", JsonArray(hits))
            put("returned", hits.size)
            put("scanned_files", next - cursor)
            put("new_blobs", fetched)
            put("max_new_blobs", maxNewBlobs)
            put("batch_size", batchSize)
            put("context_excerpt_budget_bytes", SearchContextBudget.LIMIT)
            put("context_excerpt_bytes_used", SearchContextBudget.LIMIT - contextBudget.remaining)
            put("context_truncated", contextBudget.truncated)
            put(
                "stop_reason",
                when {
                    next == all.size -> "traversal_complete"
                    readDeadline || progress.timedOut() -> "time_budget"
                    hits.size >= hitLimit -> "result_limit"
                    next - cursor >= batchSize -> "candidate_limit"
                    else -> "download_budget"
                }
            )
            put("total_candidate_files", all.size)
            put("skipped_files", skipped)
            put("symbols_incomplete", incompleteSymbols)
            put("read_deadline_reached", readDeadline)
            put("has_more", next < all.size)
            put("complete", next == all.size && fromStart && totalSkipped == 0 && !anyIncompleteSymbols && treeComplete)
            put("coverage", "complete describes file coverage across this continuation chain. Deferred paths remain unread and prevent complete coverage; inspect them with a pinned github_read request. Each file has at most ten match previews; inspect matches_complete and read matching files for all occurrences.")
            if (next < all.size) {
                put(
                    "continuation",
                    buildJsonObject {
                        put("action", "search_repository")
                        put("commit_sha", context.commitSha)
                        put("index_cursor", next)
                        put("query", query)
                        put("mode", mode)
                        put("path_prefix", request.pathPrefix)
                        put("file_extension", extension)
                        put("search_id", searchId)
                        put("batch_size", batchSize)
                        put("max_new_blobs", maxNewBlobs)
                        options.json().forEach { (key, value) -> put(key, value) }
                    }
                )
            }
        }
    }

    /** Keep the search dispatch coroutine small enough for ART to compile. */
    private suspend fun prefetchSearchBlobs(context: GitHubTaskSnapshot, candidates: List<JsonObject>, remainingMs: Long): List<Pair<String, Result<JsonObject?>>> = coroutineScope {
        candidates.map { entry ->
            async(Dispatchers.IO) {
                val result = try {
                    Result.success(kotlinx.coroutines.withTimeoutOrNull(remainingMs) { blob(context, entry) })
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    Result.failure(failure)
                }
                entry.s("sha") to result
            }
        }.awaitAll()
    }

    private data class SearchMatches(val items: List<JsonObject>, val incompleteSymbols: Boolean = false, val deadlineReached: Boolean = false)

    private class SearchContextBudget(var remaining: Int = LIMIT, var truncated: Boolean = false) {
        companion object {

            const val LIMIT = 48 * 1024
        }
    }

    private suspend fun matchSearchFile(context: GitHubTaskSnapshot, item: JsonObject, content: JsonObject, options: GitHubSearchOptions, mode: String, query: String, budget: GitHubReadWorkBudget, contextBudget: SearchContextBudget): SearchMatches {
        val sourceLines = sourceLines(content.s("content"))
        if (sourceLines.size !in options.minLines..options.maxLines) return SearchMatches(emptyList())
        val lexical = content["index"]?.jsonObject
        val symbolMode = mode in setOf("definitions", "references")
        val incompleteSymbols = symbolMode && lexical?.get("${mode}_complete")?.jsonPrimitive?.booleanOrNull != true
        val active = kotlinx.coroutines.currentCoroutineContext()
        fun checkpoint() {
            active.ensureActive()
            if (budget.expired) throw SearchPageDeadline()
        }
        val lines = try {
            if (symbolMode) {
                lexical?.get(mode)?.jsonArray.orEmpty().map { it.jsonObject }.mapNotNull { symbol ->
                    checkpoint()
                    val score = if (options.fuzzy) {
                        options.score(symbol.s("name"), query, ::checkpoint)
                    } else if (symbol.s("name").equals(query, ignoreCase = !options.caseSensitive)) {
                        1.0
                    } else {
                        0.0
                    }
                    symbol["line"]?.jsonPrimitive?.intOrNull?.takeIf { score > 0 }?.let { it to score }
                }.groupBy({ it.first }, { it.second }).mapValues { it.value.maxOrNull() ?: 0.0 }
            } else {
                emptyMap()
            }
        } catch (_: SearchPageDeadline) {
            return SearchMatches(emptyList(), incompleteSymbols, deadlineReached = true)
        }
        val matches = mutableListOf<JsonObject>()
        for ((index, line) in sourceLines.withIndex()) {
            active.ensureActive()
            if (budget.expired) return SearchMatches(matches, incompleteSymbols, deadlineReached = true)
            val confidence = if (symbolMode) {
                lines[index + 1] ?: 0.0
            } else {
                try {
                    options.score(line, query) {
                        active.ensureActive()
                        if (budget.expired) throw SearchPageDeadline()
                    }
                } catch (_: SearchPageDeadline) {
                    return SearchMatches(matches, incompleteSymbols, deadlineReached = true)
                }
            }
            if (confidence > 0) {
                val excerpt = GitHubSearchContext.extract(sourceLines, index + 1, options.contextLines, minOf(8192, contextBudget.remaining))
                contextBudget.remaining -= excerpt["excerpt"]!!.jsonPrimitive.content.toByteArray(Charsets.UTF_8).size
                contextBudget.truncated = contextBudget.truncated || excerpt["truncated"]!!.jsonPrimitive.booleanOrNull == true
                matches += buildJsonObject {
                    put("line", index + 1)
                    put("text", utf8Prefix(line, 500))
                    put("confidence", confidence)
                    put("confidence_kind", "text_similarity_not_semantic_probability")
                    put("result_id", GitHubRepositoryStateStore.digest(context.commitSha + item.s("path") + ":" + (index + 1)))
                    put("context", excerpt)
                }
            }
            if (matches.size == 11) break
        }
        return SearchMatches(matches, incompleteSymbols)
    }

    private suspend fun applicableGuidance(context: GitHubTaskSnapshot, paths: Collection<String>, pinned: JsonObject): JsonObject {
        val directories = linkedSetOf("")
        paths.forEach { path ->
            val parts = path.split('/').dropLast(1)
            parts.indices.forEach { index -> directories.add(parts.take(index + 1).joinToString("/")) }
        }
        val entries = pinned["tree"]!!.jsonArray.map { it.jsonObject }.associateBy { it.s("path") }
        val complete = pinned["truncated"]?.jsonPrimitive?.booleanOrNull != true
        val directoryTrees = mutableMapOf("" to context.treeSha)
        var budget = 8192
        val rules = mutableListOf<JsonObject>()
        for (dir in directories.sortedWith(compareBy<String> { it.count { c -> c == '/' } + if (it.isEmpty()) 0 else 1 }.thenBy { it })) {
            val names = listOf("CLAUDE.md", "AGENTS.md")
            val local = if (complete) {
                null
            } else {
                val treeSha = directoryTrees[dir] ?: continue
                val response = cachedSource(blobScope(context), "guidance-tree:v$GITHUB_TREE_CACHE_VERSION:$treeSha")
                    ?: GitHubRepositoryIndex.checkedTree(request("${root(context.owner, context.repo)}/git/trees/$treeSha").jsonObject, treeSha).also {
                        store.write(blobScope(context), "guidance-tree:v$GITHUB_TREE_CACHE_VERSION:$treeSha", it)
                    }
                require(response["truncated"]?.jsonPrimitive?.booleanOrNull != true) { "The instruction directory listing is incomplete." }
                response["tree"]!!.jsonArray.map { it.jsonObject }.associateBy { row ->
                    val full = listOf(dir, row.s("path")).filter(String::isNotEmpty).joinToString("/")
                    if (row.s("type") == "tree" && full in directories) directoryTrees[full] = row.s("sha")
                    full
                }
            }
            for (name in names) {
                val path = listOf(dir, name).filter(String::isNotEmpty).joinToString("/")
                val item = (local ?: entries)[path] ?: continue
                if (item.s("mode") !in setOf("100644", "100755")) continue
                val content = blob(context, item).s("content")
                val visible = utf8Prefix(content, minOf(4096, budget))
                budget -= visible.encodeToByteArray().size
                rules += buildJsonObject {
                    put("path", path)
                    put("blob_sha", item.s("sha"))
                    put("text", visible)
                    put("has_more", visible.length < content.length)
                    if (visible.length < content.length) put("read_with", "github_read at this commit and path")
                }
            }
        }
        return buildJsonObject {
            put("commit_sha", context.commitSha)
            put("files", JsonArray(rules))
            put("precedence", "Apply instructions to files in their directory tree. Nearer directories take precedence. AGENTS.md takes precedence over CLAUDE.md in one directory. User instructions and tool permissions remain authoritative.")
        }
    }

    private fun guidanceKey(context: GitHubTaskSnapshot) = "active-guidance:" + GitHubRepositoryStateStore.digest("${context.owner}/${context.repo}|${context.commitSha}")

    private suspend fun read(context: GitHubTaskSnapshot, args: JsonObject): JsonObject {
        val ranges = args["ranges"] as? JsonArray ?: JsonArray(listOf(args))
        require(ranges.size in 1..8) { "Read between one and eight ranges." }
        val pinnedTree = tree(context)
        val entries = pinnedTree["tree"]!!.jsonArray.map { it.jsonObject }.associateBy { it.s("path") }
        val treeComplete = pinnedTree["truncated"]?.jsonPrimitive?.booleanOrNull != true
        val resolvedRanges = mutableListOf<JsonObject>()
        val failedRanges = mutableListOf<JsonObject>()
        val selected = linkedMapOf<String, JsonObject>()
        val corrections = mutableListOf<JsonObject>()
        var firstFailure: GitHubReadValidation? = null
        withContext(Dispatchers.Default) {
            for ((rangeIndex, raw) in ranges.withIndex()) {
                val range = raw.jsonObject
                val path = range.s("path")
                try {
                    selected[path] = GitHubPinnedPathResolver.resolve(path, entries, context.commitSha, treeComplete)
                    resolvedRanges += range
                } catch (failure: GitHubReadValidation) {
                    val scoped = JsonObject(failure.details + mapOf("owner" to JsonPrimitive(context.owner), "repo" to JsonPrimitive(context.repo), "ref" to JsonPrimitive(context.ref), "range_index" to JsonPrimitive(rangeIndex), "requested_range" to range))
                    val one = JsonObject(args + ("ranges" to JsonArray(listOf(range))))
                    val rebound = GitHubSourceReadRecovery.correctedArguments("github_read", one, scoped)
                    if (ranges.size == 1) {
                        val correction = GitHubSourceReadRecovery.correctedArguments("github_read", args, scoped)
                        val details = if (correction == null) {
                            scoped
                        } else {
                            JsonObject(
                                scoped + (
                                    "correction_call" to buildJsonObject {
                                        put("tool", "github_read")
                                        put("arguments", JsonObject(correction - "action"))
                                    }
                                    )
                            )
                        }
                        throw GitHubReadValidation(failure.code, details)
                    }
                    val fixed = (rebound?.get("ranges") as? JsonArray)?.singleOrNull() as? JsonObject
                    if (fixed == null) {
                        if (firstFailure == null) firstFailure = GitHubReadValidation(failure.code, scoped)
                        failedRanges += scoped
                    } else {
                        val actual = fixed.s("path")
                        selected[actual] = GitHubPinnedPathResolver.resolve(actual, entries, context.commitSha, treeComplete)
                        resolvedRanges += fixed
                        corrections += buildJsonObject {
                            put("requested_path", path)
                            put("path", actual)
                            put("blob_sha", selected.getValue(actual).getValue("sha"))
                        }
                    }
                }
            }
        }
        if (resolvedRanges.isEmpty()) throw requireNotNull(firstFailure)
        val permits = Semaphore(2)
        val sources = coroutineScope {
            selected.map { (path, entry) -> async { permits.withPermit { path to blob(context, entry).s("content") } } }.awaitAll().toMap()
        }
        var remaining = MAX_EXCERPT_BYTES
        val results = resolvedRanges.mapIndexed { index, range ->
            val path = range.s("path").also { GitHubWorkspaceClient.filePath(it) }
            val entry = selected.getValue(path)
            require(range.s("expected_blob_sha").let { it.isBlank() || it.equals(entry.s("sha"), true) }) { "Requested blob does not match the pinned tree." }
            val content = sources.getValue(path)
            val lines = sourceLines(content)
            fun lineNumber(key: String): Int? = range[key]?.let { value ->
                requireNotNull((value as? JsonPrimitive)?.intOrNull) { "$key must be an integer." }
            }
            val start = (lineNumber("start_line") ?: 1).also { require(it >= 1) { "start_line must be positive." } }
            val requestedEnd = lineNumber("end_line")
            val maximumLines = lineNumber("max_lines")
            require(requestedEnd == null || maximumLines == null) { "Supply end_line or max_lines, not both." }
            require(maximumLines == null || maximumLines in 1..2000) { "max_lines must be between 1 and 2000." }
            require(requestedEnd == null || requestedEnd == -1 || requestedEnd >= start && requestedEnd.toLong() - start < 2000) {
                "end_line must be -1 for EOF or a range of at most 2000 lines."
            }
            val defaultLines = if (resolvedRanges.size == 1) 300 else (300 / resolvedRanges.size).coerceAtLeast(24)
            val boundedEnd = (start.toLong() + (maximumLines ?: if (requestedEnd == -1) 2000 else defaultLines) - 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val end = when (requestedEnd) {
                null -> boundedEnd
                -1 -> minOf(lines.size.coerceAtLeast(start), boundedEnd)
                else -> requestedEnd
            }
            val selected = lines.drop(start - 1).take(end - start + 1).joinToString("\n")
            val offset = (range["start_offset"]?.jsonPrimitive?.intOrNull ?: 0).also { require(it in 0..selected.length && (it == 0 || it == selected.length || !Character.isLowSurrogate(selected[it]))) { "start_offset must be a valid UTF-16 character boundary." } }
            val share = remaining / (resolvedRanges.size - index)
            val excerpt = utf8Prefix(selected.substring(offset), share)
            remaining -= excerpt.encodeToByteArray().size
            val truncated = offset + excerpt.length < selected.length
            buildJsonObject {
                put("path", path)
                put("sha", entry["sha"]!!)
                put("expected_blob_sha", entry["sha"]!!)
                put("commit_sha", context.commitSha)
                put("start_line", start)
                put("end_line", minOf(end, lines.size))
                put("total_lines", lines.size)
                put("eof", !truncated && end >= lines.size)
                put("requested_eof", requestedEnd == -1)
                put("line_limit", if (requestedEnd == -1) 2000L else maximumLines?.toLong() ?: (end.toLong() - start + 1))
                put("start_offset", offset)
                val firstLine = start + (0 until offset).count { selected[it] == '\n' }
                put("content_start_line", firstLine)
                val wholeStart = firstLine + if (offset > 0 && selected[offset - 1] != '\n') 1 else 0
                val wholeEnd = if (truncated) firstLine + excerpt.count { it == '\n' } - 1 else minOf(end, lines.size)
                if (excerpt.isNotEmpty() && wholeStart <= wholeEnd) {
                    put("captured_start_line", wholeStart)
                    put("captured_end_line", wholeEnd)
                }
                put("content", excerpt)
                put("truncated", truncated || end < lines.size || start > 1 || offset > 0)
                put("range_complete", offset == 0 && !truncated)
                put("complete_file", start == 1 && end >= lines.size && offset == 0 && !truncated)
                put("has_more", truncated || end < lines.size)
                if (truncated) {
                    put(
                        "continuation",
                        buildJsonObject {
                            put("path", path)
                            put("start_line", start)
                            put("end_line", end)
                            put("start_offset", offset + excerpt.length)
                            put("commit_sha", context.commitSha)
                        }
                    )
                } else if (end < lines.size) {
                    put(
                        "continuation",
                        buildJsonObject {
                            put("path", path)
                            put("start_line", end + 1)
                            if (requestedEnd == -1) put("end_line", -1) else maximumLines?.let { put("max_lines", it) }
                            put("commit_sha", context.commitSha)
                        }
                    )
                }
            }
        }
        val guidance = applicableGuidance(context, selected.keys, pinnedTree)
        if (guidance.getValue("files").jsonArray.isNotEmpty()) store.write(scope, guidanceKey(context), guidance)
        val contextGraph = withContext(Dispatchers.Default) { GitHubCodeContext.build(sources, entries.keys.toList(), context.commitSha) }
        val evidenceId = GitHubRepositoryStateStore.digest(scope + context.commitSha + results.toString() + if (failedRanges.isEmpty() && corrections.isEmpty()) "" else ranges.toString())
        val evidence = buildJsonObject {
            put("owner", context.owner)
            put("repo", context.repo)
            put("commit_sha", context.commitSha)
            put("tree_sha", context.treeSha)
            put("ranges", ranges)
            put("items", JsonArray(results))
            if (failedRanges.isNotEmpty()) put("failed_ranges", JsonArray(failedRanges))
            if (corrections.isNotEmpty()) put("path_corrections", JsonArray(corrections))
            put("context_graph", contextGraph)
            if (guidance["files"]?.jsonArray?.isNotEmpty() == true) put("active_instructions", guidance)
        }
        store.write(scope, "evidence:$evidenceId", evidence)
        retainSourceDescriptor(evidenceId, evidence)
        updateSnapshot { pinned ->
            val handles = (pinned.evidenceHandles + evidenceId).distinct().takeLast(128)
            if (handles == pinned.evidenceHandles) pinned else pinned.copy(evidenceHandles = handles, updatedAt = System.currentTimeMillis())
        }
        val coverage = results.groupBy { it.s("path") }.map { (path, items) ->
            val identity = buildJsonObject {
                put("owner", context.owner)
                put("repo", context.repo)
                put("commit_sha", context.commitSha)
                put("blob_sha", selected.getValue(path).getValue("sha"))
                put("path", path)
                put("total_lines", items.first().getValue("total_lines"))
            }
            val key = "source-coverage:" + GitHubRepositoryStateStore.digest(identity.toString())
            store.update(scope, key) { previous -> GitHubSourceReadCoverage.merge(previous, identity, evidenceId, items) }
        }
        return buildJsonObject {
            put("evidence_id", evidenceId)
            put("commit_sha", context.commitSha)
            put("tree_sha", context.treeSha)
            put("requested_ranges", ranges.size)
            put("returned_ranges", results.size)
            put("all_ranges_resolved", failedRanges.isEmpty())
            put("source_coverage", JsonArray(coverage))
            put("items", JsonArray(results))
            if (failedRanges.isNotEmpty()) put("failed_ranges", JsonArray(failedRanges))
            if (corrections.isNotEmpty()) put("path_corrections", JsonArray(corrections))
            put("context_graph", contextGraph)
            if (guidance["files"]?.jsonArray?.isNotEmpty() == true) put("active_instructions", guidance)
            put("concurrent_blob_limit", 2)
            put("excerpt_bytes", MAX_EXCERPT_BYTES - remaining)
        }
    }

    private suspend fun stage(context: GitHubTaskSnapshot, args: JsonObject): JsonObject {
        require(args.s("branch").isNotBlank() || snapshot()?.commitSha == context.commitSha) { "Select the intended task revision or supply an explicit patch branch." }
        val expected = args.s("expected_head_sha").ifBlank { context.commitSha }
        require(expected == context.commitSha) { "Patch expected head must equal the pinned task commit." }
        require(args.s("branch").isBlank() || args.s("branch") == context.ref) { "Patch branch must match the selected task branch." }
        val compound = "target_file" in args || "search_snippet" in args || "replacement_snippet" in args
        require(!compound || "edits" !in args) { "Use either a target_file replacement or edits, not both." }
        val edits = if (compound) {
            val path = args.s("target_file").also { GitHubWorkspaceClient.filePath(it) }
            val search = args["search_snippet"]?.jsonPrimitive?.takeIf { it.isString }?.content ?: error("search_snippet is required.")
            val replacement = args["replacement_snippet"]?.jsonPrimitive?.takeIf { it.isString }?.content ?: error("replacement_snippet is required.")
            require(search.isNotBlank() && search != replacement) { "Use github_read for inspection. Stage a non-empty exact anchor and a changed replacement." }
            JsonArray(
                listOf(
                    buildJsonObject {
                        put("path", path)
                        put("old_text", search)
                        put("new_text", replacement)
                    }
                )
            )
        } else {
            args["edits"] as? JsonArray ?: error("Supply edits or target_file, search_snippet and replacement_snippet.")
        }
        require(edits.size in 1..20) { "Supply 1–20 exact edits." }
        val pinned = tree(context)
        val entries = pinned["tree"]!!.jsonArray.map { it.jsonObject }.associateBy { it.s("path") }
        val guidance = applicableGuidance(context, edits.map { it.jsonObject.s("path") }, pinned)
        val key = guidanceKey(context)
        if (guidance["files"]!!.jsonArray.isNotEmpty() && store.read(scope, key) != guidance) {
            store.write(scope, key, guidance)
            return buildJsonObject {
                put("status", "REPOSITORY_INSTRUCTIONS")
                put("active_instructions", guidance)
                put("next_action", "Review the applicable files and any remaining instruction pages. Then submit the intended candidate against the same revision.")
            }
        }
        val originals = edits.map { it.jsonObject.s("path") }.distinct().associateWith { path ->
            val entry = requireNotNull(entries[path]) { "Patch path does not exist in the pinned tree: $path. Paths are repository-relative; do not prefix them with a branch name. Use github_search mode=paths to discover the exact file, then read its content before staging." }
            require(entry.s("mode") in setOf("100644", "100755")) { "Patch may only replace regular text blobs." }
            entry.s("sha") to blob(context, entry).s("content")
        }
        // Compound inspection reads the complete immutable blob internally. The model
        // does not need to spend additional turns assembling file ranges or blob IDs.
        val pinnedEdits = if (compound) JsonArray(edits.map { edit -> JsonObject(edit.jsonObject + ("expected_blob_sha" to JsonPrimitive(originals.getValue(edit.jsonObject.s("path")).first))) }) else edits
        val files = GitHubPatchStager.apply(originals, pinnedEdits)
        val validation = JsonArray(files.map { file -> GitHubPatchValidation.inspect(file.jsonObject.s("path"), originals.getValue(file.jsonObject.s("path")).second, file.jsonObject.s("content"), entries.keys.toList()) })
        require(validation.none { it.jsonObject.s("status") == "BLOCKED" }) { "Pre-commit checks found unresolved merge markers or credentials in added text. Correct the patch before staging." }
        val filesDigest = GitHubRepositoryStateStore.digest(GitHubExecutionJournal.canonical(files).toString())
        val id = GitHubRepositoryStateStore.digest("$scope|${context.owner.lowercase()}/${context.repo.lowercase()}|${context.ref}|${context.commitSha}|$filesDigest")
        val preview = JsonArray(
            files.map { row ->
                val file = row.jsonObject
                val before = originals.getValue(file.s("path")).second
                val after = file.s("content")
                val first = before.commonPrefixWith(after).length
                buildJsonObject {
                    put("path", file["path"]!!)
                    put("expected_blob_sha", file["expected_blob_sha"]!!)
                    put("original_bytes", before.encodeToByteArray().size)
                    put("result_bytes", after.encodeToByteArray().size)
                    put("before", utf8Prefix(before.drop((first - 120).coerceAtLeast(0)), 1200))
                    put("after", utf8Prefix(after.drop((first - 120).coerceAtLeast(0)), 1200))
                    put("preview_bounded", true)
                }
            }
        )
        val staged = store.update(scope, "stage:$id") { previous ->
            if (previous != null) {
                require(previous.s("files_digest") == filesDigest && previous.s("expected_head_sha") == expected && previous.s("branch") == context.ref) { "Stage identity conflict; inspect retained recovery context." }
                previous
            } else {
                buildJsonObject {
                    put("schema_version", 1)
                    put("stage_id", id)
                    put("version_id", id)
                    put("files_digest", filesDigest)
                    put("branch", context.ref)
                    put("tree_sha", context.treeSha)
                    put("created_at_epoch_ms", System.currentTimeMillis())
                    context.stagedPatchId?.takeIf { it != id }?.let { put("parent_stage_id", it) }
                    put("owner", context.owner)
                    put("repo", context.repo)
                    put("expected_head_sha", expected)
                    put("files", files)
                    put("preview", preview)
                    put("validation", validation)
                }
            }
        }
        rememberStage(context, id, requireSelectedRevision = args.s("branch").isBlank())
        return buildJsonObject {
            put("stage_id", id)
            put("schema_version", 1)
            put("version_id", staged.getValue("version_id"))
            staged["parent_stage_id"]?.let { put("parent_stage_id", it) }
            put("expected_head_sha", expected)
            put("preview", preview)
            put("validation", staged["validation"] ?: validation)
            put("files_changed", files.size)
            put("committed", staged["commit_receipt"] != null)
            staged["commit_receipt"]?.let { put("commit_receipt", it) }
            staged["engineering_review_receipt"]?.let { put("engineering_review_receipt", it) }
            put("branch", context.ref)
            put("next_action", if (staged["commit_receipt"] != null || staged["engineering_review_receipt"] != null) "Inspect the retained publication/review receipt; this patch was already submitted and must not be published again." else "Submit this stage with github_commit using stage_id, branch, expected_head_sha and message. The exact patch will appear for approval in chat; do not repeat source reads.")
        }
    }

    private suspend fun restoreStage(context: GitHubTaskSnapshot, args: JsonObject): JsonObject {
        require(listOf("edits", "target_file", "search_snippet", "replacement_snippet").none { it in args }) { "Restore a saved stage without supplying new edits." }
        val id = args.s("restore_stage_id")
        val staged = requireNotNull(store.read(scope, "stage:$id")) { "Stage version is unavailable in this task/account." }
        require(staged.s("owner").equals(context.owner, true) && staged.s("repo").equals(context.repo, true) && staged.s("expected_head_sha") == context.commitSha && staged.s("branch") == context.ref) { "Restore requires the same repository, branch and immutable base revision." }
        val target = args.s("expected_head_sha")
        if (target.isNotBlank() && target != context.commitSha) {
            require(args.s("branch") == context.ref && target.sha()) { "Restaging needs the original branch and its current immutable head." }
            require(args["commit_immediately"] != JsonPrimitive(true)) { "Review the revalidated stage at its new revision before publishing." }
            return restageUnpublishedPatch(context, staged, target)
        }
        rememberStage(context, id)
        return buildJsonObject {
            put("stage_id", id)
            put("version_id", staged["version_id"] ?: JsonPrimitive(id))
            put("status", "STAGE_RESTORED")
            put("expected_head_sha", context.commitSha)
            put("branch", context.ref)
            put("preview", staged["preview"] ?: JsonArray(emptyList()))
            put("validation", staged["validation"] ?: JsonArray(emptyList()))
            put("committed", staged["commit_receipt"] != null)
            put("remote_state_changed", false)
            put("message", "The saved local patch version is selected. Existing publication receipts remain attached; no branch, tag or remote write was rolled back.")
        }
    }

    private suspend fun restageUnpublishedPatch(context: GitHubTaskSnapshot, staged: JsonObject, target: String): JsonObject {
        val id = staged.s("stage_id")
        fun unsubmitted(row: JsonObject?) = row != null && "commit_receipt" !in row && "engineering_review_receipt" !in row
        val publication = staged["publication_arguments"]
        val publicationId = publication?.let { GitHubRepositoryStateStore.digest("$scope|commit_staged_patch|${GitHubExecutionJournal.canonical(it)}") }
        suspend fun requireRejectedOrUnclaimed() {
            if (publicationId != null) {
                require(store.read(scope, "execution:$publicationId")?.s("status") == "REJECTED") { "Recover the original publication or review receipt before restaging." }
            }
        }
        require(unsubmitted(staged)) { "Recover the original publication or review receipt before restaging." }
        requireRejectedOrUnclaimed()
        val unresolved = (executions.context()["executions"] as? JsonArray).orEmpty().any {
            it.jsonObject.s("action") == "commit_staged_patch" && it.jsonObject.s("status") in setOf("RUNNING", "RECONCILE_REQUIRED")
        }
        require(!unresolved) { "Reconcile the earlier staged publication with its original arguments before restaging." }
        val root = root(context.owner, context.repo)
        val head = request("$root/commits/${GitHubWorkspaceClient.segment(context.ref)}").jsonObject
        val treeSha = (head["commit"] as? JsonObject)?.get("tree")?.jsonObject?.s("sha").orEmpty()
        require(head.s("sha") == target && treeSha.sha()) { "The branch moved again. Refresh its head before restaging." }
        val ancestry = request("$root/compare/${context.commitSha}...$target?per_page=1&page=1").jsonObject
        require(ancestry.s("status") == "ahead" && (ancestry["merge_base_commit"] as? JsonObject)?.s("sha") == context.commitSha) {
            "The branch history diverged. Review the new base before preparing another patch."
        }
        val next = context.copy(commitSha = target, treeSha = treeSha, stagedPatchId = id)
        val originalTree = tree(context)
        val currentTree = tree(next)
        val files = staged.getValue("files").jsonArray
        val entries = GitHubPatchStager.revalidatedEntries(files, originalTree, currentTree)
        val edits = JsonArray(
            files.map { row ->
                val file = row.jsonObject
                val original = blob(next, entries.getValue(file.s("path")))
                buildJsonObject {
                    put("path", file.getValue("path"))
                    put("expected_blob_sha", file.getValue("expected_blob_sha"))
                    put("old_text", original.getValue("content"))
                    put("new_text", file.getValue("content"))
                }
            }
        )
        // Atomically exclude an in-flight or later publication on the old lease before exposing a child stage.
        store.update(scope, "stage:$id") { current ->
            require(unsubmitted(current) && current?.get("publication_arguments") == publication && current?.get("files") == files) { "The original publication changed during restaging. Recover its receipt." }
            JsonObject(requireNotNull(current) + ("restaged_for_head" to JsonPrimitive(target)))
        }
        // A retry that claimed the journal before this fence can still require read-only reconciliation.
        requireRejectedOrUnclaimed()
        val result = stage(
            next,
            buildJsonObject {
                put("branch", context.ref)
                put("expected_head_sha", target)
                put("edits", edits)
            }
        )
        return JsonObject(result + mapOf("previous_head_sha" to JsonPrimitive(context.commitSha), "remote_state_changed" to JsonPrimitive(false)))
    }

    private suspend fun commitStage(context: GitHubTaskSnapshot, args: JsonObject): JsonElement {
        val id = args.s("stage_id")
        require(id.isNotBlank())
        val staged = store.read(scope, "stage:$id") ?: error("Patch stage is unavailable or belongs to another task/account.")
        require(staged.s("owner").equals(context.owner, true) && staged.s("repo").equals(context.repo, true))
        require(args.s("expected_head_sha") == staged.s("expected_head_sha")) { "The supplied expected_head_sha differs from the staged patch; refresh the branch and stage evidence before committing." }
        require(staged.s("expected_head_sha") == context.commitSha) { "Task revision changed; restage the reviewed patch." }
        require(staged.s("branch").isBlank() || staged.s("branch") == context.ref) { "Stage belongs to a different working branch." }
        require(args.s("branch") == staged.s("branch")) { "The explicit commit branch differs from the staged patch." }
        staged["commit_receipt"]?.let { return it }
        staged["engineering_review_receipt"]?.let { return it }
        val publication = buildJsonObject {
            put("stage_id", id)
            put("branch", staged.getValue("branch"))
            put("expected_head_sha", staged.getValue("expected_head_sha"))
            put("message", args["message"] ?: JsonPrimitive(""))
        }
        require(publication.s("message").isNotBlank() && publication.s("message").length <= 4000) { "A bounded commit message is required." }
        // Old versions fenced the caller's whole argument object. Preserve that
        // exact claim when present; never replace a legacy uncertain publication.
        val legacyId = GitHubRepositoryStateStore.digest("$scope|commit_staged_patch|${GitHubExecutionJournal.canonical(args)}")
        val legacy = store.read(scope, "execution:$legacyId")
        if (staged["publication_arguments"] == null && legacy == null) {
            val unresolved = (executions.context()["executions"] as? JsonArray).orEmpty().any { row ->
                row.jsonObject.s("action") == "commit_staged_patch" && row.jsonObject.s("status") in setOf("RUNNING", "RECONCILE_REQUIRED")
            }
            require(!unresolved) { "An earlier staged publication needs reconciliation with its original arguments before another write." }
        }
        val savedPublication = staged["publication_arguments"] as? JsonObject
        require(savedPublication == null || listOf("stage_id", "branch", "expected_head_sha", "message").all { savedPublication[it] == publication[it] }) {
            "This stage already has a different publication intent. Recover its saved receipt before changing the commit message."
        }
        val journalArguments = savedPublication ?: if (legacy != null) args else publication
        val bound = store.update(scope, "stage:$id") { current ->
            require(current?.get("files_digest") == staged["files_digest"]) { "Stage changed before publication." }
            val savedArguments = current?.get("publication_arguments")
            require(current?.get("restaged_for_head") == null || savedArguments == journalArguments) { "This stage was revalidated at a new head. Review and submit its new stage ID." }
            require(savedArguments == null || savedArguments == journalArguments) { "This stage already has a different publication intent. Recover its saved receipt before changing the commit message." }
            JsonObject(requireNotNull(current) + ("publication_arguments" to journalArguments))
        }
        val commitArgs = JsonObject(publication + mapOf("files" to bound.getValue("files")))
        val root = root(context.owner, context.repo)
        val result = executions.run("commit_staged_patch", journalArguments, reconcile = { reconcileCommit(root, commitArgs) }) {
            // Reconciliations above remain available. A fresh dispatch must still own the current local stage.
            val latest = requireNotNull(store.read(scope, "stage:$id")) { "Restore the stage identity before publishing." }
            require(latest["restaged_for_head"] == null && latest["publication_arguments"] == journalArguments) { "Review the revalidated stage at its new head before publishing." }
            commit(root, commitArgs)
        }
        if (result.jsonObject["execution"]?.jsonObject?.s("status") != "COMPLETED") return result
        val receiptKey = if (result.jsonObject["sha"] != null) "commit_receipt" else "engineering_review_receipt"
        val receipt = JsonObject(
            result.jsonObject + mapOf(
                "stage_id" to JsonPrimitive(id),
                "files_digest" to staged.getValue("files_digest"),
                "patch_preview" to (staged["preview"] ?: JsonArray(emptyList())),
                "patch_validation" to (staged["validation"] ?: JsonArray(emptyList()))
            )
        )
        val saved = store.update(scope, "stage:$id") { current ->
            require(current?.get("files") == staged["files"] && current?.get("expected_head_sha") == staged["expected_head_sha"]) { "Stage changed during publication; reconcile the execution receipt." }
            if (current?.get(receiptKey) != null) current else JsonObject(requireNotNull(current) + (receiptKey to receipt))
        }
        return saved.getValue(receiptKey)
    }

    private suspend fun changed(context: GitHubTaskSnapshot, args: JsonObject): JsonObject {
        val baseRef = args.s("base").also { require(it.isNotBlank()) }
        val response = request("${root(context.owner, context.repo)}/commits/${GitHubWorkspaceClient.segment(baseRef)}").jsonObject
        val base = context.copy(commitSha = response.s("sha"), treeSha = response["commit"]!!.jsonObject["tree"]!!.jsonObject.s("sha"))
        require(base.commitSha.sha() && base.treeSha.sha())
        val oldTree = tree(base)
        val newTree = tree(context)
        val before = oldTree["tree"]!!.jsonArray.map { it.jsonObject }.filter { it.s("type") == "blob" }.associateBy { it.s("path") }
        val after = newTree["tree"]!!.jsonArray.map { it.jsonObject }.filter { it.s("type") == "blob" }.associateBy { it.s("path") }
        val changes = (before.keys + after.keys).sorted().filter { before[it]?.s("sha") != after[it]?.s("sha") || before[it]?.s("mode") != after[it]?.s("mode") }
        val window = GitHubComparisonPage.window(args, changes.size)
        val page = window.page
        val includePatches = args["include_patches"]?.jsonPrimitive?.booleanOrNull == true
        // GitHub compare exposes patches for at most 300 files. Missing/binary patches
        // remain explicitly unavailable; tree comparison still covers every file.
        val patches = if (includePatches) {
            request("${root(context.owner, context.repo)}/compare/${base.commitSha}...${context.commitSha}?per_page=1&page=1")
                .jsonObject["files"]?.jsonArray.orEmpty().map { it.jsonObject }.associateBy { it.s("filename") }
        } else {
            emptyMap()
        }
        return buildJsonObject {
            put("owner", context.owner)
            put("repo", context.repo)
            put("base_sha", base.commitSha)
            put("head_sha", context.commitSha)
            put("files_total", changes.size)
            put("effective_per_page", window.size)
            put("total_pages", window.totalPages)
            put(
                "files",
                JsonArray(
                    changes.subList(window.from, window.until).map { path ->
                        buildJsonObject {
                            put("filename", path)
                            put(
                                "status",
                                if (before[path] == null) {
                                    "added"
                                } else if (after[path] == null) {
                                    "removed"
                                } else {
                                    "modified"
                                }
                            )
                            put("sha", after[path]?.get("sha") ?: JsonNull)
                            put("previous_sha", before[path]?.get("sha") ?: JsonNull)
                            if (includePatches) {
                                val patch = patches[path]?.get("patch") as? JsonPrimitive
                                put("patch_available", patch != null)
                                patch?.content?.let {
                                    put("patch", utf8Prefix(it, MAX_EXCERPT_BYTES))
                                    put("patch_truncated", it.encodeToByteArray().size > MAX_EXCERPT_BYTES)
                                } ?: put("patch_unavailable_reason", "GitHub omitted the patch (binary, oversized, or outside its 300-file compare limit). Read the pinned before/after blobs for this file.")
                            }
                        }
                    }
                )
            )
            put("page", page)
            put("has_more", window.hasMore)
            put("files_truncated", window.hasMore)
            put("tree_complete", oldTree["truncated"]?.jsonPrimitive?.booleanOrNull != true && newTree["truncated"]?.jsonPrimitive?.booleanOrNull != true)
            if (window.hasMore) {
                put(
                    "continuation",
                    buildJsonObject {
                        put("action", "changed_since")
                        put("base", base.commitSha)
                        put("commit_sha", context.commitSha)
                        put("page", page + 1)
                        put("per_page", window.size)
                        put("include_patches", includePatches)
                    }
                )
            }
        }.let { GitHubComparisonPage.limitResult(args, it) ?: it }
    }

    companion object {
        internal fun sourceLines(content: String): List<String> = if (content.isEmpty()) emptyList() else content.split('\n').let { if (content.endsWith('\n')) it.dropLast(1) else it }

        const val MAX_BLOB_BYTES = 2 * 1024 * 1024
        const val MAX_EXCERPT_BYTES = 32 * 1024
        internal fun utf8Prefix(value: String, bytes: Int): String {
            if (bytes <= 0) return ""
            if (value.length <= bytes / 4 || value.encodeToByteArray().size <= bytes) return value
            var low = 0
            var high = minOf(value.length, bytes)
            while (low < high) {
                val middle = (low + high + 1) / 2
                if (value.substring(0, middle).encodeToByteArray().size <= bytes) low = middle else high = middle - 1
            }
            if (low > 0 && Character.isHighSurrogate(value[low - 1])) low--
            return value.substring(0, low)
        }
    }
}
