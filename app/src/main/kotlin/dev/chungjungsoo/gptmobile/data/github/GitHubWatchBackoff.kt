package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.network.ProviderRetryPolicy

/** Poll scheduling is durable and bounded, with a server cooldown always taking precedence. */
internal object GitHubWatchBackoff {
    fun next(now: Long, attempt: Int, serverDeadline: Long = 0): Long = maxOf(
        serverDeadline,
        ProviderRetryPolicy.deadlineMillis(now, (30_000L shl attempt.coerceIn(0, 5)).coerceAtMost(15 * 60_000L))
    )
}
