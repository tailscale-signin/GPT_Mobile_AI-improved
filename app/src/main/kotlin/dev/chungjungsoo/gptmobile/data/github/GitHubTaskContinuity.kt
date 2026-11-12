package dev.chungjungsoo.gptmobile.data.github

/** Only explicit controls and equivalent integration-test requests may reuse the active task. */
internal object GitHubTaskContinuity {
    private fun normalized(text: String): String = request(text).lowercase(java.util.Locale.ROOT)
        .trimEnd('.', '!', '?').trim().replace(Regex("\\s+"), " ").removePrefix("please ")

    /** Strip only the exact workspace header emitted by GitHubProgrammerViewModel. */
    fun request(text: String): String = text.trim().replaceFirst(
        Regex("^GitHub workspace: [^\\s/]+/[^\\s/]+\\r?\\nBranch: [^\\r\\n]+\\r?\\n\\r?\\n"),
        ""
    ).trim()

    fun diagnostic(text: String): Boolean {
        if (sessionAudit(text)) return true
        val prompt = request(text)
        // The subject must be the assistant or this conversation. A polite "can you
        // explain why CI stopped" still asks for repository evidence, not old diagnostics.
        val subject = "(?:you|your (?:(?:previous|last|current) )?(?:answer|response|run)|" +
            "(?:the )?(?:previous|last) (?:answer|response)|the (?:assistant|answer|response|reviewer)|" +
            "(?:this|the|our) (?:chat|conversation)(?: run)?)"
        val auxiliary = "(?:(?:did|do|does|is|are|was|were|has|have|had|been|being|got|get|still|just|suddenly|unexpectedly)\\s+){0,3}"
        val problem = "(?:stop(?:ped|ping)?|interrupt(?:ed|ion)?|incomplete|fail(?:ed|ing|ure|ures)?|blank|empty|withheld|blocked|stuck)"
        val explanation = "(?:why\\s+$auxiliary|(?:explain|reason(?: for)?)\\s+)"
        return Regex("(?i)\\b$explanation$subject\\s+$auxiliary$problem\\b").containsMatchIn(prompt) ||
            Regex("(?i)\\bwhat happened to $subject\\s*[?.!]*$").containsMatchIn(prompt)
    }

    fun sessionAudit(text: String): Boolean = request(text).length <= 300 &&
        Regex(
            "^(?:(?:list|find|show|identify|examine|review|audit|summarize) (?:me )?(?:all |the )?(?:issues|errors|failures|problems|bugs) (?:with|in|from|for) (?:this|the current|our) (?:github )?(?:session|chat|conversation)|audit (?:this|the current) (?:github )?(?:session|chat|conversation))(?: (?:using|with) github)?(?: and (?:offer|list|suggest) (?:fixes|solutions))?$"
        ).matches(normalized(text))

    /** This identifies a resume request only. The claimed commit is verified by read receipts. */
    fun publishedCommit(text: String): String? = GitHubNativeContinuation.publishedCommit(text)

    fun control(text: String): Boolean = normalized(text) in
        setOf(
            "test again", "retest", "continue", "continue research", "continue run", "continue response", "resume", "proceed",
            "retry", "retry full", "retry failed stage", "try again", "proceed primary-only", "proceed primary only",
            "continue primary-only", "continue primary only", "primary-only"
        ) ||
        GitHubNativeContinuation.matches(text)

    fun followUp(text: String): Boolean = normalized(text) in
        setOf(
            "yes", "yes please", "do it", "fix", "fix it", "fix them", "apply the fixes", "implement the fixes",
            "finish", "finish it", "finish the task", "finish the work", "finalize", "complete the task",
            "do the remaining work", "do all remaining work", "finish the remaining work", "complete the remaining work",
            "continue the work", "proceed with the remaining work", "go ahead"
        )

    /** A request for the saved report authorizes delivery, not another mutation or test run. */
    fun reportOnly(text: String): Boolean = normalized(text) in
        setOf("show the report", "show the full report", "give me the full report", "show me the full report", "full report", "show the results", "summarize the results", "retry writing", "resume writing", "continue writing")

    fun continuation(text: String): Boolean = control(text) || followUp(text) || reportOnly(text) || diagnostic(text)

    fun resolved(current: String, previous: String): String {
        if (!followUp(current) || previous.isBlank()) return request(current)
        val action = if (Regex("(?i)fix|implement|apply").containsMatchIn(current)) "Implement and validate the fixes identified for the following saved request" else "Continue the following saved request using its retained findings and the user's accepted next step"
        return "$action:\n\n${request(previous)}"
    }

    fun reuse(current: String, previous: String): Boolean = previous.isNotBlank() &&
        (
            continuation(current) ||
                GitHubTestChecklist.integration(request(current)) &&
                GitHubTestChecklist.integration(request(previous)) &&
                (request(current) == request(previous) || bareIntegrationContinuation.matches(normalized(current))) &&
                !Regex("(?i)\\b(?:new|fresh|restart|start over|different)\\b").containsMatchIn(request(current))
            )

    // A bare retest inherits the accepted restrictions. Extra instructions, budgets,
    // selectors or authorization changes must never be replaced by the previous task.
    private val bareIntegrationContinuation = Regex(
        "^(?:(?:test|retest|check) (?:(?:all|every|the|my|connected|authorized|available|enabled|native) )*(?:github )?(?:integration )?(?:tools?|functionality|integration)(?: again)?|test all)$"
    )

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
            (verdict["stop_reason"] as? kotlinx.serialization.json.JsonObject)?.let { stop ->
                append("\nScheduler stop: ${stop.text("code").replace(Regex("[^A-Z0-9_]"), " ")}. ${stop.text("detail").replace(Regex("[\\p{Cntrl}<>`*\\[\\]]"), " ")}")
            }
            last?.let { append(" Last successful operation: `${it.text("tool").replace(Regex("[^A-Za-z0-9_]"), "")}`.") }
            if (unfinished.isNotEmpty()) append(" ${unfinished.size} checklist capabilities remained without a passing observation.")
            (verdict["execution_ledger"] as? kotlinx.serialization.json.JsonObject)?.let { ledger ->
                append("\nTask: ${ledger.text("requested_task").lowercase().replace('_', ' ')}. Verification: ${ledger.text("verification").lowercase()}. Answer delivery: ${ledger.text("delivery").lowercase()}.")
                if (ledger.text("recovery") == "RECONCILE_REMOTE_OUTCOME") append(" A remote operation has an unknown outcome. Check its saved receipt before any further write.")
                if (ledger["user_input_required"] == kotlinx.serialization.json.JsonPrimitive(true)) append(" A recorded user decision is required to continue.")
            }
            append(" These are historical diagnostics, not a new validation of the repository or running app.")
        }
    }
}
