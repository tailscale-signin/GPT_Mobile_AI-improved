package com.tailscale.signin.location

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object LocationMcpToolModule {

    @Provides
    @Singleton
    fun provideLocationViewModel(@ApplicationContext context: Context): com.tailscale.signin.location.LocationViewModel {
        return com.tailscale.signin.location.LocationViewModel(context)
    }
}
