package dev.chungjungsoo.gptmobile.data.agent.tool

enum class SearchContentMode { OFF, SHADOW, ENABLED }

/** URL-only grouping ships first. Content suppression is an independently reversible trial. */
data class SearchMergePolicy(
    val version: Int = 2,
    val fetchLimitPerEngine: Int = 10,
    val reuseIdenticalRequests: Boolean = true,
    val dedupeUrls: Boolean = true,
    val contentMode: SearchContentMode = SearchContentMode.SHADOW,
    val snippetThreshold: Double = 0.70,
    val titleThreshold: Double = 0.60,
    val maxCandidates: Int = 1000,
    val maxContentComparisons: Int = 20_000
) {
    init {
        require(version > 0 && fetchLimitPerEngine in 1..10)
        require(snippetThreshold in 0.0..1.0 && titleThreshold in 0.0..1.0)
        require(maxCandidates in 1..2000 && maxContentComparisons in 0..100_000)
    }
}
