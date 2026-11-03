package dev.chungjungsoo.gptmobile.data.github

/** Only explicit controls and equivalent integration-test requests may reuse the active task. */
internal object GitHubTaskContinuity {
    fun diagnostic(text: String): Boolean = Regex("(?i)\\b(?:why|explain|what happened|reason)\\b").containsMatchIn(text) && Regex("(?i)\\b(?:stopp?ed|stop|interrupt(?:ed|ion)?|incomplete|previous (?:run|response)|last (?:run|response))\\b").containsMatchIn(text)
    fun reuse(current: String, previous: String): Boolean = diagnostic(current) ||
        current.trim().lowercase().trimEnd('.', '!', '?') in setOf("continue", "continue run", "resume", "proceed", "retry", "try again") ||
        GitHubTestChecklist.integration(current) &&
        GitHubTestChecklist.integration(previous) &&
        !Regex("(?i)\\b(?:new|fresh|restart|start over|different)\\b").containsMatchIn(current)

    fun explain(verdict: kotlinx.serialization.json.JsonObject?, receipts: Map<String, kotlinx.serialization.json.JsonObject>): String {
        fun kotlinx.serialization.json.JsonObject.text(key: String) = (get(key) as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
        if (verdict == null) return "The previous run's terminal verification record is unavailable. Its cause cannot be established from the retained operation receipts. ${receipts.size} prior receipts are available; successful tool calls alone do not establish task completion."
        val reason = verdict.text("verification_failure").ifBlank { verdict.text("failure_code") }.ifBlank { "No failure code was recorded" }
        val last = receipts.values.lastOrNull { it["success"] == kotlinx.serialization.json.JsonPrimitive(true) }
        val unfinished = ((verdict["test_checklist"] as? kotlinx.serialization.json.JsonObject)?.get("capabilities") as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { it as? kotlinx.serialization.json.JsonObject }.filter { it.text("status") != "PASSED" }
        return buildString {
            append("The previous response ended with **${reason.replace(Regex("[^A-Z0-9_]"), " ")}**. ")
            append(
                when (reason) {
                    "GITHUB_TASK_INCOMPLETE" -> "The completion check found unfinished requirements. This is partial task completion; it does not establish a tool or transport failure."
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
