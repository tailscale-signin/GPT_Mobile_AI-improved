package dev.chungjungsoo.gptmobile.di

import android.app.ActivityManager
import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.chungjungsoo.gptmobile.data.network.AnthropicAPI
import dev.chungjungsoo.gptmobile.data.network.AnthropicAPIImpl
import dev.chungjungsoo.gptmobile.data.network.GoogleAPI
import dev.chungjungsoo.gptmobile.data.network.GoogleAPIImpl
import dev.chungjungsoo.gptmobile.data.network.GroqAPI
import dev.chungjungsoo.gptmobile.data.network.GroqAPIImpl
import dev.chungjungsoo.gptmobile.data.network.NetworkClient
import dev.chungjungsoo.gptmobile.data.network.OpenAIAPI
import dev.chungjungsoo.gptmobile.data.network.OpenAIAPIImpl
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.cio.CIOEngineConfig
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    private fun isHighRamDevice(context: Context): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        val memoryInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memoryInfo)
        return (memoryInfo.totalMem / (1024L * 1024L * 1024L)) >= 10L
    }

    @Provides
    @Singleton
    fun provideNetworkClient(
        @ApplicationContext context: Context
    ): NetworkClient {
        val isHighRam = isHighRamDevice(context)
        val engine = CIO.create {
            if (isHighRam) {
                // High-performance concurrency pool for 12GB+ RAM multi-core devices
                maxConnectionsCount = 1000
                endpoint {
                    maxConnectionsPerRoute = 100
                    pipelineMaxSize = 20
                    keepAliveTime = 5000
                    connectTimeout = 5000
                }
            } else {
                maxConnectionsCount = 250
                endpoint {
                    maxConnectionsPerRoute = 25
                    pipelineMaxSize = 5
                    keepAliveTime = 5000
                    connectTimeout = 10000
                }
            }
        }
        return NetworkClient(engine)
    }

    @Provides
    @Singleton
    fun provideOpenAIAPI(networkClient: NetworkClient): OpenAIAPI = OpenAIAPIImpl(networkClient)

    @Provides
    @Singleton
    fun provideGroqAPI(networkClient: NetworkClient): GroqAPI = GroqAPIImpl(networkClient)

    @Provides
    @Singleton
    fun provideAnthropicAPI(networkClient: NetworkClient): AnthropicAPI = AnthropicAPIImpl(networkClient)

    @Provides
    @Singleton
    fun provideGoogleAPI(networkClient: NetworkClient): GoogleAPI = GoogleAPIImpl(networkClient)
}
