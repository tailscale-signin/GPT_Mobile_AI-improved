package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.ProfileFailureRecord
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRunStatus
import dev.chungjungsoo.gptmobile.data.diagnostics.ProviderFailureStage
import dev.chungjungsoo.gptmobile.data.diagnostics.ProviderFinishCategory
import dev.chungjungsoo.gptmobile.data.diagnostics.RunJournalSnapshot

data class GitHubSessionToolFailure(val toolName: String, val status: String, val count: Int)

data class GitHubSessionToolCount(val toolName: String, val count: Int)

/** Projects safe structured run and operation outcomes without sending the chat to a model. */
internal object GitHubSessionAudit {
    private val safeCode = Regex("[A-Z][A-Z0-9_]{0,79}")
    private val safeTool = Regex("[a-zA-Z0-9_.-]{1,80}")

    // Older saved runs persisted the application message without a structured stop code.
    private fun stopCode(run: AgentRun): String? = when (val message = run.terminalError) {
        null -> null
        "The saved page no longer matches its checkpoint. Resume from the original authorized view. Prior notes and tool receipts remain intact.",
        "The original source page could not be reopened from this checkpoint. Earlier notes and tool results are saved." -> "SAVED_PAGE_REOPEN"
        "The saved-page response contained unsupported output or exceeded its note allowance. Its pending pages remain checkpointed.",
        "The saved-page reader returned output other than reading notes. Its pending pages remain checkpointed.",
        "The saved-page reader returned no usable notes within its allowance. Its pending pages remain checkpointed." -> "SAVED_PAGE_NOTES"
        "Claim verification reached its output limit. The repository observations and completed claim batches are saved." -> "GITHUB_EXTRACTION_OUTPUT_LIMIT"
        else -> ProfileFailureRecord.classify(message).category.takeIf(safeCode::matches)
    }

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
        val stopped = interrupted.groupingBy { it.status to stopCode(it) }.eachCount()
        val codes = stopped.keys.mapNotNull { it.second }.toSet()
        val runIds = runs.mapTo(mutableSetOf()) { it.runId }
        val events = journal?.events.orEmpty().filter { it.runId in runIds }
        if (interrupted.isNotEmpty()) {
            appendLine()
            appendLine("## Run outcomes")
            stopped.forEach { (key, count) ->
                val (state, code) = key
                val label = when (code) {
                    "SAVED_PAGE_REOPEN" -> "the app could not reopen the saved source page from its checkpoint"
                    "SAVED_PAGE_NOTES" -> "the app could not retain usable notes from a saved source page"
                    "GITHUB_EXTRACTION_OUTPUT_LIMIT" -> "the verifier reached its output limit before it could finish the answer"
                    else -> code?.let { "`$it`" }
                }
                appendLine("- $count ${state.lowercase().replace('_', ' ')}${label?.let { " · $it" }.orEmpty()}")
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
        val lengthLimited = events.mapNotNull { event ->
            val failure = event.providerFailure ?: return@mapNotNull null
            if (failure.stage != ProviderFailureStage.CLAIM_VERIFICATION || failure.finishReason != ProviderFinishCategory.LENGTH) return@mapNotNull null
            (failure.invocationId ?: event.invocationId)?.let { event.runId to it }
        }.toSet()
        if (lengthLimited.isNotEmpty()) {
            val attempts = lengthLimited.size
            val affected = lengthLimited.distinctBy { it.first }.size
            appendLine()
            appendLine("The journal recorded $attempts claim-verification ${if (attempts == 1) "request" else "requests"} that reached the output limit across $affected ${if (affected == 1) "run" else "runs"}.")
        }
        val recorded = events.mapNotNull { event ->
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
                if ("SAVED_PAGE_REOPEN" in codes) add("Reopen the original source revision and match it to the saved page before continuing the review.")
                if ("SAVED_PAGE_NOTES" in codes) add("Keep a short source-linked note from each completed page read so verbose responses do not stall the review.")
                if (lengthLimited.isNotEmpty() || "GITHUB_EXTRACTION_OUTPUT_LIMIT" in codes || "GITHUB_EXTRACTION_OUTPUT_LIMIT" in recorded) {
                    add("Verify the most important claim first after an output limit, then resume the unfinished batch from that smaller request.")
                } else if ("OUTPUT_LIMIT" in recorded) {
                    add("When a response reaches its output limit, resume at the last useful finding and preserve room for a visible conclusion.")
                }
                if (failures.any { it.status == "FAILED" }) add("Show which operation failed and its recovery step in the preview. Keep previously confirmed findings visible while the operation is corrected.")
            }
            if (suggestions.isEmpty()) appendLine("The retained outcomes do not identify a specific repair.") else suggestions.forEach { appendLine("- $it") }
        }
        if (interrupted.isEmpty() && failures.isEmpty() && recorded.isEmpty()) appendLine("No failure was recorded in the retained window.")
        if (journal == null || journal.events.isEmpty() || journal.missingBatches > 0 || journal.droppedEvents > 0 || journal.missingSequences > 0 || journal.storageFailed || journal.intentionallyOmittedBatches > 0) appendLine("Some run journal evidence was unavailable in this window.")
    }.trimEnd()
}
