package dev.melo.gptmobile.improved.data.repository

import android.content.Context
import dev.melo.gptmobile.improved.data.network.NetworkClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

interface LocalRuntimeRepository {
    val isRuntimeReady: Flow<Boolean>
    suspend fun checkRuntimeStatus(): Boolean
    suspend fun downloadRuntime(onProgress: (Float) -> Unit): Boolean
}

@Singleton
class LocalRuntimeRepositoryImpl @Inject constructor(
    private val context: Context,
    private val networkClient: NetworkClient
) : LocalRuntimeRepository {

    private val _isRuntimeReady = MutableStateFlow(true)
    override val isRuntimeReady: Flow<Boolean> = _isRuntimeReady.asStateFlow()

    override suspend fun checkRuntimeStatus(): Boolean {
        return true
    }

    override suspend fun downloadRuntime(onProgress: (Float) -> Unit): Boolean {
        onProgress(1.0f)
        _isRuntimeReady.value = true
        return true
    }
}
