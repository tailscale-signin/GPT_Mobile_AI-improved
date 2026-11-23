package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Discover candidates only from the selected immutable tree; never silently read a different file. */
internal object GitHubPinnedPathResolver {
    fun resolve(path: String, entries: Map<String, JsonObject>, commitSha: String, treeComplete: Boolean): JsonObject {
        GitHubWorkspaceClient.filePath(path)
        val entry = entries[path]
        if (entry != null) {
            if (entry.text("mode") !in setOf("", "100644", "100755") || entry.text("type") != "blob") {
                val directory = entry.text("type") == "tree"
                val code = if (directory) "GITHUB_PATH_IS_DIRECTORY" else "GITHUB_PATH_NOT_REGULAR_FILE"
                throw GitHubReadValidation(
                    code,
                    buildJsonObject {
                        put("schema", "github.read_validation.v1")
                        put("error_code", code)
                        put("status", "PRECHECK_REJECTED")
                        put("failed_stage", "resolve_path")
                        put("read_dispatched", false)
                        put("retry_unchanged", false)
                        put("path", path)
                        put("commit_sha", commitSha)
                        put("tree_complete", treeComplete)
                        put("entry_type", entry.text("type"))
                        put("entry_mode", entry.text("mode"))
                        put("message", if (directory) "The requested path is a directory in the pinned tree. Discover a regular file beneath it before reading a line range." else "The requested path is not a regular file in the pinned tree. Symlinks and submodules are not followed by source reads.")
                        put("candidates", JsonArray(emptyList()))
                        put(
                            "corrective_actions",
                            JsonArray(
                                listOf(
                                    buildJsonObject {
                                        put("tool", "github_search")
                                        put(
                                            "arguments",
                                            buildJsonObject {
                                                put("mode", "paths")
                                                put("query", "*")
                                                put("path_prefix", if (directory) "$path/" else path.substringBeforeLast('/', "").let { if (it.isBlank()) "" else "$it/" })
                                                put("commit_sha", commitSha)
                                                put("index_cursor", 0)
                                            }
                                        )
                                    }
                                )
                            )
                        )
                    }
                )
            }
            return entry
        }
        val paths = entries.values.filter { it.text("type") == "blob" && it.text("mode") in setOf("", "100644", "100755") }.map { it.text("path") }
        val candidates = GitHubSearchContext.missingPathCandidates(path, paths)
        val code = if (treeComplete) "GITHUB_PATH_NOT_FOUND" else "GITHUB_TREE_INCOMPLETE"
        throw GitHubReadValidation(
            code,
            buildJsonObject {
                put("schema", "github.read_validation.v1")
                put("error_code", code)
                put("status", "PRECHECK_REJECTED")
                put("failed_stage", "resolve_path")
                put("read_dispatched", false)
                put("retry_unchanged", false)
                put("path", path)
                put("commit_sha", commitSha)
                put("tree_complete", treeComplete)
                put("message", if (treeComplete) "The requested path is absent from the pinned tree. Select a verified candidate or search exact paths before reading." else "The pinned tree is incomplete. The requested path was not observed; absence is not established. Browse its parent at the pinned commit to resolve it.")
                put(
                    "candidates",
                    JsonArray(
                        candidates.map { candidate ->
                            buildJsonObject {
                                put("path", candidate)
                                put("sha", entries.getValue(candidate).getValue("sha"))
                            }
                        }
                    )
                )
                put(
                    "corrective_actions",
                    JsonArray(
                        listOf(
                            buildJsonObject {
                                put("tool", "github_search")
                                put(
                                    "arguments",
                                    buildJsonObject {
                                        put("mode", "paths")
                                        put("query", path.substringAfterLast('/'))
                                        put("commit_sha", commitSha)
                                        put("index_cursor", 0)
                                    }
                                )
                            },
                            buildJsonObject {
                                put("tool", "github_read")
                                put("requirement", "Use an exact observed candidate path and this commit_sha; retained evidence is unchanged.")
                            }
                        )
                    )
                )
            }
        )
    }

    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.content.orEmpty()
}
