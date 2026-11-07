package dev.chungjungsoo.gptmobile.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import dev.chungjungsoo.gptmobile.data.audit.AppAuditPluginProbeSource
import dev.chungjungsoo.gptmobile.data.audit.AppAuditPluginServices
import dev.chungjungsoo.gptmobile.data.audit.AppAuditProbeSource
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal object AppAuditPluginModule {
    @Provides
    @Singleton
    @IntoSet
    fun providePluginAuditSource(services: AppAuditPluginServices): AppAuditProbeSource = AppAuditPluginProbeSource(services::inventory, services::target)
}
