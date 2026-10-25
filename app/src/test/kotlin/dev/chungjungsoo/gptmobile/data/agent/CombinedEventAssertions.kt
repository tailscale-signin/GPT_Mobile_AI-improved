package dev.chungjungsoo.gptmobile.data.agent

/** The final full-body event replaces any provisional stream in the same revision. */
internal fun List<ProviderEvent>.combinedAnswerText(): String = fold("") { answer, event ->
    when (event) {
        is ProviderEvent.CombinedAnswer -> event.text
        is ProviderEvent.TextDelta -> answer + event.text
        else -> answer
    }
}

internal fun List<ProviderEvent>.combinedReport(): CombinedQualityReport = mapNotNull { event ->
    when (event) {
        is ProviderEvent.CombinedAnswer -> event.report
        is ProviderEvent.CombinedQuality -> event.report
        else -> null
    }
}.last()
