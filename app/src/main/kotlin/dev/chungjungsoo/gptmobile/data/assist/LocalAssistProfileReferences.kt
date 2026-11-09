package dev.chungjungsoo.gptmobile.data.assist

/** Removing a saved helper invalidates its grant epoch; another profile is never selected implicitly. */
internal fun LocalAssistPolicy.reconcileSelectedProfile(availableUids: Set<String>): LocalAssistPolicy {
    val selected = profileUid ?: return this
    if (selected in availableUids) return this
    return copy(
        profileUid = null,
        permissionEpoch = java.util.UUID.randomUUID().toString(),
        profilePermissions = profilePermissions - selected
    )
}
