package dev.melo.gptmobile.improved.data.repository

import kotlinx.coroutines.flow.Flow
import java.io.File

interface LocalRuntimeRepository {
    fun isRuntimeInstalled(): Boolean
    fun getRuntimeVersion(): String?
    fun observeRuntimeStatus(): Flow<String>
    suspend fun downloadRuntime(onProgress: (Float) -> Unit): Result<File>
    suspend fun deleteRuntime(): Result<Unit>
    suspend fun checkCompatibility(): Boolean
}
