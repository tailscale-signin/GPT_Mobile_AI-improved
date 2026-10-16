package dev.chungjungsoo.gptmobile.presentation

import android.app.ActivityManager
import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import android.os.StrictMode
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.data.agent.AgentRunCoordinator
import dev.chungjungsoo.gptmobile.data.backup.SanitizedChatBackup
import dev.chungjungsoo.gptmobile.data.database.dao.AgentPersistenceDao
import dev.chungjungsoo.gptmobile.data.database.dao.AgentRunDao
import dev.chungjungsoo.gptmobile.data.localmodel.PendingLocalPlatformActivator
import dev.chungjungsoo.gptmobile.data.localruntime.LocalRuntime
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayNetworkRecoveryMonitor
import dev.chungjungsoo.gptmobile.data.repository.LocalModelRepository
import dev.chungjungsoo.gptmobile.data.repository.SecretMigrationError
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltAndroidApp
class GPTMobileApp :
    Application(),
    Configuration.Provider {
    // TODO Delete when https://github.com/google/dagger/issues/3601 is resolved.
    @Inject
    @ApplicationContext
    lateinit var context: Context

    @Inject
    lateinit var workerFactory: javax.inject.Provider<HiltWorkerFactory>

    @Inject
    lateinit var gatewayNetworkRecoveryMonitor: javax.inject.Provider<GatewayNetworkRecoveryMonitor>

    @Inject
    lateinit var durablePromptQueue: javax.inject.Provider<dev.chungjungsoo.gptmobile.data.queue.DurablePromptQueue>

    @Inject
    lateinit var semanticMemory: javax.inject.Provider<dev.chungjungsoo.gptmobile.data.memory.LocalSemanticMemory>

    @Inject lateinit var conversationDatabase: javax.inject.Provider<dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2>

    @Volatile
    var secretMigrationErrors: List<SecretMigrationError> = emptyList()
        private set

    @Inject lateinit var readiness: javax.inject.Provider<dev.chungjungsoo.gptmobile.data.readiness.ChatReadinessCoordinator>

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val isHighRamDevice: Boolean by lazy {
        runCatching {
            val actManager = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            actManager?.getMemoryInfo(memInfo)
            memInfo.totalMem >= 10L * 1024 * 1024 * 1024 // 10 GB+
        }.getOrDefault(false)
    }

    @javax.inject.Inject lateinit var invocationLedger: javax.inject.Provider<dev.chungjungsoo.gptmobile.data.accounting.InvocationLedger>

    @javax.inject.Inject lateinit var toolApprovals: javax.inject.Provider<dev.chungjungsoo.gptmobile.data.permissions.ToolApprovalManager>

    @javax.inject.Inject lateinit var conversationDeletion: javax.inject.Provider<dev.chungjungsoo.gptmobile.data.privacy.ConversationDeletion>

    override fun onCreate() {
        val startupStartTime = SystemClock.elapsedRealtime()

        // Provider fields keep the child process from eagerly opening Room or starting app services.
        if (!dev.chungjungsoo.gptmobile.data.localruntime.isInferenceProcess()) SanitizedChatBackup.restoreIfPresent(this)
        super.onCreate()
        if (dev.chungjungsoo.gptmobile.data.localruntime.isInferenceProcess()) return
        dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.initialize(this)
        if (dev.chungjungsoo.gptmobile.BuildConfig.DEBUG) {
            // Diagnose unexpected UI-thread disk and network blocking in development builds.
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectDiskReads()
                    .detectDiskWrites()
                    .detectNetwork()
                    .penaltyLog()
                    .build()
            )
        }
        registerActivityLifecycleCallbacks(AppForegroundTracker)
        StartupRecoveryGate.start(applicationScope) {
            val gateStartTime = SystemClock.elapsedRealtime()
            val startup = startupDependencies()
            val temporary = conversationDatabase.get().chatRoomDao().temporaryChats()
            if (temporary.isNotEmpty()) {
                temporary.forEach { androidx.work.WorkManager.getInstance(this@GPTMobileApp).cancelAllWorkByTag("memory-chat-${it.id}") }
                conversationDeletion.get().delete(temporary)
            }
            toolApprovals.get().recover()
            invocationLedger.get().recover()
            startup.pendingLocalPlatformActivator().start()
            applicationScope.launch {
                kotlinx.coroutines.flow.combine(startup.settingRepository().observeFeatureSettings(), startup.settingRepository().observePlatformV2s()) { settings, profiles ->
                    settings to profiles.any { dev.chungjungsoo.gptmobile.data.pricing.billingProvider(it) == "OPENROUTER" }
                }.collect { (settings, eligible) -> dev.chungjungsoo.gptmobile.data.pricing.PricingRefreshSchedule.reconcile(this@GPTMobileApp, settings, eligible) }
            }
            secretMigrationErrors = runStartupMaintenance(
                interruptPersistedWork = {
                    val interruptedAt = System.currentTimeMillis() / 1000
                    startup.agentRunDao().interruptActiveRuns(interruptedAt)
                    startup.agentPersistenceDao().cancelInterruptedToolEvents(interruptedAt)
                },
                migrateSecrets = startup.settingRepository()::migrateSecrets
            )
            if (secretMigrationErrors.isNotEmpty()) {
                runCatching {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@GPTMobileApp,
                            R.string.credential_migration_warning,
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }.onFailure { error ->
                    Log.e(TAG, "Unable to show credential migration warning.", error)
                }
            }
            startup.localModelRepository().reconcile()
            startup.localModelRepository().awaitActiveDownloadScheduling()
            gatewayNetworkRecoveryMonitor.get().start()
            durablePromptQueue.get().start()
            Log.i(TAG, "Startup maintenance completed in ${SystemClock.elapsedRealtime() - gateStartTime}ms")
        }

        applicationScope.launch {
            StartupRecoveryGate.await()
            readiness.get().start()
        }

        applicationScope.launch {
            while (isActive) {
                delay(60_000L)
                semanticMemory.get().releaseWhenIdle()
                val idleMinutes = startupDependencies().settingRepository().getFeatureSettings().localIdleMinutes.coerceIn(0, 60)
                if (idleMinutes > 0) startupDependencies().localRuntime().unloadIfIdle(idleMinutes * 60_000L)
            }
        }

        Log.i(TAG, "GPTMobileApp.onCreate completed in ${SystemClock.elapsedRealtime() - startupStartTime}ms")
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (dev.chungjungsoo.gptmobile.data.localruntime.isInferenceProcess()) return
        applicationScope.launch {
            val memoryPressure = dev.chungjungsoo.gptmobile.data.memory.MemoryTrimPolicy.releaseImmediately(level)
            semanticMemory.get().releaseWhenIdle(if (memoryPressure) 0L else 180_000L, memoryPressure = memoryPressure)
        }
        applicationScope.launch { readiness.get().trim() }
        dev.chungjungsoo.gptmobile.data.github.GitHubSessionCaches.clear()
        val hasActiveRuns = runCatching {
            startupDependencies().agentRunCoordinator().activeRuns.value.isNotEmpty()
        }.getOrDefault(false)

        // Never unload engine while background generation or agent runs are currently executing
        if (hasActiveRuns) {
            return
        }

        // On devices with 10GB+ RAM, do not prematurely unload local model weights on moderate
        // background/running-low signals (TRIM_MEMORY_RUNNING_LOW/TRIM_MEMORY_RUNNING_CRITICAL/TRIM_MEMORY_UI_HIDDEN).
        // Only unload when the system is under genuine severe pressure (TRIM_MEMORY_COMPLETE).
        @Suppress("DEPRECATION")
        val shouldUnload = if (isHighRamDevice) {
            level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE
        } else {
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW
        }

        if (shouldUnload) {
            applicationScope.launch {
                startupDependencies().localRuntime().unloadIfIdle(0L)
            }
        }
    }

    override val workManagerConfiguration: Configuration
        get() {
            check(!dev.chungjungsoo.gptmobile.data.localruntime.isInferenceProcess()) { "WorkManager belongs to the main process" }
            return Configuration.Builder()
                .setWorkerFactory(workerFactory.get())
                .build()
        }

    private fun startupDependencies(): StartupDependencies = EntryPointAccessors.fromApplication(
        this,
        StartupDependencies::class.java
    )

    private companion object {
        const val TAG = "GPTMobileApp"
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface StartupDependencies {
    fun pendingLocalPlatformActivator(): PendingLocalPlatformActivator
    fun localModelRepository(): LocalModelRepository
    fun settingRepository(): SettingRepository
    fun agentRunDao(): AgentRunDao
    fun agentPersistenceDao(): AgentPersistenceDao
    fun localRuntime(): LocalRuntime
    fun agentRunCoordinator(): AgentRunCoordinator
}

object StartupRecoveryGate {
    @Volatile
    private var job: Job? = null

    fun start(scope: CoroutineScope, block: suspend () -> Unit) {
        job = scope.launch { block() }
    }

    suspend fun await() {
        job?.join()
    }
}

internal suspend fun runStartupMaintenance(
    interruptPersistedWork: suspend () -> Unit,
    migrateSecrets: suspend () -> List<SecretMigrationError>
): List<SecretMigrationError> {
    interruptPersistedWork()
    return try {
        migrateSecrets()
    } catch (error: Exception) {
        listOf(SecretMigrationError("startup", error.message ?: "Credential migration failed."))
    }
}
