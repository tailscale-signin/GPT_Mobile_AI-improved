package dev.chungjungsoo.gptmobile.data.agent

/** Local display recovery is not permission to replay a stale model request or expose revoked evidence. */
internal object CombinedFallbackEligibility {
    fun allows(reason: String): Boolean {
        val code = reason.substringBefore(':').trim()
        if (code == "DISPATCH_POLICY_CHANGED") {
            val fields = reason.substringAfter("changed fields=", "").substringBefore('.').split(',').map(String::trim)
            return fields.isNotEmpty() && fields.all { it in setOf("budget", "spend") }
        }
        return code in setOf("COMBINED_CONTEXT_CAPACITY", "COMBINED_OUTPUT_ALLOWANCE_CHANGED", "COMBINED_WRITER_PLAN_OUTPUT_INSUFFICIENT", "COMBINED_DELIVERY_LIMIT", "MEMORY_ADMISSION_REJECTED")
    }
}
