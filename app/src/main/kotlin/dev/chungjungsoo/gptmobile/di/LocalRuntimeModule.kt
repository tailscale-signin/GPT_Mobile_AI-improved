package dev.chungjungsoo.gptmobile.di

import android.content.Context
import android.os.Build
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.chungjungsoo.gptmobile.data.localruntime.LocalEngineHolder
import dev.chungjungsoo.gptmobile.data.localruntime.LocalRuntime
import dev.chungjungsoo.gptmobile.data.localruntime.LocalRuntimeImpl
import dev.chungjungsoo.gptmobile.data.localruntime.LocalRuntimeQnnImpl
import dev.chungjungsoo.gptmobile.data.localruntime.LocalRuntimeRouter
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object LocalRuntimeModule {
    @Provides
    @Singleton
    fun provideLocalRuntime(
        @ApplicationContext context: Context,
        settingRepository: SettingRepository,
        benchmarkStore: dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkStore
    ): LocalRuntime {
        if (dev.chungjungsoo.gptmobile.BuildConfig.LOCAL_INFERENCE_PROCESS) {
            return LocalEngineHolder(dev.chungjungsoo.gptmobile.data.localruntime.PlannedLocalRuntime(context, dev.chungjungsoo.gptmobile.data.localruntime.ProcessLocalRuntime(context, settingRepository), settingRepository, benchmarkStore))
        }
        val liteRtRuntime = LocalRuntimeImpl(context)
        val qnnRuntime = LocalRuntimeQnnImpl(context)
        val router = LocalRuntimeRouter(
            settingRepository = settingRepository,
            qnnRuntime = qnnRuntime,
            liteRtRuntime = liteRtRuntime,
            artifactRuntime = dev.chungjungsoo.gptmobile.data.localruntime.NativeArtifactRuntimeFactory.create(context)
        )
        return LocalEngineHolder(dev.chungjungsoo.gptmobile.data.localruntime.PlannedLocalRuntime(context, router, settingRepository, benchmarkStore))
    }

    @Provides
    @Singleton
    @DeviceSocModel
    fun provideDeviceSocModel(): String = Build.SOC_MODEL.orEmpty()

    @Provides
    @Singleton
    @DeviceRamGb
    fun provideDeviceRamGb(
        localRuntime: LocalRuntime
    ): Long = localRuntime.deviceRamGb
}
