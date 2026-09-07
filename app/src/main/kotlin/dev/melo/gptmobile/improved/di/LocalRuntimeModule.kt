package dev.melo.gptmobile.improved.di

import android.content.Context
import android.os.Build
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.melo.gptmobile.improved.data.network.NetworkClient
import dev.melo.gptmobile.improved.data.repository.LocalRuntimeRepository
import dev.melo.gptmobile.improved.data.repository.LocalRuntimeRepositoryImpl
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object LocalRuntimeModule {

    @Provides
    @Singleton
    fun provideLocalRuntimeRepository(
        @ApplicationContext context: Context,
        networkClient: NetworkClient
    ): LocalRuntimeRepository = LocalRuntimeRepositoryImpl(context, networkClient)

    @Provides
    @Singleton
    @DeviceSocModel
    fun provideDeviceSocModel(): String = Build.SOC_MODEL.orEmpty()
}
