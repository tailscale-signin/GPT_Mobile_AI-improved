package dev.chungjungsoo.gptmobile.data.openrouter

/** Bounded, monotonic negative cache; keys contain only endpoint/account/model/policy digests. */
internal class PrivacyRejectionCache(private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private val rejected = linkedMapOf<String, Long>()

    @Synchronized fun record(key: String, message: String) {
        if (!message.contains("POLICY_RESTRICTED", true) && !message.contains("No endpoints found matching your data policy", true)) return
        rejected.remove(key)
        rejected[key] = now() + 300_000
        while (rejected.size > 256) rejected.remove(rejected.keys.first())
    }

    @Synchronized fun blocked(key: String): Boolean {
        val until = rejected[key] ?: return false
        if (now() < until) return true
        rejected.remove(key)
        return false
    }
}
