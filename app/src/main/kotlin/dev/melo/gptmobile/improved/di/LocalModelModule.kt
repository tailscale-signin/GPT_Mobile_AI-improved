package dev.melo.gptmobile.improved.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.melo.gptmobile.improved.data.repository.LocalModelRepository
import dev.melo.gptmobile.improved.data.repository.LocalModelRepositoryImpl
import dev.melo.gptmobile.improved.data.repository.LocalRuntimeRepository
import dev.melo.gptmobile.improved.data.repository.ModelCatalogRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object LocalModelModule {

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
}
