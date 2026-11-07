package dev.chungjungsoo.gptmobile.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import dev.chungjungsoo.gptmobile.data.audit.AppAuditAppearanceProbeSource
import dev.chungjungsoo.gptmobile.data.audit.AppAuditBehaviorProbeSource
import dev.chungjungsoo.gptmobile.data.audit.AppAuditProbeSource
import dev.chungjungsoo.gptmobile.data.audit.AppAuditResearchProbeSource

@Module
@InstallIn(SingletonComponent::class)
internal abstract class AppAuditContractModule {
    @Binds @IntoSet
    abstract fun behavior(source: AppAuditBehaviorProbeSource): AppAuditProbeSource

    @Binds @IntoSet
    abstract fun appearance(source: AppAuditAppearanceProbeSource): AppAuditProbeSource

    @Binds @IntoSet
    abstract fun research(source: AppAuditResearchProbeSource): AppAuditProbeSource
}
