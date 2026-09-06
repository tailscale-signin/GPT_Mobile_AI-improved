package dev.melo.gptmobile.improved.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.melo.gptmobile.improved.data.database.dao.ChatPlatformModelV2Dao
import dev.melo.gptmobile.improved.data.database.dao.ChatV2Dao
import dev.melo.gptmobile.improved.data.database.dao.PlatformV2Dao
import dev.melo.gptmobile.improved.data.network.AnthropicAPI
import dev.melo.gptmobile.improved.data.network.GoogleAPI
import dev.melo.gptmobile.improved.data.network.GroqAPI
import dev.melo.gptmobile.improved.data.network.OpenAIAPI
import dev.melo.gptmobile.improved.data.repository.ChatRepository
import dev.melo.gptmobile.improved.data.repository.ChatRepositoryImpl
import dev.melo.gptmobile.improved.data.repository.LocalModelRepository
import dev.melo.gptmobile.improved.data.security.SecretVault
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ChatRepositoryModule {

    @Provides
    @Singleton
    fun provideChatRepository(
        chatV2Dao: ChatV2Dao,
        platformV2Dao: PlatformV2Dao,
        chatPlatformModelV2Dao: ChatPlatformModelV2Dao,
        openAIAPI: OpenAIAPI,
        groqAPI: GroqAPI,
        anthropicAPI: AnthropicAPI,
        googleAPI: GoogleAPI,
        localModelRepository: LocalModelRepository,
        secretVault: SecretVault
    ): ChatRepository = ChatRepositoryImpl(
        chatV2Dao = chatV2Dao,
        platformV2Dao = platformV2Dao,
        chatPlatformModelV2Dao = chatPlatformModelV2Dao,
        openAIAPI = openAIAPI,
        groqAPI = groqAPI,
        anthropicAPI = anthropicAPI,
        googleAPI = googleAPI,
        localModelRepository = localModelRepository,
        secretVault = secretVault
    )
}
