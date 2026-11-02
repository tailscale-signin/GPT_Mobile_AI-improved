package dev.chungjungsoo.gptmobile.data.repository

/** Accessed under the repository refresh mutex. A route change releases stale cooldowns. */
internal class CatalogRefreshWindow {
    private var epoch: Long? = null
    private var until = 0L
    private var failures = 0

    fun available(now: Long, networkEpoch: Long): Boolean {
        if (epoch != networkEpoch) {
            epoch = networkEpoch
            failures = 0
            until = 0
        }
        return now >= until
    }

    fun completed(now: Long, success: Boolean) {
        failures = if (success) 0 else (failures + 1).coerceAtMost(5)
        until = now + if (success) 30_000L else (15_000L shl failures).coerceAtMost(300_000L)
    }
}
