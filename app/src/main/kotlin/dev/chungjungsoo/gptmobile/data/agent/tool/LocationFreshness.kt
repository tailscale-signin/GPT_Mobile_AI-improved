package dev.chungjungsoo.gptmobile.data.agent.tool

/** Old cached fixes must not be presented as the phone's current location. */
internal fun isRecentLocation(timestampMillis: Long, nowMillis: Long): Boolean =
    timestampMillis in (nowMillis - 120_000L)..nowMillis
