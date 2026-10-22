package dev.chungjungsoo.gptmobile.data.research

import dev.chungjungsoo.gptmobile.data.research.feature.ResearchOutbox
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** The outbox retains the source while failed indexing backs off across progress saves and restarts. */
internal fun ResearchOutbox.retryDue(now: Long): Boolean =
    Json.parseToJsonElement(operation).jsonObject.let { payload ->
        payload.text("state") != "IDENTITY_REVIEW_REQUIRED" &&
            (payload.text("retryAfterMs").toLongOrNull()?.let { now >= it } ?: true)
    }

internal fun ResearchOutbox.failedIngestion(now: Long, reason: String): ResearchOutbox {
    val payload = Json.parseToJsonElement(operation).jsonObject
    val attempt = ((payload.text("retryAttempt").toIntOrNull() ?: 0).coerceIn(0, 16) + 1).coerceAtMost(16)
    val delay = (30_000L * (1L shl (attempt - 1).coerceAtMost(7))).coerceAtMost(60 * 60_000L)
    return copy(
        operation = JsonObject(
            payload + mapOf(
                "retryAttempt" to JsonPrimitive(attempt),
                "retryAfterMs" to JsonPrimitive(now + delay),
                "lastFailure" to JsonPrimitive(reason),
                "state" to JsonPrimitive(if (reason == "IDENTITY_CONFLICT_REQUIRES_REVIEW") "IDENTITY_REVIEW_REQUIRED" else "RETRY_PENDING")
            )
        ).toString()
    )
}

internal fun archiveIngestionFailure(error: Exception): String = when {
    error.message?.contains("Conflicting strong research identifiers") == true -> "IDENTITY_CONFLICT_REQUIRES_REVIEW"
    error.message?.contains("integrity", ignoreCase = true) == true -> "BODY_INTEGRITY_FAILED"
    error is java.io.FileNotFoundException || error.message == "Missing retained source body" -> "RETAINED_BODY_UNAVAILABLE"
    error is android.database.sqlite.SQLiteFullException -> "DEVICE_STORAGE_FULL"
    error is android.database.sqlite.SQLiteException -> "ARCHIVE_DATABASE_ERROR"
    else -> "ARCHIVE_WRITE_FAILED"
}
