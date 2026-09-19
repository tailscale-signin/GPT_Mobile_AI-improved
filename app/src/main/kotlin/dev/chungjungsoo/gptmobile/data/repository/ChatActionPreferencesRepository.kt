package dev.chungjungsoo.gptmobile.data.repository

import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for managing dynamic action button preferences and prompt history.
 */
interface ChatActionPreferencesRepository {
    suspend fun isDynamicActionsEnabled(): Boolean
    suspend fun setDynamicActionsEnabled(enabled: Boolean)
    fun observeDynamicActionsEnabled(): Flow<Boolean>

    suspend fun getActionPromptHistory(): List<String>
    suspend fun recordActionPrompt(prompt: String)
    suspend fun clearActionPromptHistory()
    fun observeActionPromptHistory(): Flow<List<String>>
}
