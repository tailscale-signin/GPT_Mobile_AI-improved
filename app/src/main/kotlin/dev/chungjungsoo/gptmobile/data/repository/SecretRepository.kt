package dev.chungjungsoo.gptmobile.data.repository

import kotlinx.coroutines.flow.Flow

/**
 * Repository for managing application secrets stored in DataStore
 */
interface SecretRepository {
    /**
     * Get a secret by key
     */
    suspend fun getSecret(key: String): String?

    /**
     * Set a secret by key
     */
    suspend fun setSecret(key: String, value: String)

    /**
     * Delete a secret by key
     */
    suspend fun deleteSecret(key: String)

    /**
     * Observe a secret by key
     */
    fun observeSecret(key: String): Flow<String?>
}
