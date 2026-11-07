package dev.chungjungsoo.gptmobile.data.github

/** Persisted monotonic versions fence inspections across overlapping workers, refreshes and restarts. */
internal object GitHubWatchObservationFence {
    fun nextGeneration(previous: GitHubCheckWatch?): Long = previous?.let { Math.addExact(it.observationGeneration, 1L) } ?: 0L

    fun accepts(captured: GitHubCheckWatch, current: GitHubCheckWatch?): Boolean = current != null &&
        !current.terminal &&
        current.id == captured.id &&
        current.observationGeneration == captured.observationGeneration &&
        current.startedAt == captured.startedAt &&
        current.headSha == captured.headSha &&
        current.followPullRequest == captured.followPullRequest
}
