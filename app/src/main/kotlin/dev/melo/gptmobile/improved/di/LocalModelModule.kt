package dev.melo.gptmobile.improved.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.melo.gptmobile.improved.data.agent.tool.McpClientManager
import dev.melo.gptmobile.improved.data.agent.tool.McpOAuthCoordinator
import dev.melo.gptmobile.improved.data.database.dao.LocalModelDao
import dev.melo.gptmobile.improved.data.database.dao.ToolConnectionDao
import dev.melo.gptmobile.improved.data.huggingface.HuggingFaceTokenStore
import dev.melo.gptmobile.improved.data.localmodel.GatedDownloadCoordinator
import dev.melo.gptmobile.improved.data.repository.LocalModelRepository
import dev.melo.gptmobile.improved.data.repository.LocalModelRepositoryImpl
import dev.melo.gptmobile.improved.data.repository.LocalRuntimeRepository
import dev.melo.gptmobile.improved.data.repository.ModelCatalogRepository
import dev.melo.gptmobile.improved.data.repository.ToolConnectionRepository
import dev.melo.gptmobile.improved.data.security.SecretVault
import dev.melo.gptmobile.improved.presentation.ui.localmodel.HuggingFaceAuthClient
import dev.melo.gptmobile.improved.presentation.ui.localmodel.LocalDownloadGuards
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object LocalModelModule {

    @Provides
    @Singleton
    fun provideHuggingFaceAuthClient(
        @ApplicationContext context: Context
    ): HuggingFaceAuthClient = HuggingFaceAuthClient(context)

    @Provides
    @Singleton
    fun provideLocalDownloadGuards(
        @ApplicationContext context: Context
    ): LocalDownloadGuards = LocalDownloadGuards(context)

    @Provides
    @Singleton
    fun provideHuggingFaceTokenStore(
        @ApplicationContext context: Context
    ): HuggingFaceTokenStore = HuggingFaceTokenStore(context)

    @Provides
    @Singleton
    fun provideGatedDownloadCoordinator(
        @ApplicationContext context: Context,
        tokenStore: HuggingFaceTokenStore
    ): GatedDownloadCoordinator = GatedDownloadCoordinator(context, tokenStore)

    @Provides
    @Singleton
    fun provideLocalModelRepository(
        @ApplicationContext context: Context,
        modelCatalogRepository: ModelCatalogRepository,
        localRuntimeRepository: LocalRuntimeRepository
    ): LocalModelRepository = LocalModelRepositoryImpl(
        context = context,
        modelCatalogRepository = modelCatalogRepository,
        localRuntimeRepository = localRuntimeRepository
    )

    @Provides
    @Singleton
    fun provideSecretVault(
        @ApplicationContext context: Context
    ): SecretVault = SecretVault(context)

    @Provides
    @Singleton
    fun provideToolConnectionRepository(
        dao: ToolConnectionDao,
        vault: SecretVault
    ): ToolConnectionRepository = ToolConnectionRepository(dao, vault)

    @Provides
    @Singleton
    fun provideMcpClientManager(
        @ApplicationContext context: Context,
        vault: SecretVault
    ): McpClientManager = McpClientManager(context, vault)

    @Provides
    @Singleton
    fun provideMcpOAuthCoordinator(
        @ApplicationContext context: Context,
        repository: ToolConnectionRepository,
        vault: SecretVault
    ): McpOAuthCoordinator = McpOAuthCoordinator(context, repository, vault)
}
