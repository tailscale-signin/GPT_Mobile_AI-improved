package dev.chungjungsoo.gptmobile.data.agent.tool

internal enum class EngineeringCheckStatus { QUEUED, IN_PROGRESS, COMPLETED }

internal enum class EngineeringCheckConclusion {
    SUCCESS,
    FAILURE,
    CANCELLED,
    TIMED_OUT,
    ACTION_REQUIRED,
    NEUTRAL,
    SKIPPED,
    UNKNOWN
}

internal data class EngineeringCheckReceipt(
    val name: String,
    val status: EngineeringCheckStatus,
    val conclusion: EngineeringCheckConclusion? = null,
    val headSha: String,
    val runId: Long? = null,
    val appId: Long? = null,
    val source: String = "check_run",
    val startedAt: String? = null
)

internal data class EngineeringRequiredCheck(val name: String, val appId: Long? = null)

internal enum class EngineeringCiVerdict { PENDING, PASSED, FAILED }

/** Absence of a required check never counts as a pass. */
internal object GitHubEngineeringCiPolicy {
    fun evaluate(
        expectedHeadSha: String,
        requiredNames: Set<String>,
        receipts: List<EngineeringCheckReceipt>
    ): EngineeringCiVerdict = evaluateRules(expectedHeadSha, requiredNames.map { EngineeringRequiredCheck(it) }.toSet(), receipts)

    fun evaluateRules(
        expectedHeadSha: String,
        required: Set<EngineeringRequiredCheck>,
        receipts: List<EngineeringCheckReceipt>
    ): EngineeringCiVerdict {
        if (required.isEmpty()) return EngineeringCiVerdict.PENDING
        val matching = receipts.filter { it.headSha == expectedHeadSha }
        var pending = false
        for (rule in required) {
            val candidates = matching.filter { it.name == rule.name && (rule.appId == null || it.appId == rule.appId && it.source == "check_run") }
            // Same name from different apps is not interchangeable. A pinned app
            // resolves this explicitly; otherwise policy cannot pick a winner.
            val producers = candidates.groupBy { it.source to it.appId }
            if (producers.size != 1) {
                pending = true
                continue
            }
            val attempts = producers.values.single()
            val current = if (attempts.size == 1) {
                attempts.single()
            } else if (attempts.all { it.runId != null } && attempts.map { it.runId }.distinct().size == attempts.size) {
                attempts.maxBy { requireNotNull(it.runId) }
            } else {
                null
            }
            if (current == null || current.status != EngineeringCheckStatus.COMPLETED) {
                pending = true
                continue
            }
            when (current.conclusion) {
                EngineeringCheckConclusion.SUCCESS -> Unit
                EngineeringCheckConclusion.FAILURE, EngineeringCheckConclusion.CANCELLED,
                EngineeringCheckConclusion.TIMED_OUT, EngineeringCheckConclusion.ACTION_REQUIRED -> return EngineeringCiVerdict.FAILED
                else -> pending = true
            }
        }
        return if (pending) EngineeringCiVerdict.PENDING else EngineeringCiVerdict.PASSED
    }
}
