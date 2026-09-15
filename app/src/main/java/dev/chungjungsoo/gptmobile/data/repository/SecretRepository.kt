package dev.chungjungsoo.gptmobile.data.repository

import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for managing secrets using DataStore
 */
interface SecretRepository {
    suspend fun getSecret(key: String): String?
    suspend fun setSecret(key: String, value: String)
    suspend fun deleteSecret(key: String)
    suspend fun getAllSecrets(): Map<String, String>
    fun observeSecret(key: String): Flow<String?>
}