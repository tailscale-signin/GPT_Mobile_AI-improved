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

    /** Keep the opening anchored to the user's actual request, not a stock GitHub-status script. */
    fun taskFocus(task: String): String {
        val request = taskLabel(task)
            .replace(Regex("""(?i)^(?:(?:please|can you|could you|would you)\\s+)*"""), "")
            .replace(Regex("""(?i)^(?:fix|repair|debug|resolve|review|examine|exaine|inspect|investigate|analyze|audit|improve|enhance|redesign|design|change|update|make|implement|add|check|look at|merge)\\s+"""), "")
            .substringBefore(',').substringBefore(';').trim().trimEnd('.', '?', '!', ':')
        val clipped = request.take(78).trim()
        return (if (request.length > 78) clipped.substringBeforeLast(' ', clipped) else clipped)
            .ifBlank { "your repository request" }
    }

    fun introduction(task: String): String {
        val focus = taskFocus(task)
        return when {
            GitHubStatusRequest.matches(task) ->
                "I’ll check the repository status for $focus, including pull requests and current checks, and separate confirmed results from pending work."
            Regex("""(?i)\\bsummar""").containsMatchIn(task) ->
                "I’ll refine $focus around your request and concrete results, replacing repeated labels with short, distinct updates. I’ll check the opening message too."
            GitHubTaskCoverage.matches(task) ->
                "I’ll map $focus to available GitHub operations and their test evidence without treating unexecuted checks as successes."
            Regex("""(?i)\\b(?:fix|repair|debug|resolve|crash|failure)\\b""").containsMatchIn(task) ->
                "I’ll trace $focus through the current branch and verify the affected behavior before calling any repair complete."
            Regex("""(?i)\\b(?:merge|release|publish)\\b""").containsMatchIn(task) ->
                "I’ll check $focus against the latest PR, required checks and review state before treating it as ready to ship."
            Regex("""(?i)\\b(?:review|examine|exaine|investigate|audit|diagnos|analy[sz]e)""").containsMatchIn(task) ->
                "I’ll examine $focus in the current branch, distinguish confirmed evidence from assumptions, and report the meaningful gaps."
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
