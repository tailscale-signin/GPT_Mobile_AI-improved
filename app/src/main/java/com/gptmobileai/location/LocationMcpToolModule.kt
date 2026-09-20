package com.gptmobileai.location

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
    fun provideFusedLocationProviderClient(
        @ApplicationContext context: Context
    ): com.google.android.gms.location.FusedLocationProviderClient =
        com.google.android.gms.location.LocationServices.getFusedLocationProviderClient(context)
}
