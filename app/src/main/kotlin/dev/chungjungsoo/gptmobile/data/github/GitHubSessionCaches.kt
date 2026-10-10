package dev.chungjungsoo.gptmobile.data.github

import java.security.MessageDigest

/** Share bounded caches across UI/tool sessions without retaining credential strings. */
internal object GitHubSessionCaches {
    private val caches = LinkedHashMap<String, GitHubResponseCache>(4, 0.75f, true)

    private val rateLimits = LinkedHashMap<String, GitHubRateLimitManager>(4, 0.75f, true)

    @Synchronized fun rateLimitsForCredential(token: String): GitHubRateLimitManager {
        val key = MessageDigest.getInstance("SHA-256").digest(token.encodeToByteArray()).joinToString("") { "%02x".format(it) }
        return rateLimits.getOrPut(key) { GitHubRateLimitManager() }.also {
            while (rateLimits.size > 4) rateLimits.remove(rateLimits.keys.first())
        }
    }

    @Synchronized fun forCredential(token: String): GitHubResponseCache {
        val key = MessageDigest.getInstance("SHA-256").digest(token.encodeToByteArray()).joinToString("") { "%02x".format(it) }
        return caches.getOrPut(key) { GitHubResponseCache() }.also {
            while (caches.size > 4) caches.remove(caches.keys.first())?.clear()
        }
    }

    @Synchronized fun clear() {
        caches.values.forEach(GitHubResponseCache::clear)
        caches.clear()
        rateLimits.clear()
    }
}
