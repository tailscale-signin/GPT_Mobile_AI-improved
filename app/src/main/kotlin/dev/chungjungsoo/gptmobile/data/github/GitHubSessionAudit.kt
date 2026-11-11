package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.ProfileFailureRecord
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRunStatus
import dev.chungjungsoo.gptmobile.data.diagnostics.RunJournalSnapshot

data class GitHubSessionToolFailure(val toolName: String, val status: String, val count: Int)

/** Projects safe structured run and operation outcomes without sending the chat to a model. */
internal object GitHubSessionAudit {
    private val safeCode = Regex("[A-Z][A-Z0-9_]{0,79}")
    private val safeTool = Regex("[a-zA-Z0-9_.-]{1,80}")

    fun render(runs: List<AgentRun>, failures: List<GitHubSessionToolFailure>, journal: RunJournalSnapshot?): String = buildString {
        appendLine("# GitHub session audit")
        appendLine()
        appendLine("Reviewed ${runs.size} retained runs in this chat, up to the latest 32. No GitHub repository changes or model calls were made for this audit.")
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
        if (interrupted.isEmpty() && failures.isEmpty() && recorded.isEmpty()) appendLine("No failure was recorded in the retained window.")
        if (journal == null || journal.events.isEmpty() || journal.missingBatches > 0) appendLine("Some run journal evidence was unavailable in this window.")
    }.trimEnd()
}
