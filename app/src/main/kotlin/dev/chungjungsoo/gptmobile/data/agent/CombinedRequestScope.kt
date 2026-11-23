package dev.chungjungsoo.gptmobile.data.agent

/** Request complexity, independent of enabled profile count and accumulated source volume. */
object CombinedRequestScope {
    private val broad = Regex("(?i)\\b(?:comprehensive|exhaustive|in[- ]depth|detailed|history|survey|analysis|research|build|create|design|plan|novel|essay|report|tutorial|latest|current|today|news|prices?|pricing|medical|medication|dosage|diagnosis|legal|tax|investing|investigate|review|implement|debug|code|verify|validate|fact[- ]check|forecast|financial|health|safety|risk|advice|sources?|citations?|references?|evidence|prove|proof|step[- ]by[- ]step)\\b")
    private val length = Regex("(?i)\\b[0-9,]+\\s*[-–]?\\s*words?\\b")
    private val explicitParticipants = Regex("(?i)\\b(?:all|every|each|both|two|three|four|five|six|seven|eight|nine|ten|[2-9]|[1-9][0-9]+)\\s+(?:of\\s+)?(?:the\\s+)?(?:(?:selected|enabled)\\s+)?(?:ai\\s+)?(?:models?|profiles?|contributors?|assistants?|providers?|reviewers?)\\b|^\\s*(?:ask|consult|use|run)\\b")

    fun isStraightforward(request: String): Boolean = request.isNotBlank() &&
        request.length <= 240 &&
        request.lines().count { it.isNotBlank() } <= 2 &&
        !broad.containsMatchIn(request) &&
        !length.containsMatchIn(request)

    fun defaultMaximumWords(@Suppress("UNUSED_PARAMETER") request: String): Int? = null

    fun requestsExplicitParticipants(request: String): Boolean = explicitParticipants.containsMatchIn(request)

    fun participants(request: String, selected: List<String>, primary: String?, adaptive: Boolean, research: Boolean): List<String> {
        if (!adaptive || research || !isStraightforward(request) || requestsExplicitParticipants(request)) return selected
        // Keep a second perspective in Combined mode. Only narrow, self-contained
        // requests use two; ordinary straightforward questions keep the existing three.
        val limit = if (RoutineTaskPolicy.classify(request) != null) 2 else 3
        if (selected.size <= limit) return selected
        val lead = primary?.takeIf { it in selected }
        val chosen = (listOfNotNull(lead) + selected).distinct().take(limit).toSet()
        return selected.filter { it in chosen }
    }
}
