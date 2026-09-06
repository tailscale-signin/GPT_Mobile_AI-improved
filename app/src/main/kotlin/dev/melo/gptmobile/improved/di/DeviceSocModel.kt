package dev.melo.gptmobile.improved.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.melo.gptmobile.improved.data.hardware.DeviceHardwareInfo
import dev.melo.gptmobile.improved.data.hardware.DeviceHardwareInfoProvider
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DeviceHardwareModule {

    @Provides
    @Singleton
    fun provideDeviceHardwareInfo(
        @ApplicationContext context: Context
    ): DeviceHardwareInfo = DeviceHardwareInfoProvider.detect(context)
}
