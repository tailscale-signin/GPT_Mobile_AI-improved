package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Local pre-commit feedback. Compiler, lint, tests and CodeQL still require actual CI evidence. */
internal object GitHubPatchValidation {
    private val marker = Regex("^(?:<{7}(?: .*)?|={7}|>{7}(?: .*)?)$")
    private val credential = Regex("(?:gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----)")

    fun inspect(path: String, before: String, after: String, treePaths: List<String> = emptyList()) = buildJsonObject {
        val existing = before.lineSequence().groupingBy { it }.eachCount().toMutableMap()
        val findings = mutableListOf<kotlinx.serialization.json.JsonObject>()
        var errors = 0
        var warnings = 0
        after.lineSequence().forEachIndexed { index, line ->
            val oldCount = existing[line] ?: 0
            if (oldCount > 0) {
                existing[line] = oldCount - 1
                return@forEachIndexed
            }
            val problem = when {
                marker.matches(line) -> "UNRESOLVED_MERGE_MARKER"
                credential.containsMatchIn(line) -> "CREDENTIAL_IN_ADDED_TEXT"
                line.endsWith(' ') || line.endsWith('\t') -> "TRAILING_WHITESPACE"
                else -> null
            } ?: return@forEachIndexed
            val blocking = problem == "CREDENTIAL_IN_ADDED_TEXT" || problem == "UNRESOLVED_MERGE_MARKER" && path.substringAfterLast('.').lowercase() !in setOf("md", "rst", "txt")
            if (blocking) errors++ else warnings++
            if (findings.size < 40) {
                findings += buildJsonObject {
                    put("line", index + 1)
                    put("code", problem)
                    put("severity", if (blocking) "error" else "warning")
                }
            }
        }
        put("schema_version", 1)
        put("path", path)
        put("status", if (errors > 0) "BLOCKED" else "PRECHECKS_PASSED")
        put("errors", errors)
        put("warnings", warnings)
        put("findings", JsonArray(findings))
        put("findings_bounded", errors + warnings > findings.size)
        put("before_hash", GitHubRepositoryStateStore.digest(before))
        put("after_hash", GitHubRepositoryStateStore.digest(after))
        put("test_candidates", GitHubSearchContext.related(path, treePaths))
        put(
            "test_generation_context",
            buildJsonObject {
                put("symbols", JsonArray(GitHubLocalIndex.build(after)["definitions"]!!.let { it as JsonArray }.take(16)))
                put("guidance", "Extend a related existing test for the changed behavior; include failure, boundary and recovery cases. Generated tests need execution evidence before approval.")
            }
        )
        put(
            "external_checks",
            JsonArray(
                listOf("compiler", "lint", "unit_tests", "CodeQL").map { check ->
                    buildJsonObject {
                        put("check", check)
                        put("status", "NOT_RUN")
                    }
                }
            )
        )
        put("coverage", JsonPrimitive("Added-text hygiene, merge markers and high-confidence credentials only; this is not syntax or vulnerability qualification."))
    }
}
