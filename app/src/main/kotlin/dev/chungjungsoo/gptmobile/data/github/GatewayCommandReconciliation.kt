package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.database.entity.GatewaySubmissionRecord
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Only missing/read-interrupted outcomes back off. An absent receipt never authorizes another POST. */
internal object GatewayCommandReconciliation {
    private const val MARKER = "gateway_command_lookup_v1"
    private const val MAX_DELAY_MILLIS = 300_000L
    private val json = Json { ignoreUnknownKeys = true }
    val pendingStates = setOf("PENDING", "DISPATCHING", "OUTCOME_UNKNOWN", "MIGRATED_REVIEW", "RESERVED_ORDINARY", "PENDING_ORDINARY", "DISPATCHING_ORDINARY", "PENDING_INSTRUCTION")

    @Serializable
    private data class Checkpoint(val kind: String = MARKER, val attempts: Int, val lastAttemptAtMillis: Long, val nextAttemptAtMillis: Long)

    private fun checkpoint(record: GatewaySubmissionRecord): Checkpoint? = record.receipt?.let {
        runCatching { json.decodeFromString<Checkpoint>(it) }.getOrNull()
            ?.takeIf { saved -> saved.kind == MARKER && saved.attempts in 1..31 }
    }

    fun due(record: GatewaySubmissionRecord, now: Long = System.currentTimeMillis()): Boolean {
        val saved = checkpoint(record) ?: return true
        // Wall-clock corrections must not strand the original command indefinitely.
        return now < saved.lastAttemptAtMillis || now >= saved.nextAttemptAtMillis || saved.nextAttemptAtMillis - now > MAX_DELAY_MILLIS
    }

    fun deferred(record: GatewaySubmissionRecord, now: Long = System.currentTimeMillis()): GatewaySubmissionRecord {
        val attempts = ((checkpoint(record)?.attempts ?: 0) + 1).coerceAtMost(31)
        val delay = (10_000L shl (attempts - 1).coerceAtMost(5)).coerceAtMost(MAX_DELAY_MILLIS)
        return record.copy(receipt = json.encodeToString(Checkpoint(attempts = attempts, lastAttemptAtMillis = now, nextAttemptAtMillis = now + delay)))
    }
}
