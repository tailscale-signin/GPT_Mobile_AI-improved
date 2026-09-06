package dev.melo.gptmobile.improved.presentation

import android.app.ActivityManager
import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
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
import dev.chungjungsoo.gptmobile.data.localmodel.PendingLocalPlatformActivator
import dev.chungjungsoo.gptmobile.data.localruntime.LocalRuntime
import dev.melo.gptmobile.improved.R
import dev.melo.gptmobile.improved.data.agent.AgentRunCoordinator
import dev.melo.gptmobile.improved.data.backup.SanitizedChatBackup
import dev.melo.gptmobile.improved.data.database.dao.AgentPersistenceDao
import dev.melo.gptmobile.improved.data.database.dao.AgentRunDao
import dev.melo.gptmobile.improved.data.repository.LocalModelRepository
import dev.melo.gptmobile.improved.data.repository.SecretMigrationError
import dev.melo.gptmobile.improved.data.repository.SettingRepository
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Hilt base application class.
 *
 * When enableTransformForLocalTests or bytecode transformation is bypassed or fails to rewrite
 * the superclass in Kotlin 2.x + KSP, extending Hilt_GPTMobileApp directly is the canonical Dagger/Hilt
 * solution as documented by Google Hilt (https://dagger.dev/hilt/custom-applications.html).
 */
abstract class Hilt_GPTMobileApp :
    Application(),
    dagger.hilt.internal.GeneratedComponentManagerHolder {

    private val componentManager: dagger.hilt.android.internal.managers.ApplicationComponentManager by lazy {
        dagger.hilt.android.internal.managers.ApplicationComponentManager {
            dagger.hilt.android.internal.managers.ComponentSupplier {
                EntryPoints.get(this, GPTMobileApp_GeneratedComponent::class.java)
            }.get()
        }
    }

    override fun componentManager(): dagger.hilt.android.internal.managers.ApplicationComponentManager =
        componentManager

    override fun generatedComponent(): Any =
        componentManager.generatedComponent()
}

@HiltAndroidApp(Hilt_GPTMobileApp::class)
class GPTMobileApp :
    Hilt_GPTMobileApp(),
    Configuration.Provider {

    // TODO Delete when https://github.com/google/dagger/issues/3601 is resolved.
    @Inject
    @ApplicationContext
    lateinit var context: Context

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Volatile
    var secretMigrationErrors: List<SecretMigrationError> = emptyList()
        private set

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val isHighRamDevice: Boolean by lazy {
        runCatching {
            val actManager = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            actManager?.getMemoryInfo(memInfo)
            memInfo.totalMem >= 10L * 1024 * 1024 * 1024 // 10 GB+
        }.getOrDefault(false)
    }

    override fun onCreate() {
        SanitizedChatBackup.restoreIfPresent(this)
        super.onCreate()
        registerActivityLifecycleCallbacks(AppForegroundTracker)
        StartupRecoveryGate.start(applicationScope) {
            val startup = startupDependencies()
            startup.pendingLocalPlatformActivator().start()
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
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
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
                startupDependencies().localRuntime().unloadEngine()
            }
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

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
