package dev.chungjungsoo.gptmobile.data.github

import java.util.Base64
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
    private val commit: suspend (String, JsonObject) -> JsonElement
) {
    private val scope = listOf(accountId, connectionUid, conversationId, taskId).joinToString(":")
    private val conversationScope = listOf(accountId, connectionUid, conversationId).joinToString(":")
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
        val result = buildJsonObject {
            put("sha", sha)
            put("content", text)
            put("bytes", bytes.size)
            put("content_hash", GitHubRepositoryStateStore.digest(text))
            put("index", GitHubLocalIndex.build(text))
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
            val retained = store.read(scope, "evidence:${args.s("evidence_id")}") ?: error("Evidence handle expired or belongs to another task/account.")
            require(retained.s("owner").equals(owner, true) && retained.s("repo").equals(repo, true)) { "Evidence belongs to another repository." }
            val requested = JsonObject(args + mapOf("commit_sha" to retained.getValue("commit_sha"), "ranges" to (args["ranges"] ?: retained.getValue("ranges"))))
            return read(resolve(owner, repo, requested), requested)
        }
        val requested = if (action == "changed_since" && args.s("head").isNotBlank()) {
            val head = request("${root(owner, repo)}/commits/${GitHubWorkspaceClient.segment(args.s("head"))}").jsonObject.s("sha")
            JsonObject(args + ("commit_sha" to JsonPrimitive(head)))
        } else {
            args
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
            "search_repository" -> search(context, args)
            "read_ranges" -> read(context, args)
            "stage_patch" -> stage(context, args)
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

    private suspend fun search(context: GitHubTaskSnapshot, args: JsonObject): JsonObject {
        val query = args.s("query")
        require(query.isNotBlank() && query.length <= 200)
        val mode = args.s("mode").ifBlank { "literal" }
        require(mode in setOf("literal", "definitions", "references", "paths", "tests"))
        val data = tree(context)
        val all = data["tree"]!!.jsonArray.map { it.jsonObject }.filter { item -> item.s("type") == "blob" && (mode != "tests" || item.s("path").contains("test", true)) && (args.s("path_prefix").isBlank() || item.s("path").startsWith(args.s("path_prefix"))) }.sortedBy { it.s("path") }
        val cursor = args.s("index_cursor").ifBlank { "0" }.toInt().also { require(it in 0..all.size) }
        var next = cursor
        var fetched = 0
        var skipped = 0
        var incompleteSymbols = false
        val hits = mutableListOf<JsonObject>()
        while (next < all.size && next - cursor < 2000 && hits.size < 20) {
            val item = all[next]
            if (mode == "paths") {
                next++
                if (item.s("path").contains(query, true)) {
                    hits += buildJsonObject {
                        put("path", item["path"]!!)
                        put("sha", item["sha"]!!)
                    }
                }
                continue
            }
            val cached = store.read(blobScope(context), "blob:${item.s("sha")}")
            if (cached == null && fetched >= 24) break
            next++
            if ((item["size"]?.jsonPrimitive?.longOrNull ?: 0) > MAX_BLOB_BYTES) {
                skipped++
                continue
            }
            val content = try {
                if (cached == null) fetched++
                cached ?: blob(context, item)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: IllegalArgumentException) {
                skipped++
                continue
            } catch (_: java.nio.charset.CharacterCodingException) {
                skipped++
                continue
            }
            val text = content.s("content")
            val lexical = content["index"]?.jsonObject
            val lines = if (mode in setOf("definitions", "references")) {
                if (lexical?.get("${mode}_complete")?.jsonPrimitive?.booleanOrNull != true) incompleteSymbols = true
                lexical?.get(mode)?.jsonArray.orEmpty().map { it.jsonObject }.filter { it.s("name") == query }.mapNotNull { it["line"]?.jsonPrimitive?.intOrNull }.toSet()
            } else {
                emptySet()
            }
            val matches = text.lineSequence().mapIndexedNotNull { index, line ->
                if (if (mode in setOf("definitions", "references")) index + 1 in lines else line.contains(query, true)) {
                    buildJsonObject {
                        put("line", index + 1)
                        put("text", utf8Prefix(line, 500))
                    }
                } else {
                    null
                }
            }.take(10).toList()
            if (matches.isNotEmpty()) {
                hits += buildJsonObject {
                    put("path", item["path"]!!)
                    put("sha", item["sha"]!!)
                    put("matches", JsonArray(matches))
                }
            }
        }
        return buildJsonObject {
            put("query", query)
            put("mode", mode)
            put("commit_sha", context.commitSha)
            put("tree_sha", context.treeSha)
            put("items", JsonArray(hits))
            put("returned", hits.size)
            put("scanned_files", next - cursor)
            put("new_blobs", fetched)
            put("total_candidate_files", all.size)
            put("skipped_files", skipped)
            put("symbols_incomplete", incompleteSymbols)
            put("has_more", next < all.size)
            put("complete", next == all.size && cursor == 0 && skipped == 0 && !incompleteSymbols && data["truncated"]?.jsonPrimitive?.booleanOrNull != true)
            put("coverage", "Query coverage is limited to the scanned interval and retained lexical positions.")
            if (next < all.size) {
                put(
                    "continuation",
                    buildJsonObject {
                        put("action", "search_repository")
                        put("commit_sha", context.commitSha)
                        put("index_cursor", next)
                        put("query", query)
                        put("mode", mode)
                        put("path_prefix", args.s("path_prefix"))
                    }
                )
            }
        }
    }

    private suspend fun read(context: GitHubTaskSnapshot, args: JsonObject): JsonObject {
        val ranges = args["ranges"] as? JsonArray ?: JsonArray(listOf(args))
        require(ranges.size in 1..8) { "Read between one and eight ranges." }
        val entries = tree(context)["tree"]!!.jsonArray.map { it.jsonObject }.associateBy { it.s("path") }
        var remaining = MAX_EXCERPT_BYTES
        val results = ranges.map { raw ->
            val range = raw.jsonObject
            val path = range.s("path").also { GitHubWorkspaceClient.filePath(it) }
            val entry = entries[path] ?: error("Path is not in the pinned tree: $path")
            require(entry.s("mode") in setOf("", "100644", "100755") && entry.s("type") == "blob") { "Only regular text files can be read." }
            val content = blob(context, entry).s("content")
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
        val evidenceId = GitHubRepositoryStateStore.digest(scope + context.commitSha + results.toString())
        store.write(
            scope,
            "evidence:$evidenceId",
            buildJsonObject {
                put("owner", context.owner)
                put("repo", context.repo)
                put("commit_sha", context.commitSha)
                put("tree_sha", context.treeSha)
                put("ranges", ranges)
                put("items", JsonArray(results))
            }
        )
        updateSnapshot { pinned ->
            pinned.copy(evidenceHandles = (pinned.evidenceHandles + evidenceId).distinct().takeLast(128), updatedAt = System.currentTimeMillis())
        }
        return buildJsonObject {
            put("evidence_id", evidenceId)
            put("commit_sha", context.commitSha)
            put("tree_sha", context.treeSha)
            put("items", JsonArray(results))
            put("excerpt_bytes", MAX_EXCERPT_BYTES - remaining)
        }
    }

    private suspend fun stage(context: GitHubTaskSnapshot, args: JsonObject): JsonObject {
        require(snapshot()?.commitSha == context.commitSha) { "Select the intended task revision before staging a patch." }
        val expected = args.s("expected_head_sha").ifBlank { context.commitSha }
        require(expected == context.commitSha) { "Patch expected head must equal the pinned task commit." }
        val edits = args["edits"] as? JsonArray ?: error("edits is required.")
        val entries = tree(context)["tree"]!!.jsonArray.map { it.jsonObject }.associateBy { it.s("path") }
        val originals = edits.map { it.jsonObject.s("path") }.distinct().associateWith { path ->
            val entry = entries[path] ?: error("Patch path does not exist in the pinned tree: $path")
            require(entry.s("mode") in setOf("100644", "100755")) { "Patch may only replace regular text blobs." }
            entry.s("sha") to blob(context, entry).s("content")
        }
        val files = GitHubPatchStager.apply(originals, edits)
        val id = GitHubRepositoryStateStore.digest(scope + context.commitSha + files.toString())
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
        store.write(
            scope,
            "stage:$id",
            buildJsonObject {
                put("owner", context.owner)
                put("repo", context.repo)
                put("expected_head_sha", expected)
                put("files", files)
                put("preview", preview)
            }
        )
        updateSnapshot { pinned ->
            require(pinned.owner.equals(context.owner, true) && pinned.repo.equals(context.repo, true) && pinned.commitSha == context.commitSha && pinned.ref == context.ref) { "Task revision changed while preparing the patch; review and restage it." }
            pinned.copy(stagedPatchId = id, updatedAt = System.currentTimeMillis())
        }
        return buildJsonObject {
            put("stage_id", id)
            put("expected_head_sha", expected)
            put("preview", preview)
            put("files_changed", files.size)
            put("committed", false)
        }
    }

    private suspend fun commitStage(context: GitHubTaskSnapshot, args: JsonObject): JsonElement {
        val id = args.s("stage_id")
        require(id.isNotBlank())
        val staged = store.read(scope, "stage:$id") ?: error("Patch stage is unavailable or belongs to another task/account.")
        require(staged.s("owner").equals(context.owner, true) && staged.s("repo").equals(context.repo, true))
        staged["commit_receipt"]?.let { return it }
        staged["engineering_review_receipt"]?.let { return it }
        require(staged.s("expected_head_sha") == context.commitSha) { "Task revision changed; restage the reviewed patch." }
        val result = commit(root(context.owner, context.repo), JsonObject(args + mapOf("files" to staged.getValue("files"), "expected_head_sha" to staged.getValue("expected_head_sha"))))
        val receiptKey = if (result.jsonObject["sha"] != null) "commit_receipt" else "engineering_review_receipt"
        store.write(scope, "stage:$id", JsonObject(staged + (receiptKey to result)))
        return result
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
