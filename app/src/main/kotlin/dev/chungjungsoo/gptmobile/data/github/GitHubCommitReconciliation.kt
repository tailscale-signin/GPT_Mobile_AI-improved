package dev.chungjungsoo.gptmobile.data.github

import java.security.MessageDigest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** Read-only proof of an equivalent published change, including every unchanged tree entry. */
internal object GitHubCommitReconciliation {
    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.content.orEmpty()
    private fun String.sha() = matches(Regex("[a-f0-9]{40}"))

    fun blobSha(content: String): String {
        val bytes = content.encodeToByteArray()
        val digest = MessageDigest.getInstance("SHA-1")
        digest.update("blob ${bytes.size}\u0000".encodeToByteArray())
        return digest.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    fun filesReceipt(files: JsonArray): JsonArray = JsonArray(
        files.map { item ->
            val file = item.jsonObject
            val deleted = file["delete"] == JsonPrimitive(true)
            buildJsonObject {
                put("path", file.getValue("path"))
                file["expected_blob_sha"]?.let {
                    put("previous_blob_sha", it)
                    put("expected_blob_sha", it)
                }
                put(
                    "status",
                    if (deleted) {
                        "removed"
                    } else if (file["expected_blob_sha"] == null) {
                        "added"
                    } else {
                        "modified"
                    }
                )
                put("delete", deleted)
                if (!deleted) {
                    put("blob_sha", blobSha(file.text("content")))
                    put("result_bytes", file.text("content").encodeToByteArray().size)
                }
            }
        }
    )

    suspend fun recover(root: String, args: JsonObject, observedRef: JsonObject? = null, request: suspend (String) -> JsonElement): JsonObject? {
        val expected = args.text("expected_head_sha")
        val branch = args.text("branch")
        val files = args["files"] as? JsonArray ?: return null
        if (!expected.sha() || branch.isBlank() || files.isEmpty() || files.size > 20 || args.text("message").isBlank()) return null
        val paths = files.map { (it as? JsonObject)?.text("path") ?: return null }
        if (paths.distinct().size != paths.size || paths.any { runCatching { GitHubWorkspaceClient.filePath(it) }.isFailure }) return null
        val refPath = "$root/git/ref/heads/${GitHubWorkspaceClient.segment(branch)}"
        suspend fun head(observed: JsonObject? = null): String? {
            val ref = observed ?: request(refPath).jsonObject
            if (ref.text("ref") != "refs/heads/$branch") return null
            return (ref["object"] as? JsonObject)?.text("sha")?.takeIf { it.sha() }
        }
        val candidate = head(observedRef) ?: return null
        // An unchanged head cannot prove whether an unreferenced object was created.
        if (candidate == expected) return null
        val commit = request("$root/git/commits/$candidate").jsonObject
        val parents = (commit["parents"] as? JsonArray).orEmpty()
        if (commit.text("sha") != candidate || parents.size != 1 || (parents.single() as? JsonObject)?.text("sha") != expected || commit.text("message") != args.text("message")) return null
        val base = request("$root/git/commits/$expected").jsonObject
        val baseTree = (base["tree"] as? JsonObject)?.text("sha")?.takeIf { it.sha() } ?: return null
        val treeSha = (commit["tree"] as? JsonObject)?.text("sha")?.takeIf { it.sha() } ?: return null
        suspend fun entries(sha: String): Map<String, JsonObject>? {
            val result = request("$root/git/trees/$sha?recursive=1").jsonObject
            if ((result["truncated"] as? JsonPrimitive)?.booleanOrNull != false || result.text("sha") != sha) return null
            val rows = (result["tree"] as? JsonArray)?.map { it as? JsonObject ?: return null } ?: return null
            if (rows.any { it.text("path").isBlank() || !it.text("sha").sha() || it.text("type") !in setOf("tree", "blob", "commit") }) return null
            if (rows.map { it.text("path") }.distinct().size != rows.size) return null
            return rows.filter { it.text("type") != "tree" }.associate { row -> row.text("path") to JsonObject(row.filterKeys { it in setOf("mode", "type", "sha") }) }
        }
        val intended = (entries(baseTree) ?: return null).toMutableMap()
        files.forEach { item ->
            val file = item.jsonObject
            val path = file.text("path")
            val original = intended[path]
            if (original != null && (file.text("expected_blob_sha") != original.text("sha") || original.text("mode") !in setOf("100644", "100755"))) return null
            if (original == null && file["expected_blob_sha"] != null) return null
            if (file["delete"] == JsonPrimitive(true)) {
                if (original == null) return null
                intended.remove(path)
            } else {
                val content = (file["content"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
                intended[path] = buildJsonObject {
                    put("mode", original?.text("mode") ?: "100644")
                    put("type", "blob")
                    put("sha", blobSha(content))
                }
            }
        }
        if (intended != entries(treeSha) || head() != candidate) return null
        return buildJsonObject {
            put("sha", candidate)
            put("branch", branch)
            put("expected_head_sha", candidate)
            put("parent_sha", expected)
            put("tree_sha", treeSha)
            put("files", filesReceipt(files))
            put("files_changed", files.size)
            put("reconciled", true)
            put("verification", "REMOTE_REF_PARENT_MESSAGE_AND_COMPLETE_TREE")
            put("attribution", "Equivalent remote state verified; the lost response cannot establish which client created it.")
            put("next_action", "Reuse this verified file receipt. No write was repeated; further edits require the new head and blob SHAs.")
        }
    }
}
