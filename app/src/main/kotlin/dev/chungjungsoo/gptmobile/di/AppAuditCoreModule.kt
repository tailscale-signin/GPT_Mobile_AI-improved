package dev.chungjungsoo.gptmobile.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import dev.chungjungsoo.gptmobile.data.audit.AppAuditLocalProbeSource
import dev.chungjungsoo.gptmobile.data.audit.AppAuditProbeSource

@Module
@InstallIn(SingletonComponent::class)
abstract class AppAuditCoreModule {
    @Binds
    @IntoSet
    abstract fun localAuditProbes(source: AppAuditLocalProbeSource): AppAuditProbeSource
}
