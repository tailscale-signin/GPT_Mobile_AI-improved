package dev.chungjungsoo.gptmobile.data.agent

/** Request complexity, independent of enabled profile count and accumulated source volume. */
object CombinedRequestScope {
    private val broad = Regex("(?i)\\b(?:comprehensive|exhaustive|in[- ]depth|detailed|history|survey|analysis|research|build|create|design|plan|novel|essay|report|tutorial|latest|current|today|news|prices?|pricing|medical|medication|dosage|diagnosis|legal|tax|investing|investigate|review|implement|debug|code|all contributors|every (?:profile|contributor)|step[- ]by[- ]step)\\b")
    private val length = Regex("(?i)\\b[0-9,]+\\s*[-–]?\\s*words?\\b")

    fun isStraightforward(request: String): Boolean = request.isNotBlank() &&
        request.length <= 240 &&
        request.lines().count { it.isNotBlank() } <= 2 &&
        !broad.containsMatchIn(request) &&
        !length.containsMatchIn(request)

    fun defaultMaximumWords(@Suppress("UNUSED_PARAMETER") request: String): Int? = null

    fun participants(request: String, selected: List<String>, primary: String?, adaptive: Boolean, research: Boolean): List<String> {
        if (!adaptive || research || !isStraightforward(request) || selected.size <= 3) return selected
        val lead = primary?.takeIf { it in selected }
        val chosen = (listOfNotNull(lead) + selected).distinct().take(3).toSet()
        return selected.filter { it in chosen }
    }
}
