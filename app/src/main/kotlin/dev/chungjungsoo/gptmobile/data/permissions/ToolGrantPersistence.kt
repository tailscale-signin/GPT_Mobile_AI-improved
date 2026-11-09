package dev.chungjungsoo.gptmobile.data.permissions

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Security-sensitive writes stay durable and ordered without blocking the caller's UI thread. */
internal object ToolGrantPersistence {
    private val mutations = Mutex()

    suspend fun <T> mutate(block: () -> T): T = withContext(Dispatchers.IO) {
        mutations.withLock { block() }
    }

    /** Construct on the caller thread, before launching or waiting for an IO dispatcher. */
    fun prepareConnectionRevocation(trust: ToolTrustStore?, consent: FreeModelToolConsentStore?, connectionUid: String): Revocation {
        var toolDenial = trust?.denyConnection(connectionUid)
        var consentDenial = consent?.denyConnection(connectionUid)
        return Revocation(
            persist = {
                // Prepared operations can enter the queue out of order. Re-arm under its lock.
                toolDenial = trust?.denyConnection(connectionUid)
                consentDenial = consent?.denyConnection(connectionUid)
                toolDenial?.let { trust?.revoke(connectionUid, it, release = false) }
                consentDenial?.let { consent?.revokeConnection(connectionUid, it, release = false) }
            },
            release = {
                toolDenial?.let { trust?.finishRevocation(it) }
                consentDenial?.let { consent?.finishRevocation(it) }
            }
        )
    }

    fun prepareGrantRevocation(trust: ToolTrustStore?, consent: FreeModelToolConsentStore?, key: String, freeModel: Boolean): Revocation {
        var toolDenial = trust?.takeUnless { freeModel }?.denyGrant(key)
        var consentDenial = consent?.takeIf { freeModel }?.denyGrant(key)
        return Revocation(
            persist = {
                toolDenial = trust?.takeUnless { freeModel }?.denyGrant(key)
                consentDenial = consent?.takeIf { freeModel }?.denyGrant(key)
                toolDenial?.let { requireNotNull(trust).revokeGrant(key, it, release = false) }
                consentDenial?.let { requireNotNull(consent).revokeGrant(key, it, release = false) }
            },
            release = {
                toolDenial?.let { requireNotNull(trust).finishRevocation(it) }
                consentDenial?.let { requireNotNull(consent).finishRevocation(it) }
            }
        )
    }

    fun preparePersonalToolRevocation(consent: FreeModelToolConsentStore?, profileUid: String, toolId: String): Revocation {
        var denial = consent?.denyTool(profileUid, toolId)
        return Revocation(
            persist = {
                denial = consent?.denyTool(profileUid, toolId)
                denial?.let { consent?.revoke(profileUid, toolId, it, release = false) }
            },
            release = { denial?.let { consent?.finishRevocation(it) } }
        )
    }

    fun combine(revocations: List<Revocation>) = Revocation({ revocations.forEach { it.persist() } }, { revocations.forEach { it.release() } })

    class Revocation internal constructor(internal val persist: () -> Unit, internal val release: () -> Unit = {}) {
        /** Once denied, finish the durable revocation even if its screen closes. */
        suspend fun commit() = withContext(NonCancellable) {
            mutate {
                persist()
                release()
            }
        }

        /** Keep old approvals denied across every suspension while replacement credentials activate. */
        suspend fun <T> activate(block: suspend () -> T): T = mutations.withLock {
            // A newer revocation must not release this fence while activation is suspended.
            withContext(NonCancellable + Dispatchers.IO) { persist() }
            block().also { release() }
        }
    }
}
