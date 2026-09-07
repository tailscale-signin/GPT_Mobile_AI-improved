package dev.melo.gptmobile.improved.data.localmodel

import dev.melo.gptmobile.improved.data.model.ClientType
import dev.melo.gptmobile.improved.data.model.LocalModel
import dev.melo.gptmobile.improved.data.model.LocalModelStatus
import dev.melo.gptmobile.improved.data.repository.LocalModelRepository
import dev.melo.gptmobile.improved.data.repository.SettingRepository
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@Singleton
class PendingLocalPlatformActivator(
    private val localModelRepository: LocalModelRepository,
    private val settingRepository: SettingRepository,
    private val scope: CoroutineScope
) {
    @Inject
    constructor(
        localModelRepository: LocalModelRepository,
        settingRepository: SettingRepository
    ) : this(
        localModelRepository,
        settingRepository,
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
    )

    private val started = AtomicBoolean(false)
    private var previousStatuses: Map<String, LocalModelStatus> = emptyMap()
    private var hasBaseline = false

    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            localModelRepository.observeAll().collect { models: List<LocalModel> ->
                val current: Map<String, LocalModelStatus> = models.associate { it.id to it.status }
                if (hasBaseline) {
                    val newlyReady = current.filter { (id, status) ->
                        status == LocalModelStatus.READY && previousStatuses[id] != LocalModelStatus.READY
                    }.keys
                    if (newlyReady.isNotEmpty()) {
                        onModelsBecameReady(newlyReady)
                    }
                }
                previousStatuses = current
                hasBaseline = true
            }
        }
    }

    suspend fun onModelsBecameReady(catalogEntryIds: Set<String>) {
        settingRepository.fetchPlatformV2s()
            .filter { platform ->
                !platform.enabled &&
                    platform.compatibleType == ClientType.LITERT_LM &&
                    platform.model in catalogEntryIds
            }
            .forEach { platform ->
                settingRepository.updatePlatformV2(platform.copy(enabled = true))
            }
    }
}
