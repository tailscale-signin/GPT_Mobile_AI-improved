package dev.melo.gptmobile.improved.di

import android.app.ActivityManager
import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.melo.gptmobile.improved.data.network.AnthropicAPI
import dev.melo.gptmobile.improved.data.network.AnthropicAPIImpl
import dev.melo.gptmobile.improved.data.network.GoogleAPI
import dev.melo.gptmobile.improved.data.network.GoogleAPIImpl
import dev.melo.gptmobile.improved.data.network.GroqAPI
import dev.melo.gptmobile.improved.data.network.GroqAPIImpl
import dev.melo.gptmobile.improved.data.network.NetworkClient
import dev.melo.gptmobile.improved.data.network.OpenAIAPI
import dev.melo.gptmobile.improved.data.network.OpenAIAPIImpl
import io.ktor.client.engine.cio.CIO
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    private fun getDeviceRamGb(context: Context): Long {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return 0L
        val memoryInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memoryInfo)
        return memoryInfo.totalMem / (1024L * 1024L * 1024L)
    }

    @Provides
    @Singleton
    fun provideNetworkClient(
        @ApplicationContext context: Context
    ): NetworkClient {
        val ramGb = getDeviceRamGb(context)
        return NetworkClient(CIO)
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
