package com.tailscale.gptmobileai

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.SingletonComponent
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Provides location-related dependencies for the Location MCP tool.
 */
@Module
@InstallIn(SingletonComponent::class)
object LocationMcpToolModule {

    @Provides
    @Singleton
    fun provideLocationPermissionChecker(
        context: Context,
        fusedLocationClient: FusedLocationProviderClient
    ): LocationPermissionChecker = LocationPermissionChecker(context, fusedLocationClient)
}
