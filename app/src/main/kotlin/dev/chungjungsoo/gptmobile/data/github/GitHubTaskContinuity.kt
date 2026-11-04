package dev.chungjungsoo.gptmobile.data.github

/** Only explicit controls and equivalent integration-test requests may reuse the active task. */
internal object GitHubTaskContinuity {
    /** Strip only the exact workspace header emitted by GitHubProgrammerViewModel. */
    fun request(text: String): String = text.trim().replaceFirst(
        Regex("^GitHub workspace: [^\\s/]+/[^\\s/]+\\r?\\nBranch: [^\\r\\n]+\\r?\\n\\r?\\n"),
        ""
    ).trim()

    fun diagnostic(text: String): Boolean {
        val prompt = request(text)
        val question = Regex("(?i)\\b(?:why|explain|what happened|reason)\\b").containsMatchIn(prompt)
        val previous = Regex("(?i)\\b(?:stopp?ed|stop|interrupt(?:ed|ion)?|incomplete|previous (?:run|response)|last (?:run|response))\\b").containsMatchIn(prompt)
        val assistantFailure = Regex("(?i)\\b(?:you|your (?:answer|response)|the (?:assistant|answer|response|reviewer))\\b").containsMatchIn(prompt) &&
            Regex("(?i)\\b(?:fail(?:ed|ing|ure|ures)?|blank|empty|withheld|blocked|stuck)\\b").containsMatchIn(prompt)
        return question && (previous || assistantFailure)
    }

    /** This identifies a resume request only. The claimed commit is verified by read receipts. */
    fun publishedCommit(text: String): String? = Regex(
        "^Commit ([a-fA-F0-9]{40}) was approved and published\\. Continue from the saved task using this exact commit\\. Subscribe with github_watch_checks when CI is pending; do not repeat the commit\\.$"
    ).matchEntire(request(text))?.groupValues?.get(1)?.lowercase(java.util.Locale.ROOT)

    fun control(text: String): Boolean = request(text).lowercase(java.util.Locale.ROOT).trimEnd('.', '!', '?').trim() in
        setOf("test again", "retest", "continue", "continue run", "continue response", "resume", "proceed", "retry", "retry failed stage", "try again") ||
        publishedCommit(text) != null

    fun followUp(text: String): Boolean = request(text).lowercase(java.util.Locale.ROOT).trimEnd('.', '!', '?') in
        setOf("yes", "yes please", "do it", "fix", "fix it", "fix them", "apply the fixes", "implement the fixes")

    fun resolved(current: String, previous: String): String {
        if (!followUp(current) || previous.isBlank()) return request(current)
        val action = if (Regex("(?i)fix|implement|apply").containsMatchIn(current)) "Implement and validate the fixes identified for the following saved request" else "Continue the following saved request using its retained findings and the user's accepted next step"
        return "$action:\n\n${request(previous)}"
    }

    fun reuse(current: String, previous: String): Boolean = previous.isNotBlank() &&
        followUp(current) ||
        diagnostic(current) ||
        control(current) ||
        GitHubTestChecklist.integration(request(current)) &&
        GitHubTestChecklist.integration(request(previous)) &&
        !Regex("(?i)\\b(?:new|fresh|restart|start over|different)\\b").containsMatchIn(request(current))

    fun explain(verdict: kotlinx.serialization.json.JsonObject?, receipts: Map<String, kotlinx.serialization.json.JsonObject>): String {
        fun kotlinx.serialization.json.JsonObject.text(key: String) = (get(key) as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
        if (verdict == null) return "The previous run's terminal verification record is unavailable. Its cause cannot be established from the retained operation receipts. ${receipts.size} prior receipts are available; successful tool calls alone do not establish task completion."
        if (verdict.text("phase") == "IN_PROGRESS") {
            val remaining = (verdict["remaining_requirements"] as? kotlinx.serialization.json.JsonArray).orEmpty().size
            return "The saved workflow checkpoint precedes final acceptance verification. $remaining requirements remain unverified, and ${receipts.size} operation receipts are retained. The cause of the interruption is not recorded. Resume can reconcile the retained evidence without repeating completed writes."
        }
        val reason = verdict.text("verification_failure").ifBlank { verdict.text("failure_code") }.ifBlank { "No failure code was recorded" }
        val last = receipts.values.lastOrNull { it["success"] == kotlinx.serialization.json.JsonPrimitive(true) }
        val unfinished = ((verdict["test_checklist"] as? kotlinx.serialization.json.JsonObject)?.get("capabilities") as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { it as? kotlinx.serialization.json.JsonObject }.filter { it.text("status") !in setOf("PASSED", "RECOVERED", "OUT_OF_SCOPE", "NOT_REQUESTED") }
        return buildString {
            append("The previous response ended with **${reason.replace(Regex("[^A-Z0-9_]"), " ")}**. ")
            append(
                when (reason) {
                    "GITHUB_TASK_INCOMPLETE" -> "The completion check found unfinished requirements. This is partial task completion; it does not establish a tool or transport failure."
                    "GITHUB_REVIEW_INVALID_VERDICT", "GITHUB_REVIEW_BLOCKED" -> "The reviewer did not supply a valid structured verdict. This does not prove that a GitHub operation failed; the private draft remains unverified. Retry can resume the bounded format recovery without repeating completed operations."
                    "ANSWER_REQUEST_UNSATISFIED", "EMPTY_ANSWER", "EMPTY_MODEL_ROUND" -> "The model produced no usable visible answer. Reasoning or token usage alone cannot satisfy the request."
                    "GITHUB_EVIDENCE_REQUIRED" -> "The answer verifier could not establish the requested claims from the evidence supplied to that run."
                    else -> "This is the recorded terminal reason; no additional cause has been established."
                }
            )
            append("\n\nRetained operations: ${receipts.size}; successful: ${receipts.values.count { it["success"] == kotlinx.serialization.json.JsonPrimitive(true) }}.")
            last?.let { append(" Last successful operation: `${it.text("tool").replace(Regex("[^A-Za-z0-9_]"), "")}`.") }
            if (unfinished.isNotEmpty()) append(" ${unfinished.size} checklist capabilities remained without a passing observation.")
            append(" These are historical diagnostics, not a new validation of the repository or running app.")
        }
    }
}
