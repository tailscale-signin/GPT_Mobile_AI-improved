package dev.chungjungsoo.gptmobile.data.github

/** Evict account state together so quota and cache identities cannot diverge. */
internal object GitHubSessionCaches {
    private data class State(val cache: GitHubResponseCache = GitHubResponseCache(), val limits: GitHubRateLimitManager = GitHubRateLimitManager(), val scheduler: GitHubRequestScheduler = GitHubRequestScheduler(), val store: GitHubRepositoryStateStore = GitHubRepositoryStateStore(), val authenticationRejected: java.util.concurrent.atomic.AtomicBoolean = java.util.concurrent.atomic.AtomicBoolean())
    private val states = LinkedHashMap<String, State>(8, 0.75f, true)
    private val identities = mutableMapOf<String, String>()

    @Synchronized private fun state(token: String): State = states.getOrPut(identities[GitHubRepositoryStateStore.digest(token)] ?: GitHubRepositoryStateStore.digest(token)) { State() }.also {
        while (states.size > 8) states.remove(states.keys.first())?.cache?.clear()
    }

    /** Called only after the credential has been bound to a verified account. */
    @Synchronized fun bind(token: String, identity: String) {
        val digest = GitHubRepositoryStateStore.digest(token)
        if (identities[digest] == identity) return
        val previous = states.remove(digest)
        val state = states.getOrPut(identity) { previous ?: State() }
        state.cache.clear()
        state.authenticationRejected.set(false)
        identities[digest] = identity
        // Aliases contain hashes only. Older credentials cannot be used after rotation.
        while (identities.size > 256) identities.remove(identities.keys.first())
    }
    fun authenticationForCredential(token: String): java.util.concurrent.atomic.AtomicBoolean = state(token).authenticationRejected
    fun forCredential(token: String): GitHubResponseCache = state(token).cache
    fun rateLimitsForCredential(token: String): GitHubRateLimitManager = state(token).limits
    fun schedulerForCredential(token: String): GitHubRequestScheduler = state(token).scheduler
    fun stateForCredential(token: String): GitHubRepositoryStateStore = state(token).store

    /** Explicit credential replacement reopens authentication while preserving write receipts and rate limits. */
    @Synchronized fun credentialReconnected(token: String) {
        states[identities[GitHubRepositoryStateStore.digest(token)] ?: GitHubRepositoryStateStore.digest(token)]?.let {
            it.authenticationRejected.set(false)
            it.cache.clear()
        }
    }

    @Synchronized fun clear() {
        states.values.forEach { it.cache.clear() }
        states.clear()
        identities.clear()
    }
}
