package dev.chungjungsoo.gptmobile.data.openrouter

/** Session-only requests; never persisted or exported. Consumed when a real inference round begins. */
object OpenRouterFreshRequests {
    private val pending = linkedSetOf<String>()

    @Synchronized fun request(connectionUid: String) {
        pending.add(connectionUid)
        while (pending.size > 100) pending.remove(pending.first())
    }

    @Synchronized fun consume(connectionUid: String): Boolean = pending.remove(connectionUid)
}
