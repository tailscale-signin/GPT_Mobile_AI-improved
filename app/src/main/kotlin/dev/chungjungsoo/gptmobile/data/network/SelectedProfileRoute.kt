package dev.chungjungsoo.gptmobile.data.network

/** A GitHub request may retry the selected route, but may not choose another destination or model. */
internal fun ProviderRequestConfig.requireSelectedRoute(model: String) {
    val selected = lockedModel ?: return
    val endpoint = apiUrl.trim().trimEnd('/')
    check(model == selected && endpoint == lockedApiUrl?.trim()?.trimEnd('/')) {
        "GITHUB_PROFILE_LOCKED: This request no longer matches the selected AI profile's model and connection. Saved work remains available; no substitute was sent."
    }
    val automatic = when {
        providerType == "OPENROUTER" -> selected in setOf("openrouter/auto", "openrouter/free")
        freeProvider != null || providerType == "FREE" -> selected in setOf("automatic", "auto", "kilo-auto/free", "openrouter/auto", "openrouter/free")
        else -> false
    }
    check(!automatic) {
        "GITHUB_PROFILE_LOCKED: Choose a specific model in this AI profile's settings before using GitHub mode. Automatic model routing cannot keep the selected model fixed. No substitute was sent."
    }
}
