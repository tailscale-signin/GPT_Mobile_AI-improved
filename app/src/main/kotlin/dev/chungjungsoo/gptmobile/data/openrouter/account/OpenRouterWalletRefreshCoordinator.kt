package dev.chungjungsoo.gptmobile.data.openrouter.account

import dev.chungjungsoo.gptmobile.data.model.SettingsHomeConfiguration
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Application-owned FIFO account queues: no UI lifecycle, timer, startup replay or generation retries. */
@Singleton
class OpenRouterWalletRefreshCoordinator @Inject constructor(
    private val local: OpenRouterWalletLocalStore,
    private val access: OpenRouterManagementAccessRepository,
    private val settings: SettingRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val initialize = Mutex()
    private val admission = Mutex()
    private var initialized = false
    private val actors = java.util.concurrent.ConcurrentHashMap<String, Channel<Read>>()
    private sealed interface Read {
        data class Finalized(val event: OpenRouterWalletEvent) : Read
        data class Explicit(val result: CompletableDeferred<SettingsHomeWalletSnapshot?>) : Read
    }

    suspend fun capture(context: OpenRouterReceiptContext): OpenRouterReceiptContext {
        ready()
        val binding = local.snapshot().bindings[context.connectionUid]
        return context.copy(
            accountBindingRevision = binding?.takeIf {
                it.associated && it.endpoint == context.endpoint && context.credentialRevision in it.inferenceRevisions
            }?.revision
        )
    }

    private suspend fun ready() = initialize.withLock {
        if (!initialized) {
            local.abandonUnfinished()
            local.setEnabled(SettingsHomeConfiguration.WALLET in settings.getFeatureSettings().settingsHome.selectedItems)
            initialized = true
        }
    }

    private fun actor(uid: String): Channel<Read> = actors.computeIfAbsent(uid) {
        Channel<Read>(Channel.UNLIMITED).also { queue ->
            scope.launch {
                for (read in queue) {
                    when (read) {
                        is Read.Finalized -> {
                            try {
                                local.setEnabled(SettingsHomeConfiguration.WALLET in settings.getFeatureSettings().settingsHome.selectedItems)
                                val binding = local.claim(read.event, System.currentTimeMillis() / 1000) ?: continue
                                val snapshot = access.read(binding)
                                local.finish(read.event, if (snapshot != null) "succeeded" else "failed_or_stale")
                            } catch (_: Exception) {
                                runCatching { local.finish(read.event, "failed") }
                            }
                        }
                        is Read.Explicit -> {
                            if (!read.result.isActive) continue
                            val result = runCatching {
                                val state = local.snapshot()
                                val binding = state.bindings[uid]?.takeIf { it.associated }
                                if (binding == null || (state.observations[uid]?.cooldownUntil ?: 0) > System.currentTimeMillis() / 1000) null else access.read(binding)
                            }.getOrNull()
                            read.result.complete(result)
                        }
                    }
                }
            }
        }
    }

    suspend fun finalized(receipt: OpenRouterReceipt) {
        ready()
        if (!receipt.remoteEvidence) return
        // Persist deduplication and enqueue in the same ordering critical section. Network reads
        // happen in account actors, so unrelated accounts and chat delivery never wait on them.
        admission.withLock {
            local.setEnabled(SettingsHomeConfiguration.WALLET in settings.getFeatureSettings().settingsHome.selectedItems)
            val event = local.accept(receipt) ?: return@withLock
            actor(event.connectionUid).send(Read.Finalized(event))
        }
    }

    suspend fun readExplicit(uid: String): SettingsHomeWalletSnapshot? {
        ready()
        val result = CompletableDeferred<SettingsHomeWalletSnapshot?>(currentCoroutineContext()[Job])
        admission.withLock { actor(uid).send(Read.Explicit(result)) }
        return result.await()
    }
}
