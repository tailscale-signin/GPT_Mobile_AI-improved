package dev.chungjungsoo.gptmobile.presentation.ui.home

import dev.chungjungsoo.gptmobile.data.github.GatewayConversationSnapshot

/** A saved ID alone (including a v15 snapshot) never establishes running work. */
internal fun GatewayConversationSnapshot.isWorking(): Boolean = activeJobId != null &&
    jobState?.let {
        dev.chungjungsoo.gptmobile.data.github.gatewayJobGroup(it) == dev.chungjungsoo.gptmobile.data.github.GatewayJobGroup.RUNNING
    } == true

internal fun gatewayConversationRefreshDelay(active: Boolean, failures: Int): Long = when {
    failures > 0 -> (15_000L * (1L shl failures.coerceIn(0, 3))).coerceAtMost(120_000L)
    active -> 10_000L
    else -> 120_000L
}
