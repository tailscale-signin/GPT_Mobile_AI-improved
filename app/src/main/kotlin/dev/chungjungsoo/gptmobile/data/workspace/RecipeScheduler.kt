package dev.chungjungsoo.gptmobile.data.workspace

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.room.withTransaction
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.chungjungsoo.gptmobile.data.model.ChatMcpToolConfig
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.model.ConversationDelegationSettings
import dev.chungjungsoo.gptmobile.data.queue.DurablePromptQueue
import dev.chungjungsoo.gptmobile.data.queue.PendingPrompt
import dev.chungjungsoo.gptmobile.data.queue.PendingPromptPayload
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.encodeToString

@Singleton
class RecipeScheduler @Inject constructor(@ApplicationContext private val context: Context) {
    fun update(id: String, recipe: TaskRecipe) {
        val manager = WorkManager.getInstance(context)
        if (!recipe.scheduled) {
            manager.cancelUniqueWork("recipe:$id")
            return
        }
        manager.enqueueUniquePeriodicWork(
            "recipe:$id",
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<RecipeWorker>(recipe.intervalHours.coerceIn(1, 168), TimeUnit.HOURS)
                .setInputData(workDataOf("recipe" to id))
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .addTag("recipes").build()
        )
    }
}

@HiltWorker
class RecipeWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted parameters: WorkerParameters,
    private val workspace: WorkspaceRepository,
    private val settings: SettingRepository,
    private val queue: DurablePromptQueue
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val id = inputData.getString("recipe") ?: return Result.failure()
        try {
            val entry = workspace.dao.get(id) ?: return Result.success()
            val recipe = workspace.json.decodeFromString<TaskRecipe>(entry.payload)
            if (!recipe.scheduled) return Result.success()
            val features = settings.getFeatureSettings()
            if (!features.backgroundGeneration) return deferred(entry, "Background generation is disabled.")
            val profile = settings.fetchPlatformV2s().firstOrNull { it.uid == recipe.profileUid && it.enabled } ?: return deferred(entry, "Profile is unavailable.")
            if (recipe.localOnly && profile.compatibleType != ClientType.LITERT_LM) return deferred(entry, "Local-only recipe requires an on-device profile.")
            if (profile.compatibleType != ClientType.LITERT_LM && (!features.spendBudget.enforced || features.tokenBudget.outputTokens <= 0)) return deferred(entry, "Set a monetary allowance and output cap before scheduled remote work.")
            val chatId = entry.chatId ?: return Result.failure()
            val room = workspace.database.chatRoomDao().getChatRoomsByIds(listOf(chatId)).firstOrNull() ?: return Result.success()
            if (room.isTemporary) return Result.success()
            val now = System.currentTimeMillis()
            workspace.database.withTransaction {
                val currentEntry = workspace.dao.get(id) ?: return@withTransaction
                if (currentEntry.payload != entry.payload || currentEntry.chatId != entry.chatId) return@withTransaction
                val latest = workspace.json.decodeFromString<TaskRecipe>(currentEntry.payload)
                if (!latest.scheduled || now - latest.lastQueuedAt < latest.intervalHours * 3_600_000L) return@withTransaction
                if (workspace.database.pendingPromptDao().firstPending(chatId) != null || workspace.database.pendingPromptDao().activeRunCount(chatId) > 0) {
                    deferred(currentEntry, "Destination has queued or running work.")
                    return@withTransaction
                }
                val payload = PendingPromptPayload(profileUids = listOf(profile.uid), models = mapOf(profile.uid to profile.model), tools = ChatMcpToolConfig(allToolsDisabled = true, delegation = ConversationDelegationSettings(enabled = false)), localOnly = recipe.localOnly, requiresSpendAllowance = true)
                workspace.database.pendingPromptDao().enqueue(PendingPrompt("recipe:$id:$now", chatId, "${recipe.prompt}\n\nOutput: ${recipe.outputFormat}", workspace.json.encodeToString(payload), 0))
                workspace.dao.save(entry.copy(title = entry.title.substringBefore(" · deferred"), payload = workspace.json.encodeToString(latest.copy(lastQueuedAt = now)), updatedAt = now))
            }
            queue.start()
            return Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return Result.retry()
        }
    }
    private suspend fun deferred(entry: WorkspaceRecord, reason: String): Result {
        workspace.dao.save(entry.copy(title = entry.title.substringBefore(" · deferred") + " · deferred: $reason", updatedAt = System.currentTimeMillis()))
        return Result.success()
    }
}
