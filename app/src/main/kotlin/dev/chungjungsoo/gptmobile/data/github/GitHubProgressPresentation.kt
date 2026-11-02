package dev.chungjungsoo.gptmobile.data.github

/** Keep the activity separate from the header's single authoritative duration. */
internal object GitHubProgressPresentation {
    enum class PreviewKind { WEB, INSPECT, EDIT, CHECKS, BRANCH, WAIT, ATTENTION, REVIEW, ACTIVITY }

    private val previewKinds = listOf(
        PreviewKind.WAIT to "waiting|awaiting|queued|paused|approval",
        PreviewKind.ATTENTION to "failed|failure|problem|error|attention|recovering",
        PreviewKind.WEB to "internet|web|webpages?|websites?|online|browsing",
        PreviewKind.EDIT to "editing|writing|patch(?:ing)?|implementing|change request|requested change|response together",
        PreviewKind.CHECKS to "builds?|tests?|testing|checks|validation|verifying",
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

    fun introduction(task: String): String = if (GitHubStatusRequest.matches(task)) {
        "I’ll check the repository status, current branch, pull requests and latest checks."
    } else {
        "I’ll look into “${taskLabel(task)}”, starting with the relevant code and current repository state."
    }
    private val duration = Regex("""\b\d{1,4}:\d{2}(?::\d{2})?\b|\b\d+\s*(?:ms|s|seconds?|minutes?|hours?)\s+elapsed\b|\belapsed\s*[:=]\s*\d+\s*(?:ms|s|seconds?|minutes?|hours?)?""", RegexOption.IGNORE_CASE)
    private val prefix = Regex("""^status\b\s*(?:[:·|—-]\s*)?""", RegexOption.IGNORE_CASE)

    private val technicalDetail = Regex("(?i)\\b(receipt|dispatch|schema|token|checkpoint|prefill|reconcil|provenance|gateway|tool_|github_)")

    fun preview(raw: String): String {
        val text = raw.trim()
        if (text.isBlank()) return ""
        return when {
            technicalDetail.containsMatchIn(text) -> "Reviewing the work and checking the next step."
            else -> text
        }
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
