package dev.chungjungsoo.gptmobile.data.github

/** Describes an observed read policy; none of these decisions authorize a mutation. */
internal enum class GitHubReadFreshness {
    IMMUTABLE_SHA_REUSE,
    FRESH_INVENTORY_REUSE,
    EXPLICIT_LIVE_READ,
    MUTATION_INVALIDATED,
    INVENTORY_TTL_EXPIRED,
    MUTABLE_STATE_REVALIDATION,
    CACHE_MISS,
    CONDITIONAL_BODY_UNCHANGED,
    CONDITIONAL_ETAG_CHANGED;

    companion object {
        fun decision(forceLive: Boolean, entry: GitHubResponseCache.Entry?, inventory: Boolean, fresh: Boolean, invalidated: Boolean): GitHubReadFreshness = when {
            forceLive -> EXPLICIT_LIVE_READ
            entry?.immutable == true -> IMMUTABLE_SHA_REUSE
            entry != null && inventory && fresh -> FRESH_INVENTORY_REUSE
            invalidated -> MUTATION_INVALIDATED
            entry != null && inventory -> INVENTORY_TTL_EXPIRED
            entry != null -> MUTABLE_STATE_REVALIDATION
            else -> CACHE_MISS
        }
    }
}
