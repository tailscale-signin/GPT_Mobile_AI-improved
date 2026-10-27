package dev.chungjungsoo.gptmobile.data.github

/** Evict account state together so quota and cache identities cannot diverge. */
internal object GitHubSessionCaches {
    private data class State(val cache: GitHubResponseCache = GitHubResponseCache(), val limits: GitHubRateLimitManager = GitHubRateLimitManager(), val scheduler: GitHubRequestScheduler = GitHubRequestScheduler(), val store: GitHubRepositoryStateStore = GitHubRepositoryStateStore(), val authenticationRejected: java.util.concurrent.atomic.AtomicBoolean = java.util.concurrent.atomic.AtomicBoolean())
    private val states = LinkedHashMap<String, State>(8, 0.75f, true)

    @Synchronized private fun state(token: String): State = states.getOrPut(GitHubRepositoryStateStore.digest(token)) { State() }.also {
        while (states.size > 8) states.remove(states.keys.first())?.cache?.clear()
    }
    fun authenticationForCredential(token: String): java.util.concurrent.atomic.AtomicBoolean = state(token).authenticationRejected
    fun forCredential(token: String): GitHubResponseCache = state(token).cache
    fun rateLimitsForCredential(token: String): GitHubRateLimitManager = state(token).limits
    fun schedulerForCredential(token: String): GitHubRequestScheduler = state(token).scheduler
    fun stateForCredential(token: String): GitHubRepositoryStateStore = state(token).store

    @Synchronized fun clear() {
        states.values.forEach { it.cache.clear() }
        states.clear()
    }
}
