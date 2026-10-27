package dev.chungjungsoo.gptmobile.data.research.feature

/** Scientific artifacts and short verification receipts have different contracts. */
object ExaminationOutputBudget {
    const val APPRAISAL = 6144
    const val INDEPENDENT_REVIEW = 2048
    const val STUDY_REVIEW = 4096
    const val PART = 1024
    const val INSUFFICIENT = "RESEARCH_OUTPUT_BUDGET_INSUFFICIENT"
    const val CONTEXT_INSUFFICIENT = "RESEARCH_CONTEXT_BUDGET_INSUFFICIENT"

    fun minimum(role: String?): Int? = when (role) {
        "research-appraisal" -> APPRAISAL
        "research-review" -> 256
        "research-study-review" -> STUDY_REVIEW
        "research-extraction", "research-appraisal-part", "research-study-review-part" -> PART
        else -> null
    }

    fun examinerBlocker(available: Int): String? = if (available < PART) {
        "EXAMINER_OUTPUT_CAPACITY: This profile allows $available output tokens; article appraisal requires at least $PART. Choose a capable Examiner or increase its allowance. No model request was sent."
    } else {
        null
    }

    fun requireCapacity(role: String?, available: Long) {
        val required = minimum(role) ?: return
        check(available >= required) { "$INSUFFICIENT: role=$role required=$required available=$available; use a smaller independently valid artifact" }
    }

    fun canSplit(failure: Throwable): Boolean = generateSequence(failure) { it.cause?.takeUnless { next -> next === it } }.take(12).any {
        it.message.orEmpty().contains(INSUFFICIENT) ||
            it.message.orEmpty().contains("OUTPUT_LIMIT", ignoreCase = true) ||
            it.message.orEmpty().contains("max output tokens", ignoreCase = true) ||
            it.message.orEmpty().contains("maximum output tokens", ignoreCase = true) ||
            it.message.orEmpty().contains(CONTEXT_INSUFFICIENT) ||
            it is dev.chungjungsoo.gptmobile.data.agent.tool.DelegateGenerationException &&
            it.kind == dev.chungjungsoo.gptmobile.data.agent.tool.DelegateFailureKind.OUTPUT_LIMIT
    }
}

@kotlinx.serialization.Serializable
data class ExaminationSlice(val start: Int, val end: Int, val compact: Boolean = false) {
    fun text(source: String): Pair<Int, String> {
        require(start >= 0 && end > start && end <= source.length) { "Invalid saved appraisal slice" }
        return start to source.substring(start, end)
    }

    /** Split only unfinished work. Earlier indexes and passage IDs never move. */
    fun split(source: String): List<ExaminationSlice> {
        text(source)
        check(end - start > 512) { "${ExaminationOutputBudget.INSUFFICIENT}: smallest appraisal chunk cannot complete; choose a capable Examiner or increase its output allowance; captured evidence retained" }
        var middle = start + (end - start) / 2
        if (source[middle].isLowSurrogate() && source[middle - 1].isHighSurrogate()) middle--
        return listOf(ExaminationSlice(start, middle, true), ExaminationSlice(middle, end, true))
    }
}
