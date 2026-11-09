package dev.chungjungsoo.gptmobile.data.github

import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Repository identity, source revision and copy semantics are independent of the open workspace. */
internal class GitHubRepositoryLifecycle(
    private val read: suspend (String) -> JsonObject,
    private val write: suspend (String, String, JsonObject) -> JsonObject,
    private val gateway: (suspend (JsonObject) -> JsonObject)? = null,
    private val loadPlan: suspend (String) -> JsonObject? = { null },
    private val savePlan: suspend (String, JsonObject) -> Unit = { _, _ -> }
) {
    companion object {
        val writes = setOf("create_repository", "fork_repository", "import_repository", "clone_repository")
        val actions = writes + setOf("repository_lifecycle_status", "repository_lifecycle_capabilities")
        private val sha = Regex("[a-fA-F0-9]{40}")
        private const val MAX_FILES = 2000
        private const val MAX_BYTES = 32L * 1024 * 1024

        fun intent(request: String): String? {
            val text = GitHubTaskContinuity.request(request).lowercase(Locale.ROOT)
                .replace(Regex("(?s)```.*?(?:```|\\z)|~~~.*?(?:~~~|\\z)"), " ")
                .lineSequence().filterNot { it.trimStart().startsWith(">") }.joinToString("\n")
            if (Regex("^\\s*(?:please\\s+)?(?:explain|describe|show me how|how|what|why)\\b").containsMatchIn(text) &&
                !Regex("\\b(?:and then|then|also)\\s+(?:please\\s+)?(?:create|fork|import|clone)\\b").containsMatchIn(text)
            ) {
                return null
            }
            if (Regex("\\b(?:do not|don't|never)\\s+(?:create|fork|import|clone)\\b").containsMatchIn(text)) return null
            return when {
                Regex("\\bclone\\b.{0,120}\\b(?:gateway|computer|local workspace|locally)\\b").containsMatchIn(text) -> "clone_repository"
                Regex("\\bfork\\s+(?:(?:the|this|a|github|upstream|source)\\s+)*(?:repo(?:sitory)?\\b|https://github\\.com/|[a-z0-9_.-]+/[a-z0-9_.-]+)").containsMatchIn(text) -> "fork_repository"
                Regex("\\b(?:import|duplicate|copy)\\b.{0,120}\\b(?:repo(?:sitory)?|project)\\b|\\bcreate\\b.{0,80}\\b(?:repo(?:sitory)?)\\b.{0,100}\\bfrom\\b").containsMatchIn(text) -> "import_repository"
                Regex("\\bcreate\\s+(?:(?:a|the|new|empty|github|private|public|independent)\\s+)*repo(?:sitory)?\\b").containsMatchIn(text) -> "create_repository"
                else -> null
            }
        }

        fun capabilities(gatewayAvailable: Boolean) = buildJsonObject {
            put("schema", "github.repository-capabilities.v1")
            put("create", true)
            put("fork", true)
            put("snapshot_import", true)
            put("history_import", gatewayAvailable)
            put("gateway_clone", gatewayAvailable)
            put("snapshot_max_files", MAX_FILES)
            put("snapshot_max_bytes", MAX_BYTES)
            put("destination_selection", "EXPLICIT_OWNER_NAME_VISIBILITY")
            put("history_requirement", "A qualified gateway with Git is required for complete branch and tag history.")
        }
    }

    suspend fun execute(action: String, args: JsonObject): JsonObject {
        require(action in actions)
        if (action == "repository_lifecycle_capabilities") {
            val remote = try {
                gateway?.invoke(buildJsonObject { put("action", action) })
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            return JsonObject(capabilities(false) + remote.orEmpty().filterKeys { it in setOf("history_import", "gateway_clone") })
        }
        if (action == "repository_lifecycle_status" && args.text("operation_id").isNotBlank()) {
            return requireNotNull(gateway) { "Restore the original engineering gateway to inspect this repository operation." }.invoke(JsonObject(args + ("action" to JsonPrimitive(action))))
        }
        if (action == "clone_repository" || action == "import_repository" && args.text("copy_mode") == "history") {
            val upstream = source(args)
            require(args.text("source_ref").isNotBlank()) { "Choose the exact source_ref before submitting a transfer." }
            if (action == "import_repository") require(!upstream.fullName.equals(destination(args).fullName, true)) { "Source and destination must differ." }
            return gateway?.invoke(JsonObject(args + ("action" to JsonPrimitive(action)))) ?: buildJsonObject {
                put("schema", "github.repository-lifecycle.v1")
                put("status", "BLOCKED")
                put("error_code", "GITHUB_GATEWAY_CAPABILITY_REQUIRED")
                put("dispatched", false)
                put("remote_mutation_performed", false)
                put("remote_state_verified", false)
                put("message", "Select an online engineering gateway with repository transfer support. History imports and local clones require Git on that host; snapshot import is a separate explicit choice.")
                put("next_action", "Configure the engineering gateway or explicitly choose copy_mode=snapshot; no repository was created.")
            }
        }
        val target = destination(args)
        if (action == "repository_lifecycle_status") {
            val id = (args["expected_repository_id"] as? JsonPrimitive)?.longOrNull
            require(id != null && id > 0) { "Use expected_repository_id from the retained creation receipt; existence alone does not prove this task created a repository." }
            val receipt = loadPlan("lifecycle-receipt:$id")
            val original = receipt?.get("arguments") as? JsonObject
            require(original != null && receipt.text("operation") == args.text("operation") && destination(original) == target) { "The repository ID is not bound to this conversation's retained creation receipt. Read its metadata or recover the original operation; existence alone is not creation proof." }
            if (args.text("operation") == "import_repository") return snapshotStatus(target, id, original, receipt.text("snapshot_plan_key"))
            val observed = read(target.root())
            return verified(receipt.text("operation"), target, observed, id, original)
        }
        val source = if (action == "create_repository") {
            null
        } else {
            source(args).also {
                require(!it.fullName.equals(target.fullName, true)) { "Source and destination must be different repositories." }
            }
        }
        val sourceRepository = source?.let { read(it.root()) }
        if (action == "fork_repository") {
            require(sourceRepository?.get("private")?.let { (it as? JsonPrimitive)?.booleanOrNull } == (target.visibility == "private")) {
                "A fork retains its source visibility. Choose that visibility, or request an independent import."
            }
            val forkArguments = JsonObject(args + ("source_repository_id" to JsonPrimitive(requireNotNull(sourceRepository).requiredId())))
            authorizeOwner(target)
            val created = write(
                "POST",
                requireNotNull(source).root() + "/forks",
                buildJsonObject {
                    put("name", target.repo)
                    if (target.ownerType == "organization") put("organization", target.owner)
                    put("default_branch_only", args["default_branch_only"] ?: JsonPrimitive(false))
                }
            )
            val id = created.requiredId()
            retainCreation(action, id, forkArguments)
            // GitHub accepts forks asynchronously. A 202 receipt is not verified Git data.
            return try {
                verified(action, target, read(target.root()), id, forkArguments)
            } catch (missing: GitHubRequestRejected) {
                if (missing.status != 404) throw missing
                pending(action, target, id, source.fullName)
            } catch (missing: GitHubReadValidation) {
                // A cached absence is normal immediately after an accepted asynchronous fork.
                if (missing.code != "GITHUB_REPOSITORY_UNAVAILABLE" || missing.details["observed_http_status"] != JsonPrimitive(404)) throw missing
                pending(action, target, id, source.fullName)
            }
        }
        if (action == "import_repository") {
            require(args.text("copy_mode") == "snapshot") { "Choose copy_mode=snapshot or history explicitly; importing content does not preserve commit history." }
            return snapshotImport(target, requireNotNull(source), args)
        }
        authorizeOwner(target)
        val created = create(target, initialize = args["auto_init"] == JsonPrimitive(true), description = args.text("description"))
        retainCreation(action, created.requiredId(), args)
        return verified(action, target, read(target.root()), created.requiredId(), args)
    }

    private suspend fun retainCreation(action: String, id: Long, args: JsonObject, planKey: String? = null) {
        val record = buildJsonObject {
            put("operation", action)
            put("repository_id", id)
            put("arguments", JsonObject(args.filterKeys { it in setOf("destination_owner", "destination_repo", "owner_type", "visibility", "source_owner", "source_repo", "source_ref", "source_repository_id", "copy_mode") }))
            planKey?.let { put("snapshot_plan_key", it) }
        }
        savePlan("lifecycle-receipt:$id", record)
    }

    private suspend fun snapshotStatus(target: Target, id: Long, original: JsonObject, key: String): JsonObject {
        require(key.isNotBlank()) { "The snapshot import plan was not retained; recover the original operation." }
        val plan = requireNotNull(loadPlan("$key:destination")) { "Recover the retained snapshot plan before verifying completion." }
        val commit = requireNotNull(loadPlan("$key:commit")) { "No retained imported commit exists yet. Continue the original import from its saved checkpoint." }
        val branch = plan.text("branch")
        val head = read(target.root() + "/commits/" + GitHubWorkspaceClient.segment(branch))
        require(head.text("sha") == commit.text("destination_commit_sha") && ((head["commit"] as? JsonObject)?.get("tree") as? JsonObject)?.text("sha") == plan.text("source_tree_sha")) { "The destination no longer matches the retained imported snapshot." }
        return JsonObject(
            verified("import_repository", target, read(target.root()), id, original) + buildJsonObject {
                put("copy_mode", "snapshot")
                put("history_preserved", false)
                put("source_repository", source(original).fullName)
                put("source_commit_sha", plan.text("source_commit_sha"))
                put("source_tree_sha", plan.text("source_tree_sha"))
                put("destination_tree_sha", plan.text("source_tree_sha"))
                put("destination_commit_sha", commit.text("destination_commit_sha"))
            }
        )
    }

    private data class Target(val owner: String, val repo: String, val ownerType: String = "", val visibility: String = "") {
        val fullName get() = "$owner/$repo"
        fun root() = "/repos/${GitHubWorkspaceClient.segment(owner)}/${GitHubWorkspaceClient.segment(repo)}"
    }

    private fun destination(args: JsonObject): Target {
        val (owner, repo) = normalizeGitHubRepository(args.text("destination_owner"), args.text("destination_repo"))
        require(!owner.isNullOrBlank() && !repo.isNullOrBlank() && repo.length <= 100) { "Provide the explicit destination_owner and destination_repo." }
        val type = args.text("owner_type")
        val visibility = args.text("visibility")
        require(type in setOf("user", "organization")) { "owner_type must be user or organization." }
        require(visibility in setOf("public", "private")) { "Choose visibility=public or private explicitly." }
        return Target(owner, repo, type, visibility)
    }

    private fun source(args: JsonObject): Target {
        val (owner, repo) = normalizeGitHubRepository(args.text("source_owner"), args.text("source_repo"))
        require(!owner.isNullOrBlank() && !repo.isNullOrBlank()) { "Provide source_owner and source_repo separately from the destination." }
        return Target(owner, repo)
    }

    private suspend fun authorizeOwner(target: Target) {
        if (target.ownerType == "user") require(read("/user").text("login").equals(target.owner, true)) { "A personal repository must belong to the authenticated user. Select the intended account or use owner_type=organization." }
    }

    private suspend fun create(target: Target, initialize: Boolean, description: String = ""): JsonObject = write(
        "POST",
        if (target.ownerType == "organization") "/orgs/${GitHubWorkspaceClient.segment(target.owner)}/repos" else "/user/repos",
        buildJsonObject {
            put("name", target.repo)
            put("private", target.visibility == "private")
            put("auto_init", initialize)
            if (description.isNotBlank()) put("description", description.take(350))
        }
    )

    private suspend fun snapshotImport(target: Target, source: Target, args: JsonObject): JsonObject {
        val sourceRef = args.text("source_ref").also { require(it.isNotBlank()) { "Pin source_ref before copying a project." } }
        val key = "snapshot-import:" + GitHubRepositoryStateStore.digest("${target.fullName.lowercase(Locale.ROOT)}|${target.visibility}|${source.fullName.lowercase(Locale.ROOT)}|$sourceRef")
        val sourcePlan = loadPlan("$key:source")
        val plan = loadPlan("$key:destination")
        val commit = read(source.root() + "/commits/" + GitHubWorkspaceClient.segment(sourcePlan?.text("source_commit_sha") ?: sourceRef))
        val sourceSha = commit.text("sha").also { require(sha.matches(it)) { "Source commit is not qualified." } }
        require(args["source_commit_sha"] == null || args.text("source_commit_sha").equals(sourceSha, true)) { "The source_ref no longer resolves to the explicitly accepted source_commit_sha; no destination was created." }
        val treeSha = ((commit["commit"] as? JsonObject)?.get("tree") as? JsonObject)?.text("sha").orEmpty()
        require(sha.matches(treeSha)) { "Source tree is not qualified." }
        val tree = read(source.root() + "/git/trees/$treeSha?recursive=1")
        require(tree["truncated"] == JsonPrimitive(false)) { "The source tree is incomplete. Use a qualified gateway history import; no repository was created." }
        val entries = requireNotNull(tree["tree"] as? JsonArray) { "The source tree is unavailable." }
        require(entries.all { it is JsonObject }) { "The source tree contract is invalid." }
        val rows = entries.filterIsInstance<JsonObject>().filter { it.text("type") != "tree" }
        require(rows.isNotEmpty() && rows.size <= MAX_FILES) { "Snapshot import needs 1..$MAX_FILES complete source entries; use gateway history import for this source." }
        require(rows.map { it.text("path") }.toSet().size == rows.size) { "The source tree contains duplicate paths." }
        var sourceBytes = 0L
        rows.forEach { row ->
            GitHubWorkspaceClient.filePath(row.text("path"))
            require(row.text("mode") in setOf("100644", "100755", "120000", "160000") && sha.matches(row.text("sha"))) { "Unsupported source tree entry." }
            require(row.text("type") == if (row.text("mode") == "160000") "commit" else "blob") { "Source tree entry type does not match its mode." }
            if (row.text("type") == "blob") {
                val size = (row["size"] as? JsonPrimitive)?.longOrNull
                require(size != null && size in 0..4 * 1024 * 1024) { "A source blob exceeds the native import bound; use gateway history import." }
                sourceBytes += size
            }
        }
        require(sourceBytes <= MAX_BYTES) { "Source exceeds the native snapshot budget; use gateway history import. No repository was created." }
        // Qualify every blob before creating the destination. Native snapshots
        // cannot silently leave Git LFS pointers without their backing objects.
        val blobs = rows.filter { it.text("type") == "blob" }.distinctBy { it.text("sha") }.associate { row ->
            val blob = read(source.root() + "/git/blobs/" + row.text("sha"))
            require(blob.text("sha") == row.text("sha") && blob.text("encoding") == "base64") { "Source blob identity or encoding changed; no destination was created." }
            val bytes = java.util.Base64.getMimeDecoder().decode(blob.text("content"))
            require(bytes.size.toLong() == (row["size"] as? JsonPrimitive)?.longOrNull) { "Source blob size differs from its qualified tree entry." }
            val digest = java.security.MessageDigest.getInstance("SHA-1").apply {
                update("blob ${bytes.size}\u0000".toByteArray(Charsets.UTF_8))
                update(bytes)
            }.digest().joinToString("") { "%02x".format(it) }
            require(digest == row.text("sha")) { "The source blob does not match its Git object identity." }
            require(!bytes.take(128).toByteArray().toString(Charsets.UTF_8).startsWith("version https://git-lfs.github.com/spec/v1")) { "This source uses Git LFS. Use a qualified gateway history import to transfer its objects; no destination was created." }
            row.text("sha") to blob
        }
        val pinnedSource = buildJsonObject {
            put("source_repository", source.fullName)
            put("source_commit_sha", sourceSha)
            put("source_tree_sha", treeSha)
            put("destination_repository", target.fullName)
            put("visibility", target.visibility)
        }
        require(sourcePlan == null || sourcePlan == pinnedSource) { "The accepted source plan changed." }
        // Source and destination intent must survive a crash immediately after POST.
        savePlan("$key:source", pinnedSource)
        authorizeOwner(target)
        val created = create(target, initialize = true)
        val id = created.requiredId()
        retainCreation("import_repository", id, args, key)
        val observed = read(target.root())
        require(observed.requiredId() == id && observed.text("full_name").equals(target.fullName, true)) { "Created repository identity changed." }
        val branch = (plan?.text("branch") ?: observed.text("default_branch")).also { require(it.isNotBlank()) }
        if (sourcePlan != null && plan == null && !sha.matches(args.text("expected_head_sha"))) {
            return buildJsonObject {
                put("schema", "github.repository-lifecycle.v1")
                put("operation", "import_repository")
                put("status", "BLOCKED")
                put("error_code", "GITHUB_IMPORT_BASE_CONFIRMATION_REQUIRED")
                put("repository_id", id)
                put("source_repository", source.fullName)
                put("source_commit_sha", sourceSha)
                put("destination_repository", target.fullName)
                put("remote_mutation_performed", true)
                put("remote_state_verified", false)
                put("message", "The destination was created but its original import base was not retained. Inspect its current head and explicitly supply expected_head_sha before resuming; no content was overwritten and the source revision is still pinned.")
            }
        }
        val observedHead = if (plan == null) (read(target.root() + "/git/ref/heads/" + GitHubWorkspaceClient.segment(branch))["object"] as? JsonObject)?.text("sha").orEmpty() else ""
        if (plan == null && sourcePlan != null) require(observedHead == args.text("expected_head_sha")) { "Destination changed since its recovery base was reviewed." }
        val parent = plan?.text("initial_head_sha") ?: observedHead
        require(sha.matches(parent))
        if (plan == null && sourcePlan == null) {
            val initial = read(target.root() + "/git/commits/$parent")
            require((initial["parents"] as? JsonArray)?.isEmpty() == true) { "The newly created destination already has additional history. Review its head before importing; no content was overwritten." }
        }
        val acceptedPlan = buildJsonObject {
            put("repository_id", id)
            put("source_commit_sha", sourceSha)
            put("source_tree_sha", treeSha)
            put("initial_head_sha", parent)
            put("branch", branch)
        }
        require(plan == null || plan == acceptedPlan) { "The retained import plan no longer matches its source or destination." }
        savePlan("$key:destination", acceptedPlan)
        val copied = rows.map { row ->
            if (row.text("type") == "blob") {
                val blob = blobs.getValue(row.text("sha"))
                val written = write(
                    "POST",
                    target.root() + "/git/blobs",
                    buildJsonObject {
                        put("content", blob.getValue("content"))
                        put("encoding", "base64")
                    }
                )
                require(written.text("sha") == row.text("sha")) { "Copied blob does not match its source." }
            }
            JsonObject(row.filterKeys { it in setOf("path", "mode", "type", "sha") })
        }
        val copiedTree = write("POST", target.root() + "/git/trees", buildJsonObject { put("tree", JsonArray(copied)) })
        require(copiedTree.text("sha") == treeSha) { "Imported tree differs from the exact source snapshot." }
        val importedCommit = write(
            "POST",
            target.root() + "/git/commits",
            buildJsonObject {
                put("message", "Import snapshot from ${source.fullName} at $sourceSha")
                put("tree", treeSha)
                put("parents", JsonArray(listOf(JsonPrimitive(parent))))
            }
        )
        val importedSha = importedCommit.text("sha").also { require(sha.matches(it)) }
        savePlan("$key:commit", buildJsonObject { put("destination_commit_sha", importedSha) })
        // The non-force update fails if another writer moved the new repository.
        write(
            "PATCH",
            target.root() + "/git/refs/heads/" + GitHubWorkspaceClient.segment(branch),
            buildJsonObject {
                put("sha", importedSha)
                put("force", false)
            }
        )
        val verifiedHead = read(target.root() + "/commits/" + GitHubWorkspaceClient.segment(branch))
        require(verifiedHead.text("sha") == importedSha && ((verifiedHead["commit"] as? JsonObject)?.get("tree") as? JsonObject)?.text("sha") == treeSha) { "The destination does not contain the verified imported snapshot." }
        return JsonObject(
            verified("import_repository", target, read(target.root()), id, args) + buildJsonObject {
                put("copy_mode", "snapshot")
                put("history_preserved", false)
                put("source_repository", source.fullName)
                put("source_commit_sha", sourceSha)
                put("source_tree_sha", treeSha)
                put("destination_commit_sha", importedSha)
                put("destination_tree_sha", treeSha)
                put("copied_files", rows.size)
                put("copied_bytes", sourceBytes)
            }
        )
    }

    private fun verified(action: String, target: Target, observed: JsonObject, id: Long, args: JsonObject): JsonObject {
        require(observed.requiredId() == id && observed.text("full_name").equals(target.fullName, true)) { "Repository identity does not match the retained creation receipt." }
        require((observed["private"] as? JsonPrimitive)?.booleanOrNull == (target.visibility == "private")) { "Repository visibility differs from the requested destination." }
        if (action == "fork_repository") {
            val upstream = source(args)
            val parent = observed["parent"] as? JsonObject
            require(observed["fork"] == JsonPrimitive(true) && parent?.text("full_name")?.equals(upstream.fullName, true) == true && parent["id"] == args["source_repository_id"]) { "The fork's upstream repository identity and relationship are not verified." }
        } else {
            require(observed["fork"] == JsonPrimitive(false)) { "An independent repository was requested." }
        }
        return buildJsonObject {
            put("schema", "github.repository-lifecycle.v1")
            put("operation", action)
            put("status", "VERIFIED")
            put("repository_id", id)
            put("destination_repository", target.fullName)
            put("visibility", target.visibility)
            put("remote_state_verified", true)
            put("verification_basis", "RETAINED_CREATION_ID_AND_FRESH_REPOSITORY_READ")
            if (action == "fork_repository") {
                put("source_repository", source(args).fullName)
                put("source_repository_id", args.getValue("source_repository_id"))
            }
            put("default_branch", observed.text("default_branch"))
            put("html_url", observed.text("html_url"))
            put("workspace_selection_changed", false)
        }
    }

    private fun pending(action: String, target: Target, id: Long, source: String) = buildJsonObject {
        put("schema", "github.repository-lifecycle.v1")
        put("operation", action)
        put("status", "PENDING")
        put("repository_id", id)
        put("source_repository", source)
        put("destination_repository", target.fullName)
        put("remote_state_verified", false)
        put("next_action", "Use github_repository action=status with expected_repository_id=$id to verify this asynchronous fork; do not create another fork.")
    }
}

private fun JsonObject.text(key: String): String = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
private fun JsonObject.requiredId(): Long = (get("id") as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 } ?: error("GitHub did not return a repository ID.")
