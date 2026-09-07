package dev.melo.gptmobile.improved.data.repository

import android.content.Context
import dev.melo.gptmobile.improved.data.network.NetworkClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocalRuntimeRepositoryImpl @Inject constructor(
    private val context: Context,
    private val networkClient: NetworkClient
) : LocalRuntimeRepository {

    override fun isRuntimeInstalled(): Boolean = false

    override fun getRuntimeVersion(): String? = null

    override fun observeRuntimeStatus(): Flow<String> = flowOf("idle")

    override fun downloadRuntime(onProgress: (Float) -> Unit): Result<File> {
        return Result.failure(UnsupportedOperationException("Not implemented"))
    }

    override fun deleteRuntime(): Result<Unit> {
        return Result.success(Unit)
    }

    override fun checkCompatibility(): Boolean = true
}
