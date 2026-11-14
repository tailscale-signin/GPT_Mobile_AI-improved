package dev.chungjungsoo.gptmobile.data.github

/** Keep the activity separate from the header's single authoritative duration. */
internal object GitHubProgressPresentation {
    enum class PreviewKind { WEB, INSPECT, EDIT, CHECKS, BRANCH, WAIT, ATTENTION, REVIEW, ACTIVITY }

    private val previewKinds = listOf(
        PreviewKind.WAIT to "waiting|awaiting|queued|paused|approval",
        PreviewKind.ATTENTION to "failed|failure|problem|error|attention|recovering",
        PreviewKind.WEB to "internet|web|webpages?|websites?|online|browsing",
        PreviewKind.EDIT to "editing|writing|patch(?:ing)?|implementing|change request|requested change|response together",
        PreviewKind.CHECKS to "builds?|tests?|testing|checks?|validation|verifying",
        PreviewKind.BRANCH to "branches|branch|commits?|pull requests?|merging",
        PreviewKind.INSPECT to "search(?:ing)?|files?|source|code|reading|examining|inspecting",
        PreviewKind.REVIEW to "review(?:ing)?|results?|comparing"
    ).map { (kind, words) -> kind to Regex("\\b(?:$words)\\b", RegexOption.IGNORE_CASE) }

    /** Decorative hint from public activity only; it never implies a successful result. */
    fun previewKind(text: String): PreviewKind = previewKinds.firstOrNull { it.second.containsMatchIn(text.take(4000)) }?.first ?: PreviewKind.ACTIVITY

    fun taskLabel(task: String): String {
        val firstLine = task.lineSequence().map(String::trim).firstOrNull(String::isNotBlank).orEmpty()
        val redacted = dev.chungjungsoo.gptmobile.data.diagnostics.redactChatDebugData(kotlinx.serialization.json.JsonPrimitive(firstLine)) as kotlinx.serialization.json.JsonPrimitive
        return redacted.content.replace(Regex("[\\p{Cntrl}<>`*#\\[\\]]"), " ").replace(Regex("\\s+"), " ")
            .trim().take(120).dropLastWhile(Char::isHighSurrogate).trimEnd().ifBlank { "your repository request" }
    }

    fun introduction(task: String): String = when {
        GitHubStatusRequest.matches(task) -> "I’ll check the repository status, current branch, pull requests and latest checks. Next, I’ll identify which work is ready, still running or needs attention."
        GitHubTaskCoverage.matches(task) -> "I’ll identify the authorized GitHub operations and their available test coverage. Next, I’ll exercise the relevant paths and keep observed failures separate from unexecuted checks."
        Regex("(?i)\\b(?:fix|repair|debug|resolve)\\b").containsMatchIn(task) -> "I’ll trace the reported failure through the affected implementation and its recent changes. Next, I’ll reproduce the relevant behavior and choose a repair supported by that evidence."
        Regex("(?i)\\b(?:review|examine|investigate|post[- ]?mortem|diagnos[a-z]*)\\b").containsMatchIn(task) -> "I’ll connect the available diagnostics and repository changes to the behavior under review. Next, I’ll inspect the relevant source and distinguish confirmed findings from questions that need validation."
        else -> "I’ll locate the implementation and repository state relevant to your request. Next, I’ll identify the required change and the evidence needed to check its behavior."
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

    private val liveCounter = Regex("""^(checks?|files?|steps?|pages?|jobs?|items?|records?|operations?|tools?)\s*:?\s*\d+\s*/\s*\d+\b""", RegexOption.IGNORE_CASE)

    /** Reuse the row when an observed collection advances. */
    fun liveKey(text: String): String? {
        val kind = liveCounter.find(text.trimStart())?.groupValues?.get(1)?.lowercase(java.util.Locale.ROOT)?.removeSuffix("s") ?: return null
        return "github-$kind-counts"
    }

    /** Compare displayed lines, including whitespace and formatting differences. */
    fun lineKey(text: String): String = preview(text).replace(Regex("\\s+"), " ").trim().trimEnd('.', '…').lowercase(java.util.Locale.ROOT)

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
