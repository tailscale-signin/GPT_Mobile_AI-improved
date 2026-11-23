package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** Presentation is selected from the user's task; factual values still come only from verified receipts. */
internal object GitHubAnswerPresentation {
    private val runtimeSubject = Regex("""(?i)\b(?:tool[- ]?(?:call[s]?|budget|allowance|limit|access|availability)|(?:call|execution|current|remaining|request) (?:budget|allowance)|(?:tool|write|branch|commit|test[- ]execution|pull[- ]request) (?:tools?|capabilit(?:y|ies)|permissions?|access)|(?:available|remaining|more|no) (?:tool )?calls|(?:tools?|capabilities|permissions?) (?:are |were |remain )?(?:unavailable|missing|disabled|exhausted)|(?:environment|session|workspace) (?:that |which )?(?:with|without|lacks?|has|supports?|cannot)|(?:cannot|can't|unable to) (?:write|run tests|create (?:a )?branch|open (?:a )?pull request))\b""")
    fun runtimeClaim(text: String): Boolean = runtimeSubject.containsMatchIn(text)

    fun resources(budget: JsonObject, authorized: List<AgentToolDefinition>, advertised: List<AgentToolDefinition>): JsonObject = buildJsonObject {
        for (field in listOf("remaining_calls", "effective_call_limit", "used_calls", "remaining_source", "verification_reserve")) {
            budget[field]?.let { put(field, it) }
        }
        fun families(definitions: List<AgentToolDefinition>) = definitions.asSequence().filter { it.modelVisible }
            .map { it.name.substringBefore("__") }.filter { it.startsWith("github_") || it in setOf("github", "repository_workspace", "repository_graph", GitHubAuthorizedCatalog.TOOL) }
            .distinct().sorted().take(64).map(::JsonPrimitive).toList()
        put("authorized_tool_families", JsonArray(families(authorized)))
        put("advertised_tool_families", JsonArray(families(advertised)))
    }

    fun resourceNotice(resources: JsonObject): String = buildString {
        val remaining = (resources["remaining_calls"] as? JsonPrimitive)?.intOrNull
        val limit = (resources["effective_call_limit"] as? JsonPrimitive)?.intOrNull
        val available = (resources["advertised_tool_families"] as? JsonArray).orEmpty()
        val catalog = (resources["authorized_tool_families"] as? JsonArray).orEmpty()
        if (remaining != null) {
            append("\n\nTool calls remaining: ").append(remaining)
            if (limit != null) append(" of ").append(limit)
            append('.')
        }
        fun names(values: List<kotlinx.serialization.json.JsonElement>) = values.joinToString(", ") { (it as JsonPrimitive).content }
        if (available.isNotEmpty()) append("\n\nTools offered in this round: ").append(names(available)).append('.')
        val discoverable = catalog.filterNot { it in available }
        if (discoverable.isNotEmpty()) append("\n\nOther tools in the authorized catalog: ").append(names(discoverable)).append('.')
    }

    fun title(task: String): String {
        val text = task.lowercase()
        return when {
            GitHubStatusRequest.matches(task) -> "Repository status"
            GitHubTaskCoverage.matches(task) -> "GitHub verification report"
            listOf("review", "pull request", " pr ").any { it in text } -> "Repository review"
            listOf("fix", "implement", "add", "change", "build").any { it in text } -> "Implementation update"
            listOf("why", "explain", "investigate", "issue", "error", "analy").any { it in text } -> "Investigation findings"
            else -> "Repository update"
        }
    }

    fun render(task: String, facts: List<String>, notes: List<String>, excerpts: List<String>, questions: List<String>): String = buildString {
        append("# ").append(title(task)).append("\n\n")
        // Explanations appear before supporting details. Do not turn the API's object structure into the answer.
        val interpretations = notes.filter { it.startsWith("- **Interpretation:") }
        val limits = notes - interpretations.toSet()
        if (interpretations.isNotEmpty()) {
            append(interpretations.first().removePrefix("- ")).append('\n')
            if (interpretations.size > 1) append("\n## Findings\n\n").append(interpretations.drop(1).joinToString("\n\n")).append('\n')
        } else {
            append(if (facts.isEmpty() && excerpts.isEmpty()) "The available evidence does not yet establish the requested result.\n" else "Current observations are listed below; unresolved work is identified separately.\n")
        }
        val usefulFacts = if (title(task) == "Investigation findings") facts.filterNot { it.substringBefore(" |", "").endsWith(" · Path") || it.contains(" · Path |") } else facts
        if (usefulFacts.isNotEmpty()) {
            val sections = if (GitHubStatusRequest.matches(task)) {
                usefulFacts.distinct().groupBy { row ->
                    val label = row.substringAfter("| ").substringBefore(" |")
                    when {
                        label.startsWith("PR ") -> "Open pull requests"
                        label.startsWith("Observed failed workflow ·") -> "Observed workflow failures"
                        label.startsWith("Observed workflow ·") -> "Observed workflows"
                        label.startsWith("Workflow ") || label == "Latest workflow" -> "Latest workflow"
                        label in setOf("Check state", "Checks", "Statuses", "State") -> "Checks for this commit"
                        else -> "Repository details"
                    }
                }.toSortedMap(compareBy { listOf("Repository details", "Checks for this commit", "Open pull requests", "Latest workflow", "Observed workflows", "Observed workflow failures").indexOf(it) })
            } else {
                mapOf("Repository details" to usefulFacts.distinct())
            }
            sections.forEach { (heading, rows) ->
                append("\n## $heading\n\n| Detail | Result | As of (UTC) |\n| --- | --- | --- |\n")
                append(rows.joinToString("\n")).append('\n')
            }
        }
        if (excerpts.isNotEmpty()) append("\n## Source evidence\n\n").append(excerpts.distinct().joinToString("\n\n")).append('\n')
        if (limits.isNotEmpty()) append("\n## Open items\n\n").append(limits.distinct().joinToString("\n")).append('\n')
        if (questions.isNotEmpty()) append("\n## Next steps\n\n").append(questions.distinct().joinToString("\n") { "- $it" })
    }
}
