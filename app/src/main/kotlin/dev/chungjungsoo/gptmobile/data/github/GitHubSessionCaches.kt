package dev.chungjungsoo.gptmobile.data.github

/** One HTTP attempt keeps its credential and admission gates together across rotation. */
internal data class GitHubRequestCredential(
    val token: String,
    val authenticationRejected: java.util.concurrent.atomic.AtomicBoolean,
    val refAvailability: GitHubRefAvailability
)

/** Evict account state together so quota and cache identities cannot diverge. */
internal object GitHubSessionCaches {
    private data class State(val cache: GitHubResponseCache = GitHubResponseCache(), val limits: GitHubRateLimitManager = GitHubRateLimitManager(), val scheduler: GitHubRequestScheduler = GitHubRequestScheduler(), val store: GitHubRepositoryStateStore = GitHubRepositoryStateStore())
    private val states = LinkedHashMap<String, State>(8, 0.75f, true)
    private val identities = mutableMapOf<String, String>()

    // Tokens for the same account can have different installation/repository grants.
    // Negative access observations must not cross that credential boundary.
    private val refStates = LinkedHashMap<String, GitHubRefAvailability>(8, 0.75f, true)
    private val authenticationStates = LinkedHashMap<String, java.util.concurrent.atomic.AtomicBoolean>(8, 0.75f, true)

    @Synchronized private fun state(token: String, host: String): State = states.getOrPut(identities[credentialKey(token, host)] ?: credentialKey(token, host)) { State() }.also {
        while (states.size > 8) states.remove(states.keys.first())?.cache?.clear()
    }

    private fun credentialKey(token: String, host: String) = GitHubRepositoryStateStore.digest("${host.lowercase()}:$token")

    /** Called only after the credential has been bound to a verified account. */
    @Synchronized fun bind(token: String, identity: String, host: String = "github.com") {
        val digest = credentialKey(token, host)
        val scopedIdentity = "${host.lowercase()}:$identity"
        if (identities[digest] == scopedIdentity) return
        val previous = states.remove(digest)
        val state = states.getOrPut(scopedIdentity) { previous ?: State() }
        state.cache.clear()
        authenticationStates[digest]?.set(false)
        refStates[digest]?.clear()
        identities[digest] = scopedIdentity
        // Aliases contain hashes only. Older credentials cannot be used after rotation.
        while (identities.size > 256) identities.remove(identities.keys.first())
    }

    @Synchronized fun refsForCredential(token: String, host: String = "github.com"): GitHubRefAvailability = refStates.getOrPut(credentialKey(token, host)) { GitHubRefAvailability() }.also {
        while (refStates.size > 256) refStates.remove(refStates.keys.first())
    }

    @Synchronized fun authenticationForCredential(token: String, host: String = "github.com"): java.util.concurrent.atomic.AtomicBoolean = authenticationStates.getOrPut(credentialKey(token, host)) { java.util.concurrent.atomic.AtomicBoolean() }.also {
        while (authenticationStates.size > 256) authenticationStates.remove(authenticationStates.keys.first())
    }
    fun forCredential(token: String, host: String = "github.com"): GitHubResponseCache = state(token, host).cache
    fun rateLimitsForCredential(token: String, host: String = "github.com"): GitHubRateLimitManager = state(token, host).limits
    fun schedulerForCredential(token: String, host: String = "github.com"): GitHubRequestScheduler = state(token, host).scheduler
    fun stateForCredential(token: String, host: String = "github.com"): GitHubRepositoryStateStore = state(token, host).store

    /** Explicit credential replacement reopens authentication while preserving write receipts and rate limits. */
    @Synchronized fun credentialReconnected(token: String, host: String = "github.com") {
        refStates[credentialKey(token, host)]?.clear()
        authenticationStates[credentialKey(token, host)]?.set(false)
        states[identities[credentialKey(token, host)] ?: credentialKey(token, host)]?.let {
            it.cache.clear()
        }
    }

    @Synchronized fun clear() {
        states.values.forEach { it.cache.clear() }
        states.clear()
        identities.clear()
        refStates.clear()
        authenticationStates.clear()
    }
}
