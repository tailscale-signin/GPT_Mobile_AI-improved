package dev.chungjungsoo.gptmobile.data.marketplace

import kotlinx.coroutines.sync.Mutex

/** Serializes marketplace settings mutations across its two presentation entry points. */
object MarketplaceMutations {
    val mutex = Mutex()
}

class MarketplaceRegistryRepairRequired : IllegalStateException("Plugin registry needs repair. Rebuild disabled registrations from verified packages, then add credentials again.")
