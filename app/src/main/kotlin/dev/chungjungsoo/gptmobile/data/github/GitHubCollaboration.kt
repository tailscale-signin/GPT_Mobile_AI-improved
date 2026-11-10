package dev.chungjungsoo.gptmobile.data.github

import io.ktor.http.HttpMethod
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Every mutation uses the client's existing permission, approval and uncertain-write journal. */
internal object GitHubCollaboration {
    val prActions = setOf("request_reviewers", "remove_reviewers", "resolve_thread", "reopen_thread", "dismiss_review", "edit_review_comment", "edit_comment", "add_labels", "remove_label", "add_assignees", "remove_assignees", "react", "remove_reaction", "lock", "unlock", "enable_auto_merge", "disable_auto_merge")
    val issueActions = setOf("edit_comment", "add_labels", "remove_label", "add_assignees", "remove_assignees", "react", "remove_reaction", "lock", "unlock")
    val writes = prActions.map { "pr_$it" }.toSet() + issueActions.map { "issue_$it" }
    val reads = setOf("pr_threads")

    suspend fun execute(
        root: String,
        action: String,
        args: JsonObject,
        origin: String,
        request: suspend (String, HttpMethod, JsonObject?) -> JsonElement,
        graphQl: suspend (String, JsonObject) -> JsonObject
    ): JsonObject {
        fun text(key: String) = (args[key] as? JsonPrimitive)?.content.orEmpty()
        fun positive(key: String) = text(key).toLongOrNull()?.takeIf { it > 0 } ?: error("A positive $key is required.")
        fun strings(key: String, required: Boolean = true): List<String> {
            val values = args[key] as? JsonArray ?: JsonArray(emptyList())
            require(values.size <= 30 && (!required || values.isNotEmpty())) { "$key requires 1–30 values." }
            return values.map {
                val value = (it as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
                require(value.isNotBlank() && value.length <= 100 && value.none { char -> char.code < 32 }) { "Invalid $key value." }
                value
            }.distinct()
        }
        suspend fun read(path: String) = request(path, HttpMethod.Get, null)
        val isPr = action.startsWith("pr_")
        val operation = action.substringAfter('_')
        val number = positive(if (isPr) "pull_number" else "issue_number")
        val parentPath = "$root/${if (isPr) "pulls" else "issues"}/$number"
        val parent = read(parentPath).jsonObject
        val expected = text(if (isPr) "expected_head_sha" else "expected_updated_at")
        fun unchanged(value: JsonObject): Boolean = if (isPr) {
            value["head"]?.jsonObject?.get("sha") == JsonPrimitive(expected)
        } else {
            value["updated_at"] == JsonPrimitive(expected)
        }
        if (action !in reads) {
            require(expected.isNotBlank() && (!isPr || expected.matches(Regex("[a-fA-F0-9]{40}")))) { "Read the current target and provide its revision guard." }
            check(unchanged(parent)) { "The target changed. Refresh before continuing." }
            if (!isPr) require("pull_request" !in parent) { "Use github_pr for pull requests." }
        }
        val nodeId = parent["node_id"] ?: JsonNull
        val issuePath = "$root/issues/$number"
        var verified = false
        var outcome: JsonElement = JsonNull
        when (operation) {
            "threads" -> {
                val after = text("after").takeIf(String::isNotBlank)
                require(after == null || after.length <= 1024) { "Invalid thread continuation." }
                val data = graphQl(
                    "query Threads(\$id:ID!,\$after:String) { node(id:\$id) { ... on PullRequest { id headRefOid reviewThreads(first:30,after:\$after) { nodes { id isResolved isOutdated path comments(first:1) { nodes { id databaseId body updatedAt } } } pageInfo { hasNextPage endCursor } } } } }",
                    buildJsonObject {
                        put("id", nodeId)
                        put("after", after?.let(::JsonPrimitive) ?: JsonNull)
                    }
                )
                val pr = data["data"]?.jsonObject?.get("node")?.jsonObject ?: error("PR thread response unavailable.")
                return buildJsonObject {
                    put("pull_number", number)
                    put("head_sha", pr["headRefOid"] ?: JsonNull)
                    put("threads", pr["reviewThreads"] ?: JsonNull)
                    put("coverage", "One bounded thread page; follow pageInfo.endCursor using after while hasNextPage is true. Only the first comment of each thread is included.")
                }
            }
            "request_reviewers", "remove_reviewers" -> {
                val reviewers = strings("reviewers", false)
                val teams = strings("team_reviewers", false)
                require(reviewers.isNotEmpty() || teams.isNotEmpty()) { "Specify reviewers or team_reviewers." }
                val path = "$parentPath/requested_reviewers"
                outcome = request(
                    path,
                    if (operation == "request_reviewers") HttpMethod.Post else HttpMethod.Delete,
                    buildJsonObject {
                        put("reviewers", JsonArray(reviewers.map(::JsonPrimitive)))
                        put("team_reviewers", JsonArray(teams.map(::JsonPrimitive)))
                    }
                )
                val observed = read(path).jsonObject
                val usersNow = observed["users"]?.jsonArray.orEmpty().map { it.jsonObject["login"]?.jsonPrimitive?.content }
                val teamsNow = observed["teams"]?.jsonArray.orEmpty().map { it.jsonObject["slug"]?.jsonPrimitive?.content }
                val adding = operation == "request_reviewers"
                verified = reviewers.all { (it in usersNow) == adding } && teams.all { (it in teamsNow) == adding }
            }
            "resolve_thread", "reopen_thread" -> {
                val id = text("thread_id")
                require(id.isNotBlank() && id.length <= 200) { "Use an observed thread_id." }
                suspend fun thread(): JsonObject = graphQl(
                    "query Thread(\$id:ID!) { node(id:\$id) { ... on PullRequestReviewThread { id isResolved pullRequest { id headRefOid } } } }",
                    buildJsonObject { put("id", id) }
                )["data"]?.jsonObject?.get("node")?.jsonObject ?: error("Thread unavailable.")
                val before = thread()
                require(before["pullRequest"]?.jsonObject?.get("id") == nodeId && before["pullRequest"]?.jsonObject?.get("headRefOid") == JsonPrimitive(expected)) { "The thread does not belong to the selected PR head." }
                val resolve = operation == "resolve_thread"
                val mutation = if (resolve) "resolveReviewThread" else "unresolveReviewThread"
                outcome = graphQl("mutation Thread(\$id:ID!) { $mutation(input:{threadId:\$id}) { thread { id isResolved } } }", buildJsonObject { put("id", id) })
                val after = thread()
                verified = after["id"] == JsonPrimitive(id) && after["isResolved"] == JsonPrimitive(resolve) && after["pullRequest"]?.jsonObject?.get("id") == nodeId
            }
            "dismiss_review" -> {
                val review = positive("review_id")
                val message = text("body")
                require(message.isNotBlank() && message.length <= 10000) { "Provide the review dismissal reason." }
                val path = "$parentPath/reviews/$review"
                read(path) // Parent-scoped lookup prevents a foreign review identifier.
                outcome = request(
                    "$path/dismissals",
                    HttpMethod.Put,
                    buildJsonObject {
                        put("message", message)
                        put("event", "DISMISS")
                    }
                )
                verified = read(path).jsonObject["state"] == JsonPrimitive("DISMISSED")
            }
            "edit_comment", "edit_review_comment" -> {
                val comment = positive("comment_id")
                val review = operation == "edit_review_comment"
                val path = "$root/${if (review) "pulls" else "issues"}/comments/$comment"
                val before = read(path).jsonObject
                require(before[if (review) "pull_request_url" else "issue_url"] == JsonPrimitive(origin + if (review) parentPath else issuePath)) { "The comment does not belong to the selected conversation." }
                require(text("expected_comment_updated_at").isNotBlank() && before["updated_at"] == JsonPrimitive(text("expected_comment_updated_at"))) { "The comment changed; read it again before editing." }
                val body = text("body")
                require(body.isNotBlank() && body.length <= 10000) { "Comment body must contain 1–10,000 characters." }
                outcome = request(path, HttpMethod.Patch, buildJsonObject { put("body", body) })
                val after = read(path).jsonObject
                verified = after["id"] == JsonPrimitive(comment) && after["body"] == JsonPrimitive(body)
            }
            "add_labels", "remove_label" -> {
                val labels = if (operation == "add_labels") strings("labels") else listOf(text("label").also { require(it.isNotBlank() && it.length <= 100) })
                val path = "$issuePath/labels"
                outcome = if (operation == "add_labels") request(path, HttpMethod.Post, buildJsonObject { put("labels", JsonArray(labels.map(::JsonPrimitive))) }) else request("$path/${GitHubWorkspaceClient.segment(labels.single())}", HttpMethod.Delete, null)
                val names = read("$path?per_page=100").jsonArray.map { it.jsonObject["name"]?.jsonPrimitive?.content }
                verified = labels.all { (it in names) == (operation == "add_labels") } && (operation == "add_labels" || names.size < 100)
            }
            "add_assignees", "remove_assignees" -> {
                val assignees = strings("assignees")
                outcome = request("$issuePath/assignees", if (operation == "add_assignees") HttpMethod.Post else HttpMethod.Delete, buildJsonObject { put("assignees", JsonArray(assignees.map(::JsonPrimitive))) })
                val names = read(issuePath).jsonObject["assignees"]?.jsonArray.orEmpty().map { it.jsonObject["login"]?.jsonPrimitive?.content }
                verified = assignees.all { (it in names) == (operation == "add_assignees") }
            }
            "react", "remove_reaction" -> {
                val content = text("reaction")
                require(content in setOf("+1", "-1", "laugh", "confused", "heart", "hooray", "rocket", "eyes")) { "Choose a supported reaction." }
                if (operation == "react") {
                    outcome = request("$issuePath/reactions", HttpMethod.Post, buildJsonObject { put("content", content) })
                    verified = outcome.jsonObject["content"] == JsonPrimitive(content) && outcome.jsonObject["id"] != null
                } else {
                    val id = positive("reaction_id")
                    // Delete is scoped by the selected issue/PR. Its 204 is an authoritative deletion receipt.
                    outcome = request("$issuePath/reactions/$id", HttpMethod.Delete, null)
                    verified = true
                }
            }
            "lock", "unlock" -> {
                val lock = operation == "lock"
                val reason = text("lock_reason")
                require(reason.isBlank() || reason in setOf("off-topic", "too heated", "resolved", "spam")) { "Invalid lock reason." }
                outcome = request("$issuePath/lock", if (lock) HttpMethod.Put else HttpMethod.Delete, if (lock) buildJsonObject { if (reason.isNotBlank()) put("lock_reason", reason) } else null)
                verified = read(issuePath).jsonObject["locked"] == JsonPrimitive(lock)
            }
            "enable_auto_merge", "disable_auto_merge" -> {
                val enable = operation == "enable_auto_merge"
                require(!enable || parent["draft"] == JsonPrimitive(false)) { "A draft PR cannot enable auto-merge." }
                val method = text("merge_method").ifBlank { "SQUASH" }.uppercase()
                require(method in setOf("SQUASH", "MERGE", "REBASE")) { "Invalid merge method." }
                val mutation = if (enable) "enablePullRequestAutoMerge" else "disablePullRequestAutoMerge"
                outcome = graphQl(
                    "mutation AutoMerge(\$id:ID!${if (enable) ",\$method:PullRequestMergeMethod!" else ""}) { $mutation(input:{pullRequestId:\$id${if (enable) ",mergeMethod:\$method" else ""}}) { pullRequest { id autoMergeRequest { enabledAt mergeMethod } } } }",
                    buildJsonObject {
                        put("id", nodeId)
                        if (enable) put("method", method)
                    }
                )
                val after = read(parentPath).jsonObject
                verified = (after["auto_merge"] != null && after["auto_merge"] != JsonNull) == enable
            }
            else -> error("Unsupported collaboration action.")
        }
        if (isPr) check(unchanged(read(parentPath).jsonObject)) { "The PR head changed during the operation; reconcile before continuing. Do not repeat the write." }
        check(verified) { "GitHub did not verify the requested state. Inspect the remote result before another mutation." }
        return buildJsonObject {
            put("repository", root.removePrefix("/repos/"))
            put(if (isPr) "pull_number" else "issue_number", number)
            put("action", action)
            put("remote_state_verified", true)
            put("result", outcome)
            if (isPr) put("head_sha", expected)
            if (operation == "enable_auto_merge") put("completion", "AUTO_MERGE_ENABLED_NOT_MERGED")
        }
    }
}
