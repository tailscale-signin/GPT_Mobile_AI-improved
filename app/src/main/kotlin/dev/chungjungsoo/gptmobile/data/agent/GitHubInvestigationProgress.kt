package dev.chungjungsoo.gptmobile.data.agent

/** Bounded working state; bulk receipts remain in the durable task/evidence stores. */
internal class GitHubInvestigationProgress {
    private val observed = linkedSetOf<String>()
    private val recentPageFailures = ArrayDeque<Boolean>()
    private var staleRounds = 0

    fun observe(exchange: AgentToolExchange): String? {
        var acquired = false
        for ((call, result) in exchange.calls.zip(exchange.results)) {
            val key = toolRepeatKey(call)
            if (!result.isError && key !in observed) acquired = true
            observed += key
            while (observed.size > 256) observed.remove(observed.first())
        }
        staleRounds = if (acquired) 0 else staleRounds + 1
        recentPageFailures.addLast(exchange.results.any { it.errorCode in pageFailures })
        while (recentPageFailures.size > 8) recentPageFailures.removeFirst()
        return when {
            recentPageFailures.count { it } >= 4 -> "Repeated saved-page failures prevented further progress. Report the retained findings, exact failures and remaining source-recovery actions."
            staleRounds >= 4 -> "Four rounds added no new successful operation. Report the retained findings and remaining blockers without repeating the investigation."
            else -> null
        }
    }

    private val pageFailures = setOf("SAVED_VIEW_UNAVAILABLE", "SAVED_VIEW_WRONG_OPERATION", "SAVED_VIEW_CURSOR_ALTERED")
}
