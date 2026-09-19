package dev.chungjungsoo.gptmobile.di

import android.content.Context
import com.example.gptmobileai.debug.DiagnosticsTelemetryProvider
import com.example.gptmobileai.debug.ExportService
import com.example.gptmobileai.debug.TelemetryCollector
import com.example.gptmobileai.debug.TokenMetricsCollector
import com.example.gptmobileai.debug.ToolMetricsCollector
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DiagnosticsModule {

    @Provides
    @Singleton
    fun provideTelemetryCollector(): TelemetryCollector {
        return TelemetryCollector()
    }

    @Provides
    @Singleton
    fun provideTokenMetricsCollector(
        telemetryCollector: TelemetryCollector
    ): TokenMetricsCollector {
        return TokenMetricsCollector(telemetryCollector)
    }

    @Provides
    @Singleton
    fun provideToolMetricsCollector(
        telemetryCollector: TelemetryCollector
    ): ToolMetricsCollector {
        return ToolMetricsCollector(telemetryCollector)
    }

    @Provides
    @Singleton
    fun provideDiagnosticsTelemetryProvider(
        @ApplicationContext context: Context,
        telemetryCollector: TelemetryCollector
    ): DiagnosticsTelemetryProvider {
        return DiagnosticsTelemetryProvider(context, telemetryCollector)
    }

    @Provides
    @Singleton
    fun provideExportService(
        @ApplicationContext context: Context,
        telemetryCollector: TelemetryCollector
    ): ExportService {
        return ExportService(context, telemetryCollector)
    }
}
