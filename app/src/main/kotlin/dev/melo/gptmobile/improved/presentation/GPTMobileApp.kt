package dev.melo.gptmobile.improved.presentation

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.svg.SvgDecoder
import dagger.hilt.android.HiltAndroidApp
import dev.melo.gptmobile.improved.data.agent.AgentRunCoordinator
import dev.melo.gptmobile.improved.presentation.service.AgentRunForegroundService
import javax.inject.Inject

@HiltAndroidApp
class GPTMobileApp : Application(), Configuration.Provider, SingletonImageLoader.Factory {
    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var agentRunCoordinator: AgentRunCoordinator

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(SvgDecoder.Factory())
            }
            .build()

    override fun onCreate() {
        super.onCreate()
        AppForegroundTracker.init(this)
        agentRunCoordinator.activeRuns.observeForever { activeRuns ->
            if (activeRuns.isNotEmpty()) {
                AgentRunForegroundService.start(this)
            }
        }
    }
}
