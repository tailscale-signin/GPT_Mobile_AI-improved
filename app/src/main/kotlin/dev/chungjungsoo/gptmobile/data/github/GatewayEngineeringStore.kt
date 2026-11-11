package dev.chungjungsoo.gptmobile.data.github

import androidx.room.withTransaction
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import dev.chungjungsoo.gptmobile.data.database.entity.GatewayEngineeringBindingRecord
import dev.chungjungsoo.gptmobile.data.database.entity.GatewayEngineeringEventRecord
import dev.chungjungsoo.gptmobile.data.database.entity.GatewayMigrationRecord
import dev.chungjungsoo.gptmobile.data.database.entity.GatewaySubmissionRecord
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Normalized job/event storage; imports never send requests or replace newer saved receipts. */
@Singleton
class GatewayEngineeringStore @Inject constructor(private val database: ChatDatabaseV2, private val settings: SettingRepository) {
    private val json = Json { ignoreUnknownKeys = true }
    private val migrationLock = Mutex()
    private val dao get() = database.gatewayJobDao()

    suspend fun migrateLegacy() = migrationLock.withLock {
        val legacy = settings.getFeatureSettings().gatewayEngineeringJobs
        if (legacy.isEmpty()) return@withLock
        val checksum = gatewayIdentityKey(json.encodeToString(legacy))
        database.withTransaction {
            if (dao.migration(MIGRATION_KEY)?.checksum != checksum) {
                for (binding in legacy) {
                    dao.importBinding(record(binding))
                    dao.saveEvents(events(binding))
                    dao.pruneEvents(binding.request.requestId)
                }
                // A committed marker alone is not enough: every original request must be present.
                check(legacy.all { dao.binding(it.request.requestId) != null }) { "Saved job migration did not preserve every request." }
                dao.saveMigration(GatewayMigrationRecord(MIGRATION_KEY, checksum, System.currentTimeMillis()))
            }
            check(legacy.all { dao.binding(it.request.requestId) != null }) { "Saved job migration needs repair; the original history was retained." }
        }
        // A crash here safely repeats the same import. Concurrent backup restoration is retained.
        settings.mutateFeatureSettings { current ->
            if (gatewayIdentityKey(json.encodeToString(current.gatewayEngineeringJobs)) == checksum) current.copy(gatewayEngineeringJobs = emptyList()) else current
        }
    }

    suspend fun bindings(chatId: Int? = null, includeUpdates: Boolean = true): List<GatewayEngineeringBinding> {
        migrateLegacy()
        return dao.bindings(chatId).map { row ->
            val binding = json.decodeFromString<GatewayEngineeringBinding>(row.binding)
            if (!includeUpdates) binding else binding.copy(updates = dao.events(row.requestId).map { GatewayEngineeringUpdate(it.revision, it.state, it.message, it.createdAt) })
        }
    }

    suspend fun add(binding: GatewayEngineeringBinding) {
        migrateLegacy()
        database.withTransaction {
            check(dao.queuedDispatches().all { it.runId == binding.localRunId }) { "The Gateway queue is waiting for its current job. This engineering job was not dispatched." }
            val existing = dao.binding(binding.request.requestId)
            if (existing != null) {
                val saved = json.decodeFromString<GatewayEngineeringBinding>(existing.binding)
                check(saved.request == binding.request && saved.endpoint == binding.endpoint && saved.profileUid == binding.profileUid && saved.principalId == binding.principalId && saved.gatewayId == binding.gatewayId && saved.localUserMessageId == binding.localUserMessageId && saved.localAssistantMessageId == binding.localAssistantMessageId && saved.localRunId == binding.localRunId && saved.originalContext == binding.originalContext) { "The saved command identity belongs to a different request." }
            } else {
                dao.saveBinding(record(binding))
                dao.saveEvents(events(binding))
            }
            val payload = json.encodeToString(binding.request)
            val submission = GatewaySubmissionRecord(
                commandId = binding.request.requestId,
                chatId = binding.chatId,
                userMessageId = binding.localUserMessageId,
                assistantMessageId = binding.localAssistantMessageId,
                runId = binding.localRunId,
                connectionProfileUid = binding.profileUid,
                endpoint = binding.endpoint,
                executionProfileUid = binding.request.profileId ?: binding.profileUid,
                fingerprint = gatewayIdentityKey(payload),
                payload = payload,
                createdAtMillis = System.currentTimeMillis()
            )
            dao.insertSubmission(submission)
            check(dao.submission(submission.commandId)?.fingerprint == submission.fingerprint) { "The pending command changed; its original identity was retained." }
        }
    }

    suspend fun admissionState(requestId: String): String? = dao.submission(requestId)?.state

    suspend fun hasMigratedAdmission(chatId: Int, profileUid: String): Boolean = dao.migratedAdmissionCount(chatId, profileUid) > 0

    suspend fun markAdmission(requestId: String, state: String) = database.withTransaction {
        dao.submission(requestId)?.let { dao.saveSubmission(it.copy(state = state)) }
    }

    suspend fun save(binding: GatewayEngineeringBinding) = database.withTransaction {
        val retained = requireNotNull(dao.binding(binding.request.requestId)) { "The saved engineering binding was removed." }
        val original = json.decodeFromString<GatewayEngineeringBinding>(retained.binding)
        check(original.request == binding.request && original.profileUid == binding.profileUid && original.endpoint == binding.endpoint && original.principalId == binding.principalId && original.gatewayId == binding.gatewayId && original.localUserMessageId == binding.localUserMessageId && original.localAssistantMessageId == binding.localAssistantMessageId && original.localRunId == binding.localRunId && original.originalContext == binding.originalContext) { "The saved engineering destination or original local turn changed." }
        check(binding.job?.gatewayId == null || binding.gatewayId == null || binding.job.gatewayId == binding.gatewayId) { "The gateway installation changed." }
        dao.saveBinding(record(binding))
        dao.saveEvents(events(binding))
        dao.pruneEvents(binding.request.requestId)
        val job = binding.job
        if (job != null) {
            dao.submission(binding.request.requestId)?.let { submission ->
                val key = if (job.gatewayId != null && job.conversationId != null && binding.principalId != null) gatewayIdentityKey(job.gatewayId, binding.principalId, job.conversationId) else submission.conversationKey
                dao.saveSubmission(submission.copy(state = if (job.terminal) "SETTLED" else "ACCEPTED", conversationKey = key, receipt = json.encodeToString(job)))
            }
        }
    }

    private fun record(binding: GatewayEngineeringBinding) = GatewayEngineeringBindingRecord(
        binding.request.requestId,
        binding.chatId,
        binding.job?.id,
        json.encodeToString(binding.copy(updates = emptyList(), job = binding.job?.copy(updates = emptyList()))),
        System.currentTimeMillis()
    )

    private fun events(binding: GatewayEngineeringBinding) = (binding.updates + binding.job?.updates.orEmpty()).distinctBy { it.revision }.map {
        GatewayEngineeringEventRecord(binding.request.requestId, it.revision, it.state, it.message, it.createdAt)
    }

    companion object {
        internal const val MIGRATION_KEY = "engineering-datastore-v1"
    }
}
