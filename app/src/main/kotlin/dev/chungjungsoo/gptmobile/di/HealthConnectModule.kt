package dev.chungjungsoo.gptmobile.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.chungjungsoo.gptmobile.data.health.AndroidHealthConnectReader
import dev.chungjungsoo.gptmobile.data.health.HealthDataReader
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class HealthConnectModule {
    @Binds
    @Singleton
    abstract fun bindHealthDataReader(reader: AndroidHealthConnectReader): HealthDataReader
}
