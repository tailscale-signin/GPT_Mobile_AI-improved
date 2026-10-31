package dev.chungjungsoo.gptmobile.data.github

import java.util.Base64
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
    private val reconcileCommit: suspend (String, JsonObject) -> JsonElement? = { _, _ -> null }
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

    suspend fun resolve(owner: String, repo: String, args: JsonObject, selectContext: Boolean = false): GitHubTaskSnapshot {
        val explicit = args.s("commit_sha").ifBlank { args.s("ref").takeIf { it.sha() }.orEmpty() }
        if (explicit.isNotEmpty()) require(explicit.sha()) { "commit_sha must be an immutable 40/64-hex commit." }
        val retained = snapshot()
        val requestedRef = args.s("ref").ifBlank { args.s("branch") }
        val refresh = args["refresh"]?.jsonPrimitive?.booleanOrNull == true || selectContext && requestedRef.isNotBlank() && requestedRef != retained?.ref
        if (!refresh && retained != null && retained.owner.equals(owner, true) && retained.repo.equals(repo, true) && (explicit.isBlank() || explicit == retained.commitSha)) return retained
        val repository = root(owner, repo)
        val previous = context()?.takeIf { it.owner.equals(owner, true) && it.repo.equals(repo, true) }
        val ref = explicit.ifBlank { args.s("ref").ifBlank { args.s("branch").ifBlank { previous?.ref.orEmpty().ifBlank { request(repository).jsonObject.s("default_branch").ifBlank { "HEAD" } } } } }
        val response = request("$repository/commits/${GitHubWorkspaceClient.segment(ref)}").jsonObject
        val sha = response.s("sha")
        val tree = response["commit"]?.jsonObject?.get("tree")?.jsonObject?.s("sha").orEmpty()
        require(sha.sha() && tree.sha() && (explicit.isBlank() || explicit.equals(sha, true))) { "GitHub did not supply the requested immutable commit and tree." }
        return GitHubTaskSnapshot(accountId, connectionUid, conversationId, taskId, owner, repo, if (explicit.isBlank()) ref else requestedRef.takeUnless { it.sha() || it.isBlank() } ?: retained?.ref ?: ref, sha, tree).also { resolved ->
            if (retained == null || refresh || selectContext || retained.owner != owner || retained.repo != repo) save(resolved)
        }
    }

    private suspend fun tree(context: GitHubTaskSnapshot): JsonObject {
        val scope = blobScope(context)
        store.read(scope, "tree:${context.commitSha}")?.let { return it }
        val response = request("${root(context.owner, context.repo)}/git/trees/${context.treeSha}?recursive=1").jsonObject
        val entries = response["tree"]?.jsonArray.orEmpty()
        val bounded = buildJsonObject {
            put("tree", JsonArray(entries.take(20000).map { row -> JsonObject(row.jsonObject.filterKeys { it in setOf("path", "sha", "size", "type", "mode") }) }))
            put("truncated", entries.size > 20000 || response["truncated"]?.jsonPrimitive?.booleanOrNull == true)
            put("tree_sha", context.treeSha)
        }
        store.write(scope, "tree:${context.commitSha}", bounded)
        val old = store.read(scope, "tree_keys")?.get("keys")?.jsonArray.orEmpty().map { it.jsonPrimitive.content }.filter { it != context.commitSha }
        val keys = old + context.commitSha
        keys.dropLast(12).forEach { store.remove(scope, "tree:$it") }
        store.write(scope, "tree_keys", buildJsonObject { put("keys", JsonArray(keys.takeLast(12).map(::JsonPrimitive))) })
        return bounded
    }

    private suspend fun blob(context: GitHubTaskSnapshot, item: JsonObject): JsonObject {
        val sha = item.s("sha")
        require(sha.sha()) { "Blob identity is unavailable." }
        store.read(blobScope(context), "blob:$sha")?.let { return it }
        require((item["size"]?.jsonPrimitive?.longOrNull ?: 0) <= MAX_BLOB_BYTES) { "Blob exceeds the 2 MiB ingestion budget." }
        val response = request("${root(context.owner, context.repo)}/git/blobs/$sha").jsonObject
        require(response.s("sha").isBlank() || response.s("sha") == sha) { "Returned blob identity differs from the selected tree." }
        val bytes = when (response.s("encoding")) {
            "base64" -> {
                require(response.s("content").length <= MAX_BLOB_BYTES * 2)
                Base64.getMimeDecoder().decode(response.s("content"))
            }
            "utf-8", "utf8", "" -> response.s("content").encodeToByteArray()
            else -> error("Unsupported blob encoding.")
        }
        require(bytes.size <= MAX_BLOB_BYTES && bytes.none { it == 0.toByte() }) { "Only bounded UTF-8 text blobs can be ingested." }
        val text = bytes.decodeToString(throwOnInvalidSequence = true)
        // Cached ingestion can resume on Main when invoked from the workspace UI.
        // Hashing and lexical indexing a 2 MiB blob belongs on a CPU dispatcher.
        val result = withContext(Dispatchers.Default) {
            buildJsonObject {
                put("sha", sha)
                put("content", text)
                put("bytes", bytes.size)
                put("content_hash", GitHubRepositoryStateStore.digest(text))
                put("index", GitHubLocalIndex.build(text))
            }
        }
        store.write(blobScope(context), "blob:$sha", result)
        return result
    }

    suspend fun execute(action: String, owner: String, repo: String, args: JsonObject): JsonElement {
        if (action == "changed_since") {
            require(args.s("base").isNotBlank()) {
                "CHANGES_BASE_REQUIRED: Supply base_sha to github_context(action=changes), or base to changed_since. Use github_pr(action=context, include_files=true) for a pull request's changed files, or github_context(action=status) for repository status."
            }
        }
        if (action == "read_ranges" && args.s("evidence_id").isNotEmpty()) {
            val retained = retainedEvidence(args.s("evidence_id")) ?: error("EVIDENCE_UNAVAILABLE: This source handle is unavailable in the current account, connection and conversation. Use github_context action=recovery to find retained source handles, or read exact ranges at the observed commit_sha. Do not repeat the unavailable handle.")
            require(retained.s("owner").equals(owner, true) && retained.s("repo").equals(repo, true)) { "Evidence belongs to another repository." }
            require(args.s("commit_sha").isBlank() || args.s("commit_sha").equals(retained.s("commit_sha"), true)) { "Evidence belongs to a different commit_sha. Omit commit_sha to reopen its pinned source." }
            require(retained.s("commit_sha").sha() && retained.s("tree_sha").sha()) { "Retained source identity is unavailable." }
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
            val retained = snapshot() ?: resolve(owner, repo, JsonObject(emptyMap()))
            require(retained.owner.equals(owner, true) && retained.repo.equals(repo, true))
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
            val stageId = args.s("stage_id").ifBlank { retained.stagedPatchId.orEmpty() }
            val stage = stageId.takeIf { it.isNotBlank() }?.let { store.read(scope, "stage:$it") }
            val executionContext = executions.context()
            return buildJsonObject {
                put("commit_sha", retained.commitSha)
                put("branch", retained.ref)
                put("evidence", JsonArray(evidence))
                put("page", page)
                put("has_more", page * 16 < allHandles.size)
                put("total_retained_evidence", allHandles.size)
                put("retention_note", "Bounded source inventory for this repository, account, connection and conversation; expired records are marked unavailable. Stages remain task-local. Use read_recovery_context for the conversation action ledger.")
                put("stage_id", stageId)
                put("stage_available", stage != null)
                put("execution_context", executionContext)
                stage?.let {
                    put("stage", JsonObject(it.filterKeys { key -> key != "files" }))
                    put("staged_files", JsonArray(it["files"]?.jsonArray.orEmpty().map { file -> JsonObject(file.jsonObject.filterKeys { key -> key in setOf("path", "expected_blob_sha", "delete") }) }))
                }
            }
        }
        val context = resolve(owner, repo, requested, action in setOf("resolve_context", "task_context"))
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
            "build_index" -> buildIndex(context, args)
            "workflow_catalog" -> workflowCatalog(context, args)
            "search_repository" -> search(context, args)
            "read_ranges" -> read(context, args)
            "stage_patch" -> if (args.s("restore_stage_id").isNotBlank()) restoreStage(context, args) else stage(context, args)
            "commit_staged_patch" -> commitStage(context, args)
            "changed_since" -> changed(context, args)
            else -> error("Unknown repository session action: $action")
        }
    }

    private suspend fun indexStatus(context: GitHubTaskSnapshot): JsonObject {
        val tree = tree(context)
        val candidates = tree["tree"]!!.jsonArray.map { it.jsonObject }.filter { it.s("type") == "blob" }
        var loaded = 0
        for (entry in candidates) if (store.contains(blobScope(context), "blob:${entry.s("sha")}")) loaded++
        return buildJsonObject {
            put("commit_sha", context.commitSha)
            put("tree_sha", context.treeSha)
            put("total_paths", candidates.size)
            put("indexed_blobs", loaded)
            put("complete", loaded == candidates.size && tree["truncated"]?.jsonPrimitive?.booleanOrNull != true)
            put("tree_complete", tree["truncated"]?.jsonPrimitive?.booleanOrNull != true)
            put("kind", "lexical")
            put("compiler_resolution", false)
        }
    }

    /** Explicit foreground preparation. Every batch is bounded and pinned to one SHA. */
    private suspend fun buildIndex(context: GitHubTaskSnapshot, args: JsonObject): JsonObject {
        val entries = tree(context)["tree"]!!.jsonArray.map { it.jsonObject }.filter { it.s("type") == "blob" }.sortedBy { it.s("path") }
        val sourceExtensions = setOf("kt", "kts", "java", "py", "js", "jsx", "ts", "tsx", "json", "yaml", "yml", "xml", "toml", "md", "c", "cpp", "h", "hpp", "rs", "go", "cs", "swift", "sh", "properties", "gradle", "txt")
        val eligible = entries.filter { item -> item.s("path").substringAfterLast('.').lowercase() in sourceExtensions || item.s("path").substringAfterLast('/').uppercase() in setOf("README", "LICENSE", "DOCKERFILE", "MAKEFILE") }
        val cursor = args.s("index_cursor").ifBlank { "0" }.toInt().also { require(it in 0..eligible.size) }
        var next = cursor
        var bytes = 0L
        var fetched = 0
        var attempted = 0
        var skipped = 0
        while (next < eligible.size && next - cursor < 100 && attempted < 24 && bytes < 4L * 1024 * 1024) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val item = eligible[next++]
            if (store.contains(blobScope(context), "blob:${item.s("sha")}")) continue
            val size = item["size"]?.jsonPrimitive?.longOrNull ?: MAX_BLOB_BYTES.toLong()
            if (size > MAX_BLOB_BYTES || size < 0) {
                skipped++
                continue
            }
            if (bytes + size > 4L * 1024 * 1024) {
                next--
                break
            }
            try {
                attempted++
                val retained = blob(context, item)
                bytes += retained.s("content").toByteArray(Charsets.UTF_8).size
                fetched++
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: IllegalArgumentException) {
                skipped++
            } catch (_: java.nio.charset.CharacterCodingException) {
                skipped++
            }
        }
        val status = indexStatus(context)
        val result = buildJsonObject {
            status.forEach { (key, value) -> put(key, value) }
            put("preparation_scope", "bounded_text_source_files")
            put("eligible_paths", eligible.size)
            put("prepared_through", next)
            put("fetched_this_batch", fetched)
            put("attempted_this_batch", attempted)
            put("skipped_this_batch", skipped)
            put("prepared_bytes_this_batch", bytes)
            put("preparation_finished", next == eligible.size && status["tree_complete"]?.jsonPrimitive?.booleanOrNull == true)
            // Finished traversal is not proof that every file was ingested.
            if (next < eligible.size) put("next_index_cursor", next)
        }
        store.write(scope, "index-preparation:${context.commitSha}", result)
        return result
    }

    private suspend fun workflowCatalog(context: GitHubTaskSnapshot, args: JsonObject): JsonObject {
        val tree = tree(context)
        val workflows = tree["tree"]!!.jsonArray.map { it.jsonObject }.filter {
            it.s("type") == "blob" &&
                it.s("path").startsWith(".github/workflows/") &&
                it.s("path").substringAfter(".github/workflows/").let { name -> '/' !in name && (name.endsWith(".yml") || name.endsWith(".yaml")) }
        }.distinctBy { it.s("path") }.sortedBy { it.s("path") }
        val cursor = args.s("index_cursor").ifBlank { "0" }.toInt().also { require(it in 0..workflows.size) }
        val selected = workflows.drop(cursor).take(4)
        val rows = selected.map { file ->
            val key = "workflow-metadata-v1:${file.s("sha")}"
            val retained = store.read(blobScope(context), key)
            val metadata = retained ?: try {
                val source = blob(context, file).s("content")
                withContext(Dispatchers.Default) { GitHubWorkflowMetadata.parse(source) }.also { store.write(blobScope(context), key, it) }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (invalid: IllegalArgumentException) {
                buildJsonObject {
                    put("metadata_available", false)
                    put("reason", "Definition exceeds supported metadata limits; inspect its source.")
                }
            }
            buildJsonObject {
                put("path", file.s("path"))
                put("workflow_id", file.s("path").substringAfterLast('/'))
                put("blob_sha", file.s("sha"))
                put("cache_hit", retained != null)
                put("definition", metadata)
            }
        }
        val next = cursor + selected.size
        return buildJsonObject {
            put("commit_sha", context.commitSha)
            put("workflows", JsonArray(rows))
            put("total_count", workflows.size)
            put("tree_complete", tree["truncated"]?.jsonPrimitive?.booleanOrNull != true)
            put("has_more", next < workflows.size)
            if (next < workflows.size) {
                put(
                    "continuation",
                    buildJsonObject {
                        put("action", "workflow_catalog")
                        put("commit_sha", context.commitSha)
                        put("index_cursor", next)
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
        val priorCoverage: JsonObject?
    ) {
        val cacheKey get() = "search-page-v4:$searchId:$cursor:$batchSize:$maxNewBlobs"
        val fromStart get() = priorCoverage?.get("from_start")?.jsonPrimitive?.booleanOrNull == true
    }

    private class SearchProgress(request: SearchRequest) {
        var next = request.cursor
        var fetched = 0
        var skipped = 0
        val startedNanos = System.nanoTime()
        var readDeadline = false
        var incompleteSymbols = false
        var truncatedMatches = false
        val hits = mutableListOf<JsonObject>()
        val contextBudget = SearchContextBudget()
        val prefetched = mutableMapOf<String, Result<JsonObject?>>()
        val hitLimit = if (request.mode == "paths") 100 else 20
        fun timedOut() = (System.nanoTime() - startedNanos) / 1_000_000 >= 12_000
    }

    private suspend fun search(context: GitHubTaskSnapshot, args: JsonObject): JsonObject {
        require(args.s("query").isNotBlank() && args.s("query").length <= 200) { "Search requires a non-blank query of at most 200 characters." }
        require(args.s("mode").ifBlank { "literal" } in setOf("literal", "definitions", "references", "paths", "tests", "patterns"))
        val request = prepareSearch(context, args, tree(context))
        store.read(scope, request.cacheKey)?.let { cached ->
            return JsonObject(cached + mapOf("cache_hit" to JsonPrimitive(true), "new_blobs" to JsonPrimitive(0)))
        }
        val progress = SearchProgress(request)
        scanSearch(context, request, progress)
        val result = searchResult(context, request, progress)
        retainSearchPage(request, progress, result)
        return result
    }

    private suspend fun prepareSearch(context: GitHubTaskSnapshot, args: JsonObject, data: JsonObject): SearchRequest {
        val query = args.s("query")
        require(query.isNotBlank() && query.length <= 200) { "Search requires a non-blank query of at most 200 characters." }
        val mode = args.s("mode").ifBlank { "literal" }
        require(mode in setOf("literal", "definitions", "references", "paths", "tests", "patterns"))
        val options = GitHubSearchOptions.from(args)
        val extension = args.s("file_extension").removePrefix(".").lowercase(java.util.Locale.ROOT)
        require(extension.isEmpty() || extension.matches(Regex("[a-z0-9]+"))) { "file_extension must be a single extension such as kt or .kt." }
        val batchSize = args.s("batch_size").ifBlank { "1000" }.toInt().also { require(it in 1..1000) }
        val maxNewBlobs = args.s("max_new_blobs").ifBlank { "16" }.toInt().also { require(it in 1..100) }
        val pathPrefix = args.s("path_prefix")
        val searchId = GitHubRepositoryStateStore.digest(listOf(context.commitSha, query, mode, pathPrefix, extension, options.json().toString()).joinToString("\u0000"))
        require(args.s("search_id").isBlank() || args.s("search_id") == searchId) { "Search parameters changed. Start at index_cursor=0 without search_id, or use the exact continuation." }
        val all = data["tree"]!!.jsonArray.map { it.jsonObject }.filter { item ->
            item.s("type") == "blob" &&
                (mode != "tests" || item.s("path").contains("test", true)) &&
                (pathPrefix.isBlank() || item.s("path").startsWith(pathPrefix)) &&
                (options.suffix.isEmpty() || item.s("path").endsWith(options.suffix)) &&
                (extension.isEmpty() || item.s("path").substringAfterLast('.', "").equals(extension, true))
        }.distinctBy { it.s("path") }.sortedBy { it.s("path") }
        val cursor = args.s("index_cursor").ifBlank { "0" }.toInt().also { require(it in 0..all.size) }
        val priorCoverage = if (cursor == 0) buildJsonObject { put("from_start", true) } else store.read(scope, "search-coverage:$searchId:$cursor")
        return SearchRequest(query, mode, options, extension, pathPrefix, batchSize, maxNewBlobs, searchId, all, cursor, data["truncated"]?.jsonPrimitive?.booleanOrNull != true, priorCoverage)
    }

    private suspend fun scanSearch(context: GitHubTaskSnapshot, request: SearchRequest, progress: SearchProgress) {
        val all = request.all
        // Related-file discovery reuses one path list for the entire page.
        val paths = all.map { it.s("path") }
        while (progress.next < all.size && (request.mode == "paths" || progress.next - request.cursor < request.batchSize) && progress.hits.size < progress.hitLimit) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
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
            val cached = store.read(blobScope(context), "blob:${item.s("sha")}")
            if (cached == null && item.s("sha") !in progress.prefetched && progress.fetched >= request.maxNewBlobs) break
            progress.next++
            val content = searchContent(context, request, progress, item, cached)
            if (progress.readDeadline) break
            if (content == null) continue
            val match = matchSearchFile(context, item, content, request.options, request.mode, request.query, progress.startedNanos, progress.contextBudget)
            progress.incompleteSymbols = progress.incompleteSymbols || match.incompleteSymbols
            if (match.deadlineReached) {
                progress.next--
                progress.readDeadline = true
                break
            }
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
                    .filter { store.read(blobScope(context), "blob:${it.s("sha")}") == null }
                progress.fetched += candidates.size
                val remainingMs = (12_000 - (System.nanoTime() - progress.startedNanos) / 1_000_000).coerceIn(1, 8_000)
                prefetchSearchBlobs(context, candidates, remainingMs).forEach { (sha, result) -> progress.prefetched[sha] = result }
            }
            val retained = progress.prefetched.remove(item.s("sha"))?.getOrThrow() ?: cached ?: store.read(blobScope(context), "blob:${item.s("sha")}")
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

    private suspend fun retainSearchPage(request: SearchRequest, progress: SearchProgress, result: JsonObject) {
        if (progress.next > request.cursor) {
            store.write(
                scope,
                "search-coverage:${request.searchId}:${progress.next}",
                buildJsonObject {
                    put("from_start", request.fromStart)
                    put("skipped", result.getValue("total_skipped_files"))
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
        val startedNanos = progress.startedNanos
        val readDeadline = progress.readDeadline
        val incompleteSymbols = progress.incompleteSymbols
        val truncatedMatches = progress.truncatedMatches
        val hits = progress.hits
        val contextBudget = progress.contextBudget
        val hitLimit = progress.hitLimit
        val totalSkipped = skipped + (request.priorCoverage?.get("skipped")?.jsonPrimitive?.intOrNull ?: 0)
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
                    readDeadline || (System.nanoTime() - startedNanos) / 1_000_000 >= 12_000 -> "time_budget"
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
            put("coverage", "complete describes file coverage across this continuation chain. Each file has at most ten match previews; inspect matches_complete and read matching files for all occurrences.")
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

    private suspend fun matchSearchFile(context: GitHubTaskSnapshot, item: JsonObject, content: JsonObject, options: GitHubSearchOptions, mode: String, query: String, startedNanos: Long, contextBudget: SearchContextBudget): SearchMatches {
        val sourceLines = content.s("content").lines()
        if (sourceLines.size !in options.minLines..options.maxLines) return SearchMatches(emptyList())
        val lexical = content["index"]?.jsonObject
        val symbolMode = mode in setOf("definitions", "references")
        val incompleteSymbols = symbolMode && lexical?.get("${mode}_complete")?.jsonPrimitive?.booleanOrNull != true
        val active = kotlinx.coroutines.currentCoroutineContext()
        fun checkpoint() {
            active.ensureActive()
            if ((System.nanoTime() - startedNanos) / 1_000_000 >= 12_000) throw SearchPageDeadline()
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
            if ((System.nanoTime() - startedNanos) / 1_000_000 >= 12_000) return SearchMatches(matches, incompleteSymbols, deadlineReached = true)
            val confidence = if (symbolMode) {
                lines[index + 1] ?: 0.0
            } else {
                try {
                    options.score(line, query) {
                        active.ensureActive()
                        if ((System.nanoTime() - startedNanos) / 1_000_000 >= 12_000) throw SearchPageDeadline()
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

    private suspend fun read(context: GitHubTaskSnapshot, args: JsonObject): JsonObject {
        val ranges = args["ranges"] as? JsonArray ?: JsonArray(listOf(args))
        require(ranges.size in 1..8) { "Read between one and eight ranges." }
        val entries = tree(context)["tree"]!!.jsonArray.map { it.jsonObject }.associateBy { it.s("path") }
        fun selectedEntry(path: String): JsonObject {
            GitHubWorkspaceClient.filePath(path)
            val entry = entries[path] ?: run {
                val available = GitHubSearchContext.missingPathCandidates(path, entries.values.filter { it.s("type") == "blob" }.map { it.s("path") })
                throw IllegalArgumentException("Path is not in the pinned tree: $path. Use github_search mode=paths to discover exact paths." + if (available.isEmpty()) "" else " Candidate files in the pinned tree (verify before reading): ${available.joinToString()}")
            }
            require(entry.s("mode") in setOf("", "100644", "100755") && entry.s("type") == "blob") { "Only regular text files can be read." }
            return entry
        }
        val selected = ranges.map { it.jsonObject.s("path") }.distinct().associateWith(::selectedEntry)
        val permits = Semaphore(2)
        val sources = coroutineScope {
            selected.map { (path, entry) -> async { permits.withPermit { path to blob(context, entry).s("content") } } }.awaitAll().toMap()
        }
        var remaining = MAX_EXCERPT_BYTES
        val results = ranges.map { raw ->
            val range = raw.jsonObject
            val path = range.s("path").also { GitHubWorkspaceClient.filePath(it) }
            val entry = selected.getValue(path)
            val content = sources.getValue(path)
            val lines = content.split('\n')
            val start = (range["start_line"]?.jsonPrimitive?.intOrNull ?: 1).also { require(it >= 1) }
            val end = (range["end_line"]?.jsonPrimitive?.intOrNull ?: start + 299).also { require(it >= start && it - start < 2000) }
            val selected = lines.drop(start - 1).take(end - start + 1).joinToString("\n")
            val offset = (range["start_offset"]?.jsonPrimitive?.intOrNull ?: 0).also { require(it in 0..selected.length && (it == 0 || it == selected.length || !Character.isLowSurrogate(selected[it]))) { "start_offset must be a valid UTF-16 character boundary." } }
            val excerpt = utf8Prefix(selected.substring(offset), remaining)
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
                put("start_offset", offset)
                put("content", excerpt)
                put("truncated", truncated || end < lines.size || start > 1 || offset > 0)
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
                            put("commit_sha", context.commitSha)
                        }
                    )
                }
            }
        }
        val contextGraph = withContext(Dispatchers.Default) { GitHubCodeContext.build(sources, entries.keys.toList(), context.commitSha) }
        val evidenceId = GitHubRepositoryStateStore.digest(scope + context.commitSha + results.toString())
        val evidence = buildJsonObject {
            put("owner", context.owner)
            put("repo", context.repo)
            put("commit_sha", context.commitSha)
            put("tree_sha", context.treeSha)
            put("ranges", ranges)
            put("items", JsonArray(results))
            put("context_graph", contextGraph)
        }
        store.write(scope, "evidence:$evidenceId", evidence)
        retainSourceDescriptor(evidenceId, evidence)
        updateSnapshot { pinned ->
            pinned.copy(evidenceHandles = (pinned.evidenceHandles + evidenceId).distinct().takeLast(128), updatedAt = System.currentTimeMillis())
        }
        return buildJsonObject {
            put("evidence_id", evidenceId)
            put("commit_sha", context.commitSha)
            put("tree_sha", context.treeSha)
            put("items", JsonArray(results))
            put("context_graph", contextGraph)
            put("concurrent_blob_limit", 2)
            put("excerpt_bytes", MAX_EXCERPT_BYTES - remaining)
        }
    }

    private suspend fun stage(context: GitHubTaskSnapshot, args: JsonObject): JsonObject {
        require(snapshot()?.commitSha == context.commitSha) { "Select the intended task revision before staging a patch." }
        val expected = args.s("expected_head_sha").ifBlank { context.commitSha }
        require(expected == context.commitSha) { "Patch expected head must equal the pinned task commit." }
        require(args.s("branch").isBlank() || args.s("branch") == context.ref) { "Patch branch must match the selected task branch." }
        val compound = "target_file" in args || "search_snippet" in args || "replacement_snippet" in args
        require(!compound || "edits" !in args) { "Use either a target_file replacement or edits, not both." }
        val edits = if (compound) {
            val path = args.s("target_file").also { GitHubWorkspaceClient.filePath(it) }
            val search = args["search_snippet"]?.jsonPrimitive?.takeIf { it.isString }?.content ?: error("search_snippet is required.")
            val replacement = args["replacement_snippet"]?.jsonPrimitive?.takeIf { it.isString }?.content ?: error("replacement_snippet is required.")
            require(search.isNotBlank() && search != replacement) { "Supply a non-empty exact anchor and a changed replacement." }
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
        val entries = tree(context)["tree"]!!.jsonArray.map { it.jsonObject }.associateBy { it.s("path") }
        val originals = edits.map { it.jsonObject.s("path") }.distinct().associateWith { path ->
            val entry = entries[path] ?: error("Patch path does not exist in the pinned tree: $path")
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
        updateSnapshot { pinned ->
            require(pinned.owner.equals(context.owner, true) && pinned.repo.equals(context.repo, true) && pinned.commitSha == context.commitSha && pinned.ref == context.ref) { "Task revision changed while preparing the patch; review and restage it." }
            pinned.copy(stagedPatchId = id, updatedAt = System.currentTimeMillis())
        }
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
        updateSnapshot { current ->
            require(current.commitSha == context.commitSha && current.ref == context.ref) { "Task changed during stage restore." }
            current.copy(stagedPatchId = id, updatedAt = System.currentTimeMillis())
        }
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

    private suspend fun commitStage(context: GitHubTaskSnapshot, args: JsonObject): JsonElement {
        val id = args.s("stage_id")
        require(id.isNotBlank())
        val staged = store.read(scope, "stage:$id") ?: error("Patch stage is unavailable or belongs to another task/account.")
        require(staged.s("owner").equals(context.owner, true) && staged.s("repo").equals(context.repo, true))
        staged["commit_receipt"]?.let { return it }
        staged["engineering_review_receipt"]?.let { return it }
        require(args.s("expected_head_sha") == staged.s("expected_head_sha")) { "The supplied expected_head_sha differs from the staged patch; refresh the branch and stage evidence before committing." }
        require(staged.s("expected_head_sha") == context.commitSha) { "Task revision changed; restage the reviewed patch." }
        require(staged.s("branch").isBlank() || staged.s("branch") == context.ref) { "Stage belongs to a different working branch." }
        val commitArgs = JsonObject(args + mapOf("files" to staged.getValue("files"), "expected_head_sha" to staged.getValue("expected_head_sha")))
        val root = root(context.owner, context.repo)
        val result = executions.run("commit_staged_patch", args, reconcile = { reconcileCommit(root, commitArgs) }) {
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
        val page = (args["page"]?.jsonPrimitive?.intOrNull ?: 1).also { require(it in 1..1000) }
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
            put("base_sha", base.commitSha)
            put("head_sha", context.commitSha)
            put("files_total", changes.size)
            put(
                "files",
                JsonArray(
                    changes.drop((page - 1) * 100).take(100).map { path ->
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
            put("has_more", page * 100 < changes.size)
            put("files_truncated", page * 100 < changes.size)
            put("tree_complete", oldTree["truncated"]?.jsonPrimitive?.booleanOrNull != true && newTree["truncated"]?.jsonPrimitive?.booleanOrNull != true)
            if (page * 100 < changes.size) {
                put(
                    "continuation",
                    buildJsonObject {
                        put("action", "changed_since")
                        put("base", base.commitSha)
                        put("commit_sha", context.commitSha)
                        put("page", page + 1)
                        put("include_patches", includePatches)
                    }
                )
            }
        }
    }

    companion object {
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
