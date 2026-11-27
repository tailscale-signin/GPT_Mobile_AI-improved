package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.ProfileFailureRecord
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRunStatus
import dev.chungjungsoo.gptmobile.data.diagnostics.RunJournalSnapshot

data class GitHubSessionToolFailure(val toolName: String, val status: String, val count: Int)

data class GitHubSessionToolCount(val toolName: String, val count: Int)

/** Projects safe structured run and operation outcomes without sending the chat to a model. */
internal object GitHubSessionAudit {
    private val safeCode = Regex("[A-Z][A-Z0-9_]{0,79}")
    private val safeTool = Regex("[a-zA-Z0-9_.-]{1,80}")

    private fun duration(run: AgentRun): Long? = run.completedAt?.let { end ->
        (end - (run.startedAt ?: run.createdAt)).takeIf { it >= 0 }
    }

    fun longestInterruptedRun(runs: List<AgentRun>): AgentRun? = runs
        .filter { it.status in AgentRunStatus.terminal && it.status != AgentRunStatus.COMPLETED && duration(it) != null }
        .maxByOrNull { duration(it)!! }

    @JvmOverloads
    fun render(
        runs: List<AgentRun>,
        failures: List<GitHubSessionToolFailure>,
        journal: RunJournalSnapshot?,
        suggestChanges: Boolean = false,
        longestRunTools: List<GitHubSessionToolCount> = emptyList()
    ): String = buildString {
        appendLine("# GitHub session audit")
        appendLine()
        val completed = runs.count { it.status == AgentRunStatus.COMPLETED }
        appendLine("Of the ${runs.size} retained runs in this chat, $completed completed. This audit covers up to the latest 32 runs.")
        val interrupted = runs.filter { it.status in AgentRunStatus.terminal && it.status != AgentRunStatus.COMPLETED }
        if (interrupted.isNotEmpty()) {
            appendLine()
            appendLine("## Run outcomes")
            interrupted.groupingBy { run ->
                val category = run.terminalError?.let { ProfileFailureRecord.classify(it).category }?.takeIf(safeCode::matches)
                run.status to category
            }.eachCount().forEach { (key, count) ->
                val (state, code) = key
                appendLine("- $count ${state.lowercase().replace('_', ' ')}${code?.let { " · `$it`" }.orEmpty()}")
            }
        }
        longestInterruptedRun(interrupted)?.let { run ->
            val elapsed = duration(run)!!
            val time = if (elapsed < 60) "${elapsed}s" else "${elapsed / 60}m ${elapsed % 60}s"
            appendLine()
            append("The longest unfinished run took $time and ended with ${run.status.lowercase().replace('_', ' ')}.")
            val counts = longestRunTools.filter { it.count > 0 }.groupBy { it.toolName.takeIf(safeTool::matches)?.substringBefore("__") ?: "tool" }
                .mapValues { (_, rows) -> rows.sumOf { it.count.toLong() } }.entries.sortedByDescending { it.value }
            if (counts.isNotEmpty()) {
                val leaders = counts.take(3).joinToString(", ") { "`${it.key}` (${it.value})" }
                append(" Of ${counts.sumOf { it.value }} recorded tool events, the most frequent were $leaders.")
            }
            appendLine()
        }
        if (failures.isNotEmpty()) {
            appendLine()
            appendLine("## Tool failures and cancellations")
            failures.forEach { failure ->
                val name = failure.toolName.takeIf(safeTool::matches) ?: "tool"
                appendLine("- `$name` · ${failure.count} ${failure.status.takeIf(safeCode::matches)?.lowercase() ?: "recorded"}")
            }
        }
        val recorded = journal?.events.orEmpty().mapNotNull { event ->
            event.providerFailure?.errorCode?.takeIf(safeCode::matches)
        }.groupingBy { it }.eachCount()
        if (recorded.isNotEmpty()) {
            appendLine()
            appendLine("## Diagnostic codes")
            recorded.entries.sortedByDescending { it.value }.take(16).forEach { (code, count) ->
                val hint = GitHubFailurePolicy.recoveryHint(code).takeIf { code in GitHubFailurePolicy.codes }
                appendLine("- `$code` · $count journal records${hint?.let { ". $it" }.orEmpty()}")
            }
        }
        if (suggestChanges) {
            appendLine()
            appendLine("## Suggested GitHub integration changes")
            val suggestions = buildList {
                if ("CONTEXT_CAPACITY_EXCEEDED" in recorded || interrupted.any { it.terminalError?.startsWith("CONTINUATION_CONTEXT_CAPACITY:") == true }) {
                    add("Reserve space for the final report before adding optional tool output. Carry the most relevant findings and their receipt references into the remaining space.")
                }
                if (runs.any { it.status == AgentRunStatus.EVIDENCE_MISSING }) {
                    add("Show the first requirement still missing evidence in the preview. Reuse its saved observations and request only the evidence still missing.")
                }
                if ("OUTPUT_LIMIT" in recorded) add("When a response reaches its output limit, resume at the last useful finding and preserve room for a visible conclusion.")
                if (failures.any { it.status == "FAILED" }) add("Show which operation failed and its recovery step in the preview. Keep previously confirmed findings visible while the operation is corrected.")
            }
            if (suggestions.isEmpty()) appendLine("The retained outcomes do not identify a specific repair.") else suggestions.forEach { appendLine("- $it") }
        }
        if (interrupted.isEmpty() && failures.isEmpty() && recorded.isEmpty()) appendLine("No failure was recorded in the retained window.")
        if (journal == null || journal.events.isEmpty() || journal.missingBatches > 0) appendLine("Some run journal evidence was unavailable in this window.")
    }.trimEnd()
}
