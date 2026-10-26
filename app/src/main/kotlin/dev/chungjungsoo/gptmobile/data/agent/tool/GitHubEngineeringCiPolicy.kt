package dev.chungjungsoo.gptmobile.data.agent.tool

internal enum class EngineeringCheckStatus { QUEUED, IN_PROGRESS, COMPLETED }

internal enum class EngineeringCheckConclusion {
    SUCCESS, FAILURE, CANCELLED, TIMED_OUT, ACTION_REQUIRED, NEUTRAL, SKIPPED, UNKNOWN
}

internal data class EngineeringCheckReceipt(
    val name: String,
    val status: EngineeringCheckStatus,
    val conclusion: EngineeringCheckConclusion? = null,
    val headSha: String,
)

internal enum class EngineeringCiVerdict { PENDING, PASSED, FAILED }

/** Absence of a required check never counts as a pass. */
internal object GitHubEngineeringCiPolicy {
    fun evaluate(
        expectedHeadSha: String,
        requiredNames: Set<String>,
        receipts: List<EngineeringCheckReceipt>,
    ): EngineeringCiVerdict {
        if (requiredNames.isEmpty()) return EngineeringCiVerdict.PENDING
        val matching = receipts.filter { it.headSha == expectedHeadSha }
        for (name in requiredNames) {
            val candidates = matching.filter { it.name == name }
            if (candidates.isEmpty()) return EngineeringCiVerdict.PENDING
            if (candidates.any {
                it.status == EngineeringCheckStatus.COMPLETED &&
                    it.conclusion in setOf(
                        EngineeringCheckConclusion.FAILURE,
                        EngineeringCheckConclusion.CANCELLED,
                        EngineeringCheckConclusion.TIMED_OUT,
                        EngineeringCheckConclusion.ACTION_REQUIRED,
                    )
            }) return EngineeringCiVerdict.FAILED
            if (candidates.none {
                it.status == EngineeringCheckStatus.COMPLETED &&
                    it.conclusion == EngineeringCheckConclusion.SUCCESS
            }) return EngineeringCiVerdict.PENDING
        }
        return EngineeringCiVerdict.PASSED
    }
}
