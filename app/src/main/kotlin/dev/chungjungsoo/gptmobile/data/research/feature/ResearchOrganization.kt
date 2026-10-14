package dev.chungjungsoo.gptmobile.data.research.feature

/** Deterministic broad topics work with no model loaded. Manual non-default topics take priority. */
internal object ResearchOrganization {
    private val topics = linkedMapOf(
        "technology" to ("Computing and technology" to "computer|software|rendering|graphics|machine learning|neural|algorithm|gpu|game"),
        "health-life" to ("Health and life sciences" to "clinical|patient|disease|cancer|health|medical|cell|protein|sleep|trial|biolog"),
        "environment" to ("Environment and climate" to "climate|ecosystem|environment|carbon|biodiversity|ocean"),
        "society-humanities" to ("Society and culture" to "social|education|history|culture|language|philosophy"),
        "business-economics" to ("Business and economics" to "econom|market|finance|business|inflation")
    )
    fun topic(title: String, subjects: List<String>): Pair<String, String>? =
        topics.entries.firstOrNull { Regex(it.value.second, RegexOption.IGNORE_CASE).containsMatchIn(subjects.joinToString(" ") + " " + title) }?.let { it.key to it.value.first }
}
