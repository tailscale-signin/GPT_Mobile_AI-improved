package dev.chungjungsoo.gptmobile.data.github

/** Refresh can replace the vault reference. Consume its returned credential, never reread the stale snapshot. */
internal suspend fun gitHubDownloadCredential(refresh: (suspend () -> String)?, readLegacy: suspend () -> ByteArray?): String {
    val refreshed = refresh?.invoke()
    if (refreshed != null) return refreshed.trim().also(::requireDownloadCredential)
    val bytes = readLegacy()
    return try {
        bytes?.decodeToString()?.trim().orEmpty().also(::requireDownloadCredential)
    } finally {
        bytes?.fill(0)
    }
}

private fun requireDownloadCredential(token: String) {
    require('\r' !in token && '\n' !in token) { "The GitHub credential is invalid. Reconnect the account." }
}
