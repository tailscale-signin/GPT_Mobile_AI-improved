package dev.chungjungsoo.gptmobile.data.agent

import java.net.URI

/** Conservative local checks. A passing format/host check does not certify a model price. */
internal object CombinedPricingContract {
    private val amount = Regex("(?i)(?:[$€£]\\s*[0-9]+|\\b(?:USD|CAD|EUR|GBP)\\s*[0-9]+|[0-9]+(?:\\.[0-9]+)?\\s*(?:USD|CAD|EUR|GBP)\\b|\\b(?:free|zero[- ]cost)\\b)")
    private val missing = Regex("(?i)\\b(?:could not verify|couldn.t verify|cannot verify|can.t verify|not verified|unverified|unavailable|not found|unable to verify|could not find|couldn.t find)\\b")
    fun check(answer: String, evidence: CombinedEvidenceLedger, policy: CombinedAnswerPolicy): List<CombinedAnswerCheck> = buildList {
        val domains = policy.task?.subjects.orEmpty().mapNotNull { subject ->
            when (subject.lowercase()) {
                "llm7" -> "llm7.io"
                "openrouter" -> "openrouter.ai"
                else -> null
            }
        }
        val official = evidence.records.filter { record ->
            val host = runCatching { URI(record.url).host?.lowercase() }.getOrNull().orEmpty()
            record.readable && record.retrievedAt != null && (domains.isEmpty() || domains.any { host == it || host.endsWith(".$it") })
        }
        val hasAmounts = amount.containsMatchIn(answer)
        if (hasAmounts && official.isEmpty()) add(CombinedAnswerCheck("OFFICIAL_PRICE_EVIDENCE_MISSING", "EVIDENCE", "FAIL", true, message = "Numerical or free-price claims lack readable official evidence with a retrieval date."))
        if (!hasAmounts && !missing.containsMatchIn(answer)) add(CombinedAnswerCheck("PRICE_OR_EXPLICIT_LIMITATION_REQUIRED", "REQUEST", "FAIL", message = "State the comparable verified prices or explain what could not be verified."))
        if (hasAmounts && policy.comparative) {
            val units = Regex("(?i)\\b(?:input|prompt)\\b").containsMatchIn(answer) && Regex("(?i)\\b(?:output|completion)\\b").containsMatchIn(answer) && Regex("(?i)\\b(?:tokens?|million|1m)\\b").containsMatchIn(answer)
            if (!units) add(CombinedAnswerCheck("MATCHED_PRICE_UNITS_MISSING", "REQUEST", "FAIL", message = "Compare the same model and currency with explicit input and output token units."))
            domains.forEach { domain ->
                if (official.none { URI(it.url).host.orEmpty().let { host -> host == domain || host.endsWith(".$domain") } } && !missing.containsMatchIn(answer)) add(CombinedAnswerCheck("OFFICIAL_PRICE_SOURCE_MISSING:$domain", "EVIDENCE", "FAIL", true))
            }
        }
    }
}
