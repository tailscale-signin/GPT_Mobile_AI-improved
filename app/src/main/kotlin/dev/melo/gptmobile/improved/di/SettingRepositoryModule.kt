package dev.melo.gptmobile.improved.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.melo.gptmobile.improved.data.database.dao.ChatPlatformModelV2Dao
import dev.melo.gptmobile.improved.data.database.dao.PlatformV2Dao
import dev.melo.gptmobile.improved.data.datastore.SettingDataSource
import dev.melo.gptmobile.improved.data.repository.SettingRepository
import dev.melo.gptmobile.improved.data.repository.SettingRepositoryImpl
import dev.melo.gptmobile.improved.data.security.AndroidSecretVault
import dev.melo.gptmobile.improved.data.security.SecretVault
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SettingRepositoryModule {

    @Provides
    @Singleton
    fun provideSecretVault(androidSecretVault: AndroidSecretVault): SecretVault = androidSecretVault

    @Provides
    @Singleton
    fun provideSettingRepository(
        settingDataSource: SettingDataSource,
        platformV2Dao: PlatformV2Dao,
        chatPlatformModelV2Dao: ChatPlatformModelV2Dao,
        secretVault: SecretVault
    ): SettingRepository = SettingRepositoryImpl(settingDataSource, platformV2Dao, chatPlatformModelV2Dao, secretVault)
}
