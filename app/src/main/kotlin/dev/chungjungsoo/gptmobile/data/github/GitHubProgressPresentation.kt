package dev.chungjungsoo.gptmobile.data.github

/** Keep the activity separate from the header's single authoritative duration. */
internal object GitHubProgressPresentation {
    enum class PreviewKind { WEB, INSPECT, EDIT, CHECKS, BRANCH, WAIT, ATTENTION, REVIEW, ACTIVITY }

    private val previewKinds = listOf(
        PreviewKind.WAIT to "waiting|awaiting|queued|paused|approval|rate.limit|retry.after",
        PreviewKind.ATTENTION to "failed|failure|problem|error|attention|recovering|blocked",
        PreviewKind.WEB to "internet|web|webpages?|websites?|online|browsing",
        PreviewKind.EDIT to "editing|writing|patch(?:ing)?|implementing|change request|requested change|response together",
        PreviewKind.CHECKS to "builds?|tests?|testing|checks?|validation|verifying",
        PreviewKind.BRANCH to "branches|branch|commits?|pull requests?|merging",
        PreviewKind.INSPECT to "search(?:ing)?|files?|source|code|reading|examining|inspecting|tracing|retriev(?:ed|ing)|rechecking",
        PreviewKind.REVIEW to "review(?:ing)?|results?|comparing"
    ).map { (kind, words) -> kind to Regex("\\b(?:$words)\\b", RegexOption.IGNORE_CASE) }

    /** Decorative hint from public activity only; it never implies a successful result. */
    fun previewKind(text: String): PreviewKind = previewKinds.firstOrNull { it.second.containsMatchIn(text.take(4000)) }?.first ?: PreviewKind.ACTIVITY

    fun taskLabel(task: String): String {
        val firstLine = GitHubTaskContinuity.request(task).lineSequence().map(String::trim).firstOrNull(String::isNotBlank).orEmpty()
        val redacted = dev.chungjungsoo.gptmobile.data.diagnostics.redactChatDebugData(kotlinx.serialization.json.JsonPrimitive(firstLine)) as kotlinx.serialization.json.JsonPrimitive
        return redacted.content.replace(Regex("[\\p{Cntrl}<>`*#\\[\\]]"), " ").replace(Regex("\\s+"), " ")
            .trim().take(120).dropLastWhile(Char::isHighSurrogate).trimEnd().ifBlank { "your repository request" }
    }

    /** Keep the opening anchored to the user's actual request, not a stock GitHub-status script. */
    fun taskFocus(task: String): String {
        val request = taskLabel(task)
            .replace(Regex("""(?i)^(?:(?:please|can you|could you|would you)\s+)*"""), "")
            .replace(Regex("""(?i)^(?:fix|repair|debug|resolve|review|examine|exaine|inspect|investigate|analyze|audit|improve|enhance|redesign|design|change|update|make|implement|add|check|look at|merge)\s+"""), "")
            .substringBefore(',').substringBefore(';').trim().trimEnd('.', '?', '!', ':')
        val clipped = request.take(78).trim()
        return (if (request.length > 78) clipped.substringBeforeLast(' ', clipped) else clipped)
            .ifBlank { "your repository request" }
    }

    fun introduction(rawTask: String): String {
        val task = GitHubTaskContinuity.request(rawTask)
        if (GitHubTaskContinuity.sessionAdvice(task)) return "I’ll review where this chat lost progress and use those findings to suggest changes to the GitHub integration."
        if (GitHubTaskContinuity.analysisReport(task)) return "I’ll summarize the available findings and identify what still needs verification."
        if (Regex("""(?i)^\s*(?:what|which)\b.*\b(?:this session|this conversation|so far)\b""").containsMatchIn(task)) {
            return "I’ll review the findings and blockers recorded in this conversation and explain what they prevented."
        }
        val summaryRequest = Regex("""(?i)^(?:(?:please|can you|could you|would you)\s+)*(?:summari[sz]e|give me a summary of|provide a summary of)\s+(.+)""")
            .find(taskLabel(task))?.groupValues?.get(1)?.trimEnd('.', '?', '!')
        if (!summaryRequest.isNullOrBlank()) {
            return when {
                Regex("""(?i)^(?:how|where|why)\b""").containsMatchIn(summaryRequest) ->
                    "I’ll trace $summaryRequest in the current source and summarize what I find."
                Regex("""(?i)\b(?:pull request|PR)\b""").containsMatchIn(summaryRequest) ->
                    "I’ll read the pull request and its changed files to summarize $summaryRequest."
                else -> "I’ll check the current repository records and summarize $summaryRequest."
            }
        }
        when (GitHubWorkflowPreset.fromRequest(task)?.id) {
            "diagnose_ci" -> return "I’ll find the failed jobs on this revision and read their logs to see which step broke."
            "review_branch" -> return "I’ll compare the branch with its base, then follow the changed lines into the relevant tests."
            "test_integration" -> return "I’ll check which GitHub tools are available here, then exercise the reads and fixture actions this repository allows."
            "implement_issue" -> return "I’ll trace the reported behavior to its source, make the change on the selected branch and run the relevant tests."
            "prepare_release" -> return "I’ll match the build and test results to the selected revision, then check the packages that would ship."
        }
        val focus = taskFocus(task)
        return when {
            GitHubIssueRemediation.issueOnlyObligation(task, "") ->
                "I’ll check the complete open issue list to see which reports need action."
            GitHubStatusRequest.matches(task) ->
                "I’ll check the repository status, open pull requests and checks on the selected branch."
            Regex("""(?i)\b(?:summaries|summary (?:preview|text|format|style))\b""").containsMatchIn(focus) ->
                "I’ll refine $focus around your request and concrete results, replacing repeated labels with short, distinct updates. I’ll check the opening message too."
            GitHubTaskCoverage.matches(task) ->
                "I’ll map $focus to available GitHub operations and their test evidence without treating unexecuted checks as successes."
            Regex("""(?i)\b(?:fix|repair|debug|resolve|crash|failure)\b""").containsMatchIn(task) ->
                "I’ll trace $focus through the current branch and verify the affected behavior before calling any repair complete."
            Regex("""(?i)\b(?:merge|release|publish)\b""").containsMatchIn(task) ->
                "I’ll check $focus against the latest PR, required checks and review state before treating it as ready to ship."
            Regex("""(?i)\b(?:review|examine|exaine|investigate|audit|diagnos|analy[sz]e)""").containsMatchIn(task) ->
                "I’ll start with $focus and follow the relevant changes through the current branch."
            else ->
                "I’ll work through $focus in the current branch and check the result against what you requested."
        }
    }

    /** Obsolete application templates carry no new finding and must not replace public model prose. */
    fun isGenericProgress(text: String): Boolean {
        val normalized = text.trim().replace(Regex("\\s+"), " ")
        return Regex("(?i)^I have completed \\d+ repository operations\\b|^Saved operation results:\\s*\\d+|^I have received (?:your|the) request\\b|^\\d+ tool calls finished\\. Recent work:").containsMatchIn(normalized) ||
            normalized.trimEnd('.', '…').lowercase(java.util.Locale.ROOT) in setOf(
                "reviewing the latest result and the next step",
                "reviewing the latest result",
                "result received",
                "looking through the repository information for the task"
            )
    }

    /** Known activity-only prose is not a finding, even when the model writes it. */
    fun isEmptySummary(text: String): Boolean {
        val sentences = summaryKey(text).split(Regex("""[.!?]+\s*""")).filter(String::isNotBlank)
        return sentences.isEmpty() ||
            sentences.all { sentence ->
                emptySummarySentence.matches(sentence.trim()) ||
                    emptySummaryNotice.matches(sentence.trim()) ||
                    sentence.trim() in setOf(
                        "work is in progress",
                        "the investigation is in progress",
                        "i will report back with the results",
                        "i will share an update when i have more information",
                        "reviewing the latest result and the next step",
                        "reviewing the latest result",
                        "result received",
                        "looking through the repository information for the task"
                    )
            }
    }

    private val emptySummarySentence = Regex(
        """^(?:i am |i will |i have been )?(?:still |continue |continuing to )?(?:review(?:ing)?|check(?:ing)?|inspect(?:ing)?|examin(?:e|ing)|investigat(?:e|ing)|look(?:ing)?)(?: at| through| into)? (?:the |your |this )?(?:latest |current |requested |repository |source |available |relevant )*(?:results?|request|task|code|files?|information|details|evidence|issue|work|repository)$"""
    )
    private val emptySummaryNotice = Regex("""^(?:i have completed \d+ repository operations|saved operation results:\s*\d+|i have received (?:your|the) request|\d+ tool calls finished)$""")

    private val liveCounter = Regex("""^(checks?|files?|steps?|pages?|jobs?|items?|records?|operations?|tools?)\s*:?\s*\d+\s*/\s*\d+\b""", RegexOption.IGNORE_CASE)

    /** Reuse the row when an observed collection advances. */
    fun liveKey(text: String): String? {
        val kind = liveCounter.find(text.trimStart())?.groupValues?.get(1)?.lowercase(java.util.Locale.ROOT)?.removeSuffix("s") ?: return null
        return "github-$kind-counts"
    }

    /** A stable identity for known generic commit-history heartbeats, including saved chats. */
    fun semanticActivityKey(raw: String): String? {
        val normalized = lineKey(raw)
        val history = normalized == "reading recent changes" ||
            normalized == "commit history result received" ||
            normalized.startsWith("tracing recent repository commits") ||
            normalized.startsWith("tracing recent commits relevant to") ||
            normalized.startsWith("commit-history data retrieved for inspection") ||
            normalized.startsWith("rechecking recent commits for new evidence")
        if (!history) return null
        return "github-commit-history:" + normalized.substringAfter(" · ", "")
    }

    fun legacyNarrative(raw: String): String = when (lineKey(raw)) {
        "reading recent changes" -> "Tracing recent repository commits"
        "commit history result received" -> "Commit-history data retrieved for inspection"
        else -> raw
    }

    /** Compare displayed lines, including whitespace and formatting differences. */
    fun lineKey(text: String): String = preview(text).replace(Regex("\\s+"), " ").trim().trimEnd('.', '…').lowercase(java.util.Locale.ROOT)

    /** Ignore presentation changes without erasing paths, identifiers, numbers or negation. */
    fun summaryKey(text: String): String = preview(text)
        .replace(Regex("""(?m)^\s{0,3}(?:#{1,6}|[-*+])\s+"""), "")
        .replace(Regex("""\*\*|`"""), "")
        .replace('’', '\'')
        .lowercase(java.util.Locale.ROOT)
        .replace(Regex("""\bi'll\b"""), "i will")
        .replace(Regex("""\bi'm\b"""), "i am")
        .replace(Regex("""\bi've\b"""), "i have")
        .replace(Regex("""\s+"""), " ")
        .trim().trimEnd('.', '…')

    /** Exact application staging notices expire when a visible response or terminal result arrives. */
    fun isTransientActivity(text: String): Boolean = lineKey(text) in setOf(
        "putting the response together",
        "preparing the requested repository checks",
        "locating the relevant repository evidence"
    )

    private val duration = Regex("""\b\d{1,4}:\d{2}(?::\d{2})?\b|\b\d+\s*(?:ms|s|seconds?|minutes?|hours?)\s+elapsed\b|\belapsed\s*[:=]\s*\d+\s*(?:ms|s|seconds?|minutes?|hours?)?""", RegexOption.IGNORE_CASE)
    private val prefix = Regex("""^status\b\s*(?:[:·|—-]\s*)?""", RegexOption.IGNORE_CASE)

    // Technical words inside source paths and identifiers must retain their exact spelling.
    private fun publicWord(pattern: String) = Regex("(?<![A-Za-z0-9_./\\\\`-])(?:$pattern)(?![A-Za-z0-9_./\\\\`-])", RegexOption.IGNORE_CASE)
    private val internalOperation = publicWord("(?:github_|tool_)[A-Za-z0-9_]+")
    private val publicWords = mapOf("receipts" to "recorded results", "receipt" to "recorded result", "dispatch" to "request", "schema" to "tool definition", "checkpoint" to "saved progress", "prefill" to "model preparation", "provenance" to "source evidence").map { (internal, public) -> publicWord(internal) to public }

    fun preview(raw: String): String {
        val text = raw.trim()
        if (text.isBlank()) return ""
        val redacted = dev.chungjungsoo.gptmobile.data.diagnostics.redactChatDebugData(kotlinx.serialization.json.JsonPrimitive(text)) as kotlinx.serialization.json.JsonPrimitive
        var visible = internalOperation.replace(redacted.content) { it.value.removePrefix("github_").removePrefix("tool_").replace('_', ' ') }
        publicWords.forEach { (internal, public) -> visible = visible.replace(internal, public) }
        return visible
    }

    fun activity(raw: String): String {
        val lines = raw.lineSequence().map(String::trim).filter(String::isNotBlank).toList()
        // The retained GitHub heartbeat has a task title followed by current activity.
        val line = lines.firstOrNull { it.contains("elapsed", ignoreCase = true) }
            ?: lines.firstOrNull { !it.equals("status", ignoreCase = true) }.orEmpty()
        return duration.replace(prefix.replace(line, ""), "")
            .replace(Regex("""\s+"""), " ")
            .trim(' ', '·', '|', '—', '-', ':', '.')
            .ifBlank { "Preparing the requested repository checks" }
    }
}

/** A typed, token-free representation of real public tool progress. */
internal enum class GitHubPreviewPhase { STARTED, RESULT }

internal data class GitHubPreviewEvent(
    val family: String,
    val action: String,
    val target: String,
    val phase: GitHubPreviewPhase,
    val fallback: String
)

/** Never infer a successful fix, test, approval or merge from an ordinary API reply. */
internal class GitHubPreviewSequence(task: String) {
    private val focus = when {
        task.contains("integration", ignoreCase = true) -> "the integration audit"
        task.contains("pagination", ignoreCase = true) -> "the pagination investigation"
        task.contains("preview", ignoreCase = true) -> "the preview redesign"
        task.contains("test", ignoreCase = true) -> "the requested tests"
        else -> "the repository task"
    }
    private val observed = linkedMapOf<String, Int>()

    fun offer(event: GitHubPreviewEvent): String? {
        val identity = listOf(event.family, event.action, event.target, event.phase.name)
            .joinToString("|").lowercase(java.util.Locale.ROOT)
        val occurrences = observed[identity] ?: 0
        observed[identity] = occurrences + 1
        while (observed.size > 256) observed.remove(observed.keys.first())
        val suffix = event.target.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty()
        if (occurrences > 0) {
            // Only the second observed start is narrated as a recheck. Repeated
            // result receipts stay in the detailed execution trace.
            return if (occurrences == 1 && event.phase == GitHubPreviewPhase.STARTED) {
                when (event.family to event.action) {
                    "github_context" to "commits" -> "Rechecking recent commits for new evidence$suffix"
                    "github_read" to "" -> "Rechecking the requested source$suffix"
                    "github_search" to "" -> "Refining the repository source search$suffix"
                    else -> null
                }
            } else {
                null
            }
        }
        val started = event.phase == GitHubPreviewPhase.STARTED
        val description = when (event.family to event.action) {
            "github_context" to "commits" ->
                if (started) "Tracing recent commits relevant to $focus" else "Commit-history data retrieved for inspection"
            "github_context" to "checks" ->
                if (started) "Examining recorded CI checks" else "Recorded CI check statuses retrieved for review"
            "github_context" to "changes" ->
                if (started) "Comparing the selected revisions" else "Revision comparison retrieved for inspection"
            "github_context" to "capabilities" ->
                if (started) "Mapping authorized GitHub operations" else "Authorized tool inventory retrieved for review"
            "github_read" to "" ->
                if (started) "Examining the requested source" else "Source content retrieved for inspection"
            "github_search" to "" ->
                if (started) "Locating relevant implementation files" else "Repository matches retrieved for inspection"
            "github_ci" to "logs", "github_watch_checks" to "logs" ->
                if (started) "Inspecting the CI job log" else "CI job log retrieved for inspection"
            "github_ci" to "jobs", "github_watch_checks" to "jobs" ->
                if (started) "Examining the workflow jobs" else "Workflow job statuses retrieved for review"
            "github_pr" to "reviews" ->
                if (started) "Reviewing pull-request feedback" else "Pull-request feedback retrieved for inspection"
            "github_pr" to "list" ->
                if (started) "Locating the relevant pull requests" else "Pull-request inventory retrieved for review"
            "github_commit" to "", "github_write" to "", "github_stage_patch" to "" ->
                if (started) {
                    "Preparing an authorized change request"
                } else {
                    "Change request returned; remote outcome needs verification"
                }
            "web_search" to "" ->
                if (started) "Searching web sources relevant to the task" else "Web search results retrieved for review"
            else -> return event.fallback
        }
        return description + suffix
    }
}
