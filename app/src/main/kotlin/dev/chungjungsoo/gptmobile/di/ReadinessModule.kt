package dev.chungjungsoo.gptmobile.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ReadinessScope

@Module
@InstallIn(SingletonComponent::class)
object ReadinessModule {
    @Provides
    @Singleton
    @ReadinessScope
    fun scope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + kotlinx.coroutines.CoroutineExceptionHandler { _, error -> dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record("Readiness", "OBSERVER_DEGRADED · cause=${error.javaClass.simpleName}", "W") })
}
